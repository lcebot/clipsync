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

The Android side of all of this is Connection.java; the two files are read together.
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
    "BYE_IDLE", "PROTOCOL_VERSION", "READ_TIMEOUT", "hkdf_sha256", "SecureChannel",
]

log = logging.getLogger("clipsync")

# Frame types, grouped by purpose and renumbered for the draft protocol.  No external release has
# ever shipped, so nothing reads the old numbers.

# Session lifecycle.
T_HELLO = 1
# "I am closing this connection, and here is why" - {reason}.  A close without one becomes a loop:
# the far side sees only a disconnect, reconnects, and rebuilds the link that was discarded (§5).
T_BYE = 2
T_PING, T_PONG = 3, 4
# The reason a device sends as it goes to sleep, and the one BYE that does not mean "this link was
# redundant".  A device that says it is idle will dial out again when its screen comes on, so the
# right answer is to stop dialling it, not to look for another route to it.  (docs/p2p-plan.md §5)
BYE_IDLE = "idle"

# Key rotation (docs/p2p-plan.md §17).  Sent after HELLO, carrying {psk, since, next}.
T_KEYS = 5

# Clipboard.
T_CLIP = 6

# File transfer.
T_OFFER, T_WANT, T_HAVE, T_SKIP = 7, 8, 9, 10
T_CHUNK, T_PULL, T_END, T_ABORT = 11, 12, 13, 14

# Pairing (docs/p2p-plan.md §12): ask for the key, and here it is.  Ordinary frames on an ordinary
# channel, reached after an ordinary handshake and an ordinary HELLO -- only the key differs.
T_PAIR_ASK, T_PAIR_KEY = 15, 16
# 2: HELLO is exchanged in both directions and carries the node id, type, persistence and battery
# bucket (docs/p2p-plan.md §2). A clean break, by §9 — a version 1 peer is refused rather than
# tolerated, because a peer that cannot name itself cannot be deduplicated or recognised as self.
# 3: HELLO also carries `port` and `data_out`, and every device listens.  The bump is not
# bookkeeping: both new fields have defaults, so a version-2 peer would connect and work, and then
# open its own data connections for a file at the same moment as the other end opens its, because
# the rule that stops that is the field it does not send.  Every file would move twice.  A version
# check turns that into one refused connection with a plain message.
PROTOCOL_VERSION = 3
READ_TIMEOUT = 90          # seconds without any frame -> drop client


# ----------------------------------------------------------------------------- crypto
def hkdf_sha256(ikm: bytes, salt: bytes, info: bytes, length: int = 32) -> bytes:
    prk = hmac.new(salt, ikm, hashlib.sha256).digest()
    out, t, i = b"", b"", 1
    while len(out) < length:
        t = hmac.new(prk, t + info + bytes([i]), hashlib.sha256).digest()
        out += t
        i += 1
    return out[:length]


class SecureChannel:
    """
    One TCP connection with per-direction AEAD keys and nonce counters.

    `initiator` says which half of the nonce exchange to perform. The two key labels are named for
    who opened the connection, not for who is a server — this PC now dials as well as accepts, and a
    channel it dialled is the "c" side of its own link. Getting this backwards does not fail at the
    handshake, which exchanges plaintext nonces; it fails at the first frame, as a decrypt error.

    `psk` is whatever secret this channel is keyed on, and it is not always the PSK: a pairing
    channel passes a key derived from the six-digit code instead, and everything below — the nonces,
    the per-direction keys, the AEAD, the replay resistance — comes along unchanged.  That is the
    whole of what makes pairing possible without new machinery, and a caller without the code fails
    here in exactly the way a wrong PSK does.  (docs/p2p-plan.md §12)

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
        self.limit = 0                 # file size limit for this client (set after HELLO)
        self.opened = time.monotonic()  # which of two links to one peer is the older (§5 rule 3)
        # Set when this link lost a duplicate tiebreak and was closed from another thread. The owning
        # thread sees only a closed socket, which is indistinguishable from the peer going away —
        # and a dialler that cannot tell those apart redials into the link it just lost.
        self.superseded = False
        self.matched_secret = None     # the secret that authenticated (set on first recv)
        self.send_lock = threading.Lock()
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
        return hello

    def _recv_exact(self, n: int) -> bytes:
        buf = bytearray()
        while len(buf) < n:
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
        if length > self.max_frame:
            raise ConnectionError(f"frame too large ({length} bytes)")
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
                    return pt[0], pt[1:]
                except Exception:
                    pass
            raise ConnectionError("no accepted key could authenticate this peer")

        pt = self.rx.decrypt(self._nonce(self.rx_ctr), ct, None)
        self.rx_ctr += 1
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
        a disconnect, reconnect, and rebuild exactly the link that was discarded. (§5)

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
