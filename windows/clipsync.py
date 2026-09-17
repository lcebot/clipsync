"""
ClipSync - Windows side (server).

Listens on [::]:PORT (dual-stack), accepts Android clients, and mirrors the Windows
clipboard both ways.  Run with pythonw.exe so no console window shows.

Protocol (must match the Android side, see Connection.java / SyncService.java):
  handshake : client -> 32B Nc ; server -> 32B Ns
  keys      : HKDF-SHA256(ikm=PSK, salt=Nc||Ns, info="clipsync c2s"/"clipsync s2c") -> 32B each
  nonce     : 12B = 4 zero bytes || u64 big-endian counter, per direction, starts at 0
  frame     : u32 BE len || ChaCha20-Poly1305(type(1B) || payload)
  types     : 1 HELLO {v,device,last_seq,lan[,role=data,sha256]}   2 CLIP {seq,mime,sha256,data}
              3 PING   4 PONG
              6 OFFER {seq,name,mime,size,sha256}   7 WANT {sha256,ranges}   8 HAVE {sha256}
              9 SKIP {sha256,reason}   12 ABORT {sha256,reason}
             13 CHUNK u32 index || bytes (CHUNK = 512 KiB, last one shorter)   14 PULL {sha256,ranges}
             11 END {sha256}          (5 FILE / 10 DATA are no longer used)

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

Discovery: besides the DDNS name baked into the app, the server advertises itself on the
LAN as _clipsync._tcp via mDNS (python-zeroconf) so a client can find it when the DDNS path
is unavailable (no IPv6, no internet, router firewall).  When `host` (the DDNS name) is set,
only the PC that actually owns the name advertises (see is_ddns_host).  The network side
starts `start_delay` seconds after logon to give the DDNS updater time.
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
HERE = os.path.dirname(os.path.abspath(__file__))
CONFIG_PATH = os.path.join(HERE, "clipsync.ini")
LOG_PATH = os.path.join(HERE, "clipsync.log")

(T_HELLO, T_CLIP, T_PING, T_PONG, T_FILE, T_OFFER, T_WANT, T_HAVE, T_SKIP, T_DATA, T_END,
 T_ABORT, T_CHUNK, T_PULL) = 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14
PROTOCOL_VERSION = 1
READ_TIMEOUT = 90          # seconds without any frame -> drop client
CHUNK = 512 * 1024         # chunk size: CHUNK frames carry u32 index || bytes
MAP_SAVE_EVERY = 8         # persist the received-chunk bitmap every N chunks
WANT_RETRIES = 3           # how often the receiver re-asks for missing chunks in one session

_handlers = [logging.FileHandler(LOG_PATH, encoding="utf-8")]
if sys.stdout is not None:          # absent under pythonw.exe
    _handlers.append(logging.StreamHandler(sys.stdout))
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s", handlers=_handlers)
log = logging.getLogger("clipsync")


class Cfg:
    def __init__(self):
        raw = {"port": "47521", "psk": "", "max_bytes": str(1024 * 1024),
               "max_file_bytes": str(10 * 1024 * 1024), "max_file_bytes_local": str(100 * 1024 * 1024),
               "files_dir": os.path.join(HERE, "received"), "keep_hours": "2", "keep_max_mb": "256",
               "mdns": "1", "mdns_name": "", "host": "", "start_delay": "0"}
        with open(CONFIG_PATH, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                k, v = line.split("=", 1)
                raw[k.strip()] = v.strip()
        self.psk = bytes.fromhex(raw["psk"])
        if len(self.psk) != 32:
            raise SystemExit("psk must be 64 hex chars (32 bytes)")
        self.port = int(raw["port"])
        self.max_bytes = int(raw["max_bytes"])
        self.max_file_bytes = int(raw["max_file_bytes"])
        self.max_file_bytes_local = int(raw["max_file_bytes_local"])
        self.files_dir = raw["files_dir"] or os.path.join(HERE, "received")
        if not os.path.isabs(self.files_dir):
            self.files_dir = os.path.join(HERE, self.files_dir)
        self.keep_hours = float(raw["keep_hours"])          # 0 = keep forever
        self.keep_max_bytes = int(float(raw["keep_max_mb"]) * 1024 * 1024)   # 0 = unlimited
        self.mdns = raw["mdns"].lower() in ("1", "true", "yes", "on")
        self.mdns_name = raw["mdns_name"]
        self.host = raw["host"]
        self.start_delay = int(raw["start_delay"])
        # largest frame we accept: a DATA chunk, or a CLIP whose JSON escaping doubled the text
        self.max_frame = max(CHUNK, self.max_bytes * 2) + 64 * 1024

    @property
    def max_file_any(self) -> int:
        return max(self.max_file_bytes, self.max_file_bytes_local)


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
    """One TCP connection with per-direction AEAD keys and nonce counters."""

    def __init__(self, sock: socket.socket, psk: bytes, max_frame: int):
        self.sock = sock
        self.max_frame = max_frame
        self.device = "?"
        self.lan = False
        self.limit = 0                 # file size limit for this client (set after HELLO)
        self.send_lock = threading.Lock()
        ns = os.urandom(32)
        nc = self._recv_exact(32)
        sock.sendall(ns)
        salt = nc + ns
        self.rx = ChaCha20Poly1305(hkdf_sha256(psk, salt, b"clipsync c2s"))
        self.tx = ChaCha20Poly1305(hkdf_sha256(psk, salt, b"clipsync s2c"))
        self.rx_ctr = 0
        self.tx_ctr = 0

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
        self.seq = 0                 # server-side sequence
        self.latest = None           # (Item, seq) of the latest content, for catch-up
        self.last_remote_hash = None # hash of content we last wrote into the local clipboard
        self.last_sent_hash = None
        self.last_set_path = None    # file we last put on the clipboard (cheap loop check)
        self.aborted = {}            # sha -> time of the last ABORT (pull loops check it)

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
    @staticmethod
    def header(item: Item, seq: int) -> dict:
        return {"seq": seq, "name": item.name, "mime": item.mime, "size": item.size, "sha256": item.sha256}

    def announce(self, item: Item, seq: int, targets):
        """Push text, or offer a file (to the clients whose link allows its size)."""
        for c in targets:
            try:
                if item.kind == "text":
                    c.send_json(T_CLIP, {"seq": seq, "mime": "text/plain", "sha256": item.sha256, "data": item.text})
                elif item.size > c.limit:
                    log.info("not offering %s to %s: %d bytes > %d (%s link)", item.name, c.device, item.size,
                             c.limit, "lan" if c.lan else "internet")
                else:
                    c.send_json(T_OFFER, self.header(item, seq))
                    log.info("offered %s to %s", item.name, c.device)
            except Exception as e:
                log.warning("send to %s failed: %s", c.device, e)

    def catch_up(self, ch: SecureChannel, last_seq: int):
        with self.lock:
            latest = self.latest
        if latest and latest[1] > last_seq:
            self.announce(latest[0], latest[1], [ch])

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
            previous = self.latest[0] if self.latest else None
            self.seq += 1
            seq = self.seq
            self.last_sent_hash = h
            self.latest = (item, seq)
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
        log.info("local -> remote (%s, seq %d)", item, seq)
        self.announce(item, seq, targets)

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
        elif last and not self.is_aborted(pt.sha):
            pt.keep()
            # debounce: give the phone a moment to open its remaining streams before re-asking
            threading.Timer(2.0, self._reask, args=(pt,)).start()

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
    def on_remote_text(self, item: Item, origin: SecureChannel):
        self._apply_remote(item, origin)

    def _apply_remote(self, item: Item, origin: SecureChannel):
        h = item.sha256
        with self.lock:
            if h == self.last_sent_hash or h == self.last_remote_hash:
                return
            self.last_remote_hash = h
            self.seq += 1
            seq = self.seq
            self.latest = (item, seq)
            others = [c for c in self.clients if c is not origin]
        log.info("remote(%s) -> local (%s, seq %d)", origin.device, item, seq)
        if item.kind == "text":
            if not clipboard_set_text(item.text):
                log.warning("failed to set clipboard")
        else:
            self.last_set_path = item.path
            if not clipboard_set_file(item.path, item.mime):
                log.warning("failed to set clipboard")
            self.cache.prune(item.path)
        # relay to every other connected device (phone -> PC -> tablet); files as OFFERs
        self.announce(item, seq, others)


def parse_clip(payload: bytes, cfg: Cfg):
    msg = json.loads(payload.decode("utf-8"))
    text = msg.get("data")
    if not isinstance(text, str) or msg.get("mime", "text/plain") != "text/plain":
        return None
    if len(text.encode("utf-8")) > cfg.max_bytes:
        return None
    if msg.get("sha256") != hashlib.sha256(text.encode("utf-8")).hexdigest():
        log.warning("hash mismatch, dropping")
        return None
    # everything past this point is LF-normalised (what Windows reads back after a CRLF write)
    return Item("text", text=text.replace("\r\n", "\n"))


# ----------------------------------------------------------------------------- server
def client_thread(sock: socket.socket, addr, cfg: Cfg, state: SyncState):
    sock.settimeout(READ_TIMEOUT)
    sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    ch = None
    try:
        ch = SecureChannel(sock, cfg.psk, cfg.max_frame)
        typ, payload = ch.recv()
        if typ != T_HELLO:
            raise ConnectionError("expected HELLO")
        hello = json.loads(payload.decode("utf-8"))
        if hello.get("v") != PROTOCOL_VERSION:
            raise ConnectionError("protocol version mismatch")
        ch.device = str(hello.get("device", "?"))
        if hello.get("role") == "data":
            data_thread(ch, hello, state)
            return
        ch.lan = bool(hello.get("lan", False))
        ch.limit = cfg.max_file_bytes_local if ch.lan else cfg.max_file_bytes
        with state.lock:
            state.clients.add(ch)
            n = len(state.clients)
        log.info("client %s connected (%s, %s link, file limit %d MB), %d online", addr[0], ch.device,
                 "lan" if ch.lan else "internet", ch.limit // (1024 * 1024), n)
        state.catch_up(ch, int(hello.get("last_seq", 0)))
        while True:
            typ, payload = ch.recv()
            if typ == T_PING:
                ch.send(T_PONG)
            elif typ == T_CLIP:
                item = parse_clip(payload, cfg)
                if item:
                    state.on_remote_text(item, ch)
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
            elif typ == T_PONG:
                pass
            else:
                log.warning("unknown frame type %d", typ)
    except Exception as e:
        log.info("client %s (%s) dropped: %s", addr[0], ch.device if ch else "?", e)
    finally:
        if ch:
            with state.lock:
                state.clients.discard(ch)
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
                state.on_chunk(pt, fh, payload)
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
        sock, addr = srv.accept()
        threading.Thread(target=client_thread, args=(sock, addr, cfg, state), daemon=True).start()


# ----------------------------------------------------------------------------- mDNS (LAN fallback path)
MDNS_TYPE = "_clipsync._tcp.local."
MDNS_SCAN = 5              # seconds between local-address rescans (local only, cheap)
MDNS_PROBE = 60            # seconds between DDNS-owner probes (hits the network, can be slow)
MDNS_PROBE_FIRST = 10      # and how long the first one waits for the resolver to be up


def is_ddns_host(host: str, port: int) -> bool:
    """
    Am I the machine the DDNS name points at?  Only ever used to *withdraw* an mDNS advertisement,
    so that when several PCs share one clipsync.ini the ones that do not own the name stop
    announcing themselves and devices cannot reach the wrong hub.  A single-PC setup always
    answers True here.  Without a host name every PC is the host.

    Decision:
      * name resolves to one of my IPv6 addresses            -> host
      * DNS fails (no internet / no AAAA)                     -> host   (cannot decide; mDNS is the path
                                                                         wanted when DDNS is broken)
      * name points elsewhere AND that address accepts TCP    -> NOT host (another live PC owns it)
        on our port
      * name points elsewhere but nothing answers there       -> host   (stale record: my address changed
                                                                         and the DDNS client has not caught
                                                                         up yet — exactly when clients need
                                                                         mDNS to find me)
    """
    if not host:
        return True
    try:
        remote = {ipaddress.ip_address(sa[0].split("%")[0]).packed
                  for _, _, _, _, sa in socket.getaddrinfo(host, None, socket.AF_INET6)}
    except socket.gaierror as e:
        _say(log.warning, "cannot resolve %s (%s); assuming this PC is the host" % (host, e))
        return True
    local = {p for p in local_addresses() if len(p) == 16 and not ipaddress.ip_address(p).is_link_local}
    if remote & local:
        return True
    for p in remote:
        addr = str(ipaddress.ip_address(p))
        try:
            with socket.create_connection((addr, port), timeout=2) as s:
                if s.getsockname()[0].split("%")[0] == addr:
                    return True          # connected to myself (address not listed by getaddrinfo)
                _say(log.info, "%s -> %s answers on port %d: that PC is the host" % (host, addr, port))
                return False
        except OSError:
            pass
    _say(log.warning, "%s -> %s is not me and nothing answers there: stale DDNS record, acting as host"
         % (host, ", ".join(str(ipaddress.ip_address(p)) for p in remote)))
    return True


def _say(level, msg):
    """Log `msg` only when it differs from the last one said this way.

    The host probe repeats every MDNS_PROBE seconds and its findings are usually unchanged — a
    stale DDNS record stays stale until the updater runs. Saying so once per minute forever buries
    the events that do matter; saying it when the answer changes is the whole of its value.
    """
    if getattr(_say, "last", None) != msg:
        _say.last = msg
        level(msg)


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

    Two cadences, on two threads, because the two questions cost very different amounts:

      * "what are my addresses?" is local and instant, so it is asked every MDNS_SCAN seconds and
        is what drives the advertisement;
      * "does someone else own the DDNS name?" resolves a name and may sit out a TCP timeout — tens
        of seconds on a slow or broken resolver — so it runs on its own thread every MDNS_PROBE
        seconds and can only ever *withdraw* the advertisement.

    Keeping the slow question off the publishing path is the point: a device on the LAN is looking
    for us during exactly the seconds a broken DDNS setup would otherwise make us wait.
    """
    try:
        from zeroconf import IPVersion, ServiceInfo, Zeroconf
    except ImportError:
        log.warning("mDNS disabled: 'zeroconf' not installed (pip install zeroconf)")
        return
    host = socket.gethostname().split(".")[0]
    name = cfg.mdns_name or f"ClipSync on {host}"
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

    host_now = True             # advertise by default; only a positive probe takes it away
    was_host = None

    def probe_loop():
        nonlocal host_now, was_host
        # Nothing depends on the first answer, so it waits for the resolver to come up rather than
        # racing it at logon and logging a "cannot resolve" warning on every single boot.
        time.sleep(MDNS_PROBE_FIRST)
        while True:
            try:
                probed = is_ddns_host(cfg.host, cfg.port)
                if probed != was_host:
                    log.info("this PC %s the DDNS host (%s)", "is" if probed else "is NOT",
                             cfg.host or "no host configured")
                    was_host = probed
                host_now = probed
            except Exception as e:
                log.warning("mDNS host probe: %s", e)
            time.sleep(MDNS_PROBE)

    threading.Thread(target=probe_loop, daemon=True, name="clipsync-mdns-probe").start()

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

            if not host_now:
                if zc is not None:
                    stop("another PC owns the DDNS name")
            elif addrs and settled and addrs != published:
                publish(addrs)
        except Exception as e:
            log.warning("mDNS error: %s", e)
        time.sleep(MDNS_SCAN)


# ----------------------------------------------------------------------------- main
def main():
    cfg = Cfg()
    state = SyncState(cfg, FileCache(cfg))
    if Image is None:
        log.info("Pillow not installed: images are still exchanged as files; install 'pillow' to paste them as pictures")

    def start_network():
        threading.Thread(target=server_thread, args=(cfg, state), daemon=True).start()
        if cfg.mdns:
            threading.Thread(target=mdns_thread, args=(cfg,), daemon=True).start()

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
    # start_delay remains for the one case that still wants it: several PCs sharing one
    # clipsync.ini, where starting before the DDNS client has published makes the PC that does not
    # own the name advertise for one probe interval before withdrawing.
    if cfg.start_delay > 0:
        log.info("network start delayed by %ds", cfg.start_delay)
        threading.Timer(cfg.start_delay, start_network).start()
    else:
        start_network()

    msg = wt.MSG()
    while user32.GetMessageW(ctypes.byref(msg), None, 0, 0) > 0:
        user32.TranslateMessage(ctypes.byref(msg))
        user32.DispatchMessageW(ctypes.byref(msg))


if __name__ == "__main__":
    main()
