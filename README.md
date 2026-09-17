# ClipSync

**Share one clipboard across your rooted Android devices and your Windows PC.**
Copy on the phone, paste on the PC. Copy on the PC, paste on the tablet. Text, images and files.

No account, no cloud, no relay server — the devices talk to your PC directly, and everything on the
wire is encrypted with a key only you have.

```
 Android phone ─┐
 Android tablet ─┼──→  your Windows PC  ──→  back out to every other device
 Android …     ─┘         (the hub)
```

---

## What it does

- **Two-way text sync.** Anything you copy is on the other machine a moment later.
- **Images and files.** A screenshot copied on the phone pastes as a picture on the PC. Files
  arrive in a folder and are put on the clipboard ready to paste into Explorer.
- **More than two devices.** Every device connects to the PC, and the PC relays to all the others.
- **Works away from home.** Over IPv6 with a DDNS name, the phone reaches your PC from mobile data
  as easily as from the sofa. On the LAN it can also find the PC by mDNS, with no DNS setup at all.
- **Survives being backgrounded.** The sync runs in its own process, is exempted from the system
  freezer, and is restarted if the system kills it.
- **Encrypted end to end.** ChaCha20-Poly1305 with a 32-byte pre-shared key. Nothing is readable in
  transit, and nothing ever leaves your network unless you use the DDNS path.

## Why this needs LSPosed

Since Android 10, an app that is not in the foreground cannot read the clipboard — which is exactly
when you want clipboard sync to work. There is no permission that grants it back.

So the module hooks **system_server** (scope: *System Framework*) and does three small things:

| Hook | Why |
|---|---|
| `ClipboardService.setPrimaryClipInternalLocked` | Pushes every new clip straight to the sync service, instead of waiting for a change listener that the system no longer delivers to background apps. |
| `ClipboardService.showAccessNotificationLocked` | Suppresses the "ClipSync pasted from your clipboard" toast for our own reads. |
| The app freezer (`ProcessRecord` / `Process.setProcessFrozen`) | Keeps the sync process from being frozen while it holds the connection, and restarts it if the system kills it. |

Nothing else is touched, and no other app is hooked.

## What you need

| | |
|---|---|
| **Android** | 15 or newer, rooted, with **LSPosed** (libxposed API 102) |
| **Windows** | 10 or 11, with **Python 3.9+** installed |
| **For internet use** | IPv6 on both ends, a DDNS name pointing at the PC, and inbound TCP 47521 allowed in your router's IPv6 firewall |
| **For LAN-only use** | Nothing extra — both on the same Wi-Fi, with client isolation off |

You can start LAN-only and add the DDNS path later; the app falls back automatically.

## Download

The release contains two files:

| File | What it is |
|---|---|
| `app-release.apk` | The Android app and Xposed module, in one APK |
| `windows.zip` | The PC side: `clipsync.py`, `install.ps1`, `uninstall.ps1`, `requirements.txt`, `clipsync.ini` |

---

## Setup

### 1. Make a shared key

Run this once, anywhere Python is installed, and keep the output — both sides need the same value:

```
python -c "import os;print(os.urandom(32).hex())"
```

### 2. Windows

Unzip `windows.zip` somewhere permanent (it runs from where you put it), then:

```powershell
notepad clipsync.ini          # paste the key into psk =, and your DDNS name into host = if you have one
pip install -r requirements.txt
```

Then, in an **elevated** PowerShell:

```powershell
Set-ExecutionPolicy -Scope Process Bypass
.\install.ps1
```

`install.ps1` opens the firewall ports and registers a task that starts ClipSync when you log in.
It changes nothing else, and `.\uninstall.ps1` reverts exactly those changes — your config, logs and
received files are left alone.

Check `clipsync.log`: `mDNS advertising …` means the LAN path is up.

> `pillow` is optional. Without it, received images arrive as files instead of pasting as pictures.

### 3. Android — on each device

1. Install `app-release.apk`.
2. In **LSPosed**, enable **ClipSync** with scope **System Framework**, then **reboot**.
   The reboot is required: the module lives in system_server.
3. Open the app. Fill in the **PSK** and, if you have one, the **DDNS name**, then press
   **Apply & start**.
4. If the app shows a battery card, tap **Allow** — it keeps Android from suspending the connection.

