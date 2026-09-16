"""
Loopback test client: behaves like the Android side so you can verify the server
without the phone.

  python test_client.py [host] "text to put on the PC clipboard"
  python test_client.py [host] --file path\\to\\image.png [--threads N]   (offers a file/image)

Then copy something on the PC (text, a file in Explorer, a screenshot) and watch it arrive here;
received files are written to the current directory.  Files move over N parallel data
connections (default 4 here) exactly like the phone does.
"""
import hashlib
import json
import os
import socket
import struct
import sys
import threading
import time

from cryptography.hazmat.primitives.ciphers.aead import ChaCha20Poly1305

from clipsync import (CHUNK, Cfg, T_ABORT, T_CHUNK, T_CLIP, T_END, T_HAVE, T_HELLO, T_OFFER, T_PING, T_PONG,
                      T_PULL, T_SKIP, T_WANT, expand, guess_mime, hkdf_sha256, nchunks, sha256_file)

args = sys.argv[1:]
host = "::1"
if args and not args[0].startswith("--") and len(args) > 1:
    host = args.pop(0)
file_path = None
threads = 4
if "--threads" in args:
    i = args.index("--threads")
    threads = int(args[i + 1])
    del args[i:i + 2]
if len(args) >= 2 and args[0] == "--file":
    file_path = args[1]
text = args[0] if args and file_path is None else "hello from test_client"
cfg = Cfg()


class Chan:
    """One encrypted connection (the phone side of SecureChannel)."""

    def __init__(self):
        self.s = socket.create_connection((host, cfg.port), timeout=30)
        nc = os.urandom(32)
        self.s.sendall(nc)
        ns = b""
        while len(ns) < 32:
            ns += self.s.recv(32 - len(ns))
        self.tx = ChaCha20Poly1305(hkdf_sha256(cfg.psk, nc + ns, b"clipsync c2s"))
        self.rx = ChaCha20Poly1305(hkdf_sha256(cfg.psk, nc + ns, b"clipsync s2c"))
        self.txc = self.rxc = 0
        self.lock = threading.Lock()

    @staticmethod
    def nonce(n):
        return b"\0\0\0\0" + struct.pack(">Q", n)

    def send(self, typ, payload=b""):
        with self.lock:
            ct = self.tx.encrypt(self.nonce(self.txc), bytes([typ]) + payload, None)
            self.txc += 1
            self.s.sendall(struct.pack(">I", len(ct)) + ct)

    def send_json(self, typ, obj):
        self.send(typ, json.dumps(obj).encode())

    def recv(self):
        hdr = b""
        while len(hdr) < 4:
            hdr += self.s.recv(4 - len(hdr))
        (n,) = struct.unpack(">I", hdr)
        ct = bytearray()
        while len(ct) < n:
            ct += self.s.recv(min(n - len(ct), 256 * 1024))
        pt = self.rx.decrypt(self.nonce(self.rxc), bytes(ct), None)
        self.rxc += 1
        return pt[0], pt[1:]


def stripes(indexes, n):
    return [indexes[i::n] for i in range(n)] if indexes else []


def push(path, hdr, ranges):
    """Upload the requested chunks over `threads` data connections."""
    idx = expand(ranges)
    parts = [p for p in stripes(idx, threads) if p]

    def worker(mine):
        c = Chan()
        c.send_json(T_HELLO, {"v": 1, "device": "test_client", "role": "data", "sha256": hdr["sha256"]})
        with open(path, "rb") as f:
            for i in mine:
                f.seek(i * CHUNK)
                c.send(T_CHUNK, struct.pack(">I", i) + f.read(CHUNK))
        c.send_json(T_END, {"sha256": hdr["sha256"]})
        c.s.close()

    ts = [threading.Thread(target=worker, args=(p,)) for p in parts]
    for t in ts: t.start()
    for t in ts: t.join()
    print(f"pushed {len(idx)} chunk(s) over {len(ts)} connection(s)")


def pull(hdr):
    """Download a file the PC offered over `threads` data connections, then verify it."""
    n = nchunks(hdr["size"])
    out = os.path.basename(hdr["name"])
    with open(out, "wb") as f:
        f.truncate(hdr["size"])
    parts = [p for p in stripes(list(range(n)), threads) if p]
    lock = threading.Lock()

    def worker(mine):
        c = Chan()
        c.send_json(T_HELLO, {"v": 1, "device": "test_client", "role": "data", "sha256": hdr["sha256"]})
        c.send_json(T_PULL, {"sha256": hdr["sha256"], "ranges": [[i, i + 1] for i in mine]})
        while True:
            typ, payload = c.recv()
            if typ == T_CHUNK:
                (i,) = struct.unpack(">I", payload[:4])
                with lock, open(out, "r+b") as f:
                    f.seek(i * CHUNK)
                    f.write(payload[4:])
            elif typ in (T_END, T_ABORT):
                break
        c.s.close()

    ts = [threading.Thread(target=worker, args=(p,)) for p in parts]
    for t in ts: t.start()
    for t in ts: t.join()
    ok = sha256_file(out) == hdr["sha256"]
    print(f"PC clipboard -> file {out} ({hdr['mime']}, {hdr['size']} bytes, {len(ts)} connections) {'ok' if ok else 'HASH MISMATCH'}")


ctl = Chan()
ctl.send_json(T_HELLO, {"v": 1, "device": "test_client", "last_seq": 0, "lan": True})
offered = {}
if file_path:
    hdr = {"seq": 1, "name": os.path.basename(file_path), "mime": guess_mime(file_path),
           "size": os.path.getsize(file_path), "sha256": sha256_file(file_path)}
    offered[hdr["sha256"]] = (file_path, hdr)
    ctl.send_json(T_OFFER, hdr)
    print(f"offered file {file_path} ({hdr['size']} bytes), waiting for WANT/HAVE/SKIP")
else:
    ctl.send_json(T_CLIP, {"seq": 1, "mime": "text/plain",
                           "sha256": hashlib.sha256(text.encode()).hexdigest(), "data": text})
    print("sent clip")
print("now copy something on the PC. Ctrl+C to quit.")


def pinger():
    while True:
        time.sleep(30)
        ctl.send(T_PING)


threading.Thread(target=pinger, daemon=True).start()
ctl.s.settimeout(None)
while True:
    typ, payload = ctl.recv()
    if typ == T_CLIP:
        print("PC clipboard ->", json.loads(payload)["data"])
    elif typ == T_OFFER:
        hdr = json.loads(payload)
        print(f"PC offers {hdr['name']} ({hdr['size']} bytes) -> want")
        ctl.send_json(T_WANT, {"sha256": hdr["sha256"], "ranges": [[0, nchunks(hdr["size"])]]})
        threading.Thread(target=pull, args=(hdr,), daemon=True).start()
    elif typ == T_WANT:
        m = json.loads(payload)
        path, hdr = offered[m["sha256"]]
        print(f"PC wants {sum(b - a for a, b in m['ranges'])} chunk(s)")
        threading.Thread(target=push, args=(path, hdr, m["ranges"]), daemon=True).start()
    elif typ == T_HAVE:
        print("PC already had it: nothing transferred")
    elif typ == T_SKIP:
        print("PC skipped it:", json.loads(payload).get("reason"))
    elif typ == T_ABORT:
        print("PC aborted:", json.loads(payload).get("reason"))
    elif typ == T_PONG:
        print("pong")
