"""
The wire: frame types, the key schedule, and the channel that carries them.

Extracted from clipsync.py for the same reason clipsync_config.py was, and the reason is worth
stating once more because it is the only thing holding the three modules apart: **importing
clipsync.py has side effects** — it configures logging into the service's own log file and registers
a clipboard format — so anything that is not the service cannot import it.  The configurator needed
the validation rules; the pairing joiner needs the channel.  Restating either would mean two copies
of a protocol, and the second copy is the one that drifts.

This module is deliberately free of side effects.  It takes a logger by name rather than configuring
one, so the service's handlers apply when the service imports it and nothing is emitted when the
configurator does.

The Android side of all of this is Connection.java; the two files are read together, and between
them they are the protocol's only specification — there is no separate document to consult, so a
rule that is not written down here or there is not written down anywhere.

The shape of the thing, once, so the constants below have somewhere to hang:

* **Everyone dials everyone they can see, and everyone listens.** There is no hub and no server
  role.  A node opens a connection to every address it is configured with and every peer it finds
  over mDNS, one dialler per target with its own back-off, because a peer that is switched off must
  not slow the redial of one that is merely rebooting.  Inbound and outbound are the same thing
  once the handshake is over: the same channel, the same registry, the same broadcast.  Only the
  nonce exchange differs, by which end opened the socket.
* **Every connection is authenticated by a pre-shared key** and nothing else.  There are no
  certificates, no accounts and no server to vouch for anybody; two nodes that hold the same secret
  are the same person's devices, which is the whole of the trust model.  A connection that cannot
  decrypt the first frame is a stranger and is closed.
* **Sessions, not installations.** A node's id is generated per process and stored nowhere, and
  every use of it compares ids that arrived in a HELLO on a connection that is open right now.
* **Nothing is negotiated.** `PROTOCOL_VERSION` must match exactly and a mismatch is refused with a
  plain message; capabilities (`persistent`, `data_out`, `port`) are *declared* in HELLO and each
  end works out the consequences from the same declarations, so both reach the same answer without
  exchanging a single decision.  Every asymmetry this project has had came from breaking that:
  one end declared a field and the other never read it.
"""
import hashlib
import hmac
import json
import logging
import os
import socket
import struct
import threading
import time

from cryptography.hazmat.primitives.ciphers.aead import ChaCha20Poly1305

__all__ = [
    "T_HELLO", "T_BYE", "T_PING", "T_PONG", "T_KEYS", "T_CLIP",
    "T_OFFER", "T_WANT", "T_HAVE", "T_SKIP", "T_CHUNK", "T_PULL", "T_END", "T_ABORT",
    "T_PAIR_ASK", "T_PAIR_KEY",
    "T_RELAY_ASK", "T_RELAY_OK", "T_RELAY_NO",
    "T_PEERS",
    "BYE_IDLE", "PROTOCOL_VERSION", "READ_TIMEOUT", "HANDSHAKE_TIMEOUT", "PRE_AUTH_MAX_FRAME",
    "hkdf_sha256", "SecureChannel", "audit_hello_sent",
]

log = logging.getLogger("clipsync")

# Frame types, grouped by purpose and renumbered for the draft protocol.  No external release has
# ever shipped, so nothing reads the old numbers.

# Session lifecycle.
T_HELLO = 1
# "I am closing this connection, and here is why" - {reason}.  A close without one becomes a loop,
# and it needs no network trouble to happen: the side whose connection is closed sees nothing but a
# drop, its reconnect logic fires, and it rebuilds exactly the link that was just discarded — to be
# discarded again.  So the closing side says why first, and the peer that hears it does not schedule
# a redial.  The reason is what tells the two long waits apart: another route won, or the device
# went to sleep.
T_BYE = 2
T_PING, T_PONG = 3, 4
# The reason a device sends as it goes to sleep, and the one BYE that does not mean "this link was
# redundant".  The two must not be confused: a duplicate defers to the link that won, while an idle
# peer has no winning link to defer to, so treating it as one leaves the deferral loop finding
# nothing and dialling again at its poll interval.
#
# The right answer is to stop dialling it and wait, which is only correct because the sleeper still
# *listens* and dials out itself the moment its screen comes on.  Redialling would buy nothing and
# cost a wake-up on a phone that has just settled.  Android says it at the end of every screen-off
# session and not only at the transition, because a sleeping device still accepts a connection,
# delivers its burst and closes — and that close, unannounced, arrives as a reset and reads as a
# fault.
#
# Recorded against the **peer**, not the link (SyncState.idle_peers / SyncService.idlePeers): two
# devices that found each other over mDNS both dial, one of the two links is dropped as a duplicate,
# and the survivor may be the inbound one — so the goodbye can arrive on a link the dialler does not
# own and would never hear about.
BYE_IDLE = "idle"