The status chip in the top-right corner tells you where you stand: `Stopped`, or the kind of
connection that is live (`mDNS`, `DDNS (LAN)`, `DDNS (Internet)`). Tap it for the PC's name and
address. The **Log** tab shows everything as it happens.

---

## Living with it

- **Copy anywhere, paste anywhere.** Devices are equal; the PC is only the meeting point.
- **Received files.** They land in `Download/ClipSync` on Android and in the `received` folder next
  to `clipsync.py` on Windows, and the path is put on the clipboard so you can paste the file
  straight into a file manager.
- **Size limits.** 1 MB of text, 10 MB per file over the internet, 100 MB per file on the LAN. All
  three are configurable.
- **Big files.** They are split into chunks and sent over 8 connections at once. If a transfer is
  cut off, the rest is resumed rather than restarted, and a file the other side already has is
  never sent twice.
- **A screen that is off is disconnected, by design.** It costs nothing while you are not using it,
  and it catches up the moment you wake it.
- **Housekeeping.** Received files older than 2 hours are deleted, and the folder is capped at
  256 MB. Both are settings; `0` disables either.

## Settings

Everything below is in the app's **Settings** tab, and most of it also exists in `clipsync.ini` on
the PC. Fields are validated as you type, and **Apply** stays disabled until they are all valid.

| Setting | Default | Notes |
|---|---|---|
| Connection mode | `Both` | `Both`, `DDNS only` or `mDNS only` |
| DDNS name | — | The name that resolves to your PC's IPv6 address |
| Port | `47521` | Must match the PC |
| PSK | — | 64 hex characters, identical on both sides |
| mDNS browse time | 4 s | How long to look for the PC on the LAN before giving up |
| Max text | 1 MB | Larger clips are not sent |
| Max file (internet) | 10 MB | |
| Max file (LAN) | 100 MB | |
| Parallel connections | 8 | 1–16 |
| Received files folder | `Download/ClipSync` | Anywhere under `Download/` or `Documents/` |
| Keep for | 2 h | `0` keeps them forever |
| Keep at most | 256 MB | `0` for no limit |

Settings are saved on the device and survive reboots and updates.

## Troubleshooting

| Symptom | What to check |
|---|---|
| Copies made **while the app is in the background** never arrive, but the PC → phone direction works | The module is not active. Writing the clipboard needs no hook, so a working PC → phone direction proves nothing. Enable ClipSync in LSPosed with scope **System Framework** and reboot. |
| Status stays `Stopped` or keeps reconnecting | Open the **Log** tab — it names the reason. `ddns path failed` means no IPv6 or no AAAA record on this network; it falls back to mDNS by itself. |
| `mdns: no _clipsync._tcp service found` | The PC is not advertising. Check `clipsync.log` on the PC: missing `zeroconf`, UDP 5353 blocked, or Wi-Fi client isolation. |
| Connected, but nothing arrives | PC firewall or router IPv6 inbound. `clipsync.log` should show the device connecting. |
| `decrypt error` in `clipsync.log` | The PSK differs between the PC and that device. |
| Works on one device, not the other | Both must appear as connected in `clipsync.log`. A device whose screen is off is disconnected on purpose. |
| The app logs `process was suspended for ~N s` | Android froze the sync process. Tap **Allow** on the battery card, and check that LSPosed still has the module enabled — the freezer exemption is part of the module. |

If something still looks wrong, the **Log** tab has a **Copy** button; that log is what to attach to
an issue.

## Privacy and security

- The pre-shared key never leaves your devices. It is not derived from anything, not uploaded, and
  not recoverable — if you lose it, generate a new one and set it on every device.
- Every frame is encrypted and authenticated with ChaCha20-Poly1305 under a key derived per
  connection; a device that cannot prove it holds the PSK is disconnected before anything is read.
- Clipboard contents are held in memory. Files you receive are written to the folder you chose, and
  are deleted again by the housekeeping settings above.
- The log records what happened — sizes, file names, peer names — but never clipboard text.
- The DDNS path is only used if you configure a name. Left empty, nothing ever leaves your LAN.

## Building it yourself

Everything here is built from this repository by GitHub Actions; see
[`.github/workflows/build-apk.yml`](.github/workflows/build-apk.yml) for the exact steps, and
[`android/`](android/) for the Gradle project. The PC side is a single Python file with no build
step at all — read [`windows/clipsync.py`](windows/clipsync.py) before you run it.

## License

MIT
