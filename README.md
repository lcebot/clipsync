# clipsync

Clipboard sync between rooted Android devices (phone, tablet, …) and a Windows PC over a direct connection.
No relay server, no third-party apps beyond the Xposed framework; the Android app is a single settings + log screen.

```
Android  system_server ── Xposed hook (libxposed API 102): lets the app use the clipboard in background
Android  io.github.lcebot.clipsync ── foreground service, TCP client   (one per device, same APK)
                │   path 1: IPv6, TCP 47521  →  your-pc.example.com (DDNS)      LAN + internet
                │   path 2: mDNS _clipsync._tcp → LAN address                   LAN only, no DDNS/IPv6 needed
Windows  clipsync.py   ── listens on [::]:47521, event-driven via WM_CLIPBOARDUPDATE, star hub for all devices
```

Devices always connect to the PC. Because IPv6 has no NAT, the DDNS path works on the LAN and across the
internet. When that path is unavailable (no IPv6, no internet, router blocks inbound) the app falls back to
mDNS discovery and reaches the PC over the LAN directly. The PC is the hub: a clip from any device is written
to the Windows clipboard and relayed to every other connected device.

## How it works

| | |
|---|---|
| Android clipboard access | Android 10+ only lets the focused app or the IME touch the clipboard. A tiny hook on `ClipboardService.clipboardAccessAllowed` returns `true` for `io.github.lcebot.clipsync`, and `showAccessNotificationLocked` is skipped so no "pasted from" toast appears. Methods are matched by name (same signature on Android 10–15); the app itself targets Android 15+ (minSdk 35). |
| Transport | Device → PC TCP. Path 1: fresh AAAA lookup of the DDNS name on every reconnect. Path 2 (if path 1 fails or `host` is empty, Wi-Fi/Ethernet only): NsdManager browse for `_clipsync._tcp` (≤ 4 s, pinned to the active Wi-Fi `Network`); the PC advertises every adapter it has, so candidates on the phone's own subnet go first and are then raced Happy-Eyeballs style (250 ms stagger, first to connect wins); the last working LAN address is cached and retried before browsing again. The PC advertises via python-zeroconf — but not if the DDNS name points at *another* PC that answers on the port, so several PCs sharing one `clipsync.ini` never collide on the LAN. A stale record (PC's address changed, DDNS not yet updated — nothing answers there) does not stop advertising, since that is exactly when clients need mDNS. The PC's network side starts `start_delay` (30 s) after logon so the DDNS updater has run first. |
| Reconnect | Doubling back-off from 1 s, capped at 60 s (separate constants for Wi-Fi/Ethernet and mobile, currently equal). A default-network change (Wi-Fi ↔ mobile, new Wi-Fi) resets the back-off and reconnects immediately; with no network at all the loop sleeps until `ConnectivityManager` reports one. |
| Multi-device | Any number of Android devices connect with the same PSK. The PC keeps one sequence; a clip from device A is set on the PC and relayed to B, C, …; a device that reconnects gets the latest clip via `last_seq` catch-up. |
| Crypto | 32-byte PSK → HKDF-SHA256 (salt = client nonce ‖ server nonce) → per-direction ChaCha20-Poly1305 keys, counter nonces. Wrong PSK = decryption failure = disconnect. |
| Battery | Connection is held only while the screen is on. Screen off → disconnect; screen on or a local copy → reconnect immediately, flush, and (if still screen-off) drop again after 2 s. The PC keeps the latest clip and re-sends it on reconnect. Keep-alive PING every 30 s on Wi-Fi, 45 s on mobile (server drops silent clients at 90 s). No wake locks, no alarms, no polling: everything is event-driven (clipboard listener, screen broadcasts, network callback). mDNS browsing is skipped off-Wi-Fi. |
| Loop prevention | Each side remembers the hash of what it last received and last sent; identical content is never echoed back. |
| Scope | Text (`text/plain`, 1 MB default limit; Windows newlines are normalised to LF on the wire), plus images and files up to `max_file_bytes` (10 MB) over the internet or `max_file_bytes_local` (100 MB) on the LAN. A session counts as LAN when the PC was found via mDNS or its address lies on one of the prefixes of the network the phone is using; the phone says which in `HELLO`, and both sides apply the matching limit (an oversized `OFFER` is answered with `SKIP`). |
| Images & files | Android → PC: a clip item carrying a `content://` (or `file://`) URI — image copied in the gallery / browser / screenshot, file copied in a file manager — is resolved through `OpenableColumns` (name, size, MIME), refused if over the limit before it is read, and sent as a binary `FILE` frame. Unreadable URIs are skipped (never sent as a `content://…` string). PC → Android: the file is written to MediaStore under `Download/ClipSync` (clipboard content is transient, so it never lands in Pictures/) and its URI put on the clipboard, so image-aware apps paste the picture and any app can read the file. Windows side: a file copied in Explorer (`CF_HDROP`) or an image (`PNG` / `CF_DIB`, i.e. screenshots and "copy image") is sent; received files land in `files_dir` (default `windows/received/`) and go on the clipboard as a file (`CF_HDROP`) and — for images — also as `PNG` and `CF_DIB` (the latter needs Pillow). A copied `.png` *file* travels as a file with `image/png`; the receiver only looks at the MIME type, so it pastes as a picture just like a copied image — only the original file name is kept. |
| Transfer cache | A file is announced by hash first: `OFFER {name,mime,size,sha256}` → the peer answers `HAVE` (it still has that content in its receive folder — it re-uses it and nothing is transferred), `SKIP` (over its limit) or `WANT {ranges}` naming the 512 KB chunks it is missing. The PC serves every `WANT` from `files_dir`, Android from its MediaStore index (`files/cache.json`), so a file crosses the wire at most once per device while it is still kept. Copying the same screenshot or file again, or a reconnect catch-up, costs one small frame. |
| Parallel transfer | The chunks move over up to `threads` (8) data connections that the phone opens (`HELLO role=data`): phone → PC pushes `CHUNK` frames, PC → phone sends `PULL {ranges}` per connection and gets `CHUNK`s back. Nothing is held in memory beyond one chunk per stream; chunks are written in place into a pre-sized file. |
| Interrupt | Copying something new while a file is still moving aborts that transfer (`ABORT {sha256,reason}`, either direction, on either side); the PC does the same when its own clipboard changes or when the same device offers something newer. |
| Resume | Every receiver keeps a chunk map next to the partial file (`<name>.part` + `.part.json` on the PC, a pending MediaStore row + `files/partial/<sha>.json` on Android). A lost connection, a restart or an abort leaves it in place; the next `OFFER` of the same file is answered with `WANT` for the missing chunks only, and within a session the receiver re-asks up to 3 times by itself. Partials are subject to the same housekeeping as finished files. |
| Housekeeping | Both sides prune their receive folder after every received file: anything unused for `keep_hours` (2) is deleted, then the least recently used until the folder is under `keep_max_mb` (256). "Used" is refreshed whenever a cached file is re-used. The file currently on the clipboard is never removed; on Android only files this app stored (tracked in `cache.json`) are touched. `0` disables either rule. |