# Key rotation.  Sent after HELLO on every control connection, and again whenever the schedule
# changes, carrying {psk, since, next} — the sender's current key, when it became active (epoch ms,
# the *sender's* clock), and its successor if one exists yet.
#
# A device with rotation switched off still sends this when it has a non-empty schedule: a key set
# on one device can rotate even when the other has the checkbox off, and the schedule survives in
# both config stores regardless of the flag.
#
# There is deliberately no clock agreement between devices.  Every deadline is measured against the
# device's own wall clock, and the protocol is built so that this does not matter: a device is never
# told "retire at this instant", only "here is a successor", and it retires on its own schedule
# while still accepting the old key for a ring of KEYRING rotations.  Two devices whose clocks differ
# by hours rotate hours apart and never notice.  See clipsync_config.Schedule for the phase clock
# and the reconciliation rules.
T_KEYS = 5

# Clipboard.  {ts, from, to, forwarded, mime, sha256, data}.
#
# Every copy goes to every connected peer; there is no hub to route through, so "which clip is
# newer" needs an answer every node computes the same way.  The version is `(wall clock ms, id)`
# compared lexicographically, and a node applies an incoming clip only if its version is greater
# than what it holds.  Last writer wins.
#
# Clock skew is corrected, not tolerated — a device running 30 seconds fast would win every race it
# should lose — so each node keeps a per-peer offset (see clock_offset below) and normalises an
# incoming version into its own clock domain before comparing.  Ordering survives that because a
# common shift preserves order: every node reaches the same verdict in its own domain.  A forwarder
# must re-normalise on the way out, because the next hop has no offset for the *origin* — it has no
# connection to it, which is why the clip was forwarded at all.  Shifts compose, so the chain stays
# consistent at any depth.
#
# `to` is who the sender already sent this to, and a receiver forwards to exactly
# `my peers ∖ to` — the ones the origin could not reach itself.  Three rules keep that bounded:
# a forwarded frame carries `forwarded: true` and is never forwarded again (so depth is at most two
# hops and no TTL is needed); a frame is never sent back down the link it arrived on; and every node
# keeps the last 64 seen keys and drops repeats.  The third is the safety net that makes duplicate
# delivery harmless whatever the topology, which is what lets the rest stay this simple.
T_CLIP = 6

# File transfer.  OFFER {seq,name,mime,size,sha256,from,to,forwarded} -> HAVE (already on disk,
# nothing moves) | SKIP (over this link's size limit) | WANT {ranges of missing chunks}; then the
# bytes over up to N parallel data connections, as CHUNK (u32 index || bytes) answered by PULL
# {ranges}, ending in END or ABORT.
#
# Chunks are addressed **by index, not by byte offset**, and that is load-bearing rather than
# cosmetic: they may arrive over eight connections in any order, be forwarded in that order by a
# relay, and still be written positionally at the far end.  A byte-stream protocol could not relay
# while receiving.  There are no per-chunk hashes on purpose — the receiver verifies the whole file
# when it reassembles it, so a corrupted chunk cannot escape, and a second layer of verification
# would cost bandwidth on every transfer to save work on a failure that should not happen.
T_OFFER, T_WANT, T_HAVE, T_SKIP = 7, 8, 9, 10
T_CHUNK, T_PULL, T_END, T_ABORT = 11, 12, 13, 14

# Pairing: ask for the key, and here it is.  Ordinary frames on an ordinary channel, reached after
# an ordinary handshake and an ordinary HELLO -- only the key differs, being derived from the code
# the user reads off one screen and types into the other rather than from the PSK.  That is the
# whole of what makes pairing possible without new machinery.  See clipsync_pair.py.
T_PAIR_ASK, T_PAIR_KEY = 15, 16

