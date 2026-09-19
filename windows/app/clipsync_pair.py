"""
Pairing on the PC: taking the PSK from a device that has it, and giving it to one that does not.

The flow: the provider opens a window, advertises `_clipsync-pair._tcp` with a random salt in its
TXT record, and shows a nine-digit code.  The joiner browses, picks a device, and types the code.
Both derive the same channel key from the code and the salt, and the joiner then speaks the ordinary
protocol on an ordinary SecureChannel -- HELLO with `role: pair`, then PAIR_ASK -- and gets PAIR_KEY
with the PSK and the service port.  Nothing new is invented for it; only the key differs.

The design originally had the PC only ever *joining*, on the grounds that a provider has to listen
and a listening port on Windows is a firewall rule somebody has to create.  That is still the cost;
it is just not a reason to leave out the case where the PC is the device that was set up first.  So
the provider is here too, and the firewall rule is install.ps1's job -- a **bounded** range from
`port + 1`, the same range `Provider` searches, so the two cannot disagree about which ports are
open.

Free of side effects, like clipsync_config and clipsync_proto, because configurator.py imports it
and must not drag the service's logging and clipboard registration in with it.
"""
import hashlib
import json
import os
import socket
import threading
import time

from cryptography.exceptions import InvalidTag

from clipsync_proto import (PROTOCOL_VERSION, T_HELLO, T_PAIR_ASK, T_PAIR_KEY, SecureChannel)

# Must match Pairing.java exactly -- both ends derive the same key from the same code or nothing
# works, and nothing about the failure would say why.  CODE_DIGITS and all four scrypt parameters
# feed the key derivation, so a mismatch is not a degraded pairing, it is a pairing that fails every
# time and reports "wrong code" for a code that was typed correctly.  They are not negotiated in the
# TXT record on purpose: a joiner that lets the provider choose its own work factor lets an attacker
# advertise N=2.
SERVICE_TYPE = "_clipsync-pair._tcp.local."
TXT_SALT = b"s"
# The advertised protocol version.  Both providers have always published it and nobody read it,
# which made it look like a mechanism when it was decoration.  `find` reads it now, and the reason
# is specific to *pairing*: on an ordinary link a version mismatch costs a connection nobody was
# watching, but here it costs the user reading nine digits off another screen, typing them, and
# waiting out a key derivation -- to be told "wrong code, or the window has closed" about a code
# that was right.  Skipping the device before it is ever offered is the only place that failure can
# be turned into something true.
TXT_VERSION = b"v"

