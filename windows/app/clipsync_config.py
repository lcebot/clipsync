"""
ClipSync - Windows configuration: defaults, parsing, validation, and writing config.json.

**JSON, and configurator.py is the documentation.** The file underneath a settings window should be
the one that is cheapest to read and write correctly — native types instead of strings-that-mean-
numbers, a real list instead of a comma-joined one, a parser from the standard library. A key written
twice, a BOM, a value containing a comma, an in-place rewrite that has to preserve comments: none of
it is a problem for a file that is not pretending to be prose.

**Nothing here migrates anything** — see the note above the field checks. No migration is written,
for anything, ever: old keys are simply not read, and the release notes say to set the devices up
again.

Split out of clipsync.py so that `configurator.py` can import the rules rather than restate them.
Importing clipsync.py is not an option for a settings window: it configures logging into the
service's own log file and registers a clipboard format on load, so merely opening the settings
would start writing to the running service's log.

**This module must stay free of side effects.** No logging setup, no ctypes calls, no sockets, no
directories created — nothing but constants, functions and one class. Two processes import it.

The check_* functions mirror Config.java's on the Android side one for one, including the wording of
the messages: the same rule explained two different ways is the same bug reported twice. They return
None when the value is good and a short lower-case phrase when it is not, so a caller can write
"port: " + problem.
"""
import ipaddress
import json
import os
import re
import time

HERE = os.path.dirname(os.path.abspath(__file__))
CONFIG_PATH = os.path.join(HERE, "config.json")
LOG_PATH = os.path.join(HERE, "clipsync.log")

# Chunk size for file transfer. It lives here because Cfg.max_frame is derived from it; clipsync.py
# imports it back rather than keeping a second copy that could drift.
CHUNK = 512 * 1024

# Every key the service reads, with the value used when the file does not name it, in the type it is
# stored as. A missing key falls back to the value here, so a config.json that names only some keys
# still works. (No template is carried in the repository: config.json holds a real PSK and is
# ignored by git.)
DEFAULTS = {
    "port": 47521,
    "psk": "",
    # The key rotation schedule, persisted so it survives a restart: when the current key became
    # active, the successor that has been announced but not yet promoted, the ring of superseded
    # keys still accepted, the promotion deadline, and whether a peer has acknowledged the
    # successor. Read whether or not rotate is on, because the ring still applies: a device that
    # rotated and then had rotation turned off must go on accepting the keys it has already
    # superseded.
    "psk_rotate": False,
    "psk_since": 0,
    "psk_next": "",
    "psk_old": [],
    "psk_retire": 0,
    "psk_agreed": 0,
    "max_bytes": 1024 * 1024,
    "max_file_bytes": 10 * 1024 * 1024,
    "max_file_bytes_local": 100 * 1024 * 1024,
    "files_dir": "received",
    "keep_hours": 2,
    "keep_max_mb": 256,
    "discovery": True,
    "mdns_name": "",
    "direct": False,
    "peers": [],
    # The names and literals that point at THIS machine — typically a domain a dynamic DNS client
    # here keeps pointed at it, but a static address or a LAN name serves as well. Two things read
    # it: validation refuses an entry typed into `peers` that matches one, and the dialler skips
    # such a target without opening a socket. Both are the cheap half of the self-connection guard
    # — the authority is still the id exchanged in HELLO, because a second name for this host, or a
    # LAN address that happens to be this machine today, is not in the list and still reaches the
    # handshake. Matching is a string comparison on the normalised form (lower-cased, brackets
    # stripped) and never a DNS lookup, because validation runs on every keystroke.
    #
    # No switch of its own: empty already means "this device has no name of its own", and turning
    # such a list off could only cause the mistakes it exists to prevent.
    "own_addresses": [],
    "start_delay": 0,
    # Relay opt-out: do not be the LAN's relay for other devices. It is the power-saving control,
    # and it works by forcing this node's declared `persistent` to false, which takes it out of
    # everyone else's relay election — plus a refusal for any RELAY_ASK that arrives anyway. See
    # clipsync_node.declaration() and SyncState.on_relay_ask.
    "relay_opt_out": False,
}

# The order keys are written in. json.dump preserves insertion order, so this is also the order
# someone opening the file sees — worth keeping deliberate even though nothing parses by position.
KEY_ORDER = list(DEFAULTS)

TRUE_WORDS = ("1", "true", "yes", "on")