# Relay coordination: one copy per LAN.  A file sent to N peers is otherwise uploaded N times, and
# the origin is often the phone, on the link that can least afford it.
#
# **Receivers coordinate; the sender does not.**  The sender states who it offered to, and each
# receiver computes `(OFFER.to ∩ my LAN peers) ∪ {me} ∪ {origin, if on my LAN}` and takes the
# highest-priority member: itself or the origin means WANT as usual, anyone else means RELAY_ASK
# {sha256, size} to that one node and wait.  No topology protocol, no election messages — the
# priority order is total, so every node derives the same answer from the same inputs.
#
# RELAY_OK accepts; RELAY_NO declines with a reason that says whether to come back: `busy`
# (retryable — the relay is itself waiting for this file, which caps the depth at one hop by
# construction rather than by assumption) or `refused` (final — over its size limit, or opted out).
# On acceptance the relay sends a normal OFFER when it has the data, and the waiter answers with a
# normal WANT: there is deliberately no READY frame, because OFFER already means "I have this, do
# you want it" and reusing it puts the second half of the transfer on the existing, debugged path.
#
# A relay that fails is not a jump back to the origin: the waiter walks *down* its candidate list,
# which it fixed when the OFFER arrived and never recomputes.  Jumping to the origin would throw the
# whole optimisation away at the first failure — three waiters on a dead relay would send three
# WANTs up the expensive link, which is the exact thing this exists to prevent.  A fixed descending
# walk over a finite totally-ordered list terminates at the origin by construction.
T_RELAY_ASK, T_RELAY_OK, T_RELAY_NO = 17, 18, 19
# Peer roster exchange: {peers: [{id, name, type, persistent, battery}]}, sent after HELLO and again
# whenever a direct peer connects, disconnects or changes a relay-relevant property.
#
# Each node otherwise knows only the peers it has a TCP connection to.  In `A —internet— B —LAN— C`
# neither A nor C knows the other exists, so the `to` list A builds is {B} and C — receiving B's
# re-announcement rather than A's original OFFER — cannot make a sound relay decision from it.  The
# roster gives every node the 2-hop neighbourhood, which is what the relay election needed all
# along.
#
# **One hop, no re-gossip**: a node reports only its own direct peers, never peers it learned from
# others.  That is what makes it loop-free without sequence numbers, bounds the message count at
# |N(v)| per change, and leaves no stale transitive chain to age out.  The frame is a **full
# snapshot** and replaces the receiver's record for that sender entirely, so a dropped frame
# followed by a received one leaves the right state rather than a partial merge.
#
# It is informational and does not create a forwarding plane: A knows C exists, but it cannot send C
# a frame through B.  The roster enriches `to` lists and the status display; it does not route.
T_PEERS = 20
# The handshake rejects anything that is not an exact match, and that is the design rather than
# laziness: a capability list would allow mixed versions at the cost of a capability matrix to
# reason about and to test, and for a tool whose devices all belong to one person the version check
# is the cheaper correctness guarantee. What it buys is that every incompatibility is one refused
# connection with a plain message instead of a feature that silently does the wrong thing.
#
# 2: HELLO is exchanged in both directions and carries the node id, type, persistence and battery
# bucket. A clean break — a version 1 peer is refused rather than tolerated, because a peer that
# cannot name itself cannot be deduplicated or recognised as self.
# 3: HELLO also carries `port` and `data_out`, and every device listens.  The bump is not
# bookkeeping: both new fields have defaults, so a version-2 peer would connect and work, and then
# open its own data connections for a file at the same moment as the other end opens its, because
# the rule that stops that is the field it does not send.  Every file would move twice.  A version
# check turns that into one refused connection with a plain message.
# 4: OFFER carries `forwarded`, RELAY_ASK carries `size`, and a clip's sha256 is always taken over
# the LF-normalised text.  The first two are additive and a peer that ignores them merely behaves
# as before, but the third changes a value that is compared on the wire: a peer hashing CRLF text
# unnormalised computes a different digest for the same clip, so every CRLF clip would be dropped
# as a hash mismatch and every reconnect would resend it.  That is worth a refused connection
# rather than a silent one-way failure.
# 5: this end declares `data_out=true` and opens data connections of its own.  A version-4 peer
# would read the declaration correctly, so this alone would not need a bump; the sha rule above is
# why the version moves, and the two ship together.
PROTOCOL_VERSION = 5
READ_TIMEOUT = 90          # seconds without any frame -> drop client
# Until a peer has authenticated one frame, everything it says is an unauthenticated stranger's
# claim — including the length prefix.  A HELLO is a dozen short JSON fields, so this is several
# times what the largest legitimate one needs, and it is the difference between "a stranger can
# make us allocate 8 KiB" and "a stranger can make us allocate max_frame".  Mirrors
# Connection.PAIR_MAX_FRAME on the Android side, raised to 8192 for room for long device names.
PRE_AUTH_MAX_FRAME = 8192
# An absolute deadline for the whole handshake, not a per-read timeout.  A per-read timeout is no
# defence against a caller that sends one byte every 14 seconds: each read succeeds, and the worker
# is held forever.  Matches Connection.HANDSHAKE_TIMEOUT_MS.
HANDSHAKE_TIMEOUT = 15


