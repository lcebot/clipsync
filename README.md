# clipsync

Clipboard sync between rooted Android device(s) and Windows PC(s) over a direct connection.
No relay server, no third-party apps beyond the Xposed framework.

```
Android  io.github.lcebot.clipsync  ──  TCP client  ──→  Windows  clipsync.py  (hub)
                                         path 1: IPv6 / DDNS     (LAN + internet)
                                         path 2: mDNS             (LAN only)
```

The PC is the hub: a clip from any device is written to the Windows clipboard and relayed to every other connected device. Devices connect to the PC; path 1 uses an IPv6 DDNS record (no NAT traversal needed), path 2 falls back to mDNS on the local network.

Text, images, and files are supported. Transfers are encrypted end-to-end (ChaCha20-Poly1305 with a pre-shared key). The connection is held only while the screen is on.

## Requirements

- Android 15+ with root and latest LSPosed (libxposed API 102)
- Windows 10/11, Python 3.9+
- **Path 1:** IPv6 on both ends and a DDNS record pointing at the PC (also open inbound TCP 47521 in your router's IPv6 firewall)
- **Path 2 only:** same LAN, Wi-Fi without client isolation

## Setup

### 1. Generate a shared key

```
python -c "import os;print(os.urandom(32).hex())"
```

### 2. Windows

```powershell
cd windows
notepad clipsync.ini       # set psk (and host for path 1)
pip install -r requirements.txt
# then in an elevated PowerShell:
Set-ExecutionPolicy -Scope Process Bypass
.\install.ps1
```

`install.ps1` opens the firewall ports and registers a logon-time scheduled task.
Check `windows\clipsync.log` — `mDNS advertising …` confirms path 2 is up.

`pillow` is optional: without it, images arrive as files rather than pasting as pictures.

To uninstall: `.\uninstall.ps1` (reverts only what the installer changed; config and logs are untouched).

### 3. Android

**Build with GitHub Actions:** fork the repo, add secrets `CLIPSYNC_HOST` and `CLIPSYNC_PSK`, then download `clipsync-apk` from the workflow run.

**Or build locally:**
```
cd android
copy clipsync.properties.example clipsync.properties   # host / psk
gradle wrapper && ./gradlew assembleDebug              # JDK 17 + Android SDK
```

**Install on each device:**

1. `adb install app-debug.apk`
2. In LSPosed, enable **ClipSync** with scope **System Framework**, then reboot.
3. Open the **ClipSync** app. It starts the sync service and shows current settings — adjust host, PSK, or size limits here and tap **Apply & restart**.
4. The log at the bottom should show `connected via ddns to …` or `connected via mdns to ClipSync on …`.

## Troubleshooting

| Symptom | Check |
|---|---|
| No `hooked` line in `adb logcat -s ClipSync` | Module scope must include System Framework. |
| `connected` but nothing arrives | PC firewall / router IPv6 inbound; `clipsync.log` should show the client connecting. |
| Decrypt errors in `clipsync.log` | PSK mismatch between PC and phone. |
| `ddns path failed` on the phone | No IPv6 or no AAAA record on the current network; the app falls back to mDNS automatically. |
| `mdns: no _clipsync._tcp service found` | PC not advertising — check `clipsync.log` for the reason (wrong `host =`, missing `zeroconf`, UDP 5353 blocked, or Wi-Fi client isolation). Verify with `dns-sd -B _clipsync._tcp` on a PC or a mDNS browser app on the phone. |
| Only one of two devices receives clips | Both must show `connected` in `clipsync.log`. A device with the screen off is disconnected by design and catches up on next screen-on. |

## License

MIT