def as_hex_list(v) -> list:
    """The old-key ring: a JSON array of hex strings, or a comma-joined string."""
    if isinstance(v, (list, tuple)):
        return [str(x).strip().lower() for x in v if str(x).strip()]
    return [p.strip().lower() for p in str(v).split(",") if p.strip()]


def as_bool(v) -> bool:
    """Tolerant on purpose: the value is a real bool in config.json, but a hand-edit can hand this a
    string, and "0" is not falsey."""
    if isinstance(v, bool):
        return v
    return str(v).strip().lower() in TRUE_WORDS


def as_list(v) -> list:
    """The address lists are JSON arrays. A comma-joined string is still accepted, because someone
    editing the file by hand will write one sooner or later."""
    if isinstance(v, (list, tuple)):
        return [str(x).strip() for x in v if str(x).strip()]
    return [p.strip() for p in str(v).split(",") if p.strip()]


# ----------------------------------------------------------------------------- key rotation
# Mirrors Keys.java on the Android side — same constants, same logic, same field names in the file.

PRE_RETIRE_MS = 48 * 3600_000
RETIRE_MS = 72 * 3600_000
EXTEND_MS = 24 * 3600_000
KEYRING = 3


class Schedule:
    """One device's view of its own key schedule. Immutable — every mutation returns a new one."""

    __slots__ = ("psk", "next", "old", "since", "retire_at", "agreed_at")

    def __init__(self, psk="", nxt="", old=None, since=0, retire_at=0, agreed_at=0):
        self.psk = (psk or "").strip().lower()
        self.next = (nxt or "").strip().lower()
        self.old = list(old) if old else []
        self.since = int(since)
        self.retire_at = int(retire_at)
        self.agreed_at = int(agreed_at)

    def accepted(self) -> list:
        """Every key that may authenticate an inbound connection, current first."""
        keys = []
        if self.psk:
            keys.append(self.psk)
        if self.next:
            keys.append(self.next)
        keys.extend(self.old)
        return keys

    def phase(self, now: int) -> str:
        if not self.psk:
            return "active"
        if now - self.since < PRE_RETIRE_MS:
            return "active"
        if not self.next:
            return "pre_retired"
        if self.retire_at == 0 or now < self.retire_at:
            return "pre_retired"
        return "due" if self.agreed_at > self.since else "stranded"

    def with_next(self, successor: str) -> "Schedule":
        return Schedule(self.psk, successor, self.old, self.since, self.since + RETIRE_MS, self.agreed_at)

    def agreed(self, now: int) -> "Schedule":
        """Record that a peer has confirmed our successor. **Idempotent, and that is the point.**

        Recording it a second time changes nothing anyone reads — `phase()` only asks whether
        `agreed_at > since` — but it produces a schedule that compares unequal to the one before it,
        and `on_keys` persists and re-announces on any inequality. Both ends do that, so each T_KEYS
        provoked another one: a full config rewrite and a network-wide broadcast per round trip, for
        as long as the pre-retirement window lasted. Returning `self` once there is already an
        agreement is what lets the exchange go quiet.
        """
        if self.agreed_at > self.since:
            return self
        return Schedule(self.psk, self.next, self.old, self.since, self.retire_at, now)

    def extended(self) -> "Schedule":
        return Schedule(self.psk, self.next, self.old, self.since, self.retire_at + EXTEND_MS, self.agreed_at)

    def promoted(self, now: int) -> "Schedule":
        ring = [self.psk] + self.old
        return Schedule(self.next, "", ring[:KEYRING], now, 0, 0)

    def adopt(self, key: str, now: int) -> "Schedule":
        if key == self.psk:
            return self
        ring = ([self.psk] if self.psk else []) + [k for k in self.old if k != key]
        return Schedule(key, "", ring[:KEYRING], now, 0, 0)

    def would_adopt(self, their_psk: str) -> bool:
        """Would `reconcile` take this peer's key as ours, if it were allowed to?

        Exists so the caller can say *why* it refused without restating the branch conditions.
        """
        return bool(their_psk) and not (self.next and their_psk == self.next) \
            and their_psk != self.psk and their_psk not in self.old

    def reconcile(self, their_psk: str, their_next: str, now: int, trusted: bool) -> "Schedule":
        """Merge a peer's announced schedule into ours.

        `trusted` is whether the connection this arrived on authenticated with our *current* key or
        our successor — not merely with something in the ring. Only such a peer may hand us a key we
        have never seen (`adopt`). The ring exists because rotation assumes a superseded key may
        have leaked, so letting one authenticate an adopt would turn a temporary leak into permanent
        control of every device's key. A peer on an old key is behind; our own T_KEYS teaches it.
        """
        s = self
        if s.next and their_psk == s.next:
            s = s.agreed(now)
            if s.phase(now) == "due":
                s = s.promoted(now)
        elif trusted and their_psk != s.psk and their_psk not in s.old:
            s = s.adopt(their_psk, now)
        if their_psk == s.psk and their_next:
            if not s.next:
                s = s.with_next(their_next)
            elif s.next != their_next:
                winner = better_next(s.next, their_next)
                if winner != s.next:
                    s = s.with_next(winner)
            if s.next == their_next:
                s = s.agreed(now)
        return s

    def __eq__(self, other):
        """Value equality, **excluding `agreed_at`**.

        `agreed_at` is a timestamp of when something was confirmed, not part of what was confirmed,
        and including it made every re-confirmation look like a change worth persisting and
        broadcasting. `agreed()` is idempotent now, so this is belt-and-braces — but it is the
        cheaper of the two braces and it stops the next person reintroducing the storm.

        The one transition that must still be seen as a change is the *first* agreement (0 ->
        non-zero, which moves `phase()` from STRANDED to DUE). `on_keys` therefore compares the
        fields it cares about explicitly rather than relying on this.
        """
        if not isinstance(other, Schedule):
            return NotImplemented
        return (self.psk == other.psk and self.next == other.next and self.old == other.old
                and self.since == other.since and self.retire_at == other.retire_at)

    def __ne__(self, other):
        return not self.__eq__(other)