# ----------------------------------------------------------------------------- crypto
def hkdf_sha256(ikm: bytes, salt: bytes, info: bytes, length: int = 32) -> bytes:
    prk = hmac.new(salt, ikm, hashlib.sha256).digest()
    out, t, i = b"", b"", 1
    while len(out) < length:
        t = hmac.new(prk, t + info + bytes([i]), hashlib.sha256).digest()
        out += t
        i += 1
    return out[:length]


# ----------------------------------------------------------------------------- HELLO field contract
# The two halves of the declaration, written down so they can be compared against what actually
# arrives. Every asymmetry this project has had looked the same from the inside: one end put a field
# in HELLO and the other end simply never read it, with no exception, no timeout and no log line.
# `data_out` was sent for two protocol versions before anyone noticed nothing consumed it.
#
# Keep these in lockstep with the HELLO that clipsync.py builds (both the dialling and the accepting
# path) and with read_hello() below. Costs one set lookup per session and catches a whole class of
# bug at the moment it is introduced rather than the week a file will not transfer.
HELLO_SENT = frozenset({"v", "id", "device", "type", "persistent", "battery",
                        "clip_ts", "clip_sha", "lan", "port", "data_out"})
HELLO_READ = frozenset({"v", "id", "device", "type", "persistent", "battery",
                        "port", "data_out", "lan", "role", "clip_ts", "clip_sha", "sha256"})
# Genuinely optional: a field whose absence is a legitimate state rather than an omission.
HELLO_OPTIONAL = frozenset({"role", "sha256", "clip_sha", "lan"})

_audited = set()
_audit_lock = threading.Lock()


def audit_hello_sent(hello: dict) -> str:
    """Compare a HELLO this end is about to send against HELLO_SENT.

    The audit below is only as good as the constants, and a constant kept by hand drifts. Called
    once at start-up with a real HELLO, this is what notices that someone added a field to the
    declaration and not to the contract. Returns "" when they agree, or a description of the
    difference.
    """
    keys = set(hello)
    extra = sorted(keys - HELLO_SENT)
    missing = sorted((HELLO_SENT - HELLO_OPTIONAL) - keys)
    if not extra and not missing:
        return ""
    return "HELLO_SENT is out of date: sends but undeclared %s; declared but not sent %s" % (
        extra or "[]", missing or "[]")


def audit_hello(ch: "SecureChannel", theirs: dict) -> None:
    """One line the first time a peer is seen, naming the fields the two ends disagree about.

    Data and pairing connections are skipped: they carry a deliberately short HELLO, so the whole
    control-connection contract would show up as missing on every stream of every transfer.
    """
    if theirs.get("role") in ("data", "pair"):
        return
    key = str(theirs.get("id") or id(ch))
    with _audit_lock:
        if key in _audited:
            return
        _audited.add(key)
        if len(_audited) > 64:
            _audited.pop()
    got = set(theirs)
    ignored = sorted(got - HELLO_READ)
    missing = sorted((HELLO_READ - HELLO_OPTIONAL) - got)
    if ignored or missing:
        log.warning("hello audit with %s: peer sent but we ignore %s; we expect but peer omits %s",
                    ch.device, ignored or "[]", missing or "[]")