## Requirements

- Android 15+ (minSdk 35) with root and [LSPosed](https://github.com/JingMatrix/LSPosed) (a build that implements libxposed API 102)
- Windows 10/11, Python 3.9+
- For path 1: IPv6 on both ends and a DDNS record pointing at the PC. For path 2 only: same LAN (Wi-Fi without client isolation).

## Setup

### 1. Generate a PSK (shared by both sides)

```
python -c "import os;print(os.urandom(32).hex())"
```

### 2. Windows

```powershell
cd windows
notepad clipsync.ini                         # set psk (and host)
# elevated PowerShell:
Set-ExecutionPolicy -Scope Process Bypass
.\install.ps1
```

Install the Python packages yourself first: `pip install -r requirements.txt` (`cryptography`, `zeroconf`;
`pillow` is optional — without it images still arrive as files, they just don't paste as pictures).
`install.ps1` then opens inbound TCP 47521 and UDP 5353 (mDNS) in the Windows firewall and registers a
logon-time scheduled task running `pythonw.exe`. It writes what it changed to `install-state.json`;
`uninstall.ps1` reverts exactly that (task and the rules it created — packages, config and log are untouched).
IPv6 temporary (privacy) addresses are left enabled on purpose; the DDNS record may point at a rotated
address for a while, which is what the mDNS path and the server's stale-record check are for.
Log: `windows\clipsync.log` (`this PC is the DDNS host` + `mDNS advertising …` confirm path 2 is up).
For path 1 also allow inbound TCP 47521 to the PC in your router's IPv6 firewall.
`clipsync.ini`: `host =` the DDNS name (gates mDNS advertising to the real host), `start_delay = 30`,
`mdns = 0` to turn advertising off.

Test without the phone: `python clipsync.py` in one window, `python test_client.py "hello"` or
`python test_client.py --file photo.png` in another. `clipsync.ini`: `max_file_bytes`, `max_file_bytes_local`, `files_dir`, `keep_hours`, `keep_max_mb`.

### 3. Android

Build with GitHub Actions: fork/push, add secrets `CLIPSYNC_HOST` and `CLIPSYNC_PSK`
(`CLIPSYNC_PORT` optional; leave `CLIPSYNC_HOST` empty for an mDNS-only build), download `clipsync-apk`
from the workflow run. Or locally:

```
cd android
copy clipsync.properties.example clipsync.properties   # host / port / psk / mdns
gradle wrapper && ./gradlew assembleDebug              # JDK 17 + Android SDK
```

Then, on **each** device (phone, tablet, … — the same APK, same PSK):

1. `adb install app-debug.apk`
2. In LSPosed enable **ClipSync** with scope **System Framework**, reboot.
3. Open the **ClipSync** app once. It starts the service (which also clears the "stopped" state so
   `BOOT_COMPLETED` can restart it later) and shows the compile-time defaults; change host / port / PSK /
   mDNS / size limits / housekeeping there if needed and press **Apply & restart**. Values are stored in
   `/data/data/io.github.lcebot.clipsync/files/clipsync.conf` (the same file can still be written by hand:
   `host`, `port`, `psk`, `mdns`, `mdns_timeout_ms`, `threads`, `max_bytes`, `max_file_bytes`, `max_file_bytes_local`, `keep_hours`, `keep_max_mb`).
   Headless alternative: `adb shell su -c "am start-foreground-service -n io.github.lcebot.clipsync/.SyncService"` or
   `android/service.d/clipsync.sh` in `/data/adb/service.d/`.
4. The log pane at the bottom of the app should show `connected via ddns to <host> [addr]` or
   `connected via mdns to ClipSync on <PC> [addr]`. The app process does not log to logcat; the log is kept in
   memory and in `files/clipsync.log` (Copy / Clear buttons). Only the Xposed hook, which lives in
   system_server, still logs through the framework: `adb logcat -s ClipSync` → `hooked [clipboardAccessAllowed, showAccessNotificationLocked]`.

## Protocol

```
handshake : client → 32B Nc ; server → 32B Ns
keys      : HKDF-SHA256(ikm=PSK, salt=Nc‖Ns, info="clipsync c2s" | "clipsync s2c") → 32B each
nonce     : 12B = 4×0x00 ‖ u64 BE counter, per direction, starting at 0
frame     : u32 BE len ‖ ChaCha20-Poly1305(type(1B) ‖ payload)
types     : 1 HELLO {v,device,last_seq,lan[,role=data,sha256]}   2 CLIP {seq,mime,sha256,data}   3 PING   4 PONG
            6 OFFER {seq,name,mime,size,sha256}   7 WANT {sha256,ranges}   8 HAVE {sha256}   9 SKIP {sha256,reason}
            12 ABORT {sha256,reason}   13 CHUNK u32 index ‖ bytes (512 KB)   14 PULL {sha256,ranges}   11 END {sha256}
files     : holder → OFFER ; receiver → HAVE | SKIP | WANT {missing ranges}
            then over N data connections (HELLO role=data): phone→PC  CHUNK…, END
                                                             PC→phone  PULL {ranges} → CHUNK…, END
discovery : DNS-SD _clipsync._tcp.local., SRV port, A/AAAA, TXT v=1
```

## Troubleshooting

| Symptom | Check |
|---|---|
| No `hooked` line in logcat | Module scope must include System Framework; or the ROM renamed the methods (check `services.jar`). |
| `hooked` but clipboard reads fail | Method inlined: call `deoptimize()` on `getPrimaryClip` / `setPrimaryClip` / `addPrimaryClipChangedListener` before hooking. |
| `connected` but nothing arrives | PC firewall / router IPv6 inbound; `clipsync.log` should show `client … connected`. |
| Decrypt errors in `clipsync.log` | PSK mismatch. |
| `UnknownHostException` / `ddns path failed` on the phone | No IPv6 on the current network or no AAAA record; the app then tries mDNS. |
| `mdns: no _clipsync._tcp service found` | PC not advertising: `clipsync.log` says `is NOT the DDNS host` (another PC answers at the AAAA address — clear `host =` if that is wrong), `zeroconf` missing, UDP 5353 blocked by Windows firewall, or Wi-Fi client isolation / different VLAN. Verify from a PC with `dns-sd -B _clipsync._tcp` (Bonjour) or from the phone with a mDNS browser app. |
| Phone on mobile data never uses mDNS | By design: mDNS is link-local, so path 2 runs only on Wi-Fi/Ethernet (`retry in Ns (mobile)` in the app log). |
| Only one of two devices receives clips | Both must show `connected` in `clipsync.log` (`N online`); a device with the screen off is disconnected by design and catches up on next screen-on. |

## Layout

```
android/   Xposed module + sync service + settings/log activity (Gradle, Java 17, Material 3 Expressive, adaptive + themed launcher icon, libxposed api 102.0.0)
           SyncService  connection loop      Connection  wire protocol      Mdns  NsdManager browse
           Transfer     parallel chunk workers   Files  URI stat / ChunkSource / Partial   FileCache  index + housekeeping
           MainActivity settings + log       Logger      ring buffer + files/clipsync.log
windows/   clipsync.py server (+ zeroconf advertiser), test_client.py, install.ps1
.github/   CI: builds the APK, attaches it to releases on v* tags
```

## License

MIT