def better_next(ours: str, theirs: str) -> str:
    if not ours:
        return theirs or ""
    if not theirs:
        return ours
    return ours if ours >= theirs else theirs


def schedule_from_raw(raw: dict) -> Schedule:
    """Build a Schedule from config.json fields.

    A missing activation time is treated as **now**, matching Config.schedule() on Android.
    Otherwise an existing key looks 55 years old and triggers immediate pre-retirement the
    moment rotation is enabled.

    **Malformed key material is dropped here rather than carried.** `psk` itself is validated by
    check_all before this is ever reached, but `psk_next` and `psk_old` were not, and a single
    non-hex entry in either bricks this device: it reaches `bytes.fromhex` on the inbound path and
    every accepted connection dies there. A bad successor is treated as "no successor" and a bad ring
    entry is left out: both are recoverable states the rotation machinery already knows, whereas a
    stored value nothing can parse is not.
    """
    since = int(raw.get("psk_since", 0) or 0)
    nxt = str(raw.get("psk_next", "")).strip().lower()
    if nxt and check_psk(nxt) is not None:
        nxt = ""
    return Schedule(
        psk=str(raw.get("psk", "")).strip().lower(),
        nxt=nxt,
        old=[k for k in as_hex_list(raw.get("psk_old", [])) if check_psk(k) is None],
        since=since if since else int(time.time() * 1000),
        retire_at=int(raw.get("psk_retire", 0) or 0),
        agreed_at=int(raw.get("psk_agreed", 0) or 0),
    )


def schedule_to_raw(s: Schedule, rotate: bool) -> dict:
    """The schedule as the fields config.json holds."""
    return {
        "psk": s.psk,
        "psk_rotate": rotate,
        "psk_next": s.next,
        "psk_old": s.old,
        "psk_since": s.since,
        "psk_retire": s.retire_at,
        "psk_agreed": s.agreed_at,
    }


def random_psk_hex() -> str:
    return os.urandom(32).hex()


def short_key(hex_key: str) -> str:
    if not hex_key:
        return "(none)"
    return hex_key[:8] + "…"


# ----------------------------------------------------------------------------- reading / writing
def read_config(path: str = CONFIG_PATH) -> dict:
    """
    DEFAULTS overlaid with whatever the file names.

    A missing file is not an error: it means every default, which is what a first run should see. A
    file that is not valid JSON *is* an error and is raised, because the alternative — starting on
    defaults — would quietly ignore a PSK the user believes is set and then fail to connect, which is
    a far worse hour to spend than reading one parse error.

    utf-8-sig, because Notepad and PowerShell's Set-Content both write a BOM and json.loads chokes
    on one.
    """
    raw = dict(DEFAULTS)
    try:
        with open(path, encoding="utf-8-sig") as f:
            stored = json.load(f)
    except FileNotFoundError:
        stored = {}
    if not isinstance(stored, dict):
        raise ValueError("{}: expected a JSON object at the top level".format(path))
    raw.update(stored)
    return raw


