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
  types     : 1 HELLO   2 BYE {reason}   3 PING   4 PONG   5 KEYS {psk,since,next}
              6 CLIP {ts,from,to,forwarded,mime,sha256,data}
              7 OFFER {seq,name,mime,size,sha256,from,to}  8 WANT {sha256,ranges}
              9 HAVE {sha256}  10 SKIP {sha256,reason}
             11 CHUNK u32 index||bytes  12 PULL {sha256,ranges}
             13 END {sha256}          14 ABORT {sha256,reason}
             15 PAIR_ASK  16 PAIR_KEY {psk,port,device,type}
             17 RELAY_ASK {sha256}  18 RELAY_OK {sha256}  19 RELAY_NO {sha256,reason}
             20 PEERS {peers: [{id,name,type,persistent,battery}]}

Text goes as CLIP (JSON, <= max_bytes).  Files: OFFER -> HAVE | SKIP | WANT {ranges of missing
chunks}; the bytes then move over up to N parallel data connections the phone opens (HELLO
role=data): phone -> PC pushes CHUNKs, PC -> phone answers PULL {ranges} with CHUNKs.  Chunks
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
directly — a dynamic DNS name, a static address, a LAN address, all the same to the code.
`discovery` advertises this PC on the LAN as _clipsync._tcp via mDNS (python-zeroconf), so a
device can find it with nothing configured at all, and browses for other nodes to dial.
`own_addresses` is the other side of `peers`: the names that point at THIS machine, which is how
it recognises itself and refuses to dial itself.  The network side starts `start_delay` seconds
after logon, which is 0 by default.
"""
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
import socket
import struct
import sys
import tempfile
import threading
import time

from cryptography.hazmat.primitives.ciphers.aead import ChaCha20Poly1305

try:                                   # optional: DIB <-> PNG conversion for image clips
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
                             short_key, as_bool, EXTEND_MS)
from clipsync_node import declaration, node_id, node_name, short_id   # noqa: E402
# Frame types, the key schedule and SecureChannel, so that clipsync_pair.py can speak the protocol
# without importing this module and its side effects.  A star import, unusually, because these are
# the protocol's vocabulary and the whole file refers to them unqualified — `__all__` over there is
# what keeps that honest.
from clipsync_proto import *   # noqa: E402,F403

MAP_SAVE_EVERY = 8         # persist the received-chunk bitmap every N chunks
WANT_RETRIES = 3           # how often the receiver re-asks for missing chunks in one session
LOG_MAX_BYTES = 128 * 1024  # roll clipsync.log over at this size; see RotatingLog
RELAY_ASK_TIMEOUT_S = 30   # timeout for a single step of the relay fallback walk (§7)


def priority_key(persistent: bool, node_type: str, battery: str) -> tuple:
    """Comparable priority tuple for relay election: lower is higher priority (§3)."""
    type_rank = {"pc": 2, "tablet": 1}.get(node_type, 0)
    batt_rank = {"mains": 3, "high": 2, "medium": 1}.get(battery, 0)
    return (0 if persistent else 1, -type_rank, -batt_rank)


class RelayWait:
    """State for a file we are waiting on a relay to provide (§7)."""
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
            # Never roll an empty file. Without this, a single record longer than the whole limit —
            # a long traceback — rolls before writing, then rolls again on the next record, and the
            # second roll overwrites .old with that one record and then throws it away too. The
            # limit is a ceiling for ordinary lines, not a promise to truncate one enormous one.
            return False
        # Counted in bytes, not characters: the stream is UTF-8, and a peer name or a received file
        # name can put the two a long way apart.
        return pos + len(self.format(record).encode("utf-8")) + 1 > self.max_bytes

    def _roll(self):
        # The stream is closed directly rather than through Handler.close(), which would also mark
        # the handler closed and drop it from logging's own bookkeeping — this handler is going
        # straight back into service. Windows will not rename a file that is still open.
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
    <final>.part.json (header + received-chunk list), so an interrupted transfer — connection
    lost, PC restarted, sender aborted — resumes with only the missing chunks.
    """

    @staticmethod
    def map_path(part: str) -> str:
        return part + ".json"

    def __init__(self, folder: str, hdr: dict = None, part: str = None):
        self.lock = threading.Lock()
        self.cond = threading.Condition(self.lock)   # relay serve threads wait on this (§7)
        self.have = set()
        self.finalized = False
        self.origin = None                    # control channel of the device sending it
        self.streams = 0                      # data connections currently feeding it
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
        self.hdr = {"seq": self.seq, "name": self.name, "mime": self.mime, "size": self.size, "sha256": self.sha}
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
        every 512 KiB — 200 of them for a 100 MB file — and, worse, doing it inside the lock made
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
            self.cond.notify_all()     # wake relay serve threads waiting for this chunk (§7)
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
            final = unique_path(os.path.dirname(self.part), os.path.basename(self.final))
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
        """Read chunk `idx` from the .part file (for streaming relay, §7).

        A chunk in `self.have` has been fully written — chunks occupy disjoint ranges, so reading
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
    One clipboard content.  kind 'text': text.  kind 'file': name, mime, size, sha256, path —
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
    name = os.path.basename(name.replace("\\", "/")).strip() or "clip"
    name = re.sub(r'[<>:"/\\|?*\x00-\x1f]', "_", name)
    return name[:150]


def unique_path(folder: str, name: str) -> str:
    os.makedirs(folder, exist_ok=True)
    base, ext = os.path.splitext(name)
    p = os.path.join(folder, name)
    i = 1
    while os.path.exists(p) or os.path.exists(p + ".part"):
        p = os.path.join(folder, f"{base} ({i}){ext}")
        i += 1
    return p


class FileCache:
    """
    sha256 -> local path of every file we have (received into files_dir, or copied locally).
    Lets an OFFER be answered with HAVE instead of transferring the bytes again, and serves WANTs.

    The digests are remembered between runs in %TMP%, keyed by name + size + mtime. Without that,
    every logon re-hashed the whole folder — up to keep_max_mb of reading before the clipboard
    listener was even registered. The index is a cache in the strict sense: delete it and the only
    consequence is one slow start. It lives in %TMP% and not next to the files for exactly that
    reason, and because the folder it describes is the user's, not ours to litter.
    """

    INDEX_VERSION = 1

    def __init__(self, cfg: Cfg):
        self.cfg = cfg
        self.lock = threading.Lock()
        self.by_sha = {}
        self.dir = pathlib.Path(cfg.files_dir)
        self.dir.mkdir(parents=True, exist_ok=True)
        self.partials = {}                     # sha -> Partial (interrupted transfers, resumable)
        self.index_path = self._index_path()
        self.index = self._load_index()        # name -> [sha, size, mtime_ns]
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
                    fresh[n] = [sha, st.st_size, st.st_mtime_ns]
                    self.by_sha[sha] = p
            except OSError:
                pass
        self.index = fresh                      # entries whose file is gone die here
        self._save_index()
        if self.by_sha:
            log.info("file cache: %d file(s) in %s (%d digested, %d from the index)",
                     len(self.by_sha), self.dir, hashed, len(self.by_sha) - hashed)

    def _remember(self, sha: str, path: str):
        """Record a digest we already know, so the next start does not recompute it."""
        try:
            st = os.stat(path)
        except OSError:
            return
        self.index[os.path.basename(path)] = [sha, st.st_size, st.st_mtime_ns]
        self._save_index()

    def put(self, sha: str, path: str):
        with self.lock:
            self.by_sha[sha] = path
        if os.path.dirname(os.path.abspath(path)) == str(self.dir.resolve()):
            self._remember(sha, path)

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
        # index has to be told — otherwise every re-use of a cached file costs a re-hash next start
        name = os.path.basename(path)
        if name in self.index:
            self._remember(self.index[name][0], path)

    def prune(self, keep: str):
        """
        Drop files in files_dir unused for keep_hours, then the oldest until the folder is under
        keep_max_mb.  `keep` (the file on the clipboard right now) is never removed.
        """
        cfg = self.cfg
        try:
            # scandir, not listdir + stat: the directory entry already carries size and mtime on
            # Windows, so this is one syscall for the whole folder instead of one per file.
            #
            # The digest index is deliberately NOT used as the file list here. It knows only what
            # we put there; the folder is the user's and may hold files we never saw, and the
            # keep_max_mb budget has to count those too.
            entries = []
            for e in os.scandir(cfg.files_dir):
                n, p = e.name, e.path
                if n.endswith(".part.json") or n.endswith(".json.tmp"):
                    continue                                # handled with their .part
                if e.is_file() and p != keep:
                    st = e.stat()
                    entries.append((st.st_mtime, st.st_size, p))
            entries.sort()                                  # oldest first
            now = time.time()
            total = sum(e[1] for e in entries) + (os.path.getsize(keep) if keep and os.path.exists(keep) else 0)
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
    Files: OFFER {name,mime,size,sha256} -> HAVE (cached: re-used, nothing sent) | SKIP (too big for
    that link) | WANT {ranges: missing chunks}.  The bytes then move over data connections that the
    phone opens (HELLO role=data, up to `threads` of them): for phone -> PC it pushes CHUNK frames,
    for PC -> phone it sends PULL {ranges} and we stream CHUNKs back.  Every chunk lands in a
    Partial (positional writes + persisted chunk map), so a lost connection, a restart or an ABORT
    leaves a resumable .part behind and the next OFFER/WANT only moves what is still missing.
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
        self.clip_ts = 0             # wall-clock ms of the current local clip (§6)
        self.clip_from = node_id()   # node id of whoever produced the current clip
        self.clip_sha = None         # SHA-256 hex of the current clip content
        self.latest_item = None      # the Item, for catch-up and relay
        self.seen_set = {}           # last 64 (ts:from -> ts), for dedup across any topology
        self.last_remote_hash = None # hash of content we last wrote into the local clipboard
        self.last_sent_hash = None
        self.last_set_path = None    # file we last put on the clipboard (cheap loop check)
        self.aborted = {}            # sha -> time of the last ABORT (pull loops check it)
        # Relay coordination (§7).
        self.relay_waits = {}        # sha -> RelayWait
        self.relay_accepted = {}     # sha -> set of SecureChannel (waiters we accepted)
        # Peer roster (§18): indirect[sender_id] → list of reported peer entries.
        # A full snapshot, replaced entirely on each T_PEERS; dropped when the sender disconnects.
        self.indirect: dict[str, list[dict]] = {}
        # Key rotation state, mirroring SyncService.java's cfg.keys / cfg.rotate.
        self.schedule = cfg.keys
        self.rotate = cfg.rotate

    def persist_schedule(self, sched: Schedule):
        """Persist a new key schedule to config.json and update in-memory state."""
        try:
            save_schedule(sched, self.rotate)
            self.schedule = sched
        except Exception as e:
            log.warning("rotation: could not save schedule: %s", e)

    # -- link registry (docs/p2p-plan.md §5) --
    @staticmethod
    def _duplicate_loser(old: "SecureChannel", new: "SecureChannel") -> "SecureChannel":
        """
        Which of two links to one peer has to go.

        Both ends compute this from the same three facts — whether each link is on-link, which node
        opened it, and which is older — so they reach the same verdict independently. That is the
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
            # The plan phrases the close as the larger node's job. Performing it from whichever end
            # notices first is the same close of the same socket — both ends name the same loser —
            # and it does not depend on the larger node having both links registered yet.
            loser_is_ours = node_id() > str(new.node_id or "")
            return old if old.initiator == loser_is_ours else new
        # 3. same opener — two names for one machine. Keep the older: it is the one already carrying
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
        log.info("two links to %s [%s] — closing the %s one", other.device,
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
            # Drop what this peer told us about its peers (§18).
            if ch.node_id:
                self.indirect.pop(ch.node_id, None)
        # Roster update: remaining peers need to know this one is gone (§18).
        if ch.node_id:
            broadcast_peers(self)
        # Relay cleanup (§7): if a relay we were waiting on disconnected, walk to next candidate.
        if ch.node_id:
            for rw in list(self.relay_waits.values()):
                if ch.node_id == self._current_relay_for(rw):
                    rw.failed.add(ch.node_id)
                    rw.next_idx += 1
                    self._ask_relay(rw)
            # Remove this channel from relay-accepted waiter sets.
            with self.lock:
                for waiters in self.relay_accepted.values():
                    waiters.discard(ch)

    def holds(self, node: str) -> bool:
        """Is some link to that node still up?  Dial-time duplicate suppression asks this."""
        with self.lock:
            return node in self.by_peer

    def known_peer_ids(self) -> set:
        """Direct peers ∪ indirect peers (§18).  The full `to` set for OFFER and CLIP."""
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
    def header(self, item: Item) -> dict:
        peer_ids = list(self.known_peer_ids())
        return {"seq": int(time.time() * 1000), "name": item.name, "mime": item.mime,
                "size": item.size, "sha256": item.sha256,
                "from": node_id(), "to": peer_ids}

    def announce(self, item: Item, targets, *, ts=0, from_id="", forwarded=False, extra_to=None):
        """Push text (with version fields §6), or offer a file."""
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
                    c.send_json(T_OFFER, self.header(item))
                    log.info("offered %s to %s", item.name, c.device)
            except Exception as e:
                log.warning("send to %s failed: %s", c.device, e)

    def catch_up(self, ch: SecureChannel, peer_clip_ts: int, peer_clip_sha: str):
        """Send our clip if it is newer than the peer's (§6 version-based catch-up)."""
        with self.lock:
            item = self.latest_item
            ts, from_id, sha = self.clip_ts, self.clip_from, self.clip_sha
        if item is None or item.kind != "text":
            return
        # Same content — nothing to send.
        if peer_clip_sha and peer_clip_sha == sha:
            return
        # Version comparison: (ts, from) lexicographic.  HELLO does not carry clip_from,
        # so ch.node_id is an approximation — wrong when the peer's clip was originated by a
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
            if h == self.last_remote_hash or h == self.last_sent_hash:
                return
            previous = self.latest_item
            self.last_sent_hash = h
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
            self.cache.put(h, item.path)
            self.clear_abort(h)
        log.info("local -> remote (%s)", item)
        self.announce(item, targets)

    # -- a peer offers a file --
    def on_offer(self, hdr: dict, origin: SecureChannel):
        sha = str(hdr.get("sha256", ""))
        name = safe_name(str(hdr.get("name", "clip")))
        size = int(hdr.get("size", -1))
        with self.lock:
            echo = sha in (self.last_sent_hash, self.last_remote_hash)
        if echo:
            origin.send_json(T_HAVE, {"sha256": sha})
            return
        path = self.cache.get(sha)
        if path is not None:
            origin.send_json(T_HAVE, {"sha256": sha})
            log.info("offer from %s: %s -> already cached as %s", origin.device, name, path)
            self.cache.touch(path)
            self._apply_remote(Item.from_path(path, str(hdr.get("mime") or ""), sha), origin)
            return
        if size < 0 or size > origin.limit:
            origin.send_json(T_SKIP, {"sha256": sha, "reason": f"{size} bytes > {origin.limit} ({'lan' if origin.lan else 'internet'} link)"})
            log.info("offer from %s: %s (%d bytes) -> skip, over the %s limit", origin.device, name, size,
                     "lan" if origin.lan else "internet")
            return

        # --- Relay election (§7) ---
        rw = self.relay_waits.get(sha)
        if rw is not None and origin.node_id and origin.node_id == self._current_relay_for(rw):
            # This OFFER is from the relay we asked — skip election, WANT directly.
            self.relay_waits.pop(sha, None)
            log.info("offer from relay %s: %s -> want directly", short_id(origin.node_id), name)
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
            pt = Partial(self.cfg.files_dir, hdr=hdr)
            self.cache.partials[sha] = pt
        pt.origin = origin
        pt.retries = 0
        missing = pt.missing()
        if not missing:
            self._finalize(pt, origin)
            origin.send_json(T_HAVE, {"sha256": sha})
            log.info("offer from %s: %s -> already complete on disk", origin.device, name)
            return
        origin.send_json(T_WANT, {"sha256": sha, "ranges": missing})
        log.info("offer from %s: %s (%d bytes) -> want %s", origin.device, name, size,
                 "all" if len(pt.have) == 0 else f"{sum(b - a for a, b in missing)}/{pt.n} chunks (resume)")

    # -- a peer wants a file we offered (or that we have): it will PULL it over data connections --
    def on_want(self, hdr: dict, origin: SecureChannel):
        sha = str(hdr.get("sha256", ""))
        if self.cache.get(sha) is None:
            log.warning("%s wants %s but it is not in the cache any more", origin.device, sha[:12])
            origin.send_json(T_ABORT, {"sha256": sha, "reason": "not available any more"})
            return
        self.clear_abort(sha)
        log.info("%s wants %s: waiting for its data connections", origin.device, sha[:12])

    # -- data connection: phone pushes chunks of an inbound file --
    def on_push_open(self, sha: str, ch: SecureChannel):
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
            # debounce: give the phone a moment to open its remaining streams before re-asking
            # daemon: a two-second debounce must not be a reason the process refuses to exit
            t = threading.Timer(2.0, self._reask, args=(pt,))
            t.daemon = True
            t.start()

    def _finalize(self, pt: Partial, ch: SecureChannel):
        """Verify, publish and apply a complete file. Called from a stream ending and from an offer
        of something already on disk — which is why it is not inline in on_push_close any more."""
        try:
            path = pt.finalize()
        except ValueError as e:
            log.warning("transfer from %s rejected: %s", ch.device, e)
            self.cache.partials.pop(pt.sha, None)
            return
        self.cache.partials.pop(pt.sha, None)
        self.cache.put(pt.sha, path)
        log.info("saved %s", path)
        self._apply_remote(Item.from_path(path, pt.mime, pt.sha), pt.origin or ch)
        # Relay (§7): offer to every waiter that asked us to relay this file.
        self.offer_to_waiters(pt.sha)

    def _reask(self, pt: Partial):
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
        # Streaming relay (§7): if a Partial exists and the file is not yet in the cache,
        # serve chunks as they become available rather than waiting for the complete file.
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

    # -- a peer sent text --
    def on_remote_text(self, item: Item, origin: SecureChannel, msg: dict):
        """Handle a text CLIP from a peer, with version comparison and forwarding (§6)."""
        h = item.sha256
        remote_ts = int(msg.get("ts", 0))
        remote_from = str(msg.get("from", ""))
        forwarded = bool(msg.get("forwarded", False))

        # Normalise ts into local clock domain.
        offset = getattr(origin, "clock_offset", 0)
        norm_ts = remote_ts - offset

        # Seen-set check.
        seen_key = f"{remote_ts}:{remote_from}"
        with self.lock:
            if seen_key in self.seen_set:
                return
            self.seen_set[seen_key] = remote_ts
            # Bound to 64 entries.
            while len(self.seen_set) > 64:
                self.seen_set.pop(next(iter(self.seen_set)))

            if h == self.last_sent_hash or h == self.last_remote_hash:
                return
            # Version comparison: accept only if strictly newer.
            if self.clip_ts > 0 and (norm_ts, remote_from) <= (self.clip_ts, self.clip_from):
                return
            self.last_remote_hash = h
            self.clip_ts = norm_ts
            self.clip_from = remote_from
            self.clip_sha = h
            self.latest_item = item
            others = [c for c in self.clients if c is not origin]

        log.info("remote(%s) -> local (%s%s)", origin.device, item,
                 ", forwarded" if forwarded else "")
        if not clipboard_set_text(item.text):
            log.warning("failed to set clipboard")

        # Forwarding (§6): relay to peers not in the recipient list, up to 1 hop.
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

    def _apply_remote(self, item: Item, origin: SecureChannel):
        """Apply a remote file to the local clipboard and relay as OFFER."""
        h = item.sha256
        with self.lock:
            if h == self.last_sent_hash or h == self.last_remote_hash:
                return
            self.last_remote_hash = h
            self.latest_item = item
            others = [c for c in self.clients if c is not origin]
        log.info("remote(%s) -> local (%s)", origin.device, item)
        self.last_set_path = item.path
        if not clipboard_set_file(item.path, item.mime):
            log.warning("failed to set clipboard")
        self.cache.prune(item.path)
        # relay file as OFFER to every other connected device
        self.announce(item, others)

    # -- relay coordination (§7) --
    def _build_candidate_list(self, offer_from: str, origin_ch: SecureChannel, recipients: set) -> list:
        """Compute the candidate list for relay election, sorted by priority (best first)."""
        cands = []
        # Me (PC: always persistent, type pc, battery mains).
        cands.append((node_id(), priority_key(True, "pc", "mains")))
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

    def _ask_relay(self, rw: RelayWait):
        """Walk the candidate list: send RELAY_ASK to the next viable candidate, or fall back
        to the origin when the list is exhausted."""
        while rw.next_idx < len(rw.candidates):
            cid = rw.candidates[rw.next_idx]
            origin_id = rw.origin.node_id if rw.origin else None
            if cid == node_id() or cid == origin_id:
                break                              # reached self or origin — stop walking
            if cid in rw.failed:
                rw.next_idx += 1
                continue
            with self.lock:
                relay_ch = self.by_peer.get(cid)
            if relay_ch is None:
                rw.failed.add(cid)
                rw.next_idx += 1
                continue
            try:
                relay_ch.send_json(T_RELAY_ASK, {"sha256": rw.sha256})
                rw.ask_time = time.monotonic()
                rw.retried = False
                log.info("relay: asking %s to relay %s", short_id(cid), rw.sha256[:12])

                def _timeout(sha=rw.sha256, rw_ref=rw, cid_ref=cid):
                    w = self.relay_waits.get(sha)
                    if (w is rw_ref and w.ask_time > 0
                            and time.monotonic() - w.ask_time >= RELAY_ASK_TIMEOUT_S):
                        log.info("relay: %s timed out for %s", short_id(cid_ref), sha[:12])
                        rw_ref.failed.add(cid_ref)
                        rw_ref.next_idx += 1
                        self._ask_relay(rw_ref)

                t = threading.Timer(RELAY_ASK_TIMEOUT_S + 0.5, _timeout)
                t.daemon = True
                t.start()
                return
            except Exception:
                rw.failed.add(cid)
                rw.next_idx += 1

        # Exhausted the candidate list — WANT from origin.
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
        # Relay opt-out (§8).
        if self.cfg.relay_opt_out:
            origin.send_json(T_RELAY_NO, {"sha256": sha, "reason": "refused"})
            log.info("relay: declined %s from %s (opted out)", sha[:12], origin.device)
            return
        # PC is on mains — no low-battery refusal.
        # A node that is itself waiting declines with busy (caps depth at one hop).
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
        """Relay accepted our request — wait for its OFFER."""
        sha = str(msg.get("sha256", ""))
        rw = self.relay_waits.get(sha)
        if rw is None:
            return
        log.info("relay: %s accepted relay for %s", short_id(origin.node_id), sha[:12])

    def on_relay_no(self, msg: dict, origin: SecureChannel):
        """Relay declined — walk to the next candidate."""
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
                        relay_ch.send_json(T_RELAY_ASK, {"sha256": sha_ref})
                        rw_ref.ask_time = time.monotonic()
                        log.info("relay: retrying %s for %s", short_id(pid), sha_ref[:12])
                        return
                    except Exception:
                        pass
                rw_ref.failed.add(pid)
                rw_ref.next_idx += 1
                self._ask_relay(rw_ref)

            t = threading.Timer(jitter, _retry)
            t.daemon = True
            t.start()
        else:
            if origin.node_id:
                rw.failed.add(origin.node_id)
            rw.next_idx += 1
            self._ask_relay(rw)

    def offer_to_waiters(self, sha: str):
        """Send OFFER to every waiter that asked for this file via RELAY_ASK (§7)."""
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
                hdr = {"seq": int(time.time() * 1000), "name": item.name, "mime": item.mime,
                       "size": item.size, "sha256": item.sha256,
                       "from": node_id(), "to": [ch.node_id]}
                ch.send_json(T_OFFER, hdr)
                log.info("relay: offered %s to waiter %s", item.name, ch.device)
            except Exception as e:
                log.warning("relay: offer to %s failed: %s", ch.device, e)

    def early_offer_to_waiters(self, sha: str, pt: Partial):
        """Streaming relay (§7): send OFFER to waiters on the first chunk, so they can start
        pulling while we are still receiving.  Does NOT remove from relay_accepted — that stays
        so serve_pull knows to use the streaming path."""
        with self.lock:
            waiters = list(self.relay_accepted.get(sha) or ())
        for ch in waiters:
            with self.lock:
                alive = ch in self.clients
            if not alive:
                continue
            try:
                hdr = {"seq": int(time.time() * 1000), "name": pt.name, "mime": pt.mime,
                       "size": pt.size, "sha256": pt.sha,
                       "from": node_id(), "to": [ch.node_id]}
                ch.send_json(T_OFFER, hdr)
                log.info("relay: early offer %s to waiter %s (streaming)", pt.name, ch.device)
            except Exception as e:
                log.warning("relay: early offer to %s failed: %s", ch.device, e)

    def serve_relay_pull(self, sha: str, ranges, ch: SecureChannel, pt: Partial):
        """Streaming relay (§7): forward chunks from an in-progress Partial as they arrive.
        Waits on the Partial's condition for each missing chunk, with a 60 s per-chunk timeout."""
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
    if msg.get("sha256") != hashlib.sha256(text.encode("utf-8")).hexdigest():
        log.warning("hash mismatch, dropping")
        return None, None
    # everything past this point is LF-normalised (what Windows reads back after a CRLF write)
    return Item("text", text=text.replace("\r\n", "\n")), msg


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


def announce_keys(state: SyncState):
    """Send T_KEYS to every connected peer."""
    with state.lock:
        targets = list(state.clients)
    for c in targets:
        send_keys(c, state)


def send_peers(ch: SecureChannel, state: SyncState):
    """Send this node's direct-peer roster to one peer, excluding that peer (§18)."""
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
    """Send an updated T_PEERS to every connected peer (§18)."""
    with state.lock:
        targets = list(state.clients)
    for c in targets:
        send_peers(c, state)


def on_peers(ch: SecureChannel, msg: dict, state: SyncState):
    """A peer reported its direct-peer roster. Replace our record for that sender (§18)."""
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
    before = state.schedule
    now = int(time.time() * 1000)
    after = before.reconcile(their_psk, their_next, now)
    if after != before:
        log.info("key schedule reconciled with %s: psk=%s%s", ch.device,
                 short_key(after.psk),
                 ("" if not after.next else " next=" + short_key(after.next)))
        state.persist_schedule(after)
        announce_keys(state)


def serve(ch: SecureChannel, cfg: Cfg, state: SyncState):
    """
    The frame loop of one control connection, whichever end opened it.

    Extracted so the client role reuses it rather than growing a second copy. Nothing in here ever
    needed to know which side dialled — that is settled by the time the first frame arrives, and a
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
            # One-way offset estimate: peer_clock = my_clock + offset.
            # Accurate to within RTT/2, which is enough for clip version comparison (§6).
            # The sender (Android) gets the full NTP formula from the PONG; this end cannot
            # because it never sees t4.
            if t1 > 0:
                ch.clock_offset = t1 - t2
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
    sock.settimeout(READ_TIMEOUT)
    sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    ch = None
    try:
        # Multi-key accept: try every key in the schedule's accepted() list so a peer on the
        # current key, the successor, or any key still in the ring is let in.
        accepted = cfg.keys.accepted()
        secrets = [bytes.fromhex(h) for h in accepted] if len(accepted) > 1 else cfg.psk
        ch = SecureChannel(sock, secrets, cfg.max_frame)
        hello = ch.read_hello()
        if hello.get("role") == "data":
            data_thread(ch, hello, state)
            return
        # Refuse to talk to ourselves. Both ends hold the same PSK, so the handshake succeeds and the
        # node would enrol itself as a peer — broadcasting to itself and comparing versions against
        # its own clips. This is the authority for that; the declared own_addresses are only a fast
        # path that catches it before a socket is opened.  (docs/p2p-plan.md §5)
        if ch.node_id and ch.node_id == node_id():
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
            # redial logic cannot tell from a network fault. (§5)
            log.info("client %s (%s) refused: %s", addr[0], ch.device, refusal)
            ch.bye(refusal)
            return
        log.info("client %s connected (%s %s, %s link, file limit %d MB), %d online", addr[0],
                 ch.device, short_id(ch.node_id), "lan" if ch.lan else "internet",
                 ch.limit // (1024 * 1024), state.online())
        send_keys(ch, state)
        broadcast_peers(state)
        state.catch_up(ch, int(hello.get("clip_ts", 0)), str(hello.get("clip_sha", "")))
        serve(ch, cfg, state)
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
    One data connection (phone-initiated, HELLO role=data, sha256=<file>).  Two modes:
      push : the phone sends CHUNK frames of a file we WANTed, then END
      pull : the phone sends PULL {ranges}; we stream CHUNKs of a file we have, then END
    Several of these run in parallel for one file (the phone decides how many).
    """
    sha = str(hello.get("sha256", ""))
    # a data connection for a file we are receiving counts as a push stream from the moment it
    # opens (not from its first CHUNK): otherwise a fast stream finishing before a slow one has
    # sent anything looks like "all streams closed, file incomplete" and triggers a spurious WANT
    pt = state.cache.partials.get(sha)
    if pt is not None and not pt.finalized:
        pt = state.on_push_open(sha, ch)
    else:
        pt = None
    clean = False
    fh = None                                    # this connection's own handle on the .part file
    try:
        while True:
            typ, payload = ch.recv()
            if typ == T_PULL:
                m = json.loads(payload.decode("utf-8"))
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
                with pt.lock:
                    was_first = len(pt.have) == 0
                state.on_chunk(pt, fh, payload)
                # Streaming relay (§7): on the first chunk, offer to waiters so they can
                # start pulling while we are still receiving.
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
        try:
            ch.sock.close()
        except OSError:
            pass


def server_thread(cfg: Cfg, state: SyncState):
    srv = socket.socket(socket.AF_INET6, socket.SOCK_STREAM)
    srv.setsockopt(socket.IPPROTO_IPV6, socket.IPV6_V6ONLY, 0)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("::", cfg.port))
    srv.listen(8)
    log.info("listening on [::]:%d", cfg.port)
    while True:
        try:
            sock, addr = srv.accept()
        except OSError as e:
            # A transient accept failure — out of descriptors, a connection reset between the SYN
            # and the accept — used to kill this thread, and with it every future inbound
            # connection, silently. The service went on running and answering nothing.
            log.warning("accept failed: %s", e)
            time.sleep(1)
            continue
        threading.Thread(target=client_thread, args=(sock, addr, cfg, state), daemon=True).start()