# scrypt, and these four numbers must be BIT-FOR-BIT the same as Pairing.SCRYPT_N / SCRYPT_R /
# SCRYPT_P / DK_BITS on Android.  The reasoning for each is written out there and is not repeated;
# the short version is: 128*R*N = 16 MiB of memory hardness, P spends the remaining sub-second time
# budget without raising that footprint, and 16 MiB was chosen over the obvious 32 MiB precisely
# because 32 MiB is OpenSSL's own default cap and would sit exactly on it.
#
# SCRYPT_P is the one of the four with slack left in it, and the slack is deliberate: it was set
# from an ESTIMATE of how long a phone takes, never a measurement, so it stops at the value whose
# pessimistic estimate is exactly the one-second budget.  Pairing.SCRYPT_P carries the reasoning,
# how to take the measurement, and what to do with the answer -- P can go UP for free once anybody
# has a real figure (linear attacker cost, no extra memory), and the two ends move together or not
# at all.
#
# DK_LEN is in BYTES here and Android's DK_BITS is in BITS (Conscrypt's KeySpec contract wants bits
# and divides by 8 itself).  32 bytes == 256 bits; if one is ever changed the other must move with
# it, and nothing will tell you if it does not.
SCRYPT_N = 1 << 14
SCRYPT_R = 8
SCRYPT_P = 10
DK_LEN = 32
# NOT a derivation parameter -- it is a policy cap on how much scrypt may allocate, so it is the one
# number here that does NOT have to match the other end, and Android could not match it anyway (the
# reflective KeySpec Conscrypt accepts has six fields and max_mem is not one of them; BoringSSL's
# own default of 65 MiB applies there).
#
# It must still be passed, and this is the trap the whole parameter choice was made around:
# hashlib.scrypt defaults maxmem to 0, which means OpenSSL's SCRYPT_MAX_MEM of 32 MiB, and OpenSSL
# needs 128*R*(N+P+2) -- about 16.01 MiB for these numbers, but exactly 32 MiB + change for the
# N=2**15 that was the other candidate.  That one would have raised "memory limit exceeded" here
# while working fine on Android: a pairing broken on one end only, by a default nobody passed.
# 64 MiB is four times what is needed, so no default on either side is load-bearing.
SCRYPT_MAXMEM = 64 * 1024 * 1024
# Nine, restored from the eight one revision spent, and the reasoning is written out in
# Pairing.CODE_DIGITS: the ninth digit is 10x the offline attack cost, and the legibility it used to
# cost is bought back by SHOWING the code in groups of three instead of by shortening it.
#
# It counts DIGITS.  `format_code` below adds two spaces for display, and they are display and
# nothing else: what is generated here, what travels, what is compared and what `channel_key`
# stretches is always the nine digits.  A grouped string reaching the KDF derives a different key
# and is reported to the user as a wrong code, which is why `join` refuses anything that is not
# exactly nine ASCII digits rather than stripping for the caller.
CODE_DIGITS = 9
CODE_SPACE = 10 ** CODE_DIGITS
# Two short JSON frames is all a pairing channel ever carries, so this is also a bound on what an
# unauthenticated peer can make this end allocate.
MAX_FRAME = 4096
CONNECT_TIMEOUT = 5

# --- provider only ---------------------------------------------------------------------------
# How long a window stays open, and how many wrong codes end it early.  Must match Pairing.java:
# they are one agreement seen from two sides, and a device whose window closes while the other is
# still counting down is a bug report nobody can reproduce.
WINDOW_S = 120
MAX_TRIES = 5
# Where the provider listens.  A bounded search from port+1, and bounded is the point: install.ps1
# opens exactly this range, so a port outside it would be found free, bound, advertised -- and then
# silently unreachable behind the firewall, which is the worst of the three possible failures.
# Android has no such problem and simply asks the OS for any free port, which is why the two ends
# are asymmetric here and the asymmetry is not a bug: the port is advertised in the TXT record, so
# the joiner never needs to guess it.
PAIR_PORT_SPAN = 8
# Long enough for the handshake, short enough that a silent caller is not standing in front of the
# person actually pairing: callers are served one at a time.
HANDSHAKE_TIMEOUT = 10
# How long the provider's own user has to approve a caller before it is refused.  Long enough to
# look up from the phone and read a device name, short enough that a caller who has guessed the code
# cannot sit on the window while nobody is at the PC.  Matches PairProvider.java.
ASK_TIMEOUT = 20

# What one served connection turned out to be.  Only ASK_CODE is evidence of anyone guessing, and
# only it is allowed to spend one of MAX_TRIES -- see Provider._run.
ATTEMPT_OK = "ok"
ATTEMPT_CODE = "code"            # the first frame did not decrypt: the code was wrong
ATTEMPT_VERSION = "version"      # it decrypted, so the code was right; the two ends differ in version
ATTEMPT_NETWORK = "network"      # nothing was ever proved either way: the caller went away
ATTEMPT_PROTOCOL = "protocol"    # right code, wrong conversation
ATTEMPT_DECLINED = "declined"    # the person at this PC said no