# No migration is written, for anything, ever. This file reads what it finds and nothing else, and
# a key it does not recognise is a log line rather than a compatibility path. The rule was set when
# the protocol version made every device need updating together anyway: the cost of asking for a
# one-time reconfiguration is a few minutes, and the cost of carrying migration code is permanent.
#
# Worth keeping because it is what stops the idea coming back. One attempt at being helpful, moving
# names out of `peers` into `own_addresses`, also turned `direct` off -- which can leave both paths
# off, which check_all refuses -- so a configuration that worked became a service that exits at
# start-up. Migration code is a second, rarely exercised way to be wrong about a file, and the cost
# of carrying it is permanent while the reconfiguration it saves takes a minute once.


def save_schedule(sched: Schedule, rotate: bool, path: str = CONFIG_PATH) -> None:
    """Merge a new key schedule into the existing config and write it back.

    This is the equivalent of Config.save() on Android: it reads, overlays the schedule fields,
    and writes the whole file. The PSK field itself changes when a promotion swaps the key, so
    it is included. Does NOT raise on a missing file — every field has a default.
    """
    raw = read_config(path)
    raw.update(schedule_to_raw(sched, rotate))
    write_config(raw, path)


def write_config(values: dict, path: str = CONFIG_PATH) -> None:
    """
    Write the whole file. Written to a temporary file in the same directory and moved into place, so
    a crash halfway through leaves the old config rather than half of the new one.

    Whole-file rather than in-place because there is nothing left to preserve: the explanation that
    used to sit above each key now lives in configurator.py, where it can also be acted on.
    """
    ordered = {k: values[k] for k in KEY_ORDER if k in values}
    ordered.update({k: v for k, v in values.items() if k not in ordered})   # nothing silently lost
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(ordered, f, indent=2, ensure_ascii=False)
        f.write("\n")
    os.replace(tmp, path)


# ----------------------------------------------------------------------------- field checks
def normalise_peer(s: str) -> str:
    """Trim, strip the brackets an IPv6 literal is often pasted in, lower-case. Matches
    Config.normalisePeer so that the two sides agree on what counts as the same entry."""
    t = s.strip().lower()
    if t.startswith("[") and t.endswith("]"):
        t = t[1:-1]
    return t


def _is_ip_literal(s: str) -> bool:
    try:
        ipaddress.ip_address(s)
        return True
    except ValueError:
        return False


def check_peer(s: str):
    """One entry of the peers list: a host name, an IPv4 literal or an IPv6 literal. Nothing here
    requires dynamic DNS — a static address, a LAN address, a .local name or a VPN address are all
    equally valid, and the service resolves them all the same way."""
    t = normalise_peer(s)
    if not t:
        return "required"
    if "://" in t:
        return "just the host or address, without http://"
    if "%" in t:
        return "an interface name means nothing on another device"
    if _is_ip_literal(t):
        return None                                   # covers IPv4 and every IPv6 form
    # only now can a colon mean a port: an IPv6 literal is nothing but colons
    if re.fullmatch(r".+:\d+", t):
        return "the port has its own field"
    h = t[:-1] if t.endswith(".") else t              # a trailing dot is legal
    if len(h) > 253:
        return "too long for a host name"
    for label in h.split("."):
        if not label or len(label) > 63:
            return "not a valid host name"
        if label.startswith("-") or label.endswith("-"):
            return "not a valid host name"
        if not re.fullmatch(r"[a-z0-9-]+", label):
            return "not a valid host name"
    return None


def check_addresses(stored, *, allow_empty: bool, own=None):
    """
    A whole address list: every entry valid, no repeats, and optionally at least one entry.

    Used for both lists, which is the point — `peers` and `own_addresses` accept exactly the same
    things and must not drift into accepting different ones. They differ in two parameters only:
    the peer list needs an entry while `direct` is on, the own list never does; and a peer entry is
    additionally refused when it names this device.

    `own` is the normalised own-address list. Checking it here rather than at the call site keeps
    the rule in the same place as the rest, so the settings window and the service cannot disagree
    about it.
    """
    own = set(own or ())
    seen = []
    any_entry = False
    for s in as_list(stored):
        any_entry = True
        problem = check_peer(s)
        if problem is not None:
            return problem
        t = normalise_peer(s)
        if t in seen:
            return "listed twice: " + t
        if t in own:
            return "that is this device: " + t
        seen.append(t)
    if not any_entry and not allow_empty:
        return "add an address, or turn direct connections off"
    return None


