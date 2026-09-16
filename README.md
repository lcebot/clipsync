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

- Android 15+ with root and LSPosed implementing libxposed API 102 (the module's `scope.list` names system_server as `system`)
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

The workflow builds `assembleRelease`. Signing it needs a keystore of your own: add `KEYSTORE_B64`
(base64 of a PKCS12) and `KEYSTORE_PASSWORD` as secrets — see the header of
`.github/workflows/build-apk.yml`. Without them the build still succeeds, but the APK comes out as
`app-release-unsigned.apk`, which no device will install.

**Or build locally:**
```
cd android
copy clipsync.properties.example clipsync.properties   # host / psk
gradle wrapper && ./gradlew assembleRelease            # JDK 17 + Android SDK
```
with `android/keystore.properties` (gitignored) pointing `storeFile` at your keystore and
`storePassword` at its password.

**Install on each device:**

1. `adb install app-release.apk`
2. In LSPosed, enable **ClipSync** with scope **System Framework**, then reboot.
3. Open the **ClipSync** app. It starts the sync service; the **Settings** tab holds connection (mode `DDNS + mDNS` / `DDNS only` / `mDNS only`, port, PSK, DDNS name, mDNS browse time), transfer limits (text / file / file-on-LAN sizes, 1–16 parallel connections) and received-files options (folder under `Download/` or `Documents/`, housekeeping). Every field is validated live; **Apply** (the extended FAB at the bottom right, enabled only when everything is valid) saves and reloads the running service in place. `android/clipsync.properties` holds the compile-time defaults (`host`, `port`, `psk`, `mode`).
4. The connection status sits in the top-right of the app bar and pulses while connected. It shows one line — `Stopped` or the connection kind (`mDNS` / `DDNS (LAN|Internet)`) — and tapping it opens a dialog with the peer name and address, each row copyable. The **Log** tab shows `connected via …`, transfers and errors, with **Copy** / **Clear** as FABs at the bottom right.

## Troubleshooting

| Symptom | Check |
|---|---|
| No `module loaded in … (libxposed)` / `hooked (libxposed) […]` in `adb logcat -s ClipSync` | The module is not loaded into system_server: enable it in LSPosed with scope **System Framework** and reboot. In the libxposed world system_server is scoped as `system` (`META-INF/xposed/scope.list`), not as the legacy package name `android`. Note that *writing* the clipboard from the background works on every Android without any hook, so a working PC → phone direction is no evidence the module is active; reading / listener dispatch is what needs it. |
| `hooked (libxposed) […]` is there but background copies still do not arrive | The app log should show `clip (push): …` for every copy; if it never does while `setPrimaryClipInternalLocked -> push` is listed, the hooked callee may have been inlined by AOT on this ROM — report it (deoptimizing the callers is the fix). |
| Copies made while the app is in the background never reach the PC | The change listener is not dispatched to background apps on recent releases, so the module pushes every clipboard change from system_server straight into `SyncService` (`setPrimaryClipInternalLocked -> push` in the `hooked […]` logcat line; the app log then shows `clip (push): …`). If you only ever see `clip (listener): …`, the module is not active — re-enable it in LSPosed and reboot. |
| `connected` but nothing arrives | PC firewall / router IPv6 inbound; `clipsync.log` should show the client connecting. |
| Decrypt errors in `clipsync.log` | PSK mismatch between PC and phone. |
| `ddns path failed` on the phone | No IPv6 or no AAAA record on the current network; the app falls back to mDNS automatically. |
| `mdns: no _clipsync._tcp service found` | PC not advertising — check `clipsync.log` for the reason (wrong `host =`, missing `zeroconf`, UDP 5353 blocked, or Wi-Fi client isolation). Verify with `dns-sd -B _clipsync._tcp` on a PC or a mDNS browser app on the phone. |
| Nothing happens while the app is in the background; on reopening it logs `disconnected: EOFException` and reconnects | The process was frozen by the system (the PC dropped it after 90 s without a PING). The app log then shows `process was suspended for ~N s`. Three layers guard against this: the **Allow** card (battery-optimisation exemption), the root keep-alive the service runs at start (`dumpsys deviceidle whitelist`, `appops … RUN_ANY_IN_BACKGROUND/START_FOREGROUND allow`, standby bucket `exempted` — look for `root keep-alive applied` in the log), and the LSPosed module in system_server, which flags our `ProcessRecord` as `shouldNotFreeze` when it is created (the AOSP freezer honours that itself) and, as a safety net, no-ops `Process.setProcessFrozen` for our uid (`keep-alive hooks […]` / `process record exempted from freezing` in logcat). If the ROM kills rather than freezes: the service runs in its own `:sync` process (swiping the UI away never touches it), and a watchdog inside system_server reacts to the process dying (3 s later; a 60 s poll is the safety net, 15 s if the death hook could not be installed on this ROM) and restarts `SyncService` — clearing a force-stop first — as long as auto-start is on (**Stop** in the app turns it off, **Apply** turns it back on). `watchdog: SyncService was not running, started it` in logcat confirms a rescue. |
| Only one of two devices receives clips | Both must show `connected` in `clipsync.log`. A device with the screen off is disconnected by design and catches up on next screen-on. |

## License

MIT