def channel_key(code: str, salt: bytes) -> bytes:
    """
    The code, stretched.

    Nine digits is 10**9 possibilities: the provider's five-try window bounds *online* guessing,
    and nothing bounds an offline attack on a recorded handshake except the cost of trying a code.
    scrypt makes that cost real.  It does not make it impossible -- a low-entropy secret stays
    low-entropy, and a PAKE is the only real answer -- but it prices the attack out of being done on
    the off-chance, which is the threat that is actually there.

    **The numbers, one RTX 4090 walking the whole code space**, all from a single published hashcat
    v6.2.6 run so they are comparable to each other.  scrypt at exactly our N and r with p=1 is
    hashcat mode 8900 and measures 7 126 H/s; p is that many sequential ROMix passes, so p=10 is
    713 H/s and p=8 is 891 H/s.  The first three lines are HISTORY, kept for the shape of the curve
    and not descriptions of this build:

    * six digits, PBKDF2-HMAC-SHA256 at 200 000 rounds (where this started): **~30 seconds**
    * nine digits, scrypt p=8 (before p was raised): **13 days**
    * eight digits, scrypt p=10 (the one revision the code was shortened for): **1.6 days**
    * nine digits, scrypt p=10 (**here**): **16 days** (expected hit ~8 days)

    So the ninth digit is back and it is 10x on the revision that dropped it; p is unchanged at 10.
    The digit had been traded for two fewer glyphs to read across a room, and grouping the code
    three-and-three (`format_code`) buys that legibility back without paying the factor of ten.  The
    honest summary is a fortnight of one card rather than a day and a half.  The full argument,
    including why the memory footprint rather than the work factor is what does the work, and why p
    stopped at 10, is in the Pairing class comment on the Android side -- it is written out once,
    there, and this end's job is to agree with it.

    **The change also ended an asymmetry that used to be ten to one.**  PBKDF2 was a few hundred
    milliseconds here (`hashlib.pbkdf2_hmac` is C on top of OpenSSL) and 1.5-4 seconds on Android,
    because Conscrypt implements no PBKDF2 at all and the call fell through to the bundled pure-Java
    BouncyCastle.  Both ends now run native scrypt for something under a second each.  That is the
    *reason* minSdk had to go to 35: AOSP's Conscrypt first registers `SecretKeyFactory.SCRYPT` on
    the android15 branch, so below Android 15 this algorithm does not exist and the app would throw
    NoSuchAlgorithmException rather than fall back.  Nothing on THIS end was ever the obstacle --
    `hashlib.scrypt` has been available since CPython 3.6 wherever OpenSSL is 1.1+.

    The symmetry does not make the timeouts in this file re-readable as generous.  A mid-range phone
    is not the slowest phone, and the waits that dominate a pairing were never the derivation: a
    four-second browse, and up to ASK_TIMEOUT of a person at the other device deciding.  What has not
    changed at all is the structural point: both implementations derive the key *before* opening a
    socket, so the derivation is never inside anybody else's timeout.
    """
    # maxmem is explicit and must be -- see SCRYPT_MAXMEM.  dklen is in bytes here; Android's
    # equivalent is in bits.  ASCII, matching Java's `new String(password).getBytes("UTF-8")` over a
    # char[] of digits: for [0-9] the two encodings are byte-identical, which is the only reason the
    # two ends agree, and the reason `join` below refuses a code that is not ASCII digits.
    return hashlib.scrypt(code.encode("ascii"), salt=salt, n=SCRYPT_N, r=SCRYPT_R, p=SCRYPT_P,
                          maxmem=SCRYPT_MAXMEM, dklen=DK_LEN)


def format_code(code: str) -> str:
    """
    The code as a person should see it: "123456789" -> "123 456 789".

    **Display only, and the distinction is load-bearing.**  A nine-digit run is read back wrong and
    read *aloud* worse, and somebody copying a code off one device into another does both -- so the
    two spaces go in here, at the last moment before a label, and nowhere else.  What is generated,
    typed, compared and stretched by `channel_key` is always the nine digits (CODE_DIGITS).
    Feeding the result of this function to a KDF derives a different key on one end only, and the
    user is told their code was wrong.

    A plain U+0020 rather than a thin space: both ends draw the code in a monospace font, where
    every character has the same advance, so the width of the result is computable -- and the
    Android sheet does compute it, against a layout budget eleven characters only just fit (see
    pair_code in sheet_pair.xml).  U+2009 would measure a full advance anyway where the font has it
    and something unpredictable where it does not.
    """
    return " ".join(code[i:i + 3] for i in range(0, len(code), 3))