# ----------------------------------------------------------------------------- client role
DIAL_RETRY_MIN = 5         # seconds before re-dialling a peer that would not answer
DIAL_RETRY_MAX = 300       # ... doubling to here, per peer, so one dead name does not slow the rest
DIAL_DEFER_POLL = 5        # how often a deferred target checks whether the route that beat it is up


def on_lan(sock: socket.socket) -> bool:
    """
    Is the far end of this socket on a local network?

    Decides which of the two file-size limits applies, so it has to be decided by the dialler — an
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

    This is the client role the PC did not have: it only ever accepted before, which is why a PC
    could not reach a phone and two PCs could not find each other at all. One thread per peer, with
    its own back-off, because a peer that is switched off must not slow down the redial of one that
    is merely rebooting. Android arrived at the same shape from the other direction, having had one
    shared back-off that any single dead target could hold everything else behind.

    A connection that comes up is an ordinary client of `state`, indistinguishable from an inbound
    one from there on: the same SecureChannel, the same registry, the same broadcast. Only the
    handshake differs, and only in which half of the nonce exchange it performs.
    """
    # Checked once, not each round: cfg is a start-up snapshot, and Apply restarts the service.
    if is_self(peer, cfg.own):
        log.info("not dialling %s: that is this PC", peer)
        return
    backoff = DIAL_RETRY_MIN
    while True:
        ch = None
        defer = None
        try:
            sock = socket.create_connection((peer, cfg.port), timeout=10)
            sock.settimeout(READ_TIMEOUT)
            sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            lan = on_lan(sock)
            # Outbound: use the current PSK. The accepter does multi-key trial decryption, so
            # even if we are behind by a rotation, it will still let us in.
            ch = SecureChannel(sock, cfg.psk, cfg.max_frame, initiator=True)
            # The dialler declares first and the accepter answers — the same order as before, now
            # with the PC on the other end of it.
            with state.lock:
                my_clip_ts, my_clip_sha = state.clip_ts, state.clip_sha
            ch.send_json(T_HELLO, {"v": PROTOCOL_VERSION, "lan": lan,
                                   "clip_ts": my_clip_ts, "clip_sha": my_clip_sha or "",
                                   **declaration(cfg)})
            reply = ch.read_hello()
            if ch.node_id and ch.node_id == node_id():
                log.info("%s is this PC; not dialling it again", peer)
                return
            ch.lan = lan
            ch.limit = cfg.max_file_bytes_local if lan else cfg.max_file_bytes
            refusal = state.register(ch)
            if refusal is not None:
                # This name reaches a peer another route already holds. Say so and defer below,
                # rather than connecting and handshaking every back-off interval to re-learn it.
                log.info("%s is %s [%s] by another name — closing this link", peer, ch.device,
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
                if reason == BYE_IDLE:
                    # It has gone to sleep on purpose, and it dials out the moment its screen comes
                    # on — this PC is always listening, so waiting costs nothing and a redial costs a
                    # wake-up on a phone that has just settled.  Not the deferral path either: there
                    # is no winning link to wait on, so that loop would find nothing holding the node
                    # and dial again at its poll interval.
                    log.info("%s is idle; leaving it alone until it comes back", peer)
                    backoff = DIAL_RETRY_MAX
                elif reason is not None:
                    # It closed us deliberately — a duplicate link, most often, because it reached
                    # us by another route as well. Redialling would rebuild exactly what it just
                    # discarded.
                    defer = ch.node_id
        except Exception as e:
            log.info("dial %s: %s", peer, e)
        finally:
            if ch is not None:
                state.unregister(ch)
                try:
                    ch.sock.close()
                except OSError:
                    pass
        if ch is not None and ch.superseded:
            defer = ch.node_id          # closed from the accept side as the duplicate; see above
        if defer:
            # A deferral, not a surrender (§5). This target reaches a peer that is already connected
            # by another route, so stop dialling it — but only while that route is up. Returning
            # here instead, as this used to, meant a name that lost the tiebreak once was never
            # dialled again, and the peer became unreachable the moment the winning route died.
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
    LAN by being found rather than by looking. That is enough for the pair to connect — only one of
    two nodes has to do the finding — and it is the remaining asymmetry between the two platforms.
    """
    for peer in cfg.peers:
        threading.Thread(target=dial_thread, args=(peer, cfg, state), daemon=True,
                         name="clipsync-dial-%s" % peer).start()


# ----------------------------------------------------------------------------- mDNS (LAN fallback path)
MDNS_TYPE = "_clipsync._tcp.local."
MDNS_SCAN = 5              # seconds between local-address rescans (local only, cheap)


# owns_a_listed_name(), its DNS-and-TCP probe, the 60-second thread that drove it and the de-duplicated
# logger it needed are gone — see docs/p2p-plan.md §4. The probe existed to stop two PCs on one LAN
# both claiming to be *the* hub, by guessing, from DNS, which of them a shared configuration named.
# Two things removed the need for it at once:
#
#   * a node now declares its own addresses (§4a), so the question "is this name mine?" is answered
#     from the configuration instead of resolved over the network; and
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
        # an APIPA address counts as a plain IPv4 one. (fe80::/10 is kept but ranked last — it is
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

    One cadence now, on one thread: "what are my addresses?" is local and instant, so it is asked
    every MDNS_SCAN seconds and is the only thing that drives the advertisement. The second thread
    that used to run beside it asked whether another PC owned a listed address, and it is gone with
    the hub it was arbitrating (see the note above local_addresses). The advertising path no longer
    waits on a resolver.
    """
    try:
        from zeroconf import IPVersion, ServiceInfo, Zeroconf
    except ImportError:
        log.warning("mDNS disabled: 'zeroconf' not installed (pip install zeroconf)")
        return
    host = node_name()
    # The device name, and nothing added to it. Android advertises Build.MODEL — the same string it
    # puts in HELLO's `device` — while this end advertised "ClipSync on HOSTNAME", so one LAN showed
    # two naming conventions and the browse results did not look like they came from one product.
    #
    # Unadorned is also the correct half of the disagreement to keep. The service type already says
    # what the service is, so "ClipSync on" was repeating it in the one field that exists to say
    # *which* device; and the instance name is what the peer sheet shows and what the dialler keys a
    # target by, so it wants to read like a device, not like a sentence.
    name = cfg.mdns_name or host
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
        # later is invisible to it — re-registering on the old instance would keep announcing on
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
            # addresses — announcing that set would advertise a PC that is not reachable at some of
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
ROTATION_CHECK_INTERVAL = 30   # seconds — same cadence as Android's heartbeat


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


# ----------------------------------------------------------------------------- main
def main():
    cfg = Cfg()
    state = SyncState(cfg, FileCache(cfg))
    # First line of every run. The id is per-process (see clipsync_node), so this is what ties every
    # later "client <id> connected" in this file to the session it belongs to — the thing the old
    # persisted id used to provide for free, and the only thing worth keeping from it.
    log.info("ClipSync %s, node %s (protocol %d)", node_name(), short_id(node_id()), PROTOCOL_VERSION)
    if Image is None:
        log.info("Pillow not installed: images are still exchanged as files; install 'pillow' to paste them as pictures")

    # The rotation thread runs unconditionally — the check inside is what skips when rotate is off.
    threading.Thread(target=rotation_thread, args=(state,), daemon=True, name="clipsync-rotation").start()

    def start_network():
        threading.Thread(target=server_thread, args=(cfg, state), daemon=True).start()
        if cfg.discovery:
            threading.Thread(target=mdns_thread, args=(cfg,), daemon=True).start()
        if cfg.direct and cfg.peers:
            client_role_thread(cfg, state)

    # Reading the clipboard can mean writing a screenshot to disk, and handling the result means
    # hashing a file that may be 100 MB — none of which belongs on the thread that pumps the
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
    # needs no delay of its own — the listening socket is a wildcard bind and serves interfaces
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
    main()