class SecureChannel:
    """
    One TCP connection with per-direction AEAD keys and nonce counters.

    `initiator` says which half of the nonce exchange to perform. The two key labels are named for
    who opened the connection, not for who is a server — this PC now dials as well as accepts, and a
    channel it dialled is the "c" side of its own link. Getting this backwards does not fail at the
    handshake, which exchanges plaintext nonces; it fails at the first frame, as a decrypt error.

    `psk` is whatever secret this channel is keyed on, and it is not always the PSK: a pairing
    channel passes a key derived from the eight-digit code instead, and everything below — the nonces,
    the per-direction keys, the AEAD, the replay resistance — comes along unchanged.  That is the
    whole of what makes pairing possible without new machinery, and a caller without the code fails
    here in exactly the way a wrong PSK does.

    Multi-key accept: `psk` may be a list of secrets.  All are candidates for the first `recv()`;
    the one that decrypts it wins, and the channel commits to it.  `matched_secret` holds the
    winner so the caller can tell whether the peer is on the current key, the successor, or an old
    one.
    """

    def __init__(self, sock: socket.socket, psk, max_frame: int, initiator: bool = False):
        self.sock = sock
        self.max_frame = max_frame
        self.initiator = initiator
        self.device = "?"
        self.node_id = None            # peer's id, set from HELLO
        self.node_type = "?"
        self.persistent = False
        self.battery = "medium"
        self.lan = False
        # The peer's *listening* port and whether it can open data connections of its own. Both come
        # from HELLO because neither is observable: an accepted socket's remote port is the peer's
        # ephemeral source port and reaches nothing, and "can you dial out?" is a capability, not a
        # property of the socket. Together they decide which end opens data connections — see
        # drives_transfer().
        self.peer_port = 0
        self.peer_data_out = False
        self.limit = 0                 # file size limit for this client (set after HELLO)
        # Which of two links to one peer is the older. Only needed for the third duplicate rule —
        # when both links were opened by the *same* node, two names for one machine, the older one
        # is kept because it is the one already carrying traffic.
        self.opened = time.monotonic()
        # Set when this link lost a duplicate tiebreak and was closed from another thread. The owning
        # thread sees only a closed socket, which is indistinguishable from the peer going away —
        # and a dialler that cannot tell those apart redials into the link it just lost.
        self.superseded = False
        self.matched_secret = None     # the secret that authenticated (set on first recv)
        # Offset to the peer's clock in ms: peer_clock = my_clock + offset, used to normalise an
        # incoming clip's version into this node's own clock domain before comparing it.
        #
        # Measured with a round trip and not a single reading: `peer clock − my clock` taken on
        # arrival is wrong by about half the round-trip time, while the NTP form
        # `((t2 − t1) + (t3 − t4)) / 2` costs the same two messages. Re-measured continuously,
        # because phones jump when NTP corrects them — PING/PONG is already a round trip on a timer,
        # so carrying the four timestamps on it makes this nearly free.
        self.clock_offset = 0
        self.send_lock = threading.Lock()
        # Pre-authentication state, and it is only ever about *accepted* connections. `authed` flips
        # on the first frame that decrypts; until then an accepted channel caps frames at
        # PRE_AUTH_MAX_FRAME and bounds the whole exchange by one absolute deadline.
        #
        # A channel we opened is exempt from both, because there is no unauthenticated party on it:
        # we chose the address, we hold the key, and the first frame we read may legitimately be a
        # 512 KiB CHUNK on a data connection — which a HELLO-sized cap would reject and a 15-second
        # deadline would kill while the peer was still seeking to the right offset. Android draws
        # the same line: its cap keys off `keyResolved`, which an outbound single-key connection has
        # from the moment it is constructed.
        #
        # Set before the nonce exchange below, because that exchange is part of what it bounds.
        self.authed = False
        self.handshake_deadline = time.monotonic() + HANDSHAKE_TIMEOUT
        mine = os.urandom(32)
        if initiator:
            sock.sendall(mine)
            theirs = self._recv_exact(32)
            salt = mine + theirs       # nc || ns
        else:
            theirs = self._recv_exact(32)
            sock.sendall(mine)
            salt = theirs + mine

        secrets = psk if isinstance(psk, list) else [psk]
        if len(secrets) == 1:
            c2s = ChaCha20Poly1305(hkdf_sha256(secrets[0], salt, b"clipsync c2s"))
            s2c = ChaCha20Poly1305(hkdf_sha256(secrets[0], salt, b"clipsync s2c"))
            self.tx, self.rx = (c2s, s2c) if initiator else (s2c, c2s)
            self.matched_secret = secrets[0]
            self._candidates = None
        else:
            # Derive key pairs for every candidate; the first recv() picks the winner.
            cands = []
            for s in secrets:
                c2s = ChaCha20Poly1305(hkdf_sha256(s, salt, b"clipsync c2s"))
                s2c = ChaCha20Poly1305(hkdf_sha256(s, salt, b"clipsync s2c"))
                tx, rx = (s2c, c2s) if not initiator else (c2s, s2c)
                cands.append((tx, rx, s))
            self._candidates = cands
            self.tx = None
            self.rx = None
        self.rx_ctr = 0
        self.tx_ctr = 0

    def read_hello(self) -> dict:
        """The first frame on a control connection, and the only one read before anything is trusted."""
        typ, payload = self.recv()
        if typ != T_HELLO:
            raise ConnectionError("expected HELLO")
        hello = json.loads(payload.decode("utf-8"))
        if hello.get("v") != PROTOCOL_VERSION:
            raise ConnectionError("protocol version mismatch (peer speaks %s, we speak %d)"
                                  % (hello.get("v"), PROTOCOL_VERSION))
        self.device = str(hello.get("device", "?"))
        self.node_id = hello.get("id")
        self.node_type = str(hello.get("type", "?"))
        self.persistent = bool(hello.get("persistent", False))
        self.battery = str(hello.get("battery", "medium"))
        self.peer_port = int(hello.get("port", 0) or 0)
        self.peer_data_out = bool(hello.get("data_out", False))
        audit_hello(self, hello)
        return hello

    def _unauthenticated(self) -> bool:
        """Is this a connection someone else opened that has not yet proved it holds a key?"""
        return not (self.authed or self.initiator)

    def drives_transfer(self) -> bool:
        """Does this end open the data connections for files on this link?

        Exactly one of the two must: both opening moves every file twice, neither opening leaves a
        WANT unanswered forever. If the peer cannot open them we do, whoever dialled; otherwise the
        node that dialled does, because it has already proved it can reach the other's listening
        port, which is precisely what a data connection needs. Both ends decide from the same two
        facts and therefore agree.

        Mirrors Connection.drivesTransfer(), whose `!inbound` is this end's `initiator`.
        """
        return (not self.peer_data_out) or self.initiator

    def _recv_exact(self, n: int) -> bytes:
        buf = bytearray()
        while len(buf) < n:
            # Checked per read, and only before authentication: the socket's own timeout bounds each
            # individual read, and this bounds the sum of them. A slow trickle satisfies every
            # per-read timeout and would otherwise hold a worker for as long as it cared to.
            if self._unauthenticated() and time.monotonic() > self.handshake_deadline:
                raise ConnectionError("handshake did not finish within %d s" % HANDSHAKE_TIMEOUT)
            chunk = self.sock.recv(min(n - len(buf), 256 * 1024))
            if not chunk:
                raise ConnectionError("peer closed")
            buf += chunk
        return bytes(buf)

    @staticmethod
    def _nonce(ctr: int) -> bytes:
        return b"\x00\x00\x00\x00" + struct.pack(">Q", ctr)

    def recv(self):
        (length,) = struct.unpack(">I", self._recv_exact(4))
        # The cap before anything has authenticated is the small one: the length prefix is the first
        # thing an unauthenticated caller controls, and max_frame is sized for a 512 KiB chunk.
        cap = min(self.max_frame, PRE_AUTH_MAX_FRAME) if self._unauthenticated() else self.max_frame
        if length > cap:
            raise ConnectionError(f"frame too large ({length} bytes, cap {cap})")
        ct = self._recv_exact(length)

        if self._candidates is not None:
            # Trial-decrypt with each candidate; the first that succeeds wins.
            for tx, rx, secret in self._candidates:
                try:
                    pt = rx.decrypt(self._nonce(self.rx_ctr), ct, None)
                    self.tx, self.rx = tx, rx
                    self.matched_secret = secret
                    self._candidates = None
                    self.rx_ctr += 1
                    self.authed = True
                    return pt[0], pt[1:]
                except Exception:
                    pass
            raise ConnectionError("no accepted key could authenticate this peer")

        pt = self.rx.decrypt(self._nonce(self.rx_ctr), ct, None)
        self.rx_ctr += 1
        self.authed = True
        return pt[0], pt[1:]

    def send(self, typ: int, payload: bytes = b""):
        with self.send_lock:
            ct = self.tx.encrypt(self._nonce(self.tx_ctr), bytes([typ]) + payload, None)
            self.tx_ctr += 1
            self.sock.sendall(struct.pack(">I", len(ct)) + ct)

    def send_json(self, typ: int, obj: dict):
        self.send(typ, json.dumps(obj, ensure_ascii=False).encode("utf-8"))

    def bye(self, reason: str):
        """
        Close deliberately, telling the peer why first.

        The BYE is the whole point: a close without one becomes a loop. The far side would see only
        a disconnect, reconnect, and rebuild exactly the link that was discarded.

        Closing the socket is what ends the link — the owning thread is blocked in `recv()` and
        unwinds through its own `finally`, so this is safe to call from another thread and needs no
        co-operation from the one that owns the channel.
        """
        try:
            self.send_json(T_BYE, {"reason": reason})
        except Exception:
            pass                       # it is going away regardless; the peer still sees the close
        try:
            self.sock.close()
        except OSError:
            pass