def find(timeout: float = 4.0) -> list:
    """
    Browse for devices offering to pair.

    Devices that advertise a protocol version this build cannot speak are left out -- see
    TXT_VERSION.  That means an empty result has two causes, not one, and a caller that only offers
    "check the other device has its window open" will be wrong about the second; the configurator's
    wording names both.

    :return: [(display name, [(host, port), ...], salt bytes)], newest resolution last
    :raises RuntimeError: if zeroconf is not installed -- the same dependency the advertiser needs
    """
    try:
        from zeroconf import ServiceBrowser, ServiceListener, Zeroconf
    except ImportError:
        raise RuntimeError("mDNS needs the 'zeroconf' package (pip install zeroconf)")

    found = {}

    class Listener(ServiceListener):
        def _resolve(self, zc, type_, name):
            info = zc.get_service_info(type_, name, timeout=2000)
            if info is None:
                return
            props = info.properties or {}
            raw = props.get(TXT_SALT)
            if not raw:
                return                       # not one of ours, or a truncated record
            # Only an explicit disagreement is a reason to hide a device.  A record without `v` is
            # an older build, not an incompatible one, and the handshake is still the authority on
            # whether the two can talk -- this is an early exit from a case we can already name, not
            # a second gate that gets to veto.  Erring the other way would make a device silently
            # invisible because its TXT record was truncated by a flaky resolver.
            ver = props.get(TXT_VERSION)
            if ver is not None and ver.decode("ascii", "replace") != str(PROTOCOL_VERSION):
                return
            # from_hex, not bytes(): the TXT value is the salt written as 32 hex characters, so
            # bytes() gives the ASCII of the digits rather than the sixteen bytes they spell.  The
            # two ends then derive different keys from the same correct code, the joiner's HELLO
            # fails to decrypt, the provider closes -- and every code, right or wrong, reports
            # "peer closed".  Nothing about that failure points at this line, which is why it is
            # worth a paragraph.
            try:
                salt = bytes.fromhex(raw.decode("ascii"))
            except (UnicodeDecodeError, ValueError):
                return                       # malformed: refusing beats guessing a salt
            addrs = [(socket.inet_ntop(socket.AF_INET6 if len(a) == 16 else socket.AF_INET, a), info.port)
                     for a in info.addresses]
            if addrs:
                found[name] = (info.name.split(".")[0], addrs, salt)

        def add_service(self, zc, type_, name):
            self._resolve(zc, type_, name)

        def update_service(self, zc, type_, name):
            self._resolve(zc, type_, name)

        def remove_service(self, zc, type_, name):
            found.pop(name, None)

    zc = Zeroconf()
    try:
        ServiceBrowser(zc, SERVICE_TYPE, Listener())
        time.sleep(timeout)                  # the whole window: several devices do not answer together
    finally:
        zc.close()
    return list(found.values())


