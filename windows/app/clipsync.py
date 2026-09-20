"""
ClipSync - Windows side (server).

Listens on [::]:PORT (dual-stack), accepts clients, dials the peers it is configured with, and
mirrors the Windows clipboard both ways.  Run with pythonw.exe so no console window shows.

Inbound and outbound connections are the same thing once the handshake is over: the same
SecureChannel, the same registry in SyncState.clients, the same broadcast.  Only the nonce exchange
differs, by which end opened the socket.

Protocol (must match the Android side, see Connection.java / SyncService.java):
  handshake : client -> 32B Nc ; server -> 32B Ns
  keys      : HKDF-SHA256(ikm=PSK, salt=Nc||Ns, info="clipsync c2s"/"clipsync s2c") -> 32B each
  nonce     : 12B = 4 zero bytes || u64 big-endian counter, per direction, starts at 0
  frame     : u32 BE len || ChaCha20-Poly1305(type(1B) || payload)
  types     : 1 HELLO   2 BYE {reason}   3 PING {t1}   4 PONG {t1,t2,t3}   5 KEYS {psk,since,next}
              6 CLIP {ts,from,to,forwarded,mime,sha256,data}
              7 OFFER {seq,name,mime,size,sha256,from,to,forwarded}  8 WANT {sha256,ranges}
              9 HAVE {sha256}  10 SKIP {sha256,reason}
             11 CHUNK u32 index||bytes  12 PULL {sha256,ranges}
             13 END {sha256}          14 ABORT {sha256,reason}
             15 PAIR_ASK  16 PAIR_KEY {psk,port,device,type}
             17 RELAY_ASK {sha256,size}  18 RELAY_OK {sha256}  19 RELAY_NO {sha256,reason}
             20 PEERS {peers: [{id,name,type,persistent,battery}]}

Text goes as CLIP (JSON, <= max_bytes; sha256 is always over the LF-normalised text, so the two
platforms agree about a clip that came from a CRLF clipboard).  Files: an OFFER is answered with
HAVE, SKIP, or WANT {ranges of missing chunks}; the bytes then move over up to N parallel data connections that
*one* of the two ends opens (HELLO role=data), whichever SecureChannel.drives_transfer() names,
which is the dialler when both ends can open them.  The opener pushes CHUNKs when it holds the
file and sends PULL {ranges} when it wants them.  Chunks
are written in place into a pre-sized .part file with a persisted chunk map, so a lost
connection, a restart or an ABORT (a newer clip superseded the transfer) leaves a resumable
partial and the next OFFER/WANT moves only what is missing.  Two size limits: max_file_bytes
for clients on the internet, max_file_bytes_local for clients on the LAN (the client says which
in HELLO: it connected via mDNS, or the peer address is on one of its own prefixes).

Received files are stored in `files_dir` (default: received/ next to this script), which is also
the transfer cache (a file still there is never transferred again), and put on the clipboard as
a file (CF_HDROP: Ctrl+V in Explorer or any app that accepts dropped files); images additionally
as "PNG" and, when Pillow is installed, CF_DIB so image editors / chat apps paste them as pictures.
Locally, a copied file (Explorer) or image (screenshot, browser) is offered; text goes as CLIP.

Discovery has two independent halves, named for how a peer is found rather than for the route
taken to it.  `peers` is a list of host names or literal addresses that devices connect to
directly, whether a dynamic DNS name, a static address, or a LAN address; all the same to the code.
`discovery` advertises this PC on the LAN as _clipsync._tcp via mDNS (python-zeroconf), so a
device can find it with nothing configured at all, and browses for other nodes to dial.
`own_addresses` is the other side of `peers`: the names that point at THIS machine, which is how
it recognises itself and refuses to dial itself.  The network side starts `start_delay` seconds
after logon, which is 0 by default.
"""
import atexit
import ctypes
import ctypes.wintypes as wt
import hashlib
import hmac
import io
import ipaddress
import json
import logging
import mimetypes
import os
import pathlib
import queue
import re
import signal
import socket
import struct
import sys
import tempfile
import threading
import time
from concurrent.futures import ThreadPoolExecutor

from cryptography.hazmat.primitives.ciphers.aead import ChaCha20Poly1305

try:                                   # optional: converts between DIB and PNG for image clips
    from PIL import Image
except ImportError:                    # pragma: no cover
    Image = None

# ----------------------------------------------------------------------------- config
# Defaults, parsing, validation and the ini writer live in clipsync_config so that configurator.py
# can import the rules instead of restating them. That module is deliberately free of side effects;
# this one is not (it configures logging and registers a clipboard format below), which is why the
# dependency only runs one way.
from clipsync_config import (CHUNK, LOG_PATH, Cfg, is_self, Schedule, schedule_from_raw,  # noqa: E402
                             schedule_to_raw, save_schedule, read_config, random_psk_hex,
                             short_key, as_bool, check_psk, EXTEND_MS, PRE_RETIRE_MS)
from clipsync_node import declaration, node_id, node_name, short_id   # noqa: E402
# Frame types, the key schedule and SecureChannel, so that clipsync_pair.py can speak the protocol
# without importing this module and its side effects.  A star import, unusually, because these are
# the protocol's vocabulary and the whole file refers to them unqualified, which is what `__all__`
# over there is there to keep honest.
from clipsync_proto import *   # noqa: E402,F403

MAP_SAVE_EVERY = 8         # persist the received-chunk bitmap every N chunks
WANT_RETRIES = 3           # how often the receiver re-asks for missing chunks in one session
LOG_MAX_BYTES = 128 * 1024  # roll clipsync.log over at this size; see RotatingLog
# One step of the relay fallback walk, not the walk as a whole, and it only ever has to cover one
# case. "Will not do it" is answered immediately by RELAY_NO; "died mid-transfer" shows up as a
# closed TCP connection, because the waiter is on the same LAN as the relay. Only "alive but stuck"
# needs a timeout at all, and that is rare enough that the value can be generous without making
# failures slow. It is a backstop, not a scheduler, so do not "optimise" it downwards.
RELAY_ASK_TIMEOUT_S = 30
# Parallel data connections this end opens for one file, matching Android's `threads` default. Not
# a configuration key here on purpose: there is no settings window field for it and the number that
# matters is the one the *opener* picks, which on a link between a PC and a phone is usually the phone.
DATA_STREAMS = 8
HEARTBEAT_INTERVAL = 30    # seconds between PINGs, matching Android's PING_WIFI_MS
MAX_INBOUND = 24           # concurrent inbound connections, matching Server.MAX_INBOUND
# A connection that has not finished its handshake gets a much shorter leash than READ_TIMEOUT: a
# peer that has said nothing at all is not a peer yet, and 90 seconds of silence per read is 90
# seconds of a worker held by anyone who can open a socket. The absolute deadline inside
# SecureChannel bounds the whole handshake; this bounds each read within it.
HANDSHAKE_READ_TIMEOUT = 10


def priority_key(persistent: bool, node_type: str, battery: str) -> tuple:
    """Comparable priority tuple for relay election: lower is higher priority.

    One total order, compared left to right: `(persistent, type, battery, id)`, where pc beats
    tablet beats phone and mains beats high beats medium beats low. The caller appends the node id
    as the last component, and that is what makes the order **total**: every node computes the same
    answer from the same declarations, so the relay is derived rather than elected and not one
    message is exchanged to decide it.

    The order is deliberately not recomputed continuously. Battery is a moving input, and two phones
    at 61 % and 59 % would trade the role back and forth with every trade re-routing traffic; so the
    incumbent keeps the role until it disappears, loses `persistent`, or drops to the lowest bucket.
    Simpler than a deadband, obviously terminating, and needs no shared state.
    """
    type_rank = {"pc": 2, "tablet": 1}.get(node_type, 0)
    batt_rank = {"mains": 3, "high": 2, "medium": 1}.get(battery, 0)
    return (0 if persistent else 1, -type_rank, -batt_rank)


class _Bounded:
    """Insertion-ordered, bounded membership set. Mirrors SyncService.sentHashes.

    Echo suppression needs more than a single remembered hash per direction, because with three or
    more nodes a clip can circulate, A's, then B's, before A's own copy comes back around, and a
    single slot would no longer recognise it as ours and would write it back over the clip the user
    just made. Remembering a handful (16) instead is far more than any real round trip needs and
    still bounded.

    Re-adding a hash moves it to the newest end, so something still circulating does not age out
    from underneath us.
    """

    __slots__ = ("_d", "_n")

    def __init__(self, n: int = 16):
        self._d, self._n = {}, n

    def add(self, h):
        if not h:
            return
        self._d.pop(h, None)
        self._d[h] = True
        while len(self._d) > self._n:
            self._d.pop(next(iter(self._d)))

    def __contains__(self, h):
        return h in self._d


class RelayWait:
    """State for a file we are waiting on a relay to provide.

    `candidates` is fixed at the moment the OFFER arrived and is walked strictly downward, never
    revisited and never recomputed: recomputing it as LAN membership changes could send the walk
    back up and loop, while a fixed descending walk over a finite totally-ordered list terminates at
    the origin by construction. `failed` is the set that has already refused or died for this sha,
    skipped for the rest of the transfer.
    """
    __slots__ = ("sha256", "offer_hdr", "origin", "candidates", "next_idx",
                 "ask_time", "failed", "retried")

    def __init__(self, sha: str, hdr: dict, origin, candidates: list):
        self.sha256 = sha
        self.offer_hdr = hdr
        self.origin = origin           # SecureChannel of the originator
        self.candidates = candidates   # priority-sorted node ids
        self.next_idx = 0
        self.ask_time = 0.0            # monotonic time when RELAY_ASK was sent
        self.failed = set()
        self.retried = False


class RotatingLog(logging.FileHandler):
    """
    A file handler that keeps one generation: at LOG_MAX_BYTES the current log becomes
    clipsync.log.old and a fresh one starts.

    logging.handlers.RotatingFileHandler does this already, and is not used, for one reason: it
    rolls *after* writing the record that crossed the line, and it renames through a chain
    (.1 -> .2, ...). Here the size is checked before the record is emitted, so the file never
    exceeds the limit rather than exceeding it by one line, and there is exactly one .old, silently
    replaced. A crash log is worth one generation; it is not worth a directory of them.

    A failed roll is not allowed to take the service down: if the rename loses a race with a tail
    or an editor holding the file open, the handler keeps writing to the current file and tries
    again on the next record. Losing the rotation is a nuisance; losing the log is not.
    """

    def __init__(self, path, max_bytes=LOG_MAX_BYTES):
        super().__init__(path, encoding="utf-8")
        self.max_bytes = max_bytes
        # From baseFilename, which FileHandler has already made absolute, and not from `path`:
        # os.replace below uses baseFilename, so a relative path would otherwise rename the log into
        # whatever the working directory happens to be rather than next to itself.
        self.old_path = self.baseFilename + ".old"

    def emit(self, record):
        try:
            if self._should_roll(record):
                self._roll()
        except Exception:                # noqa: BLE001 - never lose a record over housekeeping
            pass
        super().emit(record)

    def _should_roll(self, record) -> bool:
        if self.stream is None:
            return False
        try:
            pos = self.stream.tell()
        except (OSError, ValueError):
            return False
        if pos == 0:
            # Never roll an empty file. Without this, a single record longer than the whole limit,
            # such as a long traceback, rolls before writing, then rolls again on the next record, and the
            # second roll overwrites .old with that one record and then throws it away too. The
            # limit is a ceiling for ordinary lines, not a promise to truncate one enormous one.
            return False
        # Counted in bytes, not characters: the stream is UTF-8, and a peer name or a received file
        # name can put the two a long way apart.
        return pos + len(self.format(record).encode("utf-8")) + 1 > self.max_bytes

    def _roll(self):
        # The stream is closed directly rather than through Handler.close(), which would also mark
        # the handler closed and drop it from logging's own bookkeeping, because this handler is
        # going straight back into service. Windows will not rename a file that is still open.
        stream, self.stream = self.stream, None
        try:
            stream.flush()
        finally:
            stream.close()
        try:
            os.replace(self.baseFilename, self.old_path)   # silently replaces an existing .old
        finally:
            self.stream = self._open()   # reopened either way: a failed rename must not stop logging


_handlers = [RotatingLog(LOG_PATH)]
# The check is not defensive programming, it is the documented state this service normally runs in:
# "Under some conditions stdin, stdout and stderr as well as the original values __stdin__,
# __stdout__ and __stderr__ can be None. It is usually the case for Windows GUI apps that aren't
# connected to a console and Python apps started with pythonw."
# (https://docs.python.org/3/library/sys.html#sys.__stdout__). logging.StreamHandler(None) would
# fall back to sys.stderr, which is None too, and every record would then raise inside logging.
# The file handler is therefore not the *second* log, it is the only one.
if sys.stdout is not None:          # absent under pythonw.exe
    _handlers.append(logging.StreamHandler(sys.stdout))
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s", handlers=_handlers)
log = logging.getLogger("clipsync")


