"""
ClipSync - Windows configuration: defaults, parsing, validation, and writing config.json.

**JSON, not an ini.** The ini existed to carry a paragraph of explanation above every key, because a
text editor was the only way to change anything. configurator.py is that explanation now, so the file
underneath it should be the one that is cheapest to read and write correctly — which means native
types instead of strings-that-mean-numbers, a real list instead of a comma-joined one, and a parser
from the standard library instead of the hand-rolled splitter that was here. A key written twice, a
BOM, a value containing a comma, an in-place rewrite that had to preserve comments: all of it stops
being a problem the moment the file stops pretending to be prose.

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

HERE = os.path.dirname(os.path.abspath(__file__))
CONFIG_PATH = os.path.join(HERE, "config.json")
LEGACY_INI_PATH = os.path.join(HERE, "clipsync.ini")
LOG_PATH = os.path.join(HERE, "clipsync.log")

# Chunk size for file transfer. It lives here because Cfg.max_frame is derived from it; clipsync.py
# imports it back rather than keeping a second copy that could drift.
CHUNK = 512 * 1024

# Every key the service reads, with the value used when the file does not name it, in the type it is
# stored as. The config.json committed to the repo carries the same values, so a fresh install and a
# missing key behave identically.
DEFAULTS = {
    "port": 47521,
    "psk": "",
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
    "start_delay": 0,
}

# The order keys are written in. json.dump preserves insertion order, so this is also the order
# someone opening the file sees — worth keeping deliberate even though nothing parses by position.
KEY_ORDER = list(DEFAULTS)

TRUE_WORDS = ("1", "true", "yes", "on")


def as_bool(v) -> bool:
    """Tolerant on purpose: the value is a real bool in config.json, but a hand-edit or an import
    from the old ini can hand this a string, and "0" is not falsey."""
    if isinstance(v, bool):
        return v
    return str(v).strip().lower() in TRUE_WORDS


def as_list(v) -> list:
    """peers is a JSON array. A comma-joined string is still accepted, both for a hand-edit and for
    the one-time import of an old clipsync.ini."""
    if isinstance(v, (list, tuple)):
        return [str(x).strip() for x in v if str(x).strip()]
    return [p.strip() for p in str(v).split(",") if p.strip()]


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
        stored = _import_legacy_ini()
    if not isinstance(stored, dict):
        raise ValueError("{}: expected a JSON object at the top level".format(path))
    raw.update(stored)
    return raw


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


def _import_legacy_ini(path: str = LEGACY_INI_PATH) -> dict:
    """
    One-time read of the old clipsync.ini when no config.json exists yet.

    Kept only so that an existing install does not have to retype a 64-character key it already has.
    Nothing writes the ini any more and nothing deletes it; the first Apply creates config.json,
    which wins from then on. Delete the ini by hand once that has happened.
    """
    raw = {}
    try:
        with open(path, encoding="utf-8-sig") as f:
            for line in f:
                line = line.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                k, v = line.split("=", 1)
                raw[k.strip()] = v.strip()
    except (OSError, UnicodeDecodeError):
        return {}
    if not raw:
        return {}
    out = {}
    for key, default in DEFAULTS.items():
        if key not in raw:
            continue
        value = raw[key]
        if key == "peers":
            out[key] = as_list(value)
        elif isinstance(default, bool):
            out[key] = as_bool(value)
        elif isinstance(default, int):
            try:
                out[key] = int(float(value))
            except ValueError:
                out[key] = value          # left for check_all to report in its own words
        else:
            out[key] = value
    return out


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


def check_peers(stored):
    """The whole list: every entry valid, at least one entry, no repeats. Takes the JSON array, or a
    comma-joined string from a hand-edit."""
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
        seen.append(t)
    return None if any_entry else "add an address, or turn direct connections off"


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
    """Empty means "ClipSync on <hostname>". A service instance name is otherwise free text; DNS-SD
    caps its length at 63 bytes of UTF-8, and a dot would split it into labels."""
    s = str(s).strip()
    if not s:
        return None
    if len(s.encode("utf-8")) > 63:
        return "at most 63 characters"
    if "." in s:
        return "no dots"
    return None


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
        "peers": check_peers(peers) if direct else None,
    }
    if not discovery and not direct:
        # Same invariant as Config.from() on Android: with neither half enabled nothing can be
        # reached. Reported against peers because that is the half the user can act on here.
        problems["peers"] = "add an address, or turn local network discovery on"
    return {k: v for k, v in problems.items() if v is not None}


# ----------------------------------------------------------------------------- the parsed config
class Cfg:
    """The service's view of config.json: parsed, coerced and derived. Raises SystemExit on a config
    the service cannot run with, because that is exactly what should happen at start-up."""

    def __init__(self, path: str = CONFIG_PATH):
        try:
            raw = read_config(path)
        except (ValueError, OSError) as e:
            raise SystemExit("{}: {}".format(os.path.basename(path), e))
        problems = check_all(raw)
        if problems:
            first = sorted(problems)[0]
            raise SystemExit("{}: {}: {}".format(os.path.basename(path), first, problems[first]))

        self.psk = bytes.fromhex(str(raw["psk"]).strip())
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
        # Host names or literal addresses of peers to reach directly. Used by the advertiser to tell
        # whether this PC is the one a shared config points at; from phase 3 it is also what this PC
        # dials. Empty when the switch is off, exactly as Config.from() does on Android: the
        # addresses stay in the file so they survive a round trip through the switch, but nothing
        # acts on them.
        self.peers = as_list(raw["peers"]) if self.direct else []
        self.start_delay = int(raw["start_delay"])
        # largest frame we accept: a CHUNK, or a CLIP whose JSON escaping doubled the text
        self.max_frame = max(CHUNK, self.max_bytes * 2) + 64 * 1024

    @property
    def max_file_any(self) -> int:
        return max(self.max_file_bytes, self.max_file_bytes_local)