def check_range(s: str, low, high, unit: str = ""):
    """Integer in [low, high]; unit only decorates the message."""
    s = str(s).strip()
    if not s:
        return "required"
    try:
        v = int(s)
    except ValueError:
        return "must be a whole number"
    u = (" " + unit) if unit else ""
    if v < low or v > high:
        return "must be {}–{}{}".format(low, high, u)
    return None


def check_port(s: str):
    return check_range(s, 1, 65535)


def check_psk(s: str):
    s = str(s).strip()
    if not s:
        return "required"
    if not re.fullmatch(r"[0-9a-fA-F]{64}", s):
        return "must be 64 hex characters ({} given)".format(len(s))
    return None


def check_files_dir(s: str):
    """
    Unlike Android's, this is a real filesystem path: absolute, or relative to this folder.
    """
    s = str(s).strip()
    if not s:
        return "required"
    # Only the characters Windows refuses outright, and only after a drive letter's colon has been
    # accounted for. Whether the folder exists is not checked on purpose: it is created at start-up,
    # and refusing to save a path on a drive that happens not to be mounted right now would be worse
    # than useless.
    tail = s[2:] if re.match(r"^[A-Za-z]:", s) else s
    bad = set('<>"|?*') & set(tail)
    if ":" in tail:
        bad.add(":")
    if bad:
        return "cannot contain " + " ".join(sorted(bad))
    if any(ord(c) < 32 for c in s):
        return "not a valid path"
    return None


def check_mdns_name(s: str):
    """Empty means this machine's name — the same string it declares as `device`, which is also what
    Android advertises. A service instance name is otherwise free text; DNS-SD caps its length at
    63 bytes of UTF-8, and a dot would split it into labels."""
    s = str(s).strip()
    if not s:
        return None
    if len(s.encode("utf-8")) > 63:
        return "at most 63 characters"
    if "." in s:
        return "no dots"
    return None


def own_set(raw: dict) -> set:
    """This device's own addresses, normalised, as a set. The one place that spelling is decided, so
    that validation, dialling and the mDNS filter all agree on what counts as the same name."""
    return {normalise_peer(s) for s in as_list(raw.get("own_addresses", []))}


def is_self(address: str, own) -> bool:
    """Does this address name this device, as far as the declared list can tell?

    A string comparison on the normalised form, never a DNS lookup — validation runs on every
    keystroke. It therefore catches the spellings that were declared and nothing else: a second name
    for the same host still reaches the handshake, where the node id decides.
    """
    return normalise_peer(address) in (own if isinstance(own, (set, frozenset)) else set(own or ()))


def check_all(raw: dict, *, discovery: bool = None, direct: bool = None) -> dict:
    """
    Every field, as {key: problem} for the ones that fail. The two switches can be passed in by a UI
    that holds them as checkboxes; left out, they are read from the values.

    `direct` is a stored key here and not merely "the list is non-empty", matching Android. Without
    it there is no way to turn the list off without deleting it, and a settings window that cleared
    the addresses the moment the box was unticked would be throwing away work on a click.
    """
    if discovery is None:
        discovery = as_bool(raw.get("discovery", True))
    if direct is None:
        direct = as_bool(raw.get("direct", False))
    peers = raw.get("peers", [])

    problems = {
        "port": check_port(raw.get("port", "")),
        "psk": check_psk(raw.get("psk", "")),
        "max_bytes": check_range(raw.get("max_bytes", ""), 1024, 64 * 1024 * 1024, "bytes"),
        "max_file_bytes": check_range(raw.get("max_file_bytes", ""), 1024 * 1024, 4096 * 1024 * 1024, "bytes"),
        "max_file_bytes_local": check_range(raw.get("max_file_bytes_local", ""), 1024 * 1024, 4096 * 1024 * 1024, "bytes"),
        "files_dir": check_files_dir(raw.get("files_dir", "")),
        "keep_hours": check_range(raw.get("keep_hours", ""), 0, 8760, "h"),
        "keep_max_mb": check_range(raw.get("keep_max_mb", ""), 0, 1024 * 1024, "MB"),
        "start_delay": check_range(raw.get("start_delay", ""), 0, 3600, "s"),
        "mdns_name": check_mdns_name(raw.get("mdns_name", "")),
        "own_addresses": check_addresses(raw.get("own_addresses", []), allow_empty=True),
        # Checked against the own list, so pasting this machine's own name into the peer list is
        # refused in the field rather than dialled, connected, handshaken and discarded. This is the
        # cheap half of the self-connection guard; the id comparison in HELLO remains the authority,
        # because a second name for the same host looks like any other name here.
        "peers": check_addresses(peers, allow_empty=False, own=own_set(raw)) if direct else None,
    }
    if not discovery and not direct:
        # Same invariant as Config.from() on Android: with neither half enabled nothing can be
        # reached. Reported against peers because that is the half the user can act on here.
        problems["peers"] = "add an address, or turn local network discovery on"
    return {k: v for k, v in problems.items() if v is not None}