def nchunks(size: int) -> int:
    return max(1, (size + CHUNK - 1) // CHUNK)


def ranges_of(indexes) -> list:
    """Compress a sorted iterable of chunk indexes into [[from, to), ...]."""
    out = []
    for i in sorted(indexes):
        if out and out[-1][1] == i:
            out[-1][1] = i + 1
        else:
            out.append([i, i + 1])
    return out


def expand(ranges) -> list:
    return [i for a, b in ranges for i in range(int(a), int(b))]


class Partial:
    """
    An inbound file being assembled from CHUNK frames that may arrive on several data
    connections, in any order.  Backed by <final>.part (pre-sized, positional writes) plus
    <final>.part.json (header + received-chunk list), so an interrupted transfer, whether from a
    lost connection, a PC restart, or a sender abort, resumes with only the missing chunks.
    """

    @staticmethod
    def map_path(part: str) -> str:
        return part + ".json"

    def __init__(self, folder: str, hdr: dict = None, part: str = None):
        self.lock = threading.Lock()
        # Relay serve threads wait on this for a chunk that has not landed yet. A relay streams
        # rather than storing and forwarding, because waiting for a 500 MB file to arrive before
        # offering it doubles the end-to-end time and demands 500 MB of scratch space on whatever
        # device was elected, so a waiter's pull can legitimately run ahead of our own download.
        self.cond = threading.Condition(self.lock)
        self.have = set()
        self.finalized = False
        self.first_chunk_claimed = False      # see claim_first_chunk()
        self.origin = None                    # control channel of the device sending it
        self.streams = 0                      # data connections that have pushed a chunk at it
        self.retries = 0
        self.unsaved = 0
        if part is not None:                  # resume from disk
            with open(self.map_path(part), encoding="utf-8") as f:
                m = json.load(f)
            hdr = m["hdr"]
            self.have = set(m.get("have", []))
            self.part = part
        self.name = safe_name(str(hdr.get("name", "clip")))
        self.mime = str(hdr.get("mime") or guess_mime(self.name))
        self.size = int(hdr.get("size", -1))
        self.sha = str(hdr.get("sha256", ""))
        self.seq = int(hdr.get("seq", 0))
        # Carried from the OFFER because the forwarding decision is made when the file is complete,
        # which may be minutes and one restart later: who it came from, who already has it, and
        # whether it had been forwarded once already.
        self.origin_id = str(hdr.get("from", ""))
        self.to = [str(x) for x in (hdr.get("to") or [])]
        self.forwarded = bool(hdr.get("forwarded", False))
        self.hdr = {"seq": self.seq, "name": self.name, "mime": self.mime, "size": self.size,
                    "sha256": self.sha, "from": self.origin_id, "to": self.to,
                    "forwarded": self.forwarded}
        self.n = nchunks(self.size)
        if part is None:
            self.part = unique_path(folder, self.name) + ".part"
            with open(self.part, "wb") as f:
                f.truncate(self.size)
            self.save_map()
        elif os.path.getsize(self.part) != self.size:
            raise ValueError("partial file size changed")

    @property
    def final(self) -> str:
        return self.part[:-5]

    def missing(self) -> list:
        with self.lock:
            return ranges_of(i for i in range(self.n) if i not in self.have)

    def complete(self) -> bool:
        with self.lock:
            return len(self.have) == self.n

    def claim_first_chunk(self) -> bool:
        """"Nothing had landed yet, and I am the one who gets to say so": true for one caller only.

        `len(pt.have) == 0` read here and `pt.write` called there are two operations, and eight
        data connections reach their first write together: every one of them can see an empty set
        before any of them has filled it, and every one of them then sends the relay's early OFFER
        to the same waiter.  Testing and setting under one acquisition of the lock `write` uses
        makes that unrepresentable.

        One rule, one implementation, shared by both of this end's paths: `data_thread` when a peer
        pushes at us and `_pull_worker` when we drive the download, matching `Files.Partial.
        claimFirstChunk` / `Transfer.firstChunkFired` on the Android side (`serveData` and
        `Transfer.pull` there).  Cleared by `keep()`, so a transfer that
        stopped before a single chunk landed can still announce its first one when it resumes (and
        only then: a non-empty `have` fails the claim regardless of the flag).
        """
        with self.lock:
            if self.first_chunk_claimed or self.have:
                return False
            self.first_chunk_claimed = True
            return True

    def save_map(self):
        tmp = self.map_path(self.part) + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump({"hdr": self.hdr, "have": sorted(self.have)}, f)
        os.replace(tmp, self.map_path(self.part))
        self.unsaved = 0

    def open_writer(self):
        """
        A file handle for one data connection to keep for its lifetime.

        Two reasons not to open the file per chunk. It is a syscall and a handle allocation for
        every 512 KiB, 200 of them for a 100 MB file, and, worse, doing it inside the lock made
        the eight parallel streams take turns at the disk, which is the opposite of what opening
        eight of them was for. Chunks occupy disjoint ranges, so a handle each is safe.
        """
        return open(self.part, "r+b")

    def write(self, f, idx: int, data: bytes):
        if idx < 0 or idx >= self.n:
            raise ValueError(f"chunk {idx} out of range")
        expect = CHUNK if idx < self.n - 1 else self.size - idx * CHUNK
        if len(data) != expect:
            raise ValueError(f"chunk {idx}: {len(data)} bytes, expected {expect}")
        with self.lock:
            if idx in self.have:
                return
        # outside the lock: this is the only slow part, and no two chunks share a byte. Two streams
        # racing on the same index would write identical bytes, which costs a little work and
        # nothing else.
        f.seek(idx * CHUNK)
        f.write(data)
        with self.lock:
            if idx in self.have:
                return
            self.have.add(idx)
            self.cond.notify_all()     # wake any relay serve thread waiting for this chunk
            self.unsaved += 1
            if self.unsaved >= MAP_SAVE_EVERY:
                self.save_map()

    def finalize(self) -> str:
        """Verify the whole file, drop the .part suffix.  Raises on hash mismatch (partial discarded)."""
        with self.lock:
            if self.finalized:
                return self.final
            self.save_map()
            if sha256_file(self.part) != self.sha:
                self.discard_locked()
                raise ValueError("hash mismatch after reassembly")
            final = unique_path(os.path.dirname(self.part), os.path.basename(self.final),
                                ignore_part=self.part)
            os.replace(self.part, final)
            os.remove(self.map_path(self.part))
            self.finalized = True
            self.part = final + ".part"
            return final

    def keep(self):
        """Stop for now but leave .part + map on disk so the transfer can resume later."""
        with self.lock:
            if not self.finalized:
                self.save_map()
            # a resumed transfer may announce its first chunk again (see claim_first_chunk)
            self.first_chunk_claimed = False

    def discard_locked(self):
        for p in (self.part, self.map_path(self.part)):
            try:
                os.remove(p)
            except OSError:
                pass

    def discard(self):
        with self.lock:
            self.discard_locked()

    def read_chunk(self, idx: int) -> bytes:
        """Read chunk `idx` from the .part file, for forwarding it on while we are still receiving.

        A chunk in `self.have` has been fully written; chunks occupy disjoint ranges, so reading
        from a separate file handle while another connection is writing a *different* chunk is safe.
        After finalize() the file has been renamed; use `self.final` in that case.
        """
        with self.lock:
            if idx not in self.have:
                raise ValueError(f"chunk {idx} not received yet")
            path = self.final if self.finalized else self.part
        pos = idx * CHUNK
        want = min(CHUNK, self.size - pos)
        with open(path, "rb") as f:
            f.seek(pos)
            data = f.read(want)
        if len(data) != want:
            raise ValueError(f"short read at chunk {idx}")
        return data

    def __str__(self):
        with self.lock:
            return f"{self.name} {len(self.have)}/{self.n} chunks"


# ----------------------------------------------------------------------------- clipboard (Win32)
user32 = ctypes.WinDLL("user32", use_last_error=True)
kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
shell32 = ctypes.WinDLL("shell32", use_last_error=True)

CF_UNICODETEXT = 13
CF_DIB = 8
CF_HDROP = 15
GMEM_MOVEABLE = 0x0002
HWND_MESSAGE = wt.HWND(-3)
WM_CLIPBOARDUPDATE = 0x031D

LRESULT = ctypes.c_ssize_t
WNDPROC = ctypes.WINFUNCTYPE(LRESULT, wt.HWND, wt.UINT, wt.WPARAM, wt.LPARAM)


class WNDCLASSW(ctypes.Structure):
    _fields_ = [
        ("style", wt.UINT), ("lpfnWndProc", WNDPROC), ("cbClsExtra", ctypes.c_int),
        ("cbWndExtra", ctypes.c_int), ("hInstance", wt.HINSTANCE), ("hIcon", wt.HICON),
        ("hCursor", wt.HANDLE), ("hbrBackground", wt.HBRUSH),
        ("lpszMenuName", wt.LPCWSTR), ("lpszClassName", wt.LPCWSTR),
    ]


for fn, res, args in [
    (user32.DefWindowProcW, LRESULT, [wt.HWND, wt.UINT, wt.WPARAM, wt.LPARAM]),
    (user32.RegisterClassW, wt.ATOM, [ctypes.POINTER(WNDCLASSW)]),
    (user32.CreateWindowExW, wt.HWND, [wt.DWORD, wt.LPCWSTR, wt.LPCWSTR, wt.DWORD, ctypes.c_int,
                                       ctypes.c_int, ctypes.c_int, ctypes.c_int, wt.HWND, wt.HMENU,
                                       wt.HINSTANCE, wt.LPVOID]),
    (user32.AddClipboardFormatListener, wt.BOOL, [wt.HWND]),
    (user32.GetMessageW, wt.BOOL, [ctypes.POINTER(wt.MSG), wt.HWND, wt.UINT, wt.UINT]),
    (user32.TranslateMessage, wt.BOOL, [ctypes.POINTER(wt.MSG)]),
    (user32.DispatchMessageW, LRESULT, [ctypes.POINTER(wt.MSG)]),
    (user32.OpenClipboard, wt.BOOL, [wt.HWND]),
    (user32.CloseClipboard, wt.BOOL, []),
    (user32.EmptyClipboard, wt.BOOL, []),
    (user32.GetClipboardData, wt.HANDLE, [wt.UINT]),
    (user32.SetClipboardData, wt.HANDLE, [wt.UINT, wt.HANDLE]),
    (user32.IsClipboardFormatAvailable, wt.BOOL, [wt.UINT]),
    (user32.RegisterClipboardFormatW, wt.UINT, [wt.LPCWSTR]),
    (shell32.DragQueryFileW, wt.UINT, [wt.HANDLE, wt.UINT, wt.LPWSTR, wt.UINT]),
    (kernel32.GlobalAlloc, wt.HGLOBAL, [wt.UINT, ctypes.c_size_t]),
    (kernel32.GlobalLock, wt.LPVOID, [wt.HGLOBAL]),
    (kernel32.GlobalUnlock, wt.BOOL, [wt.HGLOBAL]),
    (kernel32.GlobalSize, ctypes.c_size_t, [wt.HGLOBAL]),
    (kernel32.GlobalFree, wt.HGLOBAL, [wt.HGLOBAL]),
    (kernel32.GetModuleHandleW, wt.HMODULE, [wt.LPCWSTR]),
]:
    fn.restype, fn.argtypes = res, args

CF_PNG = user32.RegisterClipboardFormatW("PNG")     # used by Chrome, GIMP, Paint.NET, Discord, ...


def _open_clipboard(retries=10, delay=0.02) -> bool:
    for _ in range(retries):
        if user32.OpenClipboard(None):
            return True
        time.sleep(delay)
    return False


def _global_bytes(h) -> bytes:
    p = kernel32.GlobalLock(h)
    if not p:
        return b""
    try:
        return ctypes.string_at(p, kernel32.GlobalSize(h))
    finally:
        kernel32.GlobalUnlock(h)


def _global_from_bytes(data: bytes):
    h = kernel32.GlobalAlloc(GMEM_MOVEABLE, len(data))
    if not h:
        return None
    p = kernel32.GlobalLock(h)
    ctypes.memmove(p, data, len(data))
    kernel32.GlobalUnlock(h)
    return h


def sha256_file(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


class Item:
    """
    One clipboard content.  kind 'text': text.  kind 'file': name, mime, size, sha256, path;
    files always live on disk (a screenshot is written to files_dir first), never in memory.
    """

    def __init__(self, kind, text=None, name=None, mime=None, path=None, size=0, sha256=None):
        self.kind, self.text, self.name, self.mime, self.path, self.size = kind, text, name, mime, path, size
        self._sha = sha256

    @property
    def sha256(self) -> str:
        if self._sha is None:
            self._sha = (hashlib.sha256(self.text.replace("\r\n", "\n").encode("utf-8")).hexdigest()
                         if self.kind == "text" else sha256_file(self.path))
        return self._sha

    @staticmethod
    def from_path(path: str, mime=None, sha=None):
        return Item("file", name=os.path.basename(path), mime=mime or guess_mime(path), path=path,
                    size=os.path.getsize(path), sha256=sha)

    def __str__(self):
        return f"text {len(self.text)} chars" if self.kind == "text" else f"{self.mime} {self.name} {self.size} bytes"


def dib_to_bmp(dib: bytes) -> bytes:
    """Prepend a BITMAPFILEHEADER so a CF_DIB blob becomes a .bmp file."""
    hdr = struct.unpack_from("<I", dib, 0)[0]
    if hdr >= 40:
        bpp = struct.unpack_from("<H", dib, 14)[0]
        compression = struct.unpack_from("<I", dib, 16)[0]
        clr_used = struct.unpack_from("<I", dib, 32)[0]
        entry = 4
    else:                                   # BITMAPCOREHEADER
        bpp = struct.unpack_from("<H", dib, 10)[0]
        compression, clr_used, entry = 0, 0, 3
    colors = clr_used if clr_used else ((1 << bpp) if bpp <= 8 else 0)
    masks = 12 if (compression == 3 and hdr == 40) else 0
    offset = 14 + hdr + masks + colors * entry
    return b"BM" + struct.pack("<IHHI", 14 + len(dib), 0, 0, offset) + dib


def clipboard_read(cfg: Cfg, skip_path=None):
    """
    Highest-value content on the clipboard: file > image > text. None if nothing usable.
    skip_path: the file we ourselves put there last (don't hash it just to echo-drop it).
    Images that only exist in memory are written to files_dir so they can be served later.
    """
    if not _open_clipboard():
        return None
    try:
        # 1. a file copied in Explorer (or any app that offers CF_HDROP)
        if user32.IsClipboardFormatAvailable(CF_HDROP):
            h = user32.GetClipboardData(CF_HDROP)
            if h:
                n = shell32.DragQueryFileW(h, 0xFFFFFFFF, None, 0)
                for i in range(n):
                    buf = ctypes.create_unicode_buffer(32768)
                    shell32.DragQueryFileW(h, i, buf, 32768)
                    path = buf.value
                    if path == skip_path:
                        return None
                    if not os.path.isfile(path):
                        continue
                    size = os.path.getsize(path)
                    if size > cfg.max_file_any:
                        log.info("skip %s: %d bytes > every limit", path, size)
                        continue
                    if n > 1:
                        log.info("%d files on clipboard, sending the first usable one", n)
                    return Item.from_path(path)
        # 2. an image (screenshot tool, browser "copy image", ...)
        png = None
        if user32.IsClipboardFormatAvailable(CF_PNG):
            h = user32.GetClipboardData(CF_PNG)
            if h:
                data = _global_bytes(h)
                if data[:8] == b"\x89PNG\r\n\x1a\n":
                    png = data
        if png is None and user32.IsClipboardFormatAvailable(CF_DIB):
            h = user32.GetClipboardData(CF_DIB)
            if h:
                bmp = dib_to_bmp(_global_bytes(h))
                if Image is not None:
                    out = io.BytesIO()
                    Image.open(io.BytesIO(bmp)).save(out, "PNG")
                    png = out.getvalue()
                else:
                    path = unique_path(cfg.files_dir, time.strftime("clip_%Y%m%d_%H%M%S.bmp"))
                    with open(path, "wb") as f:
                        f.write(bmp)
                    return Item.from_path(path, "image/bmp")
        if png is not None:
            path = unique_path(cfg.files_dir, time.strftime("clip_%Y%m%d_%H%M%S.png"))
            with open(path, "wb") as f:
                f.write(png)
            return Item.from_path(path, "image/png")
        # 3. text
        if user32.IsClipboardFormatAvailable(CF_UNICODETEXT):
            h = user32.GetClipboardData(CF_UNICODETEXT)
            if h:
                p = kernel32.GlobalLock(h)
                if p:
                    try:
                        return Item("text", text=ctypes.wstring_at(p).replace("\r\n", "\n"))
                    finally:
                        kernel32.GlobalUnlock(h)
        return None
    finally:
        user32.CloseClipboard()


def clipboard_set_text(text: str) -> bool:
    data = text.replace("\r\n", "\n").replace("\n", "\r\n")
    buf = ctypes.create_unicode_buffer(data)
    raw = ctypes.string_at(ctypes.addressof(buf), ctypes.sizeof(buf))
    if not _open_clipboard():
        return False
    try:
        user32.EmptyClipboard()
        h = _global_from_bytes(raw)
        if not h or not user32.SetClipboardData(CF_UNICODETEXT, h):
            if h:
                kernel32.GlobalFree(h)
            return False
        return True
    finally:
        user32.CloseClipboard()


def clipboard_set_file(path: str, mime: str) -> bool:
    """
    Put a saved file on the clipboard.  Always CF_HDROP (pastes as a file in Explorer and in
    apps that accept dropped files).  For images also "PNG" and CF_DIB so image-aware apps
    paste the picture itself.
    """
    if not _open_clipboard():
        return False
    try:
        user32.EmptyClipboard()
        # DROPFILES { pFiles=20, pt=(0,0), fNC=0, fWide=1 } + UTF-16 path + \0 + \0
        wide = (path + "\0\0").encode("utf-16-le")
        h = _global_from_bytes(struct.pack("<IiiII", 20, 0, 0, 0, 1) + wide)
        ok = bool(h and user32.SetClipboardData(CF_HDROP, h))
        if mime.startswith("image/"):
            with open(path, "rb") as f:
                data = f.read()
            png = data if mime == "image/png" else None
            if Image is not None:
                try:
                    im = Image.open(io.BytesIO(data))
                    if png is None:
                        out = io.BytesIO()
                        im.save(out, "PNG")
                        png = out.getvalue()
                    bmp = io.BytesIO()
                    im.convert("RGBA" if im.mode in ("RGBA", "LA", "P") else "RGB").save(bmp, "BMP")
                    hd = _global_from_bytes(bmp.getvalue()[14:])
                    if hd:
                        user32.SetClipboardData(CF_DIB, hd)
                except Exception as e:
                    log.info("image conversion skipped: %s", e)
            if png:
                hp = _global_from_bytes(png)
                if hp:
                    user32.SetClipboardData(CF_PNG, hp)
        return ok
    finally:
        user32.CloseClipboard()


_MIME = {".png": "image/png", ".jpg": "image/jpeg", ".jpeg": "image/jpeg", ".gif": "image/gif", ".webp": "image/webp",
         ".bmp": "image/bmp", ".pdf": "application/pdf", ".txt": "text/plain", ".zip": "application/zip",
         ".mp4": "video/mp4", ".mp3": "audio/mpeg", ".apk": "application/vnd.android.package-archive"}


def guess_mime(name: str) -> str:
    ext = os.path.splitext(name)[1].lower()
    return _MIME.get(ext) or mimetypes.guess_type(name)[0] or "application/octet-stream"


def safe_name(name: str) -> str:
    """A peer's file name reduced to something that can only land inside files_dir.

    `\\` is folded to `/` first because the name came from another platform, where it may not be a
    separator at all; basename then drops every directory part. The character filter is the set
    Windows refuses, and the length cap is well under MAX_PATH's share for a name.

    `.` and `..` survive all of that: basename returns them unchanged, they contain no filtered
    character and they are short, and joining `..` to files_dir writes to its *parent*. They are
    the one pair that has to be named. Android's safeName() applies the same rules to the same
    inputs; the two are meant to produce the same string for any name.
    """
    name = os.path.basename(name.replace("\\", "/")).strip() or "clip"
    if name in (".", ".."):
        name = "clip"
    name = re.sub(r'[<>:"/\\|?*\x00-\x1f]', "_", name)
    return name[:150]


def unique_path(folder: str, name: str, ignore_part: str = None) -> str:
    """A free path for `name` in `folder`, treating a reserved `.part` as taken.

    `ignore_part` is the transfer's *own* part file. Without it finalize() asked whether
    `clip.png.part` existed, found the very file it was about to rename, decided the name was taken
    and saved as `clip (1).png`, every single time, for every received file.
    """
    os.makedirs(folder, exist_ok=True)
    base, ext = os.path.splitext(name)
    p = os.path.join(folder, name)
    i = 1
    while os.path.exists(p) or (os.path.exists(p + ".part") and p + ".part" != ignore_part):
        p = os.path.join(folder, f"{base} ({i}){ext}")
        i += 1
    return p


class FileCache:
    """
    sha256 -> local path of every file we have (received into files_dir, or copied locally).
    Lets an OFFER be answered with HAVE instead of transferring the bytes again, and serves WANTs.

    The digests are remembered between runs in %TMP%, keyed by name + size + mtime. Without that,
    every logon re-hashed the whole folder, up to keep_max_mb of reading before the clipboard
    listener was even registered. The index is a cache in the strict sense: delete it and the only
    consequence is one slow start. It lives in %TMP% and not next to the files for exactly that
    reason, and because the folder it describes is the user's, not ours to litter.
    """

    # 2: index entries carry an origin ("local" or "remote") as a fourth element, which prune needs
    # in order to delete only what we received. An older index is simply discarded and rebuilt.
    INDEX_VERSION = 2

    def __init__(self, cfg: Cfg):
        self.cfg = cfg
        self.lock = threading.Lock()
        self.by_sha = {}
        self.dir = pathlib.Path(cfg.files_dir)
        self.dir.mkdir(parents=True, exist_ok=True)
        self.partials = {}                     # maps a sha to its Partial (interrupted transfers, resumable)
        self.index_path = self._index_path()
        # maps a name to [sha, size, mtime_ns, origin]. `origin` is "remote" for a file this service
        # received and "local" for anything else, such as a screenshot we wrote or a file the user
        # dropped in the folder. Only prune reads it, and only to decide what it may delete.
        self.index = self._load_index()
        self._scan()

    def _index_path(self) -> pathlib.Path:
        # one index per files_dir: the same %TMP% may serve several folders over time, and an
        # index describing a different folder is worse than none
        key = hashlib.sha256(str(self.dir.resolve()).lower().encode("utf-8")).hexdigest()[:16]
        return pathlib.Path(tempfile.gettempdir()) / f"clipsync-index-{key}.json"

    def _load_index(self) -> dict:
        try:
            doc = json.loads(self.index_path.read_text(encoding="utf-8"))
            if doc.get("v") == self.INDEX_VERSION and doc.get("dir") == str(self.dir.resolve()):
                return {k: list(v) for k, v in doc.get("files", {}).items()}
        except (OSError, ValueError, TypeError):
            pass
        return {}

    def _save_index(self):
        try:
            tmp = self.index_path.with_suffix(".tmp")
            tmp.write_text(json.dumps({"v": self.INDEX_VERSION, "dir": str(self.dir.resolve()),
                                       "files": self.index}), encoding="utf-8")
            os.replace(tmp, self.index_path)
        except OSError as e:
            log.info("cannot write the digest index (%s); it will be rebuilt next time", e)

    def _scan(self):
        """One pass over files_dir: resume partials, drop orphans, digest what is new or changed."""
        fresh, hashed = {}, 0
        try:
            entries = list(os.scandir(self.dir))
        except OSError as e:
            log.warning("cannot read %s: %s", self.dir, e)
            return
        for e in entries:
            n = p = None
            try:
                n, p = e.name, e.path
                if n.endswith(".part"):
                    try:
                        pt = Partial(str(self.dir), part=p)
                        self.partials[pt.sha] = pt
                        log.info("resumable transfer found: %s", pt)
                    except Exception:
                        for q in (p, Partial.map_path(p)):
                            try:
                                os.remove(q)
                            except OSError:
                                pass
                elif n.endswith(".part.json") or n.endswith(".part.json.tmp"):
                    if not os.path.exists(p[:-5] if n.endswith(".json") else p[:-9]):
                        os.remove(p)
                elif e.is_file():
                    st = e.stat()
                    if st.st_size > self.cfg.max_file_any:
                        continue
                    known = self.index.get(n)
                    # size and mtime together: a file whose content changed but kept both is a
                    # file someone built to be mistaken for another one, and it would have to
                    # survive our own writes, which always produce a new name
                    if known and known[1] == st.st_size and known[2] == st.st_mtime_ns:
                        sha = known[0]
                    else:
                        sha = sha256_file(p)
                        hashed += 1
                    # A file we cannot place, because the index is gone or someone put it here, counts
                    # as local, so prune leaves it alone. Keeping a file too long costs disk;
                    # deleting one of the user's costs the file.
                    origin = known[3] if known and len(known) > 3 else "local"
                    fresh[n] = [sha, st.st_size, st.st_mtime_ns, origin]
                    self.by_sha[sha] = p
            except OSError:
                pass
        self.index = fresh                      # entries whose file is gone die here
        self._save_index()
        if self.by_sha:
            log.info("file cache: %d file(s) in %s (%d digested, %d from the index)",
                     len(self.by_sha), self.dir, hashed, len(self.by_sha) - hashed)

    def _remember(self, sha: str, path: str, origin: str = "local"):
        """Record a digest we already know, so the next start does not recompute it."""
        try:
            st = os.stat(path)
        except OSError:
            return
        self.index[os.path.basename(path)] = [sha, st.st_size, st.st_mtime_ns, origin]
        self._save_index()

    def put(self, sha: str, path: str, origin: str = "local"):
        """Record content we hold. `origin` says whether we received it, which is what prune asks."""
        with self.lock:
            self.by_sha[sha] = path
        if os.path.dirname(os.path.abspath(path)) == str(self.dir.resolve()):
            self._remember(sha, path, origin)

    def get(self, sha: str):
        """Path if we still have that content, else None (stale entries are dropped)."""
        with self.lock:
            p = self.by_sha.get(sha)
            if p and os.path.isfile(p):
                return p
            self.by_sha.pop(sha, None)
            return None

    def touch(self, path: str):
        """Mark a file as used just now. mtime doubles as "last used" for housekeeping."""
        try:
            os.utime(path, None)
        except OSError:
            return
        # touching is exactly the case where mtime moves without the content changing, so the
        # index has to be told, otherwise every re-use of a cached file costs a re-hash next start
        name = os.path.basename(path)
        known = self.index.get(name)
        if known:
            self._remember(known[0], path, known[3] if len(known) > 3 else "local")

    def prune(self, keep: str):
        """
        Drop files we *received* and have not used for keep_hours, then the oldest of them until
        the folder is under keep_max_mb.  `keep` (the file on the clipboard right now) is never
        removed.

        **Counting and deleting have deliberately different scopes.** The budget counts everything
        in files_dir, because the folder's size is the folder's size and the user's own files take
        up the same disk. Only files marked "remote" in the index, and `.part` files, are candidates
        for deletion: files_dir is also where this service writes a screenshot it is about to offer,
        and deleting those after keep_hours meant a phone asking for one got "not available any
        more" for a picture the user could still see on the clipboard.

        The consequence is that keep_max_mb can be unreachable: a folder full of the user's own
        files stays full. That is the right way round to fail.
        """
        cfg = self.cfg
        try:
            # scandir, not listdir + stat: the directory entry already carries size and mtime on
            # Windows, so this is one syscall for the whole folder instead of one per file.
            entries = []
            counted = 0
            for e in os.scandir(cfg.files_dir):
                n, p = e.name, e.path
                if n.endswith(".part.json") or n.endswith(".json.tmp"):
                    continue                                # handled with their .part
                if not e.is_file():
                    continue
                st = e.stat()
                counted += st.st_size                       # the budget sees the whole folder
                if p == keep:
                    continue
                known = self.index.get(n)
                removable = n.endswith(".part") or (known is not None and len(known) > 3
                                                    and known[3] == "remote")
                if removable:
                    entries.append((st.st_mtime, st.st_size, p))
            entries.sort()                                  # oldest first
            now = time.time()
            total = counted
            removed = 0
            for mtime, size, p in entries:
                too_old = cfg.keep_hours > 0 and now - mtime > cfg.keep_hours * 3600
                too_big = cfg.keep_max_bytes > 0 and total > cfg.keep_max_bytes
                if not (too_old or too_big):
                    continue
                try:
                    if p.endswith(".part"):
                        with self.lock:
                            pt = next((x for x in self.partials.values() if x.part == p), None)
                            if pt is not None and pt.streams > 0:
                                continue                    # still being received
                            if pt is not None:
                                del self.partials[pt.sha]
                        try:
                            os.remove(Partial.map_path(p))
                        except OSError:
                            pass
                    os.remove(p)
                    total -= size
                    removed += 1
                    self.index.pop(os.path.basename(p), None)
                    with self.lock:
                        for k, v in list(self.by_sha.items()):
                            if v == p:
                                del self.by_sha[k]
                except OSError as e:
                    log.info("prune: cannot remove %s: %s", p, e)
            if removed:
                self._save_index()
                log.info("prune: removed %d old file(s) from %s", removed, cfg.files_dir)
        except Exception as e:
            log.info("prune failed: %s", e)


# ----------------------------------------------------------------------------- sync state
class SyncState:
    """
    Text: CLIP frames, pushed as-is.
    Files: OFFER {name,mime,size,sha256,forwarded} -> HAVE (cached: re-used, nothing sent) | SKIP
    (too big for that link) | WANT {ranges: missing chunks}.  The bytes then move over data
    connections (HELLO role=data) opened by whichever end SecureChannel.drives_transfer() names;
    both ends can open them now, so it is the dialler unless the other end says it cannot.  The end
    that opens them pushes CHUNK frames when it holds the file and sends PULL {ranges} when it
    wants them; the accepting end answers in data_thread.  Every chunk lands in a Partial
    (positional writes + persisted chunk map), so a lost connection, a restart or an ABORT leaves a
    resumable .part behind and the next OFFER/WANT only moves what is still missing.
    ABORT {sha256} from either side stops a transfer (the local clipboard changed while it ran).
    """

    def __init__(self, cfg: Cfg, cache: FileCache):
        self.cfg = cfg
        self.cache = cache
        self.lock = threading.Lock()
        self.clients: set[SecureChannel] = set()
        # The live links, keyed by the peer's node id. Keyed by id and not by address because that
        # is what makes a duplicate visible: two names can be two names for one machine, and the
        # only moment that becomes knowable is when a second handshake returns an id already held.
        self.by_peer: dict[str, SecureChannel] = {}
        # The version of the clip this node currently holds: `(wall-clock ms, node id)`, compared
        # lexicographically, last writer wins. There is no hub and no monotonic sequence number, so
        # this is the only answer to "which clip is newer", and it has to be one every node
        # computes the same way. Incoming versions are normalised by the per-link clock offset
        # before being compared against it.
        self.clip_ts = 0
        self.clip_from = node_id()   # node id of whoever produced the current clip
        self.clip_sha = None         # SHA-256 hex of the current clip content
        self.latest_item = None      # the Item, for catch-up and relay
        # Last 64 "ts:from:sha" keys, for dedup across any topology. The sha is part of the key
        # because two different clips can share a millisecond and a sender, and because files join
        # this set too, keyed on the OFFER's `seq`, where the digest is the whole identity.
        self.seen_set = {}
        # Echo suppression, 16 entries each (see _Bounded): what we wrote into the local clipboard,
        # and what we sent out. A hash in either is ours coming back and must not be applied.
        self.remote_hashes = _Bounded()
        self.sent_hashes = _Bounded()
        self.last_set_path = None    # file we last put on the clipboard (cheap loop check)
        self.last_announce = 0.0     # monotonic time of the last T_KEYS broadcast (rate limit)
        self.last_announced_keys = None   # (psk, since, next) of it; only exact repeats are damped
        self.aborted = {}            # maps a sha to the time of its last ABORT (pull loops check it)
        self.pushing = set()         # shas we are currently pushing over our own data connections
        # Shas with a debounced re-ask already armed (see _schedule_reask). One timer per file at a
        # time: eight streams of one transfer can end within microseconds of each other, and one
        # WANT per ended stream is a re-ask storm that also burns the WANT_RETRIES budget in a
        # single breath.
        self.reask_pending = set()
        # Relay coordination: files we are waiting on someone else to fetch for us, and files we
        # have agreed to fetch for someone else.
        self.relay_waits = {}        # maps a sha to its RelayWait
        self.relay_accepted = {}     # maps a sha to the set of SecureChannel waiters we accepted
        # What each direct peer told us about *its* direct peers, so this node knows the two-hop
        # neighbourhood and can build an OFFER `to` list naming devices it has no connection to.
        # A full snapshot, replaced entirely on each T_PEERS; dropped when the sender disconnects.
        self.indirect: dict[str, list[dict]] = {}
        # Peers that said BYE {reason: idle}, by node id, and that is the whole reason this is a
        # set here rather than a flag on the dialler.
        #
        # Two devices that found each other over mDNS both dial, one of the two links is dropped as
        # a duplicate, and the survivor may be the *inbound* one. So when that peer goes to sleep,
        # the goodbye arrives on a link the dialler does not own and would never hear about. The
        # dialler asks this instead, and is told.
        #
        # Cleared by register(), because a peer that has just completed a handshake is by definition
        # awake whatever it said last time it left; and by a failed dial, because a device that will
        # not answer at all is not merely asleep and the real error is the better thing to show.
        # Those two are also what keeps a peer switched off while idle from reading as idle for the
        # life of the process, which is why the idle claim only stretches the back-off to its
        # maximum instead of suppressing the dial outright. Mirrors SyncService.idlePeers.
        self.idle_peers: set[str] = set()
        # Key rotation state, mirroring SyncService.java's cfg.keys / cfg.rotate.
        self.schedule = cfg.keys
        self.rotate = cfg.rotate

    def persist_schedule(self, sched: Schedule):
        """Persist a new key schedule to config.json and update everything that reads it.

        **Including cfg.** `cfg` is otherwise a start-up snapshot, but the three key fields are the
        ones the connection paths read on every accept and every dial, so a rotation that only wrote
        the new key to disk without updating them would leave every live connection path using a key
        the file no longer agrees with. Android gets the same effect by reloading Config after the
        save; this is the equivalent, and the contract is written down on Cfg itself.

        Done inside the lock so nobody can observe a psk from before the swap together with an
        accepted list from after it.
        """
        try:
            save_schedule(sched, self.rotate)
            with self.lock:
                self.schedule = sched
                self.cfg.keys = sched
                self.cfg.psk = bytes.fromhex(sched.psk)
                self.cfg.psk_hex = sched.psk
        except Exception as e:
            log.warning("rotation: could not save schedule: %s", e)

    # -- link registry --
    #
    # The same node can be reachable two ways at once, a listed address *and* its mDNS
    # advertisement, or two listed names pointing at one machine, and both sides may dial each
    # other in the same moment. When a second connection to a known id appears, three rules decide
    # which one goes:
    #
    #   1. keep the one whose peer address is on-link, drop the other;
    #   2. if both are on-link or neither is, and the two were opened by *different* nodes, the node
    #      with the larger id closes the connection it opened;
    #   3. if both were opened by the *same* node, two names for one machine, keep the older.
    #
    # Rule 2's asymmetry is essential: if both sides closed, the pair would disconnect entirely.
    # Rule 3 is not an afterthought: several names for one host is a supported configuration, and
    # without it the rule set is undefined for the case it creates.
    @staticmethod
    def _duplicate_loser(old: "SecureChannel", new: "SecureChannel") -> "SecureChannel":
        """
        Which of two links to one peer has to go.

        Both ends compute this from the same three facts, whether each link is on-link, which node
        opened it, and which is older, so they reach the same verdict independently. That is the
        property the rules exist for: a tiebreak the two ends can disagree about closes *both*
        links and disconnects the pair. It is also why `lan` is the term here and not `via`: only a
        dialler knows whether it found the peer by mDNS or by name, so `via` is not a shared fact.
        """
        if old.lan != new.lan:
            return old if new.lan else new                 # 1. keep the one that is on-link
        if old.initiator != new.initiator:
            # 2. opened by different nodes: the link opened by the *larger* id goes. `initiator` is
            # true for the links this PC opened, so this picks a side, not a link we happen to own.
            #
            # The larger node's link is the one to close; performing it from whichever end notices
            # first is the same close of the same socket, both ends name the same loser, and it
            # does not depend on the larger node having both links registered yet.
            loser_is_ours = node_id() > str(new.node_id or "")
            return old if old.initiator == loser_is_ours else new
        # 3. same opener, two names for one machine. Keep the older: it is the one already carrying
        # traffic, and `new` is the newer by construction, having registered second.
        return new

    def register(self, ch: "SecureChannel"):
        """
        Claim the peer behind `ch`, or arbitrate against the link that already holds it.

        :return: None when `ch` is now the link for its peer, or the reason it must be closed with.
                 When the incumbent loses instead it is closed from here and `ch` takes its place.
        """
        if not ch.node_id:
            # Protocol 2 requires one, and without it none of the rules above can be applied: an
            # anonymous peer cannot be recognised as a duplicate, or as this PC.
            return "no node id"
        with self.lock:
            # A peer that has just completed a handshake is awake, whatever it said last time it
            # left. Dropped here and not on the BYE, so it also covers the peer coming back on a
            # link some *other* thread dialled.
            self.idle_peers.discard(ch.node_id)
            other = self.by_peer.get(ch.node_id)
            if other is None or other is ch:
                self.clients.add(ch)
                self.by_peer[ch.node_id] = ch
                return None
            if self._duplicate_loser(other, ch) is ch:
                return "duplicate"
            self.clients.discard(other)
            self.clients.add(ch)
            self.by_peer[ch.node_id] = ch
        log.info("two links to %s [%s], closing the %s one", other.device,
                 short_id(other.node_id), "outbound" if other.initiator else "inbound")
        other.superseded = True
        other.bye("duplicate")
        return None

    def unregister(self, ch: "SecureChannel"):
        """Conditional on still being the registered link: a replacement may already have taken it."""
        with self.lock:
            self.clients.discard(ch)
            if ch.node_id and self.by_peer.get(ch.node_id) is ch:
                del self.by_peer[ch.node_id]
            # Drop what this peer told us about its peers: the roster is a snapshot of one node's
            # direct links, so with that node gone there is nothing left to vouch for it.
            if ch.node_id:
                self.indirect.pop(ch.node_id, None)
        # The remaining peers need to know this one is gone, or their own `to` lists go on naming a
        # device that cannot be reached through us any more.
        if ch.node_id:
            broadcast_peers(self)
        # If a relay we were waiting on disconnected, walk to the next candidate rather than falling
        # back to the origin; see _ask_relay.
        if ch.node_id:
            for rw in list(self.relay_waits.values()):
                if ch.node_id == self._current_relay_for(rw) and self._advance(rw, ch.node_id):
                    self._ask_relay(rw)
            # Remove this channel from relay-accepted waiter sets.
            with self.lock:
                for waiters in self.relay_accepted.values():
                    waiters.discard(ch)

    def holds(self, node: str) -> bool:
        """Is some link to that node still up?  Dial-time duplicate suppression asks this."""
        with self.lock:
            return node in self.by_peer

    def note_bye(self, ch: "SecureChannel", reason):
        """Record an idle goodbye against the peer, whichever link it arrived on.

        Called from both ends of every control connection, the accepting one as well as the
        dialling one, because that is the whole point of keeping it by node id: the link that hears
        the goodbye is often not the link that would otherwise redial.
        """
        if reason == BYE_IDLE and ch.node_id:
            with self.lock:
                self.idle_peers.add(ch.node_id)

    def is_idle(self, node: str) -> bool:
        with self.lock:
            return node in self.idle_peers

    def not_idle(self, node: str):
        """A dial that failed outright: whatever it is, it is not merely asleep."""
        if node:
            with self.lock:
                self.idle_peers.discard(node)

    def known_peer_ids(self) -> set:
        """Direct peers ∪ the peers they reported.  The full `to` set for OFFER and CLIP.

        Naming a device this node cannot reach is the point: it tells whoever *can* reach it that
        the originator meant it to have the content, which is what lets a receiver two hops away
        compute a sound relay decision instead of one based on the forwarder's own client list.
        """
        with self.lock:
            result = {c.node_id for c in self.clients if c.node_id}
            for entries in self.indirect.values():
                for entry in entries:
                    pid = entry.get("id")
                    if pid:
                        result.add(pid)
        return result

    def online(self) -> int:
        with self.lock:
            return len(self.clients)

    # -- abort helpers --
    def is_aborted(self, sha: str) -> bool:
        with self.lock:
            return sha in self.aborted

    def abort(self, sha: str, reason: str, notify=None, keep=True):
        """Stop every transfer of `sha`: pull loops exit, an inbound Partial is kept for resume."""
        with self.lock:
            now = time.time()
            self.aborted = {k: v for k, v in self.aborted.items() if now - v < 3600}
            self.aborted[sha] = now
            targets = list(notify) if notify else []
        pt = self.cache.partials.get(sha)
        if pt is not None:
            if keep:
                pt.keep()
            else:
                pt.discard()
                self.cache.partials.pop(sha, None)
        for c in targets:
            try:
                c.send_json(T_ABORT, {"sha256": sha, "reason": reason})
            except Exception:
                pass
        log.info("transfer %s aborted: %s", sha[:12], reason)

    def clear_abort(self, sha: str):
        with self.lock:
            self.aborted.pop(sha, None)

    # -- frames --
    def header(self, item: Item, *, forwarded: bool = False, from_id: str = "",
               seq: int = 0, to=None) -> dict:
        """An OFFER header.

        `forwarded` is what caps a file at one hop, exactly as it does for text: a node that
        receives an OFFER already marked forwarded passes it to nobody. Without it a file reaching
        a node with two other peers is re-offered by each of them in turn, and in a triangle it
        comes back to where it started, where, the digest being unknown by then, it is applied to
        the clipboard and offered onwards again.

        `seq` and `from` together with the digest are also the seen-set key, so a forwarded copy of
        a file keeps the *originator's* identity rather than acquiring ours.
        """
        return {"seq": seq or int(time.time() * 1000), "name": item.name, "mime": item.mime,
                "size": item.size, "sha256": item.sha256, "forwarded": forwarded,
                "from": from_id or node_id(),
                "to": list(self.known_peer_ids()) if to is None else list(to)}

    def announce(self, item: Item, targets, *, ts=0, from_id="", forwarded=False, extra_to=None,
                 seq=0):
        """Push text (with its version fields), or offer a file."""
        all_ids = self.known_peer_ids()
        if extra_to:
            all_ids |= extra_to
        for c in targets:
            try:
                if item.kind == "text":
                    c.send_json(T_CLIP, {
                        "ts": ts or self.clip_ts,
                        "from": from_id or self.clip_from,
                        "to": list(all_ids),
                        "forwarded": forwarded,
                        "mime": "text/plain",
                        "sha256": item.sha256,
                        "data": item.text,
                    })
                elif item.size > c.limit:
                    log.info("not offering %s to %s: %d bytes > %d (%s link)", item.name, c.device, item.size,
                             c.limit, "lan" if c.lan else "internet")
                else:
                    c.send_json(T_OFFER, self.header(item, forwarded=forwarded, from_id=from_id,
                                                     seq=seq, to=all_ids))
                    log.info("offered %s to %s%s", item.name, c.device,
                             " (forwarded)" if forwarded else "")
            except Exception as e:
                log.warning("send to %s failed: %s", c.device, e)

    def catch_up(self, ch: SecureChannel, peer_clip_ts: int, peer_clip_sha: str):
        """Send our clip if it is newer than the peer's.

        There is no hub: each side states its `(version, sha256)` in HELLO and the one holding the
        newer content sends it. Both evaluate independently, only the newer one acts, and no request
        frame is needed, so catching a device back up does not depend on any one particular machine
        having been awake.
        """
        with self.lock:
            item = self.latest_item
            ts, from_id, sha = self.clip_ts, self.clip_from, self.clip_sha
        # Files take part too, same as Android's catchUp(). announce() sends them as an OFFER, and
        # a peer that already has the bytes answers HAVE -- so the cost of offering something it
        # does not need is one frame, while the cost of *not* offering was that a device which went
        # offline before the transfer finished could never get the file without a fresh copy.
        if item is None:
            return
        # Same content, so there is nothing to send.
        if peer_clip_sha and peer_clip_sha == sha:
            return
        # Version comparison: (ts, from) lexicographic.  HELLO does not carry clip_from,
        # so ch.node_id is an approximation, which is wrong when the peer's clip was originated by a
        # third node.  The sha check above handles the common case; this guard is a tiebreak.
        if peer_clip_ts > 0 and (ts, from_id) <= (peer_clip_ts, ch.node_id or ""):
            return
        self.announce(item, [ch])

    # -- local clipboard changed --
    def on_local_change(self, item: Item):
        if item.kind == "text" and (not item.text or len(item.text.encode("utf-8")) > self.cfg.max_bytes):
            return
        if item.kind == "file" and item.path == self.last_set_path:
            return                                   # our own CF_HDROP
        h = item.sha256
        with self.lock:
            if h in self.remote_hashes or h in self.sent_hashes:
                return
            previous = self.latest_item
            self.sent_hashes.add(h)
            self.latest_item = item
            if item.kind == "text":
                self.clip_ts = int(time.time() * 1000)
                self.clip_from = node_id()
                self.clip_sha = h
            targets = list(self.clients)
        # a newer clip supersedes whatever is still in flight, in either direction
        if previous is not None and previous.kind == "file" and previous.sha256 != h:
            self.abort(previous.sha256, "superseded by a newer clip on the PC", notify=targets)
        for sha, pt in list(self.cache.partials.items()):
            if pt.streams > 0 and sha != h:
                self.abort(sha, "superseded by a newer clip on the PC", notify=[pt.origin] if pt.origin else None)
        if item.kind == "file":
            # "local": this is a file the user copied here, or a screenshot we just wrote. prune
            # must not delete it out from under a peer that is about to ask for it.
            self.cache.put(h, item.path, origin="local")
            self.clear_abort(h)
        log.info("sent local clip to remote (%s)", item)
        self.announce(item, targets)

    # -- a peer offers a file --
    def on_offer(self, hdr: dict, origin: SecureChannel):
        sha = str(hdr.get("sha256", ""))
        name = safe_name(str(hdr.get("name", "clip")))
        size = int(hdr.get("size", -1))
        # `forwarded` is read off the header by Partial and carried to _apply_remote, which is where
        # the forwarding decision is finally made, by then the bytes have arrived, possibly minutes
        # and one restart later, so it cannot be decided here.
        #
        # Files go through the same seen-set as text, keyed on the OFFER's `seq` where text uses its
        # `ts`. Without it a file has no dedup at all: a peer that reconnects and re-offers
        # something it sent an hour ago is indistinguishable from a peer offering it for the first
        # time, and in a three-node network the same file circulates until something else is
        # copied. Hitting the set means we have already decided about this exact file from this
        # exact sender, so answer HAVE if we have it, and otherwise say nothing.
        seen_key = "%s:%s:%s" % (int(hdr.get("seq", 0) or 0), str(hdr.get("from", "")), sha)
        with self.lock:
            seen = seen_key in self.seen_set
            if not seen:
                self.seen_set[seen_key] = int(hdr.get("seq", 0) or 0)
                while len(self.seen_set) > 64:
                    self.seen_set.pop(next(iter(self.seen_set)))
            echo = sha in self.sent_hashes or sha in self.remote_hashes
        if seen:
            if self.cache.get(sha) is not None:
                origin.send_json(T_HAVE, {"sha256": sha})
            log.info("offer from %s: %s already seen, ignored", origin.device, name)
            return
        if echo:
            origin.send_json(T_HAVE, {"sha256": sha})
            return
        path = self.cache.get(sha)
        if path is not None:
            # HAVE and a touch, and nothing else: an OFFER for content we already hold on disk is
            # not evidence the user just copied it, so it must not be applied to the clipboard;
            # otherwise a reconnecting phone re-offering an old file would silently replace whatever
            # is on the clipboard right now and get that copy broadcast back out.
            origin.send_json(T_HAVE, {"sha256": sha})
            log.info("offer from %s: %s already cached as %s", origin.device, name, path)
            self.cache.touch(path)
            return
        if size < 0 or size > origin.limit:
            origin.send_json(T_SKIP, {"sha256": sha, "reason": f"{size} bytes > {origin.limit} ({'lan' if origin.lan else 'internet'} link)"})
            log.info("offer from %s: %s (%d bytes) skipped, over the %s limit", origin.device, name, size,
                     "lan" if origin.lan else "internet")
            return

        # --- Relay election ---
        # A file offered to N peers is otherwise uploaded N times, and the origin is often the
        # phone, on the link that can least afford it. Receivers coordinate and the sender does not:
        # the OFFER says who it went to, and each receiver picks the single highest-priority node in
        # `(to ∩ my LAN peers) ∪ {me} ∪ {origin if on my LAN}`. Us or the origin means WANT as
        # usual; anyone else means ask that one node to fetch it and wait.
        #
        # The *single highest* matters rather than merely someone who could serve: with a looser
        # rule D asks C while C is itself waiting on B, a chain forms, latency stacks and one
        # failure cascades.
        rw = self.relay_waits.get(sha)
        if rw is not None and origin.node_id and origin.node_id == self._current_relay_for(rw):
            # This OFFER is from the relay we asked, so skip election and WANT directly.
            self.relay_waits.pop(sha, None)
            log.info("offer from relay %s: %s, want directly", short_id(origin.node_id), name)
        else:
            offer_from = str(hdr.get("from", ""))
            to_list = hdr.get("to") or []
            if to_list and offer_from:
                recipients = set(str(x) for x in to_list)
                best = self._elect_relay(offer_from, origin, recipients)
                if best and best != node_id() and best != offer_from:
                    candidates = self._build_candidate_list(offer_from, origin, recipients)
                    wait = RelayWait(sha, hdr, origin, candidates)
                    self.relay_waits[sha] = wait
                    self._ask_relay(wait)
                    return

        # Normal WANT path.
        self._want_from_peer(hdr, origin)

    def _want_from_peer(self, hdr: dict, origin: SecureChannel):
        """The common WANT path: prepare a Partial, send WANT (extracted for relay fallback)."""
        sha = str(hdr.get("sha256", ""))
        name = safe_name(str(hdr.get("name", "clip")))
        size = int(hdr.get("size", -1))
        # a new offer from this device supersedes anything it was still sending us
        for other, pt in list(self.cache.partials.items()):
            if other != sha and pt.origin is origin and pt.streams > 0:
                self.abort(other, f"{origin.device} offered something newer", notify=[origin])
        self.clear_abort(sha)
        pt = self.cache.partials.get(sha)
        if pt is None or pt.finalized:
            try:
                pt = Partial(self.cfg.files_dir, hdr=hdr)
            except OSError as e:
                # A read-only files_dir, a full disk, a name the filesystem will not take. Letting
                # this propagate closed the *control* connection, so the peer saw a link failure and
                # reconnected into the same wall, with nothing anywhere saying why. SKIP is the
                # frame that means "not this file", and it leaves the link up.
                log.warning("cannot open a file for %s: %s", name, e)
                origin.send_json(T_SKIP, {"sha256": sha, "reason": "cannot open a file for it"})
                return
            self.cache.partials[sha] = pt
        pt.origin = origin
        pt.retries = 0
        missing = pt.missing()
        if not missing:
            self._finalize(pt, origin)
            origin.send_json(T_HAVE, {"sha256": sha})
            log.info("offer from %s: %s already complete on disk", origin.device, name)
            return
        origin.send_json(T_WANT, {"sha256": sha, "ranges": missing})
        log.info("offer from %s: %s (%d bytes), want %s", origin.device, name, size,
                 "all" if len(pt.have) == 0 else f"{sum(b - a for a, b in missing)}/{pt.n} chunks (resume)")
        # Only one end opens the data connections. When it is this one, the WANT above is not the
        # whole of our part: nothing else will happen until we go and pull the bytes.
        if origin.drives_transfer():
            self.start_pull(origin, pt, missing)

    # -- a peer wants a file we offered (or that we have): whoever drives the link moves the bytes --
    def on_want(self, hdr: dict, origin: SecureChannel):
        sha = str(hdr.get("sha256", ""))
        path = self.cache.get(sha)
        if path is None:
            # Streaming relay: a file we accepted to relay is still arriving, so it is in `partials`
            # and by definition not in the cache, but we can forward it chunk by chunk as it lands,
            # which is what serve_relay_pull does. ABORTing here on the control connection killed
            # that before the data connection ever got the chance, so the relay degraded to
            # store-and-forward and doubled the end-to-end time for every relayed file.
            pt = self.cache.partials.get(sha)
            if not (pt is not None and sha in self.relay_accepted):
                log.warning("%s wants %s but it is not in the cache any more", origin.device, sha[:12])
                origin.send_json(T_ABORT, {"sha256": sha, "reason": "not available any more"})
                return
        self.clear_abort(sha)
        if not origin.drives_transfer():
            # The other end opens the streams on this link: it will PULL, and serve_pull answers.
            log.info("%s wants %s: waiting for its data connections", origin.device, sha[:12])
            return
        if path is None:
            # We drive, but the bytes are not all here yet. There is nothing to push; the waiter
            # will be offered the file again by offer_to_waiters when it completes.
            log.info("%s wants %s: still receiving it ourselves", origin.device, sha[:12])
            return
        ranges = hdr.get("ranges") or [[0, nchunks(os.path.getsize(path))]]
        self.start_push(origin, sha, path, ranges)

    # -- data connection: phone pushes chunks of an inbound file --
    def on_push_open(self, sha: str, ch: SecureChannel):
        """This data connection has shown itself to be a pusher: count it in, and hand back the
        Partial it feeds.  Called from the first CHUNK, never at open, because until a frame arrives
        a pusher and a relay waiter's puller are indistinguishable (see data_thread)."""
        pt = self.cache.partials.get(sha)
        if pt is None or pt.finalized:
            raise ConnectionError(f"no transfer in progress for {sha[:12]}")
        with pt.lock:
            pt.streams += 1
        return pt

    @staticmethod
    def on_chunk(pt: Partial, f, payload: bytes):
        (idx,) = struct.unpack(">I", payload[:4])
        pt.write(f, idx, payload[4:])

    def on_push_close(self, pt: Partial, ch: SecureChannel, clean: bool):
        """A push stream ended (END frame or connection loss).  Finalize when complete, re-ask otherwise."""
        with pt.lock:
            pt.streams -= 1
            last = pt.streams == 0
        if pt.finalized:
            return
        if pt.complete():
            self._finalize(pt, ch)
        elif last and not self.is_aborted(pt.sha):
            pt.keep()
            self._schedule_reask(pt)

    def on_stream_gone(self, sha: str, ch: SecureChannel):
        """A data connection we accepted closed without ever having been counted as a push stream.

        Counting a push stream at its first CHUNK rather than at open is right, because until a frame
        arrives, a pusher and a relay waiter's puller are indistinguishable, and counting a puller
        as a pusher let an unrelated connection decide when "the last push stream is out". But it
        left a hole: a connection that opens *to push* and dies before its first byte is counted by
        nobody, so `on_push_close` never runs and the one thing that restarts a stalled transfer,
        the debounced WANT, is never armed. With all eight streams failing before their first byte
        (a peer whose storage read fails, a link that drops the moment it is used) the transfer then
        waited for the peer's next OFFER, which may be a reconnect or a catch-up away.

        Deliberately *not* a stream for counting purposes: this connection pushed nothing, and
        counting it in `pt.streams` would be exactly what the late counting is designed to avoid. It
        only arms the same debounced re-ask, which re-checks everything before it sends anything.

        The caller filters out connections that identified themselves as pullers (a PULL arrived),
        so what reaches here is "pushed nothing and never said it was pulling". A connection that
        died before saying anything at all is ambiguous by construction; re-asking is the safe way
        to resolve it, because a stray WANT to a peer that drives the transfer costs one log line
        and nothing else, while not asking costs the transfer.
        """
        pt = self.cache.partials.get(sha)
        if pt is None or pt.finalized or self.is_aborted(sha):
            return
        # Only where WANT is our lever at all: when the peer drives, it opens the data connections
        # and a WANT asks it to do what it is already doing. When *we* drive there is no stall to
        # clear either, since _pull_transfer retries its own stripes.
        origin = pt.origin
        if origin is None or origin.drives_transfer():
            return
        with pt.lock:
            busy = pt.streams > 0
        if busy or pt.complete():
            return
        log.info("%s opened a data connection for %s and closed it before the first chunk; re-asking",
                 ch.device, sha[:12])
        pt.keep()
        self._schedule_reask(pt)

    def _schedule_reask(self, pt: Partial):
        """Arm the debounced re-ask for `pt`, at most one timer per file.

        The debounce gives the peer a moment to open its remaining streams before we re-ask, so the
        ordinary "one stripe finished early" case never produces a WANT at all. The one-timer rule
        is what keeps eight streams ending together, or failing together before their first byte,
        from turning into eight WANTs.

        daemon: a two-second debounce must not be a reason the process refuses to exit.
        """
        with self.lock:
            if pt.sha in self.reask_pending:
                return
            self.reask_pending.add(pt.sha)
        t = threading.Timer(2.0, self._reask, args=(pt,))
        t.daemon = True
        t.start()

    def _finalize(self, pt: Partial, ch: SecureChannel):
        """Verify, publish and apply a complete file. Called from a stream ending and from an offer
        of something already on disk, which is why it is not inline in on_push_close any more."""
        try:
            path = pt.finalize()
        except ValueError as e:
            log.warning("transfer from %s rejected: %s", ch.device, e)
            self.cache.partials.pop(pt.sha, None)
            return
        self.cache.partials.pop(pt.sha, None)
        self.cache.put(pt.sha, path, origin="remote")
        log.info("saved %s", path)
        self._apply_remote(Item.from_path(path, pt.mime, pt.sha), pt.origin or ch,
                           forwarded=pt.forwarded, to=pt.to, from_id=pt.origin_id, seq=pt.seq)
        # Offer it to every waiter that asked us to relay this file, carrying the originator's
        # seq/from, since this is the only caller that still holds them, and without them the completed
        # offer would land under a different seen-set key than the early one already sent.
        self.offer_to_waiters(pt.sha, seq=pt.seq, from_id=pt.origin_id)

    def _reask(self, pt: Partial):
        # Released first thing, so a stream that ends while this one is deciding can arm the next
        # timer rather than being swallowed by a flag that outlives its timer.
        with self.lock:
            self.reask_pending.discard(pt.sha)
        with pt.lock:
            busy = pt.streams > 0
        if busy or pt.finalized or pt.complete() or self.is_aborted(pt.sha):
            return
        origin = pt.origin
        with self.lock:
            alive = origin in self.clients
        if alive and pt.retries < WANT_RETRIES:
            pt.retries += 1
            missing = pt.missing()
            log.info("%s incomplete (%s), asking %s again for %d chunk(s)", pt.name, pt, origin.device,
                     sum(b - a for a, b in missing))
            try:
                origin.send_json(T_WANT, {"sha256": pt.sha, "ranges": missing})
            except Exception:
                pass
        else:
            log.info("%s incomplete (%s); kept for resume", pt.name, pt)

    # -- data connection: phone pulls chunks of a file we have --
    def serve_pull(self, sha: str, ranges, ch: SecureChannel):
        # If a Partial exists and the file is not yet in the cache, serve chunks as they become
        # available rather than waiting for the complete file. Only possible because chunks are
        # addressed by index: they may arrive here over eight connections in any order, be forwarded
        # in that order, and still be written positionally at the far end.
        pt = self.cache.partials.get(sha)
        if pt is not None and not pt.complete() and sha in self.relay_accepted:
            self.serve_relay_pull(sha, ranges, ch, pt)
            return
        path = self.cache.get(sha)
        if path is None:
            ch.send_json(T_ABORT, {"sha256": sha, "reason": "not available any more"})
            return
        size = os.path.getsize(path)
        n = nchunks(size)
        sent = 0
        with open(path, "rb") as f:
            for idx in expand(ranges):
                if idx < 0 or idx >= n:
                    continue
                if self.is_aborted(sha):
                    ch.send_json(T_ABORT, {"sha256": sha, "reason": "aborted"})
                    return
                f.seek(idx * CHUNK)
                ch.send(T_CHUNK, struct.pack(">I", idx) + f.read(CHUNK))
                sent += 1
        ch.send_json(T_END, {"sha256": sha})
        log.info("streamed %d chunk(s) of %s to %s", sent, os.path.basename(path), ch.device)

    # -- data connections this end opens (the other half of drives_transfer) --
    def _data_connect(self, control: SecureChannel, sha: str) -> SecureChannel:
        """One outbound data connection to the peer behind `control`, already past HELLO.

        Aimed at the peer's **declared listening port**, not at the control socket's remote port:
        on a connection we accepted that is the peer's ephemeral source port and reaches nothing.
        The rest of the address is copied from the control socket verbatim, flow info and scope id
        included, because a link-local IPv6 peer is unreachable without its scope.
        """
        peer = control.sock.getpeername()
        port = control.peer_port or self.cfg.port
        addr = (peer[0], port) + tuple(peer[2:])
        with self.lock:
            psk = self.cfg.psk
        sock = socket.socket(control.sock.family, socket.SOCK_STREAM)
        try:
            sock.settimeout(10)
            sock.connect(addr)
            sock.settimeout(READ_TIMEOUT)
            sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            ch = SecureChannel(sock, psk, self.cfg.max_frame, initiator=True)
        except Exception:
            try:
                sock.close()
            except OSError:
                pass
            raise
        # A data connection's HELLO is deliberately short: it enrols nothing, so it carries only
        # what the accepter needs to route it: who we are, and which file this stream is for.
        ch.send_json(T_HELLO, {"v": PROTOCOL_VERSION, "id": node_id(), "device": node_name(),
                               "role": "data", "sha256": sha})
        return ch

    @staticmethod
    def _stripes(idxs: list) -> list:
        """Deal chunk indexes round-robin into at most DATA_STREAMS stripes, as Transfer does."""
        n = max(1, min(DATA_STREAMS, len(idxs)))
        return [idxs[k::n] for k in range(n)]

    def start_pull(self, control: SecureChannel, pt: Partial, ranges):
        """Fetch the missing chunks of `pt` over data connections we open."""
        t = threading.Thread(target=self._pull_transfer, args=(control, pt, ranges),
                             daemon=True, name="clipsync-pull")
        t.start()

    def _pull_transfer(self, control: SecureChannel, pt: Partial, ranges):
        sha = pt.sha
        try:
            for _ in range(1 + WANT_RETRIES):
                if pt.finalized or pt.complete() or self.is_aborted(sha):
                    break
                idxs = [i for i in expand(ranges) if 0 <= i < pt.n]
                if not idxs:
                    break
                stripes = self._stripes(idxs)
                log.info("pulling %s from %s: %d chunk(s) over %d connection(s)", pt.name,
                         control.device, len(idxs), len(stripes))
                threads = []
                for mine in stripes:
                    t = threading.Thread(target=self._pull_worker, args=(control, pt, mine),
                                         daemon=True, name="clipsync-pull")
                    t.start()
                    threads.append(t)
                for t in threads:
                    t.join()
                if pt.complete() or self.is_aborted(sha):
                    break
                ranges = pt.missing()
                # Retried from here rather than by re-sending WANT: we are the end that opens the
                # connections, so a WANT would ask the peer to do something it is not going to do.
                log.info("%s incomplete (%s), pulling the rest", pt.name, pt)
            if pt.complete() and not pt.finalized:
                self._finalize(pt, control)
            elif not pt.finalized:
                pt.keep()
                log.info("%s incomplete (%s); kept for resume", pt.name, pt)
        except Exception as e:
            log.warning("pull of %s failed: %s", pt.name, e)

    def _pull_worker(self, control: SecureChannel, pt: Partial, mine: list):
        ch, fh, counted = None, None, False
        try:
            ch = self._data_connect(control, pt.sha)
            with pt.lock:
                # Counted from the moment it opens, unlike the connections *they* open (data_thread
                # waits for a CHUNK, because there a pusher and a relay waiter's puller look alike
                # until the first frame). There is no ambiguity here: we opened this one, to pull
                # this file, so it feeds this Partial or it fails.
                pt.streams += 1
            counted = True
            ch.send_json(T_PULL, {"sha256": pt.sha, "ranges": ranges_of(mine)})
            fh = pt.open_writer()
            # Read until the peer's END rather than until our own chunks are in: closing earlier
            # makes its final send fail with a reset and log a fault that did not happen.
            while True:
                if self.is_aborted(pt.sha):
                    return
                typ, payload = ch.recv()
                if typ == T_CHUNK:
                    (idx,) = struct.unpack(">I", payload[:4])
                    # Streaming relay, the half this end was missing: when *we* drive the download
                    # the write happens here rather than in data_thread, so nothing was watching the
                    # first chunk land and the early OFFER was never sent, so a PC relay that pulled
                    # silently degraded to store-and-forward and doubled the end-to-end time for
                    # every file crossing it. This is the symmetric half of Java's
                    # FileExchange.startDownload, which hangs Transfer.onFirstChunk on the download
                    # for exactly this; `claim_first_chunk` is the one shared judgement of "first",
                    # used by the push path (data_thread) and by Java's `claimFirstChunk` on both of
                    # its paths, and asked *before* the write, of the state the write is about to
                    # change. Eight stripes reach their first write together, and the test-and-set
                    # under the Partial's own lock is what makes one of them win.
                    was_first = pt.claim_first_chunk()
                    pt.write(fh, idx, payload[4:])
                    # Only when we are relaying it: an early OFFER means "start pulling from me",
                    # and with no waiter there is nobody it could mean it to. Java's condition is
                    # `relay.hasWaiters(sha)`, which is this same membership test on the accepted
                    # map: RelayCoordinator.hasWaiters is the same thing as relayAccepted.containsKey.
                    if was_first and pt.sha in self.relay_accepted:
                        self.early_offer_to_waiters(pt.sha, pt)
                elif typ == T_END:
                    return
                elif typ == T_ABORT:
                    reason = json.loads(payload.decode("utf-8")).get("reason", "")
                    log.info("%s aborted the transfer: %s", control.device, reason)
                    return
                elif typ == T_PING:
                    ch.send(T_PONG)
        except Exception as e:
            if not self.is_aborted(pt.sha):
                log.info("pull stream for %s failed: %s", pt.sha[:12], e)
        finally:
            if fh is not None:
                try:
                    fh.close()
                except OSError:
                    pass
            if counted:
                with pt.lock:
                    pt.streams -= 1
            if ch is not None:
                try:
                    ch.sock.close()
                except OSError:
                    pass

    def start_push(self, control: SecureChannel, sha: str, path: str, ranges):
        """Send the chunks a peer asked for over data connections we open."""
        with self.lock:
            if sha in self.pushing:
                log.info("%s wants %s: already sending it", control.device, sha[:12])
                return
            self.pushing.add(sha)
        t = threading.Thread(target=self._push_transfer, args=(control, sha, path, ranges),
                             daemon=True, name="clipsync-push")
        t.start()

    def _push_transfer(self, control: SecureChannel, sha: str, path: str, ranges):
        try:
            n = nchunks(os.path.getsize(path))
            idxs = [i for i in expand(ranges) if 0 <= i < n]
            if not idxs:
                return
            stripes = self._stripes(idxs)
            log.info("pushing %s to %s: %d chunk(s) over %d connection(s)",
                     os.path.basename(path), control.device, len(idxs), len(stripes))
            threads = []
            for mine in stripes:
                t = threading.Thread(target=self._push_worker, args=(control, sha, path, mine),
                                     daemon=True, name="clipsync-push")
                t.start()
                threads.append(t)
            for t in threads:
                t.join()
        except Exception as e:
            log.warning("push of %s failed: %s", sha[:12], e)
        finally:
            with self.lock:
                self.pushing.discard(sha)

    def _push_worker(self, control: SecureChannel, sha: str, path: str, mine: list):
        ch = None
        try:
            ch = self._data_connect(control, sha)
            with open(path, "rb") as f:
                for idx in mine:
                    if self.is_aborted(sha):
                        return
                    f.seek(idx * CHUNK)
                    ch.send(T_CHUNK, struct.pack(">I", idx) + f.read(CHUNK))
            ch.send_json(T_END, {"sha256": sha})
            # The peer may answer ABORT while we were pushing; a short wait drains it, and an
            # EOF or a timeout here is the ordinary ending.
            ch.sock.settimeout(0.5)
            try:
                ch.recv()
            except Exception:
                pass
        except Exception as e:
            if not self.is_aborted(sha):
                log.info("push stream for %s failed: %s", sha[:12], e)
        finally:
            if ch is not None:
                try:
                    ch.sock.close()
                except OSError:
                    pass

    # -- a peer sent text --
    def on_remote_text(self, item: Item, origin: SecureChannel, msg: dict):
        """Handle a text CLIP from a peer, with version comparison and forwarding."""
        h = item.sha256
        remote_ts = int(msg.get("ts", 0))
        remote_from = str(msg.get("from", ""))
        forwarded = bool(msg.get("forwarded", False))

        # Normalise ts into local clock domain.
        offset = getattr(origin, "clock_offset", 0)
        norm_ts = remote_ts - offset

        # Seen-set check. The digest is part of the key, not just (ts, from): two different clips
        # from one sender can land in the same millisecond, and the second one was being dropped
        # without trace. The same key shape is used for files, where `seq` stands in for `ts`.
        seen_key = f"{remote_ts}:{remote_from}:{h}"
        with self.lock:
            if seen_key in self.seen_set:
                return
            self.seen_set[seen_key] = remote_ts
            # Bound to 64 entries.
            while len(self.seen_set) > 64:
                self.seen_set.pop(next(iter(self.seen_set)))

            if h in self.sent_hashes or h in self.remote_hashes:
                return
            # Version comparison: accept only if strictly newer.
            if self.clip_ts > 0 and (norm_ts, remote_from) <= (self.clip_ts, self.clip_from):
                return
            self.remote_hashes.add(h)
            self.clip_ts = norm_ts
            self.clip_from = remote_from
            self.clip_sha = h
            self.latest_item = item
            others = [c for c in self.clients if c is not origin]

        log.info("applied remote(%s) clip locally (%s%s)", origin.device, item,
                 ", forwarded" if forwarded else "")
        if not clipboard_set_text(item.text):
            log.warning("failed to set clipboard")

        # Forward to exactly `my peers ∖ to`: the ones the origin could not reach itself. One hop
        # only: a frame we forward is marked so and nobody forwards it again, which bounds the
        # depth at two without a TTL, and it never goes back down the link it arrived on.
        if not forwarded and others:
            to_set = set(msg.get("to") or [])
            to_set.add(remote_from)
            to_set.add(node_id())
            forward_targets = [c for c in others if c.node_id and c.node_id not in to_set]
            if forward_targets:
                self.announce(item, forward_targets, ts=remote_ts, from_id=remote_from,
                              forwarded=True, extra_to=to_set)
                for c in forward_targets:
                    log.info("forwarded clip to %s", c.device)

    def _apply_remote(self, item: Item, origin: SecureChannel, *, forwarded: bool = False,
                      to=None, from_id: str = "", seq: int = 0):
        """Apply a received file to the local clipboard, and forward it where text would go.

        Forwarding is the same rule as for text (`on_remote_text`), and it has to be: unconditional
        forwarding to every other connected device would re-offer the file to a peer that already
        had it and bounce it back and forth on a node with two peers. Instead it goes only to peers
        the OFFER's `to` list did not already cover, carries `forwarded=True` so they pass it no
        further, and keeps the originator's `from`/`seq` so everyone's seen-set agrees about which
        file this is.
        """
        h = item.sha256
        with self.lock:
            if h in self.sent_hashes or h in self.remote_hashes:
                return
            self.remote_hashes.add(h)
            self.latest_item = item
            others = [c for c in self.clients if c is not origin]
        log.info("applied remote(%s) clip locally (%s%s)", origin.device, item,
                 ", forwarded" if forwarded else "")
        self.last_set_path = item.path
        if not clipboard_set_file(item.path, item.mime):
            log.warning("failed to set clipboard")
        self.cache.prune(item.path)
        if forwarded or not others:
            return                       # one hop only, as for text
        to_set = set(to or ())
        to_set.add(from_id or (origin.node_id or ""))
        to_set.add(node_id())
        targets = [c for c in others if c.node_id and c.node_id not in to_set]
        if targets:
            self.announce(item, targets, forwarded=True, from_id=from_id or node_id(),
                          seq=seq, extra_to=to_set)

    # -- relay coordination --
    def _build_candidate_list(self, offer_from: str, origin_ch: SecureChannel, recipients: set) -> list:
        """Compute the candidate list for relay election, sorted by priority (best first)."""
        cands = []
        # Me. A PC is type pc on mains, so it wins this order against anything else on the LAN,
        # which is the intended outcome: in a normal household the relay resolves to the PC every
        # time, the difference from a hardcoded hub being that the role is derived and replaceable.
        #
        # `persistent` comes from relay_opt_out and is not hardcoded true, because this has to agree
        # with what declaration() puts in our HELLO. If it did not, a PC that has opted out would go
        # on electing *itself* while every other node had already written it out of their candidate
        # lists, so nobody would ask it to relay and it would never ask anyone else either, and the
        # file would go the expensive way round with no log line anywhere saying why.
        cands.append((node_id(), priority_key(not self.cfg.relay_opt_out, "pc", "mains")))
        # Origin, if on LAN.
        if origin_ch.lan:
            cands.append((offer_from, priority_key(origin_ch.persistent, origin_ch.node_type,
                                                    origin_ch.battery)))
        # LAN peers that are in the recipient list.
        with self.lock:
            peers = [(pid, ch) for pid, ch in self.by_peer.items()
                     if pid != offer_from and ch.lan and pid in recipients]
        for pid, ch in peers:
            cands.append((pid, priority_key(ch.persistent, ch.node_type, ch.battery)))
        cands.sort(key=lambda x: (x[1], x[0]))
        return [c[0] for c in cands]

    def _elect_relay(self, offer_from: str, origin_ch: SecureChannel, recipients: set):
        """Return the best relay candidate id, or None."""
        ids = self._build_candidate_list(offer_from, origin_ch, recipients)
        return ids[0] if ids else None

    def _current_relay_for(self, rw: RelayWait):
        """Return the node id of the relay we are currently asking, or None."""
        if rw.next_idx < len(rw.candidates):
            cid = rw.candidates[rw.next_idx]
            origin_id = rw.origin.node_id if rw.origin else None
            if cid != node_id() and cid != origin_id:
                return cid
        return None

    def _advance(self, rw: RelayWait, cid: str) -> bool:
        """Mark `cid` failed and move the walk past it, **once**.

        Several threads can reach this for the same candidate: the ask timeout fires, a RELAY_NO
        arrives, and the relay's link drops, all within the same second. Advancing `next_idx` only
        once no matter how many of them arrive is what keeps the walk from skipping a candidate that
        was never asked, or walking off the end of the candidate list. Returns False when the walk
        has already moved on, which is the caller's signal to do nothing at all.
        """
        with self.lock:
            if rw.next_idx >= len(rw.candidates) or rw.candidates[rw.next_idx] != cid:
                return False
            rw.failed.add(cid)
            rw.next_idx += 1
            return True

    def _ask_relay(self, rw: RelayWait):
        """Walk the candidate list: send RELAY_ASK to the next viable candidate, or fall back
        to the origin when the list is exhausted."""
        while True:
            with self.lock:
                if rw.next_idx >= len(rw.candidates):
                    break
                cid = rw.candidates[rw.next_idx]
            origin_id = rw.origin.node_id if rw.origin else None
            if cid == node_id() or cid == origin_id:
                break                              # reached self or origin, so stop walking
            if cid in rw.failed:
                if not self._advance(rw, cid):
                    return                         # another thread is already walking
                continue
            with self.lock:
                relay_ch = self.by_peer.get(cid)
            if relay_ch is None:
                if not self._advance(rw, cid):
                    return
                continue
            try:
                # `size` lets the candidate refuse immediately when the file is over its own limit.
                # Without it an over-limit file cost the full RELAY_ASK timeout at every step of
                # the walk: 30 seconds each, three candidates, a minute and a half of silence
                # before falling back to the origin.
                relay_ch.send_json(T_RELAY_ASK, {"sha256": rw.sha256,
                                                 "size": int(rw.offer_hdr.get("size", -1))})
                rw.ask_time = time.monotonic()
                rw.retried = False
                log.info("relay: asking %s to relay %s", short_id(cid), rw.sha256[:12])

                def _timeout(sha=rw.sha256, rw_ref=rw, cid_ref=cid):
                    w = self.relay_waits.get(sha)
                    if (w is rw_ref and w.ask_time > 0
                            and time.monotonic() - w.ask_time >= RELAY_ASK_TIMEOUT_S):
                        log.info("relay: %s timed out for %s", short_id(cid_ref), sha[:12])
                        if self._advance(rw_ref, cid_ref):
                            self._ask_relay(rw_ref)

                t = threading.Timer(RELAY_ASK_TIMEOUT_S + 0.5, _timeout)
                t.daemon = True
                t.start()
                return
            except Exception:
                if not self._advance(rw, cid):
                    return

        # Exhausted the candidate list, so WANT from origin.
        self.relay_waits.pop(rw.sha256, None)
        if rw.origin is None:
            return
        with self.lock:
            alive = rw.origin in self.clients
        if not alive:
            return
        try:
            self._want_from_peer(rw.offer_hdr, rw.origin)
            log.info("relay: fell back to origin for %s", rw.sha256[:12])
        except Exception as e:
            log.warning("relay: fallback to origin failed: %s", e)

    def on_relay_ask(self, msg: dict, origin: SecureChannel):
        """A peer asks us to relay a file.  Accept unless we are ourselves waiting for it."""
        sha = str(msg.get("sha256", ""))
        # Relay opt-out, second layer. The first is the `persistent: false` this node declares in
        # HELLO, which takes it out of everyone's election before they ask; this catches the peers
        # that handshook before the switch was set, or that computed their candidate list from a
        # roster entry someone else reported. `refused` and not `busy`: this will never change its
        # mind, so the asker must skip us at once rather than retrying.
        if self.cfg.relay_opt_out:
            origin.send_json(T_RELAY_NO, {"sha256": sha, "reason": "refused"})
            log.info("relay: declined %s from %s (opted out)", sha[:12], origin.device)
            return
        # Over our own limit: say so now. The asker's walk is driven by our answer, and without
        # this it had nothing to go on but the 30-second timeout, per candidate.
        size = int(msg.get("size", -1))
        if size >= 0 and size > self.cfg.max_file_any:
            origin.send_json(T_RELAY_NO, {"sha256": sha, "reason": "refused"})
            log.info("relay: declined %s from %s (%d bytes > our %d limit)", sha[:12],
                     origin.device, size, self.cfg.max_file_any)
            return
        # PC is on mains, so there is no low-battery refusal.
        # A node that is itself waiting declines with `busy`, and that is what caps the depth at one
        # hop by construction rather than by assumption: views are not always consistent; AP
        # isolation can let D see C but not B, so D can pick C as its relay while C is itself
        # waiting on B. Retryable, because when a relay dies its waiters notice at slightly
        # different moments and the node answering `busy` may be about to become the new relay.
        if sha in self.relay_waits:
            origin.send_json(T_RELAY_NO, {"sha256": sha, "reason": "busy"})
            log.info("relay: declined %s from %s (busy)", sha[:12], origin.device)
            return
        # Accept.
        origin.send_json(T_RELAY_OK, {"sha256": sha})
        with self.lock:
            self.relay_accepted.setdefault(sha, set()).add(origin)
        log.info("relay: accepted %s for %s", sha[:12], origin.device)
        # If we already have the file, offer immediately.
        if self.cache.get(sha) is not None:
            self.offer_to_waiters(sha)
            return
        # If a complete Partial exists, finalize and offer.
        pt = self.cache.partials.get(sha)
        if pt is not None and pt.complete():
            self._finalize(pt, origin)
            # _finalize calls offer_to_waiters

    def on_relay_ok(self, msg: dict, origin: SecureChannel):
        """Relay accepted our request; wait for its OFFER."""
        sha = str(msg.get("sha256", ""))
        rw = self.relay_waits.get(sha)
        if rw is None:
            return
        log.info("relay: %s accepted relay for %s", short_id(origin.node_id), sha[:12])

    def on_relay_no(self, msg: dict, origin: SecureChannel):
        """Relay declined; walk to the next candidate."""
        sha = str(msg.get("sha256", ""))
        reason = str(msg.get("reason", ""))
        rw = self.relay_waits.get(sha)
        if rw is None:
            return
        log.info("relay: %s declined %s: %s", short_id(origin.node_id), sha[:12], reason)
        if reason == "busy" and not rw.retried:
            rw.retried = True
            jitter = 0.5 + struct.unpack(">H", os.urandom(2))[0] / 65535.0

            def _retry(sha_ref=sha, rw_ref=rw, pid=origin.node_id):
                w = self.relay_waits.get(sha_ref)
                if w is not rw_ref:
                    return
                with self.lock:
                    relay_ch = self.by_peer.get(pid)
                if relay_ch is not None:
                    try:
                        # size goes on the retry too: a relay that refuses on its own file limit can
                        # only do so if it is told the size, and leaving it off here would make the
                        # busy path fall back to the 30 s timeout the size field exists to avoid.
                        relay_ch.send_json(T_RELAY_ASK, {"sha256": sha_ref,
                                                         "size": int(rw_ref.offer_hdr.get("size", -1))})
                        rw_ref.ask_time = time.monotonic()
                        log.info("relay: retrying %s for %s", short_id(pid), sha_ref[:12])
                        return
                    except Exception:
                        pass
                if self._advance(rw_ref, pid):
                    self._ask_relay(rw_ref)

            t = threading.Timer(jitter, _retry)
            t.daemon = True
            t.start()
        elif origin.node_id and self._advance(rw, origin.node_id):
            self._ask_relay(rw)

    def offer_to_waiters(self, sha: str, *, seq: int = 0, from_id: str = ""):
        """Send OFFER to every waiter that asked for this file via RELAY_ASK.

        A normal OFFER, deliberately: there is no READY frame, because OFFER already means "I have
        this, do you want it", the digest identifies it unambiguously, and reusing it puts the
        second half of the transfer on the path that already works: data connections, chunking,
        resume, dedup.

        `seq`/`from_id` are the ORIGINATOR's, passed in by whoever still has the Partial that
        carried them. A relay must not restamp them: they are two thirds of the seen-set key, so a
        waiter that also gets the file by the direct route has to compute the same key from both
        copies; and, just as importantly, the early offer this same relay already sent
        (`early_offer_to_waiters`) used the originator's pair, so restamping here would make one
        file look like two to the same waiter. Zero means "we never had the header", the case
        `on_relay_ask` hits when the file was already in the cache; `header()` then falls back to
        this node's own values, exactly as SyncService.relayOrigin does.
        """
        with self.lock:
            waiters = self.relay_accepted.pop(sha, None)
        if not waiters:
            return
        path = self.cache.get(sha)
        if path is None:
            return
        item = Item.from_path(path)
        for ch in waiters:
            with self.lock:
                alive = ch in self.clients
            if not alive:
                continue
            try:
                # forwarded=True: we are a relay handing on someone else's file, so the waiter that
                # asked for it must not pass it along again. Matches SyncService.offerToWaiters.
                ch.send_json(T_OFFER, self.header(item, forwarded=True, seq=seq,
                                                  from_id=from_id, to=[ch.node_id]))
                log.info("relay: offered %s to waiter %s", item.name, ch.device)
            except Exception as e:
                log.warning("relay: offer to %s failed: %s", ch.device, e)

    def early_offer_to_waiters(self, sha: str, pt: Partial):
        """Send OFFER to waiters on the first chunk, so they can start pulling while we are still
        receiving.  Does NOT remove from relay_accepted; that stays so serve_pull knows to use the
        streaming path.

        This is what makes the relay a relay rather than a store-and-forward hop, and it is worth
        the extra state: waiting for the whole file first doubles the end-to-end time for every
        relayed byte.
        """
        with self.lock:
            waiters = list(self.relay_accepted.get(sha) or ())
        for ch in waiters:
            with self.lock:
                alive = ch in self.clients
            if not alive:
                continue
            try:
                # forwarded=True, and seq/from taken from the Partial rather than restamped: this is
                # a relay handing on someone else's file, so the waiter must not pass it along again
                # (one hop), and the seen-set key it computes has to match the one it would get by
                # the direct route. Matches SyncService.earlyOfferToWaiters.
                ch.send_json(T_OFFER, {"seq": pt.seq, "name": pt.name,
                                       "mime": pt.mime, "size": pt.size, "sha256": pt.sha,
                                       "forwarded": True, "from": pt.origin_id or node_id(),
                                       "to": [ch.node_id]})
                log.info("relay: early offer %s to waiter %s (streaming)", pt.name, ch.device)
            except Exception as e:
                log.warning("relay: early offer to %s failed: %s", ch.device, e)

    def serve_relay_pull(self, sha: str, ranges, ch: SecureChannel, pt: Partial):
        """Forward chunks from an in-progress Partial as they arrive.
        Waits on the Partial's condition for each missing chunk, with a 60 s per-chunk timeout.

        A relay that dies mid-transfer costs the waiter nothing new: it is left holding a .part and
        its chunk map, and falls back to asking the origin for the ranges it is missing, the
        ordinary resume path, unchanged.
        """
        sent = 0
        for idx in expand(ranges):
            if idx < 0 or idx >= pt.n:
                continue
            if self.is_aborted(sha):
                ch.send_json(T_ABORT, {"sha256": sha, "reason": "aborted"})
                return
            deadline = time.monotonic() + 60
            with pt.cond:
                while idx not in pt.have:
                    if len(pt.have) == pt.n:   # inlined complete(): we already hold pt.lock
                        break
                    remain = deadline - time.monotonic()
                    if remain <= 0:
                        log.warning("relay pull: timed out waiting for chunk %d of %s", idx, sha[:12])
                        ch.send_json(T_ABORT, {"sha256": sha, "reason": "relay timeout"})
                        return
                    pt.cond.wait(min(remain, 0.5))
            if idx not in pt.have:
                ch.send_json(T_ABORT, {"sha256": sha, "reason": "chunk not available"})
                return
            data = pt.read_chunk(idx)
            ch.send(T_CHUNK, struct.pack(">I", idx) + data)
            sent += 1
        ch.send_json(T_END, {"sha256": sha})
        log.info("relay: streamed %d chunk(s) of %s to %s", sent, sha[:12], ch.device)


def parse_clip(payload: bytes, cfg: Cfg):
    msg = json.loads(payload.decode("utf-8"))
    text = msg.get("data")
    if not isinstance(text, str) or msg.get("mime", "text/plain") != "text/plain":
        return None, None
    if len(text.encode("utf-8")) > cfg.max_bytes:
        return None, None
    # The digest is always over the LF-normalised text, on both platforms and at every point that
    # computes one (Item.sha256 does the same). Line endings are a property of the clipboard the
    # text passed through, not of the clip: Windows hands back CRLF for text that arrived as LF, so
    # hashing what we hold made the two ends disagree about a clip they both had, so every reconnect
    # re-sent it, and each catch-up decided the peer was behind again.
    text = text.replace("\r\n", "\n")
    if msg.get("sha256") != hashlib.sha256(text.encode("utf-8")).hexdigest():
        log.warning("hash mismatch, dropping")
        return None, None
    return Item("text", text=text), msg


# ----------------------------------------------------------------------------- server
def send_keys(ch: SecureChannel, state: SyncState):
    """Send this device's key schedule to a peer."""
    s = state.schedule
    if not s.psk:
        return
    try:
        ch.send_json(T_KEYS, {"psk": s.psk, "since": s.since, "next": s.next})
    except Exception as e:
        log.info("could not send key schedule: %s", e)


# The shortest gap between two T_KEYS broadcasts. `agreed()`'s idempotence already keeps the
# reconciliation exchange self-limiting; this is a second line of defence, because a broadcast is
# also a full config write on every peer that reconciles, and nothing legitimate needs to announce
# twice in five seconds. The rotation clock ticks every 30 s, so it is never affected.
ANNOUNCE_MIN_INTERVAL = 5


def announce_keys(state: SyncState):
    """Send T_KEYS to every connected peer, suppressing only *repeats* inside the window.

    A plain time window would be wrong: a real change that lands a second after the previous
    broadcast -- a freshly generated successor, say -- would be swallowed and would not go out until
    the next phase change, possibly a day later. So the window only suppresses an announcement whose
    content is identical to the last one. Matches SyncService.announceKeys().
    """
    now = time.monotonic()
    with state.lock:
        # The three fields the frame actually carries, not just (psk, next): the damping is meant to
        # suppress a *repeat*, and a repeat is a frame identical to the last one. `since` moves on
        # its own when a schedule is adopted from a peer that started the key earlier than we
        # thought, and a peer that never hears the corrected value keeps a key age that is wrong in
        # the direction that delays rotation. SyncService.announceKeys compares the whole schedule
        # for the same reason.
        what = (state.schedule.psk, state.schedule.since, state.schedule.next)
        if what == state.last_announced_keys and now - state.last_announce < ANNOUNCE_MIN_INTERVAL:
            return
        state.last_announce = now
        state.last_announced_keys = what
        targets = list(state.clients)
    for c in targets:
        send_keys(c, state)


def send_peers(ch: SecureChannel, state: SyncState):
    """Send this node's direct-peer roster to one peer, excluding that peer.

    Excluded because telling B that we are connected to B is noise, and one hop only: we report the
    peers we hold a connection to, never peers we learned about from someone else. That single rule
    is what makes this loop-free without sequence numbers or a TTL, bounds the traffic at one frame
    per peer per change, and leaves no stale transitive chain to age out: every entry is one hop
    from us, and we are one hop from the receiver.
    """
    with state.lock:
        entries = []
        for c in state.clients:
            if c.node_id and c.node_id != ch.node_id:
                entries.append({
                    "id": c.node_id, "name": c.device,
                    "type": c.node_type, "persistent": c.persistent,
                    "battery": c.battery,
                })
    try:
        ch.send_json(T_PEERS, {"peers": entries})
        names = ", ".join(e.get("name", "?") for e in entries) if entries else "(empty)"
        log.info("sent roster to %s: %d peer(s) [%s]", ch.device, len(entries), names)
    except Exception as e:
        log.info("could not send peers to %s: %s", ch.device, e)


def broadcast_peers(state: SyncState):
    """Send an updated T_PEERS to every connected peer."""
    with state.lock:
        targets = list(state.clients)
    for c in targets:
        send_peers(c, state)


def on_peers(ch: SecureChannel, msg: dict, state: SyncState):
    """A peer reported its direct-peer roster. Replace our record for that sender.

    Replaced entirely and never merged: the frame is a full snapshot, so a dropped one followed by a
    received one leaves the right state rather than a half-updated view of somebody else's LAN. An
    empty list is a legitimate value: "I have no other peers right now".
    """
    if not ch.node_id:
        return
    entries = msg.get("peers", [])
    if not isinstance(entries, list):
        return
    with state.lock:
        state.indirect[ch.node_id] = entries
    names = ", ".join(e.get("name", "?") for e in entries) if entries else "(none)"
    log.info("roster from %s: %d peer(s) [%s]", ch.device, len(entries), names)


def on_keys(ch: SecureChannel, msg: dict, state: SyncState):
    """A peer reported its key schedule. Reconcile, persist if changed, and reply with ours."""
    their_psk = str(msg.get("psk", "")).strip().lower()
    their_next = str(msg.get("next", "")).strip().lower()
    if not their_psk:
        return
    # Validated at the door. An authenticated peer could otherwise put anything at all in these two
    # fields, and a non-hex `psk_next` written to config.json bricks this device permanently: every
    # inbound connection builds its candidate key list from the ring, and one unparsable entry
    # makes all of them fail. Refusing the frame is cheap; recovering from the file is not.
    if check_psk(their_psk) is not None:
        log.warning("keys: %s sent a malformed psk, ignored", ch.device)
        return
    if their_next and check_psk(their_next) is not None:
        log.warning("keys: %s sent a malformed next key, ignored", ch.device)
        return
    before = state.schedule
    now = int(time.time() * 1000)
    # Which key this connection authenticated with decides whether the peer may hand us a key we
    # have never seen. A peer on a retired ring key is one we still talk to; the ring is what lets
    # a device that was switched off for a week back in, but rotation exists on the premise that a
    # superseded key may have leaked, so it does not get to name the next one.
    matched = (ch.matched_secret or b"").hex()
    trusted = bool(matched) and (matched == before.psk or (before.next and matched == before.next))
    if not trusted and before.would_adopt(their_psk):
        log.warning("keys: %s authenticated with a retired key; not adopting its psk", ch.device)
    after = before.reconcile(their_psk, their_next, now, trusted)
    # Compared field by field rather than with `!=`: Schedule equality deliberately ignores
    # `agreed_at`, and the first agreement (0 to non-zero, which moves phase() from stranded to
    # due) is a real change that must reach the file. Everything else about `agreed_at` is noise
    # that must not be treated as a change, or the two ends would keep re-announcing to each other
    # indefinitely.
    changed = (after.psk != before.psk or after.next != before.next or after.old != before.old
               or after.since != before.since or after.retire_at != before.retire_at
               or (before.agreed_at == 0 and after.agreed_at != 0))
    if changed:
        log.info("key schedule reconciled with %s: psk=%s%s", ch.device,
                 short_key(after.psk),
                 ("" if not after.next else " next=" + short_key(after.next)))
        state.persist_schedule(after)
        announce_keys(state)


def serve(ch: SecureChannel, cfg: Cfg, state: SyncState):
    """
    The frame loop of one control connection, whichever end opened it.

    Extracted so the client role reuses it rather than growing a second copy. Nothing in here ever
    needed to know which side dialled; that is settled by the time the first frame arrives, and a
    peer is a peer from then on. The caller owns registration and cleanup.

    :return: the reason the peer gave in BYE, or None if it simply went away. A dialler must not
             redial a peer that said goodbye on purpose: that is the loop BYE exists to prevent.
    """
    while True:
        typ, payload = ch.recv()
        if typ == T_BYE:
            reason = str(json.loads(payload.decode("utf-8")).get("reason", "")) or "no reason given"
            log.info("%s said goodbye: %s", ch.device, reason)
            return reason
        if typ == T_PING:
            ping = json.loads(payload.decode("utf-8")) if payload else {}
            t1 = ping.get("t1", 0)
            t2 = int(time.time() * 1000)
            ch.send_json(T_PONG, {"t1": t1, "t2": t2, "t3": int(time.time() * 1000)})
            # Answering is the whole of this end's part. The offset is computed from the PONG to
            # *our own* PING below, where all four timestamps are known; a one-way estimate taken
            # from someone else's PING is not merely coarser, it is an estimate that includes the
            # network delay with the wrong sign. Setting it here overwrote the correct value every
            # heartbeat, so the correct one was never in effect for long.
        elif typ == T_KEYS:
            on_keys(ch, json.loads(payload.decode("utf-8")), state)
        elif typ == T_CLIP:
            item, msg = parse_clip(payload, cfg)
            if item and msg:
                state.on_remote_text(item, ch, msg)
        elif typ == T_OFFER:
            state.on_offer(json.loads(payload.decode("utf-8")), ch)
        elif typ == T_WANT:
            state.on_want(json.loads(payload.decode("utf-8")), ch)
        elif typ == T_HAVE:
            log.info("%s already has %s", ch.device, json.loads(payload.decode("utf-8")).get("sha256", "")[:12])
        elif typ == T_SKIP:
            m = json.loads(payload.decode("utf-8"))
            log.info("%s skipped %s: %s", ch.device, m.get("sha256", "")[:12], m.get("reason", ""))
        elif typ == T_ABORT:
            m = json.loads(payload.decode("utf-8"))
            state.abort(str(m.get("sha256", "")), f"{ch.device}: {m.get('reason', '')}")
        elif typ == T_RELAY_ASK:
            state.on_relay_ask(json.loads(payload.decode("utf-8")), ch)
        elif typ == T_RELAY_OK:
            state.on_relay_ok(json.loads(payload.decode("utf-8")), ch)
        elif typ == T_RELAY_NO:
            state.on_relay_no(json.loads(payload.decode("utf-8")), ch)
        elif typ == T_PEERS:
            on_peers(ch, json.loads(payload.decode("utf-8")), state)
        elif typ == T_PONG:
            t4 = int(time.time() * 1000)
            pong = json.loads(payload.decode("utf-8")) if payload else {}
            t1, t2, t3 = pong.get("t1", 0), pong.get("t2", 0), pong.get("t3", 0)
            if t1 > 0 and t2 > 0 and t3 > 0:
                ch.clock_offset = ((t2 - t1) + (t3 - t4)) // 2
        else:
            log.warning("unknown frame type %d", typ)


def client_thread(sock: socket.socket, addr, cfg: Cfg, state: SyncState):
    # A short leash until the peer has said something. READ_TIMEOUT is sized for a link that is
    # simply idle, which a caller that has not yet declared itself is not; the absolute deadline
    # inside SecureChannel bounds the handshake as a whole, and this bounds each read within it.
    sock.settimeout(HANDSHAKE_READ_TIMEOUT)
    sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    ch = None
    try:
        # Multi-key accept: try every key in the schedule's accepted() list so a peer on the
        # current key, the successor, or any key still in the ring is let in.
        #
        # Read under the lock and read *together*: rotation rewrites cfg.keys and cfg.psk while this
        # runs (see persist_schedule), and a list from before the swap paired with a key from after
        # it authenticates nobody.
        with state.lock:
            accepted, psk = cfg.keys.accepted(), cfg.psk
        if len(accepted) > 1:
            secrets = []
            for h in accepted:
                # A single malformed entry must not fail the whole accept: skipping it costs one
                # peer that happens to hold that key, while failing here would refuse every inbound
                # connection this device would ever see.
                try:
                    secrets.append(bytes.fromhex(h))
                except ValueError:
                    log.warning("keys: skipping a malformed key in the ring (%s)", short_key(h))
            if not secrets:
                raise ConnectionError("no usable key in the ring")
        else:
            secrets = psk
        ch = SecureChannel(sock, secrets, cfg.max_frame)
        hello = ch.read_hello()
        sock.settimeout(READ_TIMEOUT)          # it has spoken; the short leash was for silence
        if hello.get("role") == "data":
            data_thread(ch, hello, state)
            return
        # Refuse to talk to ourselves. Both ends hold the same PSK, so the handshake succeeds and the
        # node would enrol itself as a peer, broadcasting to itself and comparing versions against
        # its own clips. This is the authority for that; the declared own_addresses are only a fast
        # path that catches it before a socket is opened. Nothing else can replace this rule: a name
        # that resolves to this machine looks like any other name, and only the id exchange knows
        # for certain. It is easy to arrange by accident, such as the same config copied onto a second PC,
        # a LAN address typed on the machine that owns it, or a name that resolves home, and a device
        # browsing the LAN it advertises on finds itself every single time.
        if ch.node_id and ch.node_id == node_id():
            # Answer the handshake anyway, then close. The dialler is this same process, and the
            # only way it can discover that is to read back an id it recognises. Closing without
            # replying left it blocked in its own read until the timeout, where an EOF is
            # indistinguishable from a peer that crashed, so it redialled itself forever on the
            # back-off ladder and reported the LAN as failing.
            try:
                ch.send_json(T_HELLO, {"v": PROTOCOL_VERSION, "clip_ts": 0, "clip_sha": "",
                                       **declaration(cfg)})
            except Exception:
                pass                           # it is closing regardless
            raise ConnectionError("that is this device")
        ch.lan = bool(hello.get("lan", False))
        ch.limit = cfg.max_file_bytes_local if ch.lan else cfg.max_file_bytes
        # The accepter answers with its own declaration, so both ends know who they are talking to.
        # Before protocol 2 this was one-way and only the PC learned anything.
        # clip_ts/clip_sha carry the local clip version so the peer can decide whether to catch us up.
        with state.lock:
            my_clip_ts, my_clip_sha = state.clip_ts, state.clip_sha
        ch.send_json(T_HELLO, {"v": PROTOCOL_VERSION,
                               "clip_ts": my_clip_ts, "clip_sha": my_clip_sha or "",
                               **declaration(cfg)})
        refusal = state.register(ch)
        if refusal is not None:
            # Announced, not just dropped: the peer dialled us, and a silent close is the thing its
            # redial logic cannot tell from a network fault.
            log.info("client %s (%s) refused: %s", addr[0], ch.device, refusal)
            ch.bye(refusal)
            return
        log.info("client %s connected (%s %s, %s link, file limit %d MB), %d online", addr[0],
                 ch.device, short_id(ch.node_id), "lan" if ch.lan else "internet",
                 ch.limit // (1024 * 1024), state.online())
        send_keys(ch, state)
        broadcast_peers(state)
        state.catch_up(ch, int(hello.get("clip_ts", 0)), str(hello.get("clip_sha", "")))
        # The return value is not decoration on this path either. The goodbye that says "I am going
        # to sleep" very often arrives here, on a link the peer dialled, while the thread that would
        # otherwise redial it is the one in dial_thread, which never sees this frame. Dropping the
        # result on the floor meant the dialler went on waking a phone that had just settled, with
        # no way to find out it had said so.
        state.note_bye(ch, serve(ch, cfg, state))
    except Exception as e:
        log.info("client %s (%s) dropped: %s", addr[0], ch.device if ch else "?", e)
    finally:
        if ch:
            state.unregister(ch)
        try:
            sock.close()
        except OSError:
            pass


def data_thread(ch: SecureChannel, hello: dict, state: SyncState):
    """
    One data connection we accepted (HELLO role=data, sha256=<file>).  Two modes:
      push : the peer sends CHUNK frames of a file we WANTed, then END
      pull : the peer sends PULL {ranges}; we stream CHUNKs of a file we have, then END
    Several of these run in parallel for one file, and the end that opened them decides how many.

    This is the *accepting* half. The other half, opening them, is SyncState._pull_worker and
    ._push_worker, and which of the two ends does which is settled by drives_transfer().
    """
    sha = str(hello.get("sha256", ""))
    # `pt` is set by the first CHUNK and by nothing else, so it is both "this connection turned out
    # to be a pusher" and the Partial on_push_close has to be told about. Deliberately not looked up
    # (and counted) at open, before either mode is known: a waiter opening a connection to PULL a
    # file we are relaying has no bearing on any push, and counting at open would let such a
    # connection hold `pt.streams` above zero and confuse "the last push stream is out", which
    # decides the re-ask, with an unrelated pull.
    #
    # A stream that has opened but not yet sent is therefore not counted, so a fast stream finishing
    # first can still look like "all streams closed, file incomplete". Nothing is lost when it does,
    # because the re-ask below is debounced by two seconds and re-checks pt.streams, and pt.keep() only
    # flushes the map, and the window is one disk read on the pushing side. The phone's
    # FileExchange.serveData is the same shape.
    pt = None
    # What this connection turned out to be, when it turned out to be nothing. A PULL identifies a
    # puller for good: it is a relay waiter fetching from us and it has no bearing on a transfer
    # coming *in*. Everything else that closes without a single CHUNK, including a connection that
    # said nothing at all, may have been a push that died before its first byte, and that case has
    # to reach on_stream_gone or the transfer stalls until the peer's next OFFER.
    was_pull = False
    clean = False
    fh = None                                    # this connection's own handle on the .part file
    try:
        while True:
            typ, payload = ch.recv()
            if typ == T_PULL:
                m = json.loads(payload.decode("utf-8"))
                was_pull = True                  # a puller, and never a push stream that failed
                state.serve_pull(sha, m.get("ranges") or [[0, 1 << 30]], ch)
                clean = True                     # our END went out; the peer closing now is normal
            elif typ == T_CHUNK:
                if pt is None:
                    pt = state.on_push_open(sha, ch)
                if state.is_aborted(sha):
                    ch.send_json(T_ABORT, {"sha256": sha, "reason": "aborted"})
                    break
                if fh is None:
                    fh = pt.open_writer()
                # claim_first_chunk, not `len(pt.have) == 0` read under the lock and acted on after
                # it: eight streams reach their first write together and every one of them would
                # see an empty set. Same rule, same method, as the pull side (Partial docstring).
                was_first = pt.claim_first_chunk()
                state.on_chunk(pt, fh, payload)
                # On the first chunk, offer to the waiters we accepted so they can start pulling
                # while we are still receiving: the whole difference between relaying and storing
                # and forwarding is that this does not wait for the last chunk.
                if was_first and sha in state.relay_accepted:
                    state.early_offer_to_waiters(sha, pt)
            elif typ == T_END:
                clean = True
                break
            elif typ == T_ABORT:
                break
            elif typ == T_PING:
                ch.send(T_PONG)
            else:
                log.warning("data connection: unexpected frame %d", typ)
    except Exception as e:
        if not clean:
            log.info("data connection from %s (%s) dropped: %s", ch.device, sha[:12], e)
    finally:
        if fh is not None:
            try:
                fh.close()                       # before on_push_close, which may finalize the file
            except OSError:
                pass
        if pt is not None:
            state.on_push_close(pt, ch, clean)
        elif not was_pull:
            # Opened, pushed nothing, never said it was pulling: this must not pass in silence. It
            # is not counted as a stream, since that is what on_push_open is for; it only arms the same
            # debounced re-ask, so eight streams failing before their first byte are one WANT and
            # not a stall.
            state.on_stream_gone(sha, ch)
        try:
            ch.sock.close()
        except OSError:
            pass


def exclusive_bind(sock: socket.socket):
    """Ask for sole ownership of the port this socket is about to bind. Call before bind().

    **Never SO_REUSEADDR on Windows.** The name is the same as the POSIX one and the meaning is not:
    Microsoft's own page says a second socket setting SO_REUSEADDR "can forcibly bind to a port in
    use by another socket", after which "the behavior for all sockets bound to that port is
    indeterminate", and that "no special privileges are required to use this option"
    (https://learn.microsoft.com/en-us/windows/win32/winsock/using-so-reuseaddr-and-so-exclusiveaddruse).
    The hijack needs the *first* binder to have opted in, which is exactly what the old
    `setsockopt(SO_REUSEADDR, 1)` here did; on Windows Server 2003 and later the same table shows
    first=SO_REUSEADDR/wildcard, second=SO_REUSEADDR/wildcard as "Success". So this service was
    holding the door open for the thing the comment was worried about. CPython reached the same
    conclusion and cites the same page: `socket.create_server` sets SO_REUSEADDR only when
    `os.name not in ('nt', 'cygwin')` (Lib/socket.py).

    SO_EXCLUSIVEADDRUSE is not merely the Windows spelling of SO_REUSEADDR; it is stronger than
    doing nothing. With no option at all, a second bind to *our* port on a *specific* interface
    still succeeds ("Default wildcard" x "Default specific" = Success in the Server 2003+ table);
    with the exclusive flag on our wildcard bind it is refused. It needs no privilege on anything
    newer than Windows XP, and the checks run on both stacks for a dual-stack wildcard bind, which
    is what this socket is.

    The constant is registered only under `#ifdef SO_EXCLUSIVEADDRUSE`, i.e. on Windows only
    (cpython commit 42851ab, Modules/socketmodule.c); CPython's own test helper guards it with
    `hasattr(socket, 'SO_EXCLUSIVEADDRUSE')` and notes "(i.e. on Windows)"
    (Lib/test/support/socket_helper.py). Hence the getattr. The fallback is deliberately *not*
    SO_REUSEADDR-on-Windows: if the constant were ever missing here, doing nothing is safe (the
    Windows default already refuses a second wildcard bind) and SO_REUSEADDR is not.
    """
    opt = getattr(socket, "SO_EXCLUSIVEADDRUSE", None)
    if opt is not None:
        sock.setsockopt(socket.SOL_SOCKET, opt, 1)
    elif os.name != "nt" and hasattr(socket, "SO_REUSEADDR"):
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)


def server_thread(cfg: Cfg, state: SyncState):
    # Retried, and only because of the exclusive flag. The same Microsoft page warns that a socket with
    # SO_EXCLUSIVEADDRUSE set "cannot necessarily be reused immediately after socket closure":
    # if the listener accepted a connection and was then closed, the port stays taken "until the
    # original connection becomes inactive". The settings window restarts this service by killing
    # the old process and starting a new one within the same second, which is precisely that case.
    # Without the retry the exclusive flag would have traded a rare hijack for a routine failure to
    # come back; ten seconds is far more than a closing TCP connection needs and far less than a
    # user waits before deciding ClipSync is broken.
    deadline = time.monotonic() + 10
    while True:
        # A fresh socket per attempt: a socket whose bind failed is not documented to be reusable,
        # and one file descriptor per retry is not worth the question.
        srv = socket.socket(socket.AF_INET6, socket.SOCK_STREAM)
        srv.setsockopt(socket.IPPROTO_IPV6, socket.IPV6_V6ONLY, 0)
        exclusive_bind(srv)
        try:
            srv.bind(("::", cfg.port))
            srv.listen(8)
            break
        except OSError as e:
            srv.close()
            if time.monotonic() < deadline:
                log.info("port %d still busy (%s); retrying", cfg.port, e)
                time.sleep(0.5)
                continue
            # Logged loudly and fatal on purpose: under pythonw.exe there is no console to show a
            # silent failure, and a service that kept running with a clipboard listener and an mDNS
            # advertisement but no listening socket would look alive while being useless.
            log.error("cannot listen on [::]:%d (%s); another instance, or the port is taken. Exiting.",
                      cfg.port, e)
            os._exit(1)
    log.info("listening on [::]:%d", cfg.port)
    # Bounded, and **rejecting rather than queueing**. A bare thread per connection let anyone who
    # could open sockets decide how many threads this process has; a queue would be no better,
    # because it lets an unauthenticated caller put unbounded work in front of a real peer. The
    # executor caps how many run at once and the semaphore caps how many are admitted at all, because a
    # ThreadPoolExecutor's own queue is unbounded, so the cap has to be taken before submitting.
    # The ceiling matches Server.MAX_INBOUND on the Android side. A handful of peers hold one
    # control link each plus a few data connections during a transfer, so it is generous for the
    # real case and still a ceiling for the other one.
    pool = ThreadPoolExecutor(max_workers=MAX_INBOUND, thread_name_prefix="clipsync-inbound")
    slots = threading.Semaphore(MAX_INBOUND)

    def serve_one(s, a):
        try:
            client_thread(s, a, cfg, state)
        finally:
            slots.release()

    while True:
        try:
            sock, addr = srv.accept()
        except OSError as e:
            # Caught rather than left to propagate: a transient accept failure, such as running out of descriptors or
            # a connection reset between the SYN and the accept, must not kill this loop, or every
            # future inbound connection dies with it while the service looks like it is still running.
            log.warning("accept failed: %s", e)
            time.sleep(1)
            continue
        if not slots.acquire(blocking=False):
            log.warning("refusing %s: %d inbound connections already", addr[0], MAX_INBOUND)
            try:
                sock.close()
            except OSError:
                pass
            continue
        try:
            pool.submit(serve_one, sock, addr)
        except RuntimeError as e:
            slots.release()
            log.warning("refusing %s: %s", addr[0], e)
            try:
                sock.close()
            except OSError:
                pass


# ----------------------------------------------------------------------------- client role
DIAL_RETRY_MIN = 5         # seconds before re-dialling a peer that would not answer
DIAL_RETRY_MAX = 300       # ... doubling to here, per peer, so one dead name does not slow the rest
DIAL_DEFER_POLL = 5        # how often a deferred target checks whether the route that beat it is up


def on_lan(sock: socket.socket) -> bool:
    """
    Is the far end of this socket on a local network?

    Decides which of the two file-size limits applies, so it has to be decided by the dialler; an
    inbound client tells us, but there is nobody to ask on the way out.

    Private, unique-local and link-local addresses are LAN; everything else is treated as the
    internet. This is deliberately the conservative direction: a peer on the same LAN reached at a
    *global* IPv6 address is called "internet" and gets the smaller limit, which costs a large
    transfer that would have been allowed. The opposite error would push a 100 MB file over a
    metered link. The Android side answers the same question properly, by comparing against the
    prefixes of the interface in use; doing that here needs per-interface prefixes that
    local_addresses() does not currently collect.
    """
    try:
        ip = ipaddress.ip_address(sock.getpeername()[0].split("%")[0])
    except (OSError, ValueError, IndexError):
        return False
    return ip.is_private or ip.is_link_local or ip.is_loopback


def dial_thread(peer: str, cfg: Cfg, state: SyncState):
    """
    Keep one outbound connection to one listed peer.

    This gives the PC an outbound client role symmetric to Android's: without it, only whichever side
    happens to accept can ever find the other, so a PC could not reach a phone and two PCs could not
    find each other at all. One thread per peer, with its own back-off, because a peer that is
    switched off must not slow down the redial of one that is merely rebooting -- a shared back-off
    would let any single dead target hold up everything else.

    A connection that comes up is an ordinary client of `state`, indistinguishable from an inbound
    one from there on: the same SecureChannel, the same registry, the same broadcast. Only the
    handshake differs, and only in which half of the nonce exchange it performs.
    """
    # Checked once, not each round: `own` is one of the fields of cfg that never changes while the
    # process runs (only the three key fields do; see Cfg), and Apply restarts the service anyway.
    if is_self(peer, cfg.own):
        log.info("not dialling %s: that is this PC", peer)
        return
    backoff = DIAL_RETRY_MIN
    # The node the last handshake on this target reached. The link is gone by the time this loop
    # asks what its silence means, so the id has to outlive it, being the only handle on
    # state.idle_peers, which is keyed by peer and not by target precisely because a target is not
    # who you reach.
    last_peer_id = None
    while True:
        ch = None
        defer = None
        try:
            sock = socket.create_connection((peer, cfg.port), timeout=10)
            sock.settimeout(HANDSHAKE_READ_TIMEOUT)
            sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            lan = on_lan(sock)
            # Outbound: use the current PSK. The accepter does multi-key trial decryption, so
            # even if we are behind by a rotation, it will still let us in.
            #
            # Read here, inside the loop and inside the lock, and deliberately not hoisted out: this
            # thread outlives any number of rotations, and reading it once at start-up would leave
            # it offering a key the rest of the network has since retired.
            with state.lock:
                psk = cfg.psk
            ch = SecureChannel(sock, psk, cfg.max_frame, initiator=True)
            # The dialler declares first and the accepter answers.
            with state.lock:
                my_clip_ts, my_clip_sha = state.clip_ts, state.clip_sha
            ch.send_json(T_HELLO, {"v": PROTOCOL_VERSION, "lan": lan,
                                   "clip_ts": my_clip_ts, "clip_sha": my_clip_sha or "",
                                   **declaration(cfg)})
            reply = ch.read_hello()
            sock.settimeout(READ_TIMEOUT)      # it has spoken; the short leash was for silence
            if ch.node_id and ch.node_id == node_id():
                log.info("%s is this PC; not dialling it again", peer)
                return
            last_peer_id = ch.node_id
            ch.lan = lan
            ch.limit = cfg.max_file_bytes_local if lan else cfg.max_file_bytes
            refusal = state.register(ch)
            if refusal is not None:
                # This name reaches a peer another route already holds. Say so and defer below,
                # rather than connecting and handshaking every back-off interval to re-learn it.
                log.info("%s is %s [%s] by another name, closing this link", peer, ch.device,
                         short_id(ch.node_id))
                ch.bye(refusal)
                defer = ch.node_id
            else:
                log.info("dialled %s (%s %s, %s link, file limit %d MB), %d online", peer, ch.device,
                         short_id(ch.node_id), "lan" if lan else "internet",
                         ch.limit // (1024 * 1024), state.online())
                backoff = DIAL_RETRY_MIN
                send_keys(ch, state)
                broadcast_peers(state)
                # Symmetric with the inbound path: tell the peer whatever it is behind on. Without
                # this an outbound link delivers nothing until the next local copy, which reads as a
                # link that works in one direction only.
                state.catch_up(ch, int(reply.get("clip_ts", 0)), str(reply.get("clip_sha", "")))
                reason = serve(ch, cfg, state)
                state.note_bye(ch, reason)
                if reason == BYE_IDLE:
                    # It has gone to sleep on purpose, and it dials out the moment its screen comes
                    # on, so this PC is always listening; waiting costs nothing and a redial costs a
                    # wake-up on a phone that has just settled.  Not the deferral path either: there
                    # is no winning link to wait on, so that loop would find nothing holding the node
                    # and dial again at its poll interval.  The back-off itself is set below, from
                    # idle_peers rather than from here, because the goodbye may just as well have
                    # arrived on an inbound link this thread knows nothing about.
                    log.info("%s is idle; leaving it alone until it comes back", peer)
                elif reason is not None:
                    # It closed us deliberately, most often a duplicate link, because it reached
                    # us by another route as well. Redialling would rebuild exactly what it just
                    # discarded.
                    defer = ch.node_id
        except Exception as e:
            log.info("dial %s: %s", peer, e)
            # This is the one branch that is a *fault*: a timeout, a refusal, a name that will not
            # resolve, or a link that died without saying anything. A device that will not answer at
            # all is not merely asleep, so any idle claim against it is stale and has to go;
            # otherwise a peer that was switched off while idle would sit at the maximum back-off
            # for the life of this process, with the last thing it ever said standing in for the
            # real reason it is unreachable.
            if not (ch is not None and ch.superseded):
                state.not_idle(last_peer_id)
        finally:
            if ch is not None:
                state.unregister(ch)
                try:
                    ch.sock.close()
                except OSError:
                    pass
        if ch is not None and ch.superseded:
            defer = ch.node_id          # closed from the accept side as the duplicate; see above
        if last_peer_id and state.is_idle(last_peer_id):
            # It said it was going to sleep, as asked of idle_peers and not of this link, because the
            # goodbye may have arrived on an inbound one this thread never sees. A long wait, not
            # silence: suppressing the dial outright would be cheaper still and would leave a peer
            # switched off while idle reading as idle for ever, since the only things that clear the
            # claim are a handshake and a failed dial. Redialling at the slowest rate buys a state
            # that corrects itself instead of one that needs a timeout to babysit it.
            backoff = DIAL_RETRY_MAX
        if defer:
            # A deferral, not a surrender. This target reaches a peer that is already connected
            # by another route, so stop dialling it, but only while that route is up. Returning
            # outright here instead would leave a name that lost the tiebreak once never dialled
            # again, and the peer unreachable the moment the winning route died.
            log.info("%s: deferring while %s is connected another way", peer, short_id(defer))
            # Sleep first, so a winner that dies in the same instant cannot turn this into a spin.
            while True:
                time.sleep(DIAL_DEFER_POLL)
                if not state.holds(defer):
                    break
            log.info("%s: that route is gone, dialling again", peer)
            backoff = DIAL_RETRY_MIN
            continue
        time.sleep(backoff)
        backoff = min(backoff * 2, DIAL_RETRY_MAX)


def client_role_thread(cfg: Cfg, state: SyncState):
    """
    One dialler per listed peer.

    Still only the *listed* ones: this PC advertises but does not browse, so it finds a phone on the
    LAN by being found rather than by looking. That is enough for the pair to connect, since only one of
    two nodes has to do the finding, and it is the remaining asymmetry between the two platforms.
    """
    for peer in cfg.peers:
        threading.Thread(target=dial_thread, args=(peer, cfg, state), daemon=True,
                         name="clipsync-dial-%s" % peer).start()


# ----------------------------------------------------------------------------- mDNS (LAN fallback path)
MDNS_TYPE = "_clipsync._tcp.local."
MDNS_SCAN = 5              # seconds between local-address rescans (local only, cheap)


# owns_a_listed_name(), its DNS-and-TCP probe, the 60-second thread that drove it and the de-duplicated
# logger it needed are gone. The probe existed to stop two PCs on one LAN both claiming to be *the*
# hub, by guessing, from DNS, which of them a shared configuration named. Two things removed the
# need for it at once:
#
#   * a node now declares its own addresses in `own_addresses`, so the question "is this name mine?"
#     is answered from the configuration instead of resolved over the network; and
#   * with many peers there is no hub to be, so several PCs advertising on one LAN is the normal
#     case rather than the conflict the probe was arbitrating.
#
# What it cost while it existed is worth recording: a resolver timeout on the advertising path, a
# stale-record heuristic that could not be right in every case, and a warning that repeated once a
# minute forever unless suppressed.


def local_addresses():
    """All non-loopback unicast addresses of this host (IPv4 + IPv6), packed."""
    out = []
    try:
        infos = socket.getaddrinfo(socket.gethostname(), None)
    except socket.gaierror:
        return out
    for family, _, _, _, sockaddr in infos:
        try:
            ip = ipaddress.ip_address(sockaddr[0].split("%")[0])
        except ValueError:
            continue
        if ip.is_loopback or ip.is_multicast or ip.is_unspecified:
            continue
        # 169.254/16 means the adapter never got a lease. Nothing can reach us there, and it would
        # otherwise be advertised *first*: the ranking below only demotes link-local for IPv6, so
        # an APIPA address counts as a plain IPv4 one. (fe80::/10 is kept but ranked last; it is
        # at least usable by a device on the same segment.)
        if ip.version == 4 and ip.is_link_local:
            continue
        if ip.packed not in out:
            out.append(ip.packed)
    # IPv4 first (most reliable on a LAN), then global IPv6, link-local last
    def rank(p):
        ip = ipaddress.ip_address(p)
        return (0 if ip.version == 4 else (2 if ip.is_link_local else 1), p)
    out.sort(key=rank)
    return out


def mdns_thread(cfg: Cfg):
    """
    Advertise _clipsync._tcp on every network this PC is attached to.

    One cadence, on one thread: "what are my addresses?" is local and instant, so it is asked every
    MDNS_SCAN seconds and is the only thing that drives the advertisement. There is no hub to
    arbitrate against and nothing here waits on a resolver.
    """
    try:
        from zeroconf import IPVersion, ServiceInfo, Zeroconf
    except ImportError:
        log.warning("mDNS disabled: 'zeroconf' not installed (pip install zeroconf)")
        return
    host = node_name()
    # The device name, and nothing added to it. Android advertises Build.MODEL, the same string it
    # puts in HELLO's `device`, while this end advertised "ClipSync on HOSTNAME", so one LAN showed
    # two naming conventions and the browse results did not look like they came from one product.
    #
    # Unadorned is also the correct half of the disagreement to keep. The service type already says
    # what the service is, so "ClipSync on" was repeating it in the one field that exists to say
    # *which* device; and the instance name is what the peer sheet shows and what the dialler keys a
    # target by, so it wants to read like a device, not like a sentence.
    name = cfg.mdns_name or host
    # `v` is published and, on THIS service type, read by nobody -- state it plainly rather than
    # leave a field that looks like a negotiation.  This end never browses _clipsync._tcp at all
    # (there is no ServiceBrowser for it anywhere in this file); Android browses it, and its dialler
    # keys targets by instance name without consulting the TXT record.  A version disagreement is
    # therefore found at HELLO, one connection later, which is cheap: nobody is standing there
    # waiting on it.
    #
    # Kept rather than deleted, and the reason is not inertia.  A TXT key is free to send and cannot
    # be added retroactively to builds already installed, so removing it would mean any future
    # filter could only be applied to peers running a build that came after the decision -- which is
    # exactly backwards, since the peers worth filtering are the old ones.  The same key on
    # _clipsync-pair._tcp already earns its keep (clipsync_pair.TXT_VERSION), where the cost of
    # finding out at HELLO instead is a person who has typed a nine-digit code for nothing.
    props = {"v": str(PROTOCOL_VERSION)}

    zc = None
    info = None
    published = None            # the address set currently being advertised

    def stop(why=None):
        nonlocal zc, info, published
        if zc is None:
            return
        try:
            if info is not None:
                zc.unregister_service(info)
            zc.close()
        except Exception as e:
            log.warning("mDNS teardown: %s", e)
        if why:                 # a republish passes no reason: it is not an outage
            log.info("mDNS advertising stopped (%s)", why)
        zc, info, published = None, None, None

    def publish(addrs):
        """(Re)announce on `addrs`, from a Zeroconf bound to the interfaces that exist right now."""
        nonlocal zc, info, published
        again = published is not None       # read it before stop() clears it
        # A Zeroconf instance binds its sockets when it is created, so an adapter that comes up
        # later is invisible to it, so re-registering on the old instance would keep announcing on
        # the old sockets only. Recreating it is what makes a new network actually see us.
        stop()
        try:
            zc = Zeroconf(ip_version=IPVersion.All)
        except Exception as e:
            log.warning("mDNS dual-stack init failed (%s), retrying IPv4 only", e)
            zc = Zeroconf(ip_version=IPVersion.V4Only)
        info = ServiceInfo(MDNS_TYPE, f"{name}.{MDNS_TYPE}", addresses=addrs, port=cfg.port,
                           properties=props, server=f"{host}.local.")
        zc.register_service(info, allow_name_change=True)
        published = addrs
        # Two wordings, because the two events mean different things to someone reading the log:
        # the first is "the LAN path is up", a later one is "the network moved and we followed it".
        if again:
            log.info("mDNS re-advertising %s after a network change (%d addresses)", name, len(addrs))
        else:
            log.info("mDNS advertising %s on port %d (%d addresses)", name, cfg.port, len(addrs))

    seen = None
    while True:
        try:
            addrs = local_addresses()
            # Announce only once two consecutive scans agree. The scheduled task fires at logon,
            # when adapters are still coming up and the first enumeration is usually short a few
            # addresses; announcing that set would advertise a PC that is not reachable at some of
            # them, and would miss the network that appears a second later. Waiting for the list to
            # settle costs one scan when the network is already up, and exactly as long as it takes
            # when it is not.
            settled = addrs == seen
            seen = addrs

            if addrs and settled and addrs != published:
                publish(addrs)
        except Exception as e:
            log.warning("mDNS error: %s", e)
        time.sleep(MDNS_SCAN)


# ----------------------------------------------------------------------------- key rotation scheduler
ROTATION_CHECK_INTERVAL = 30   # seconds, same cadence as Android's heartbeat


def check_rotation(state: SyncState):
    """The rotation clock, called every ~30 s. Mirrors SyncService.checkRotation()."""
    if not state.rotate:
        return
    s = state.schedule
    if not s.psk:
        return
    now = int(time.time() * 1000)
    phase = s.phase(now)
    nxt = None
    if phase == "pre_retired":
        if not s.next:
            successor = random_psk_hex()
            nxt = s.with_next(successor)
            log.info("rotation: generated successor %s, announcing to peers", short_key(successor))
    elif phase == "due":
        nxt = s.promoted(now)
        log.info("rotation: promoted successor to current key %s", short_key(nxt.psk))
    elif phase == "stranded":
        nxt = s.extended()
        log.info("rotation: no peer agreed, extending deadline by %dh",
                 EXTEND_MS // 3600_000)
    if nxt is not None:
        state.persist_schedule(nxt)
        announce_keys(state)


def rotation_thread(state: SyncState):
    """A daemon thread that periodically checks whether a rotation action is due."""
    while True:
        try:
            check_rotation(state)
        except Exception as e:
            log.warning("rotation check failed: %s", e)
        time.sleep(ROTATION_CHECK_INTERVAL)


# ----------------------------------------------------------------------------- heartbeat
def heartbeat_thread(state: SyncState):
    """PING every link every HEARTBEAT_INTERVAL, and run the folder housekeeping while we are here.

    Two things were missing and they are the same thing. This end answered PINGs and never sent
    any, so the four-timestamp offset calculation in the PONG branch of `serve()` could not run at
    all; between two PCs the clock offset was permanently zero, and version comparison then simply
    believes whichever machine's clock is ahead. And a link that dies without closing (a cable
    pulled, a laptop lid shut) was only noticed when READ_TIMEOUT expired ninety seconds later; a
    ping that fails is a dead link found in thirty.

    prune rides along because it had exactly one caller, receiving a file, so a PC that only ever
    sends kept everything for ever, and `keep_hours` was a setting that did nothing on it.
    """
    while True:
        time.sleep(HEARTBEAT_INTERVAL)
        with state.lock:
            targets = list(state.clients)
        for c in targets:
            try:
                c.send_json(T_PING, {"t1": int(time.time() * 1000)})
            except Exception:
                # Close it: the thread that owns this link is blocked in recv(), and closing the
                # socket is what makes that return and the link get rebuilt. Nothing else notices.
                try:
                    c.sock.close()
                except OSError:
                    pass
        try:
            state.cache.prune(state.last_set_path)
        except Exception as e:
            log.info("prune failed: %s", e)


def say_goodbye(state: SyncState):
    """Tell every peer we are going, on the way out.

    Without it each of them waits out a full READ_TIMEOUT before deciding we are gone, and in the
    meantime keeps handing clips to a socket nobody is reading. Five lines to turn ninety seconds
    of a peer talking to a corpse into an immediate, deliberate close.
    """
    with state.lock:
        targets = list(state.clients)
    for c in targets:
        try:
            c.bye("shutting down")
        except Exception:
            pass


# ----------------------------------------------------------------------------- main
_MUTEX = []            # the single-instance handle, held for the life of the process


def single_instance() -> bool:
    """Take the named mutex that says "the ClipSync service is running here".

    Four things can start this service: the scheduled task at logon, the installer, the settings
    window's Apply, and a person double-clicking it, and two of them running at once is not
    harmless. Both register a clipboard listener, so every copy is read twice and broadcast twice;
    both build a FileCache over the same folder, so each one's prune deletes the other's `.part`
    files mid-transfer. The listening socket is no longer enough of a guard now that it is
    exclusive, because the loser would simply exit having already done the damage.

    Returns False when another instance holds it. A failure to create one at all is not treated as
    a refusal: being unable to tell is not a reason not to run.

    This is a *coordination* check, not a security boundary: Microsoft's CreateMutexW page points
    out that anyone can create the name first and keep an application from starting. The thing that
    actually guarantees one listener is the exclusive bind in server_thread(); the mutex only exists
    so that the second instance stops before it has registered a clipboard listener and started
    pruning the other one's .part files.
    """
    error_already_exists = 183                  # ERROR_ALREADY_EXISTS
    error_access_denied = 5                     # ERROR_ACCESS_DENIED
    kernel32.CreateMutexW.restype = wt.HANDLE
    kernel32.CreateMutexW.argtypes = [wt.LPVOID, wt.BOOL, wt.LPCWSTR]
    # `Global\`, with no `Local\` fallback. The previous fallback was written for a privilege check
    # that does not apply here: SeCreateGlobalPrivilege gates "the creation of a file-mapping object
    # or symbolic link object in the global namespace", and "the privilege check is limited to the
    # creation of these objects"
    # (https://learn.microsoft.com/en-us/windows/win32/termserv/kernel-object-namespaces). A mutex is
    # neither, so an ordinary interactive user can create one in Global\ and the fallback could only
    # ever fire for some *other* reason, in which case dropping to a per-session name would answer a
    # different question than the one being asked. And the question is machine-wide: the port is, the
    # config file is, files_dir is. Two sessions on one PC (fast user switching) are exactly the case
    # the per-session namespace would have hidden.
    #
    # get_last_error(), not GetLastError(): kernel32 is loaded with use_last_error=True, so ctypes
    # keeps a private copy of the thread's last-error and hands it back here
    # (https://docs.python.org/3/library/ctypes.html#ctypes.get_last_error). It has to be read
    # immediately after the call, before any other ctypes call can overwrite it; hence the next line.
    h = kernel32.CreateMutexW(None, False, "Global\\ClipSyncService")
    err = ctypes.get_last_error()
    if h:
        # "If the mutex is a named mutex and the object existed before this function call, the
        # return value is a handle to the existing object, and GetLastError returns
        # ERROR_ALREADY_EXISTS", so a non-NULL handle plus that code is the whole test.
        if err == error_already_exists:
            return False
        # Held for the life of the process and never released: bInitialOwner is False, so this
        # process never *owns* the mutex, it merely keeps the named object alive. No leak is
        # possible across runs, since "the system closes the handle automatically when the process
        # terminates. The mutex object is destroyed when its last handle has been closed"
        # (https://learn.microsoft.com/en-us/windows/win32/api/synchapi/nf-synchapi-createmutexw),
        # which covers the crash and the kill as well as the tidy exit. The list is only here to
        # keep the handle referenced so nothing collects it.
        _MUTEX.append(h)
        return True
    if err == error_access_denied:
        # The name exists and belongs to someone else's logon session: CreateMutexW gives a new
        # mutex the creator's default DACL, so this is another *account* already running ClipSync
        # on this PC rather than a missing privilege. It is still "one is already running".
        log.error("a ClipSync service is already running under another account on this PC")
        return False
    return True


def startup_report(cfg: Cfg, state: SyncState):
    """One line naming the facts that otherwise fail silently.

    Every one of these has been a bug that presented as "it just does not work, and there is
    nothing in the log": a key whose age was unknown so rotation never fired, a ring entry nothing
    could parse, a `data_out` nobody honoured, two PCs on one LAN invisible to each other because
    this end advertises but does not browse, a files_dir that grew for ever because prune had one
    caller. Printing them costs a directory scan at start-up.
    """
    s = state.schedule
    now = int(time.time() * 1000)
    age = max(0, now - s.since) // 60000
    ring = len(s.old)
    drift = audit_hello_sent({"v": PROTOCOL_VERSION, "clip_ts": 0, "clip_sha": "", "lan": False,
                              **declaration(cfg)})
    if drift:
        log.warning("hello audit: %s", drift)
    try:
        files = [e for e in os.scandir(cfg.files_dir) if e.is_file()]
        used = sum(e.stat().st_size for e in files)
    except OSError:
        files, used = [], 0
    log.info("config: key %s age %dh%02dm (rotate %s, next %s), ring %d old key(s), "
             "listening [::]:%d, data_out %s, discovery %s, files_dir %s (%.1f MB, %d files, "
             "prune every %ds, keep %sh / %s MB)",
             short_key(s.psk), age // 60, age % 60, "on" if state.rotate else "off",
             short_key(s.next) if s.next else "none", ring, cfg.port,
             declaration(cfg).get("data_out"),
             "advertise only (this end does not browse)" if cfg.discovery else "off",
             cfg.files_dir, used / (1024 * 1024), len(files), HEARTBEAT_INTERVAL,
             cfg.keep_hours or "forever", cfg.keep_max_bytes // (1024 * 1024) or "unlimited")
    if state.rotate and s.psk and now - s.since < PRE_RETIRE_MS:
        log.info("rotation: nothing due for another %dh",
                 (PRE_RETIRE_MS - (now - s.since)) // 3600_000)


def main():
    if not single_instance():
        log.error("another ClipSync service is already running; this one is exiting")
        return
    cfg = Cfg()
    state = SyncState(cfg, FileCache(cfg))
    # First line of every run. The id is per-process (see clipsync_node), so this is what ties every
    # later "client <id> connected" in this file to the session it belongs to.
    log.info("ClipSync %s, node %s (protocol %d)", node_name(), short_id(node_id()), PROTOCOL_VERSION)
    if Image is None:
        log.info("Pillow not installed: images are still exchanged as files; install 'pillow' to paste them as pictures")

    # A key with no recorded start date is 55 years old by arithmetic and zero seconds old by
    # intent, and schedule_from_raw resolves that to "now" in memory only, so every restart reset
    # the clock and a rotation window that needs 48 hours was never reached. Write it down once.
    # Here and not in clipsync_config, which must stay free of side effects.
    if cfg.psk_hex and int(read_config().get("psk_since", 0) or 0) == 0:
        state.persist_schedule(cfg.keys)      # cfg.keys.since is already now
        log.info("key age was unknown; recorded as starting now")
    startup_report(cfg, state)

    # The rotation thread runs unconditionally; the check inside is what skips when rotate is off.
    threading.Thread(target=rotation_thread, args=(state,), daemon=True, name="clipsync-rotation").start()
    threading.Thread(target=heartbeat_thread, args=(state,), daemon=True, name="clipsync-heartbeat").start()
    # BYE on the way out, for every ordinary exit. Registered before the network starts so that a
    # failure during start-up still says goodbye to whatever did connect.
    atexit.register(say_goodbye, state)
    for _sig in (signal.SIGINT, signal.SIGTERM):
        try:
            signal.signal(_sig, lambda *_a: sys.exit(0))
        except (ValueError, OSError):
            pass                              # not the main thread, or not supported here

    def start_network():
        threading.Thread(target=server_thread, args=(cfg, state), daemon=True).start()
        if cfg.discovery:
            threading.Thread(target=mdns_thread, args=(cfg,), daemon=True).start()
        if cfg.direct and cfg.peers:
            client_role_thread(cfg, state)

    # Reading the clipboard can mean writing a screenshot to disk, and handling the result means
    # hashing a file that may be 100 MB, none of which belongs on the thread that pumps the
    # window's messages. A listener window that stops answering is one Windows may drop from the
    # clipboard chain, and it would stall every other message besides. So the callback does the one
    # thing it must do quickly: note that something changed.
    changed = queue.Queue()

    def wndproc(hwnd, msg, wparam, lparam):
        if msg == WM_CLIPBOARDUPDATE:
            changed.put(None)
            return 0
        return user32.DefWindowProcW(hwnd, msg, wparam, lparam)

    def clipboard_worker():
        while True:
            changed.get()
            # Several updates in a row need one read, not one each: an app that writes text and
            # then an image raises two, and only the final state is worth sending.
            while True:
                try:
                    changed.get_nowait()
                except queue.Empty:
                    break
            try:
                item = clipboard_read(cfg, state.last_set_path)
                if item is not None:
                    state.on_local_change(item)
            except Exception as e:
                log.warning("clipboard read failed: %s", e)

    threading.Thread(target=clipboard_worker, daemon=True, name="clipsync-clipboard").start()

    proc = WNDPROC(wndproc)  # keep a reference alive
    wc = WNDCLASSW()
    wc.lpfnWndProc = proc
    wc.hInstance = kernel32.GetModuleHandleW(None)
    wc.lpszClassName = "ClipSyncListener"
    if not user32.RegisterClassW(ctypes.byref(wc)):
        raise ctypes.WinError(ctypes.get_last_error())
    hwnd = user32.CreateWindowExW(0, wc.lpszClassName, "ClipSync", 0, 0, 0, 0, 0,
                                  HWND_MESSAGE, None, wc.hInstance, None)
    if not hwnd:
        raise ctypes.WinError(ctypes.get_last_error())
    if not user32.AddClipboardFormatListener(hwnd):
        raise ctypes.WinError(ctypes.get_last_error())
    log.info("clipboard listener ready, files go to %s", cfg.files_dir)

    # Only now: a device that connects can immediately be sent a clip, and answering one means
    # writing this PC's clipboard, so the listener window has to exist first. The network side
    # needs no delay of its own; the listening socket is a wildcard bind and serves interfaces
    # that appear later anyway, and the mDNS advertiser waits for the address list to settle.
    # start_delay remains for the one case that still wants it: a PC whose DDNS record is published
    # by something else at boot, where dialling before the record exists costs a full back-off
    # ladder of failures before the first success.
    if cfg.start_delay > 0:
        log.info("network start delayed by %ds", cfg.start_delay)
        t = threading.Timer(cfg.start_delay, start_network)
        t.daemon = True     # a pending delay is not a reason the service cannot be stopped
        t.start()
    else:
        start_network()

    msg = wt.MSG()
    while user32.GetMessageW(ctypes.byref(msg), None, 0, 0) > 0:
        user32.TranslateMessage(ctypes.byref(msg))
        user32.DispatchMessageW(ctypes.byref(msg))


if __name__ == "__main__":
    # The same `sys.stderr is None` that the handler list above is built around has a second
    # consequence, and this is it: the default excepthook prints an uncaught traceback to
    # sys.stderr, and under pythonw.exe there is nowhere for it to go. Left uncaught, any `raise` in
    # main(), such as the WinError()s around the listener window or an unreadable config, would end the
    # process leaving nothing behind but a missing service. One catch-all puts them in the file
    # instead.
    #
    # Re-raised afterwards, so nothing about the exit code or a console run changes; the only
    # effect is that the traceback exists somewhere.
    try:
        main()
    except SystemExit:
        raise
    except BaseException:
        log.exception("ClipSync is stopping on an unhandled error")
        raise