def join(addrs, salt: bytes, code: str, device: str) -> dict:
    """
    Ask one provider for the key.

    A wrong code does not produce a "wrong code" message and cannot: the code *is* the channel key,
    so getting it wrong fails at the first frame as a decrypt error.  That is the design working --
    there is no cheaper way to test a guess than by spending one of the provider's five attempts --
    and it is why the error raised here says what the user can act on rather than what the cipher
    reported.

    :param addrs: every address of ONE device, tried in turn.  Not raced: an attempt that reaches
                  the far end with the wrong code costs one of its five.
    :return: the PAIR_KEY payload -- {"psk", "device", "type"}
    """
    # Checked here as well as in the window that collected it: a code of the wrong length cannot be
    # right, and finding that out from the provider would spend one of its five attempts on a typo.
    # isascii() as well as isdigit(), because isdigit() is true of ٤ and ４ and channel_key encodes
    # the code as ASCII.
    #
    # It does NOT strip the display grouping, deliberately, although the code is shown as
    # "123 456 789" and will be typed that way.  Dropping the spaces belongs to the window that
    # collected the string, one step up; doing it here as well would make this function accept the
    # display form, and "the display form is acceptable input" is exactly the belief that ends with
    # somebody handing a spaced string to channel_key.  Nine ASCII digits or nothing.
    if len(code) != CODE_DIGITS or not (code.isascii() and code.isdigit()):
        raise ValueError("the code is %d digits" % CODE_DIGITS)
    key = channel_key(code, salt)
    last = None
    for host, port in addrs:
        # The connect is separated from everything after it, and the split is exactly where the
        # meaning changes.  Only reaching the address can fail in a way another address might fix;
        # once a socket is open we are talking to the device, and the nonce exchange is plaintext,
        # so a failure past this point is the code being wrong -- which every address would report
        # identically while spending one of the provider's five attempts each time.
        #
        # Getting that boundary wrong is what turned a wrong code into "no address answered: peer
        # closed": a provider that cannot decrypt closes the socket, the joiner's next read raises
        # ConnectionError, and ConnectionError is an OSError, so it was being read as "unreachable".
        try:
            sock = socket.create_connection((host, port), timeout=CONNECT_TIMEOUT)
        except OSError as e:
            last = e
            continue
        ch = None
        try:
            sock.settimeout(CONNECT_TIMEOUT)
            ch = SecureChannel(sock, key, MAX_FRAME, initiator=True)
            # role=pair, and nothing a configured node would claim: no id, no cursor, no capability
            # flags.  A device without a key has no business enrolling itself into the machinery
            # those fields drive.
            ch.send_json(T_HELLO, {"v": PROTOCOL_VERSION, "role": "pair", "device": device, "type": "pc"})
            ch.send(T_PAIR_ASK)
            # The wait for PAIR_KEY is a person, not a network. The provider now shows its user who
            # is asking before it sends the key, so five seconds -- fine for a handshake -- would
            # time this end out while somebody was still reading a device name off a dialog, and the
            # failure would be reported to them as a wrong code. ASK_TIMEOUT is how long that can
            # take, plus one connect timeout of slack for the frame itself.
            ch.sock.settimeout(ASK_TIMEOUT + CONNECT_TIMEOUT)
            typ, payload = ch.recv()
            if typ != T_PAIR_KEY:
                raise ConnectionError("expected PAIR_KEY, got frame %d" % typ)
            return json.loads(payload.decode("utf-8"))
        except Exception as e:
            # Three endings share this one message because the wire cannot tell them apart: a wrong
            # code, a window that has closed, and -- since the provider started asking its user
            # before handing the key over -- a person who pressed Cancel.  All three are a socket
            # that closes before PAIR_KEY arrives.
            raise ConnectionError(
                "wrong code, or the other device refused, or the pairing window has closed") from e
        finally:
            if ch is not None:
                try:
                    ch.sock.close()
                except OSError:
                    pass
            else:
                sock.close()
    raise ConnectionError("could not reach it: %s" % last)


# ------------------------------------------------------------------------------- provider
def _local_addresses() -> list:
    """Every non-loopback address of this host, packed, for the advertisement."""
    out = []
    try:
        infos = socket.getaddrinfo(socket.gethostname(), None)
    except socket.gaierror:
        return out
    for family, _, _, _, sockaddr in infos:
        try:
            packed = socket.inet_pton(family, sockaddr[0].split("%")[0])
        except (OSError, ValueError):
            continue
        if packed not in out:
            out.append(packed)
    return out


