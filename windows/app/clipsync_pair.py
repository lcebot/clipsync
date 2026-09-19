"""
Pairing on the PC: taking the PSK from a device that has it, and giving it to one that does not.

§12 said the PC would only ever join, on the grounds that a provider has to listen and a listening
port on Windows is a firewall rule somebody has to create.  That is still the cost; it is just not a
reason to leave out the case where the PC is the device that was set up first.  So the provider is
here too, and the firewall rule is install.ps1's job -- a **bounded** range from `port + 1`, the same
range `Provider` searches, so the two cannot disagree about which ports are open.

Free of side effects, like clipsync_config and clipsync_proto, because configurator.py imports it
and must not drag the service's logging and clipboard registration in with it.
"""
import hashlib
import json
import os
import socket
import threading
import time

from clipsync_proto import (PROTOCOL_VERSION, T_HELLO, T_PAIR_ASK, T_PAIR_KEY, SecureChannel)

# Must match Pairing.java exactly -- both ends derive the same key from the same code or nothing
# works, and nothing about the failure would say why.
SERVICE_TYPE = "_clipsync-pair._tcp.local."
TXT_SALT = b"s"
ROUNDS = 200_000
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
# Android has no such problem and simply asks the OS for any free port (docs/p2p-plan.md §12).
PAIR_PORT_SPAN = 8
# Long enough for the handshake, short enough that a silent caller is not standing in front of the
# person actually pairing: callers are served one at a time.
HANDSHAKE_TIMEOUT = 10


def channel_key(code: str, salt: bytes) -> bytes:
    """
    The code, stretched.

    Six digits is a million possibilities: the provider's five-try window bounds *online* guessing,
    and nothing bounds an offline attack on a recorded handshake except the cost of trying a code.
    PBKDF2 makes that cost real.  It does not make it impossible -- a low-entropy secret stays
    low-entropy, and a PAKE is the only real answer -- but it moves a few seconds of CPU to
    something a casual listener on the same Wi-Fi will not pay.  (docs/p2p-plan.md §12)
    """
    return hashlib.pbkdf2_hmac("sha256", code.encode("ascii"), salt, ROUNDS, 32)


def find(timeout: float = 4.0) -> list:
    """
    Browse for devices offering to pair.

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
            raw = (info.properties or {}).get(TXT_SALT)
            if not raw:
                return                       # not one of ours, or a truncated record
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

    :param addrs: every address of ONE device, tried in turn.  Not raced: each attempt that reaches
                  the far end costs one of its five.
    :return: the PAIR_KEY payload -- {"psk", "device", "type"}
    """
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
            typ, payload = ch.recv()
            if typ != T_PAIR_KEY:
                raise ConnectionError("expected PAIR_KEY, got frame %d" % typ)
            return json.loads(payload.decode("utf-8"))
        except Exception as e:
            raise ConnectionError("wrong code, or the pairing window has already closed") from e
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

    Every failure counts towards the five, including the ones that are not guesses: a dropped
    connection is indistinguishable from a wrong code from this side, and erring towards closing the
    window early is the safe direction when the thing being guarded is the key itself.
    """

    def __init__(self, psk_hex: str, base_port: int, device: str, on_paired, on_closed):
        """
        Opens the window.  Blocking -- the key derivation is deliberately slow -- so call it off the
        UI thread.

        :param on_paired: (device, type) -> None, once, when the key has been given away
        :param on_closed: burned: bool -> None, once, if the window ends without pairing anyone
        """
        try:
            from zeroconf import IPVersion, ServiceInfo, Zeroconf
        except ImportError:
            raise RuntimeError("mDNS needs the 'zeroconf' package (pip install zeroconf)")

        self._psk = psk_hex
        self._device = device
        self._on_paired = on_paired
        self._on_closed = on_closed
        self._closed = False
        self._failures = 0

        # Rejection-sampled rather than reduced modulo a million, matching Pairing.java: 2**32 is not
        # a multiple of 1e6, so the plain modulo favours the low values.  The bias is tiny and free
        # to avoid, and impossible to explain away afterwards.  Leading zeros kept, because a code
        # the user reads as five digits is a code they will type as five.
        limit = (1 << 32) - ((1 << 32) % 1_000_000)
        while True:
            n = int.from_bytes(os.urandom(4), "big")
            if n < limit:
                break
        self.code = "%06d" % (n % 1_000_000)
        salt = os.urandom(16)
        # Once per window, not once per connection: 200 000 PBKDF2 rounds is the point of the
        # exercise, and paying it per caller would let anyone on the network cost this PC a tenth of
        # a second by opening a socket.
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
        burned = paired = False
        try:
            while not self._closed and time.monotonic() < self.closes_at:
                try:
                    conn, _ = self._sock.accept()
                except socket.timeout:
                    continue                 # just the 1 s poll; the while condition is the window
                except OSError:
                    break                    # closed from under us
                if self._serve(conn):
                    paired = True
                    break
                self._failures += 1
                if self._failures >= MAX_TRIES:
                    burned = True
                    break
        finally:
            self._shut()
            # Exactly one ending is reported: the success was announced from _serve, and a window the
            # caller closed itself needs no telling.
            if not paired and not self._closed:
                self._closed = True
                self._on_closed(burned)
            self._closed = True

    def _serve(self, conn) -> bool:
        ch = None
        try:
            conn.settimeout(HANDSHAKE_TIMEOUT)
            ch = SecureChannel(conn, self._key, MAX_FRAME)
            hello = ch.read_hello()
            if hello.get("role") != "pair":
                raise ConnectionError("not a pairing client")
            typ, _ = ch.recv()
            if typ != T_PAIR_ASK:
                raise ConnectionError("expected PAIR_ASK, got %d" % typ)
            ch.send_json(T_PAIR_KEY, {"psk": self._psk, "device": self._device, "type": "pc"})
            self._on_paired(ch.device, ch.node_type)
            return True
        except Exception:
            return False
        finally:
            try:
                (ch.sock if ch is not None else conn).close()
            except OSError:
                pass

    def _shut(self):
        try:
            self._zc.unregister_service(self._info)
        except Exception:
            pass
        try:
            self._zc.close()
        except Exception:
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