# ----------------------------------------------------------------------------- the parsed config
class Cfg:
    """The service's view of config.json: parsed, coerced and derived. Raises SystemExit on a config
    the service cannot run with, because that is exactly what should happen at start-up.

    **Three fields are mutable; everything else is a start-up snapshot.** `psk`, `psk_hex` and
    `keys` are rewritten by `SyncState.persist_schedule` — inside `state.lock`, and nowhere else —
    because key rotation changes them while the process runs and the connection paths read them on
    every dial and every accept. Android reaches the same place by reloading Config after a save;
    this is the equivalent, and leaving it out meant the rotation machinery computed new keys that
    nothing ever used, so a PC fell out of the network two or three cycles after it started.

    A reader that needs a consistent pair (the accepted list *and* the current key, say) must take
    `state.lock` for the read: a rotation between the two reads hands back a mismatched combination.
    Anything else here is set once and safe to read without the lock."""

    def __init__(self, path: str = CONFIG_PATH):
        try:
            raw = read_config(path)
        except (ValueError, OSError) as e:
            raise SystemExit("{}: {}".format(os.path.basename(path), e))
        problems = check_all(raw)
        if problems:
            # No file at all is the first run, not a broken config, and it has a different answer:
            # run the settings window. Saying "psk: required" to someone who has never configured
            # anything names a field they have never seen.
            if not os.path.exists(path):
                raise SystemExit("No {} yet. Run: python configurator.py".format(os.path.basename(path)))
            first = sorted(problems)[0]
            raise SystemExit("{}: {}: {}".format(os.path.basename(path), first, problems[first]))

        self.psk = bytes.fromhex(str(raw["psk"]).strip())
        self.psk_hex = str(raw["psk"]).strip().lower()
        self.rotate = as_bool(raw.get("psk_rotate", False))
        self.keys = schedule_from_raw(raw)
        self.port = int(raw["port"])
        self.max_bytes = int(raw["max_bytes"])
        self.max_file_bytes = int(raw["max_file_bytes"])
        self.max_file_bytes_local = int(raw["max_file_bytes_local"])
        self.files_dir = str(raw["files_dir"]).strip() or "received"
        self.files_dir = os.path.expandvars(os.path.expanduser(self.files_dir))
        if not os.path.isabs(self.files_dir):
            self.files_dir = os.path.join(HERE, self.files_dir)
        self.keep_hours = float(raw["keep_hours"])                            # 0 = keep forever
        self.keep_max_bytes = int(float(raw["keep_max_mb"]) * 1024 * 1024)    # 0 = unlimited
        self.discovery = as_bool(raw["discovery"])
        self.mdns_name = str(raw["mdns_name"]).strip()
        self.direct = as_bool(raw["direct"])
        # Host names or literal addresses of peers to reach directly -- this is what this PC dials.
        # It used to do double duty: an advertiser probe asked "does this PC own one of these names?"
        # and stayed quiet on the LAN if not. That probe is gone (several PCs advertising on one LAN
        # is normal now), so the list means one thing only.
        # Empty when the switch is off, exactly as Config.from() does on Android: the
        # addresses stay in the file so they survive a round trip through the switch, but nothing
        # acts on them.
        self.peers = as_list(raw["peers"]) if self.direct else []
        # Always read, switch or no switch: it is what the node knows itself by, and that stays true
        # whether or not it is dialling anyone.
        self.own_addresses = as_list(raw["own_addresses"])
        self.own = own_set(raw)
        self.start_delay = int(raw["start_delay"])
        self.relay_opt_out = as_bool(raw.get("relay_opt_out", False))
        # largest frame we accept: a CHUNK, or a CLIP whose JSON escaping doubled the text
        self.max_frame = max(CHUNK, self.max_bytes * 2) + 64 * 1024

    @property
    def max_file_any(self) -> int:
        return max(self.max_file_bytes, self.max_file_bytes_local)
