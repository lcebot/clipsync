"""
The PC as a joiner: taking the PSK from a device that already has it.

**Joins only, never offers.**  §12 settles this and it is worth repeating where the code is: a
provider has to listen, which on Windows means a port Windows Firewall has to be told about and
install.ps1 has to open.  A joiner opens nothing.  The PC is also the device most likely to be set
up second, and it can already browse mDNS — so the whole provider half buys one scenario (starting
from the PC) at the cost of a firewall rule, and is simply left out.

Free of side effects, like clipsync_config and clipsync_proto, because configurator.py imports it
and must not drag the service's logging and clipboard registration in with it.
"""
import hashlib
import json
import socket
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
            salt = (info.properties or {}).get(TXT_SALT)
            if not salt:
                return                       # not one of ours, or a truncated record
            addrs = [(socket.inet_ntop(socket.AF_INET6 if len(a) == 16 else socket.AF_INET, a), info.port)
                     for a in info.addresses]
            if addrs:
                found[name] = (info.name.split(".")[0], addrs, bytes(salt))

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
        ch = None
        try:
            sock = socket.create_connection((host, port), timeout=CONNECT_TIMEOUT)
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
        except OSError as e:
            last = e                         # could not reach this address; another may work
        except Exception as e:
            # Reached it, and the channel would not open.  Every address of this device fails the
            # same way, and each attempt spends one of its five -- so stop rather than burn them all
            # proving it.
            raise ConnectionError("wrong code, or the pairing window has already closed") from e
        finally:
            if ch is not None:
                try:
                    ch.sock.close()
                except OSError:
                    pass
    raise ConnectionError("no address answered: %s" % last)