class Provider:
    """
    Offer this PC's key for a while.

    The mirror of PairProvider.java, and the same three bounds: a window, five attempts, and one
    caller served at a time.  Pairing is something a person does with two devices in front of them,
    so there is no concurrency to serve -- and not spawning a thread per connection is what makes a
    flood cost nothing but a queue.

    Only a wrong code counts towards the five.  It used to be every failure, on the reasoning that a
    dropped connection is indistinguishable from a guess -- but it is distinguishable, and cheaply:
    the first frame either decrypts or it does not.  Counting the rest meant that a phone which lost
    Wi-Fi mid-handshake, or one refused for speaking the wrong protocol version, silently spent the
    window the user was standing there waiting on, and the error they finally saw said "too many
    wrong codes" about a code they never mistyped.  `on_attempt` is what stops those failures being
    silent; `_serve` is where they are told apart.
    """

    def __init__(self, psk_hex: str, base_port: int, device: str, on_ask, on_paired, on_closed,
                 on_attempt=None):
        """
        Opens the window.  Blocking -- the key derivation is deliberately slow, and binding and
        advertising are network calls -- so call it off the UI thread.

        :param on_ask: (device, type) -> bool, called on the provider's thread BEFORE the key is
                       sent, and blocking until the person at this PC answers.  Knowing the code is
                       not consent: a caller that has guessed it, or one the user did not mean to
                       pair, gets as far as a device name on screen and no further.  Returning False
                       -- which is also what a timeout must return -- refuses without spending an
                       attempt.
        :param on_paired: (device, type) -> None, ONCE PER DEVICE.  The window does not end on a
                          success: setting up three devices is the ordinary case, and closing after
                          the first would mean a new window and a new code read out for each of the
                          others -- two minutes of work to save nothing.
        :param on_closed: burned: bool -> None, once, when the window is over
        :param on_attempt: (outcome, detail) -> None for every connection that did not end in a
                           key being handed over, on the provider's thread.  Optional only because
                           a caller that shows nothing is a choice; making it required would not
                           make anyone read it.
        """
        try:
            from zeroconf import IPVersion, ServiceInfo, Zeroconf
        except ImportError:
            raise RuntimeError("mDNS needs the 'zeroconf' package (pip install zeroconf)")

        self._psk = psk_hex
        self._port = base_port          # the SERVICE port, handed over with the key
        self._device = device
        self._on_ask = on_ask
        self._on_paired = on_paired
        self._on_closed = on_closed
        self._on_attempt = on_attempt
        self._closed = False
        self._failures = 0

        # Rejection-sampled rather than reduced modulo 10**9, matching Pairing.java: 2**32 is not a
        # multiple of 1e9, so the plain modulo favours the low values.  The bias is tiny and free to
        # avoid, and impossible to explain away afterwards.  Leading zeros kept, because a code the
        # user reads as nine digits is a code they will type as nine.  (Four bytes are still enough,
        # with less room than there was: 10**9 < 2**32 by a factor of 4.29, so the limit is 4e9 and
        # the loop takes a second turn about 7% of the time -- a few extra reads of os.urandom, once
        # per pairing window.  A tenth digit would not fit in four bytes at all.)
        limit = (1 << 32) - ((1 << 32) % CODE_SPACE)
        while True:
            n = int.from_bytes(os.urandom(4), "big")
            if n < limit:
                break
        self.code = "%0*d" % (CODE_DIGITS, n % CODE_SPACE)
        salt = os.urandom(16)
        # Once per window, not once per connection: being slow and 16 MiB heavy is the point of the
        # exercise, and paying it per caller would let anyone on the network cost this PC the better
        # part of a second of the one thread that serves everybody, just by opening a socket.  (The
        # same line now costs the phone about the same -- see channel_key; it used to cost it
        # several seconds.)
        self._key = channel_key(self.code, salt)

        self._sock, self.port = self._bind(base_port)
        self._sock.settimeout(1.0)          # so the accept loop can notice close() promptly

        self._zc = Zeroconf(ip_version=IPVersion.All)
        self._info = ServiceInfo(
            SERVICE_TYPE, "%s.%s" % (device, SERVICE_TYPE),
            addresses=_local_addresses(), port=self.port,
            properties={b"v": str(PROTOCOL_VERSION).encode(), TXT_SALT: salt.hex().encode()},
            server="%s.local." % device)
        self._zc.register_service(self._info, allow_name_change=True)

        # Stamped here, immediately before the loop starts counting: everything above has already
        # spent some of the window, and a countdown started from "now" by the UI would run late and
        # still be showing seconds after this had closed.
        self.closes_at = time.monotonic() + WINDOW_S
        self._thread = threading.Thread(target=self._run, name="clipsync-pair-provider", daemon=True)
        self._thread.start()

    @staticmethod
    def _bind(base_port: int):
        """
        The first free port in the range install.ps1 opened.

        Bounded, and an honest error if none is free, rather than walking to 65535: in practice the
        first candidate is always free, and an unbounded scan would find a port the firewall has
        never heard of -- which fails later, silently, and looks like a pairing problem.
        """
        last = None
        for port in range(base_port + 1, base_port + 1 + PAIR_PORT_SPAN):
            s = socket.socket(socket.AF_INET6, socket.SOCK_STREAM)
            try:
                s.setsockopt(socket.IPPROTO_IPV6, socket.IPV6_V6ONLY, 0)
                # See exclusive_bind() in clipsync.py for the whole argument; restated rather than
                # imported because this module deliberately does not import clipsync.py. Short
                # version: on Windows SO_REUSEADDR lets a *later* binder take the port, and setting
                # it on the first binder is what makes that possible
                # (https://learn.microsoft.com/en-us/windows/win32/winsock/using-so-reuseaddr-and-so-exclusiveaddruse).
                # It matters more here than anywhere else in the project: this listener is the one
                # that hands out the PSK, and the whole point of the scan below is to find a port
                # nobody else has. SO_REUSEADDR turned "taken" into "shared", so a port that looked
                # free could already belong to something else; the exclusive flag is what makes the
                # loop's answer mean what it says.
                opt = getattr(socket, "SO_EXCLUSIVEADDRUSE", None)
                if opt is not None:
                    s.setsockopt(socket.SOL_SOCKET, opt, 1)
                elif os.name != "nt" and hasattr(socket, "SO_REUSEADDR"):
                    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
                s.bind(("::", port))
                s.listen(4)
                return s, port
            except OSError as e:
                last = e
                s.close()
        raise RuntimeError("no free port in %d-%d for pairing: %s"
                           % (base_port + 1, base_port + PAIR_PORT_SPAN, last))

    def _run(self):
        burned = False
        try:
            while not self._closed and time.monotonic() < self.closes_at:
                try:
                    conn, _ = self._sock.accept()
                except socket.timeout:
                    continue                 # just the 1 s poll; the while condition is the window
                except OSError:
                    break                    # closed from under us
                outcome, detail = self._serve(conn)
                if outcome == ATTEMPT_OK:
                    # A success does NOT end the window; the next device wants the same key and the
                    # same code is still on screen.  It does clear the strikes, because five wrong
                    # codes means someone guessing, and a caller who has just proved it knows the
                    # code is evidence that nobody was.
                    self._failures = 0
                    continue
                if self._on_attempt is not None:
                    try:
                        self._on_attempt(outcome, detail)
                    except Exception:
                        pass             # a UI that throws must not close the pairing window
                if outcome != ATTEMPT_CODE:
                    continue             # not a guess, so not one of the five
                self._failures += 1
                if self._failures >= MAX_TRIES:
                    burned = True
                    break
        finally:
            self._shut()
            # Only for an ending nobody asked for: a window the caller closed itself needs no
            # telling, and each success was announced as it happened.
            if not self._closed:
                self._closed = True
                self._on_closed(burned)
            self._closed = True

    def _serve(self, conn):
        """
        One caller, start to finish.

        :return: (outcome, detail) -- one of the ATTEMPT_* constants, and a line for the user.
                 Every exit tells the caller apart from the others on purpose: this used to be a
                 single `except Exception: return False`, and the cost of that one line was that a
                 version mismatch, a dropped socket and a wrong code were all reported as, and
                 charged as, a wrong code.
        """
        ch = None
        try:
            conn.settimeout(HANDSHAKE_TIMEOUT)
            try:
                ch = SecureChannel(conn, self._key, MAX_FRAME)
            except OSError as e:
                # The nonce exchange is plaintext, so nothing that fails here says anything about
                # the code.  (ConnectionError is an OSError, and both land here.)
                return ATTEMPT_NETWORK, str(e)
            try:
                hello = ch.read_hello()
            except InvalidTag:
                # THE one failure that is evidence of guessing: the frame arrived whole and did not
                # authenticate under the key this window's code derives.
                return ATTEMPT_CODE, "the code did not match"
            except ConnectionError as e:
                # read_hello raises ConnectionError for two unrelated things, and rx_ctr tells them
                # apart without parsing the message: it only advances once a frame has decrypted, so
                # a non-zero counter means the caller already proved it knows the code and this is a
                # version or protocol disagreement, while zero means the socket died before anything
                # was proved either way.
                if not ch.rx_ctr:
                    return ATTEMPT_NETWORK, str(e)
                return (ATTEMPT_VERSION if "version" in str(e) else ATTEMPT_PROTOCOL), str(e)
            except OSError as e:
                return ATTEMPT_NETWORK, str(e)
            except Exception as e:       # noqa: BLE001 - malformed JSON and the like
                return ATTEMPT_PROTOCOL, str(e)

            if hello.get("role") != "pair":
                return ATTEMPT_PROTOCOL, "not a pairing client"
            typ, _ = ch.recv()
            if typ != T_PAIR_ASK:
                return ATTEMPT_PROTOCOL, "expected PAIR_ASK, got frame %d" % typ
            # The last gate, and the only one a correct code does not open: the person at this PC
            # sees who is asking before the key leaves it.  PairProvider.java asks in the same place,
            # for the same reason -- the code is a channel key, not an authorisation.
            if not self._on_ask(ch.device, ch.node_type):
                return ATTEMPT_DECLINED, "%s was refused at this PC" % ch.device
            # The port goes with the key: it is the other half of being able to connect at all, and
            # the one setting a joiner cannot discover or negotiate.  Two devices that agree on the
            # key and disagree on the port never meet.
            ch.send_json(T_PAIR_KEY, {"psk": self._psk, "port": self._port,
                                      "device": self._device, "type": "pc"})
            self._on_paired(ch.device, ch.node_type)
            return ATTEMPT_OK, ""
        except OSError as e:
            return ATTEMPT_NETWORK, str(e)
        except Exception as e:           # noqa: BLE001 - nothing may take the window down with it
            return ATTEMPT_PROTOCOL, str(e)
        finally:
            try:
                (ch.sock if ch is not None else conn).close()
            except OSError:
                pass

    def _shut(self):
        """Take the window down.  Idempotent, and that is the whole of the fix below.

        The window can end three ways -- the clock, MAX_TRIES, and close() from the UI -- and the
        first two race the third, so this used to run twice.  It also used to call
        `unregister_service(self._info)` before `close()`, which was redundant: Zeroconf.close()
        unregisters everything that Zeroconf registered, goodbye packets and all.

        Redundant *and* noisy.  `unregister_service` schedules `Zeroconf.async_unregister_service`
        onto Zeroconf's event loop and waits for it; on the second call that loop is already gone,
        so it raised with the coroutine never awaited, and the bare `except: pass` here swallowed
        the exception -- leaving Python to print "coroutine 'Zeroconf.async_unregister_service' was
        never awaited" from a line number that pointed at the `pass` rather than at the cause.
        Taking the Zeroconf out of the field first means the second caller finds nothing to do.
        """
        zc, self._zc = self._zc, None
        if zc is not None:
            try:
                zc.close()
            except Exception:       # noqa: BLE001 - teardown must not take the window with it
                pass
        try:
            self._sock.close()
        except OSError:
            pass

    def close(self):
        """Give up on the window early -- the user closed the dialog, or pressed Cancel."""
        if self._closed:
            return
        self._closed = True
        self._shut()
