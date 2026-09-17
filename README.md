# ClipSync

**Share one clipboard across your rooted Android devices and your Windows PC.**
Copy & paste anywhere with text, images and files fully supported.

---

## What it does

- Devices connect to your PC directly without a server in the middle. Every device connects to the PC, and the PC relays to all the others.
- ClipSync works like a small-file transfer tool too. It features a robust design that supports multithreaded transfers, resume-from-breakpoint functionality and cache reuse, enabling the easy & rapid transfer of files between devices.
- When on the same LAN, devices can also find the PC by mDNS, with no DNS setup at all. With a DDNS name pointing at your PC over IPv6, the phone can also reach it from mobile data as well.
- With root privileges, sync process is exempted from the system freezer and is restarted if the system kills it. Also usable without root in one direction. See below.
- Messages are encrypted with a pre-shared key. Nothing is readable in transit.

## Root, and what you get without root

Root and LSPosed buy exactly one thing: **reading** the clipboard while the app is not on screen.

Since Android 10, an app that is not in the foreground cannot read the clipboard, and there is no
permission that grants it back. Writing is not restricted the same way. So on a device with no root:

| Direction | Without root | With root + LSPosed |
|---|---|---|
| Another device to this device | **Works, in the background** | Works |
| This device to everything else | Only while ClipSync is on screen | Works |

A non-rooted phone therefore still works as a receiver, permanently and unattended, which covers
the common case of "send this to my phone". To keep it connected, grant it what any long-running
app needs on your ROM: exemption from battery optimisation, permission to autostart, and no
background restriction. The app's own **Allow** card handles the first; the rest live in your ROM's
settings and are worth locking in, because a killed process cannot receive anything either.

Install the APK normally and skip the LSPosed step. Nothing else changes.

## What the module does, when you do have root

It hooks **system_server** and does three small things:

| Hook | Why |
|---|---|
| `ClipboardService.setPrimaryClipInternalLocked` | Pushes every new clip straight to the sync service, instead of waiting for a change listener that the system no longer delivers to background apps. |
| `ClipboardService.showAccessNotificationLocked` | Suppresses the "ClipSync pasted from your clipboard" toast for our own reads. |
| The app freezer (`ProcessRecord` / `Process.setProcessFrozen`) | Keeps the sync process from being frozen while it holds the connection, and restarts it if the system kills it. |

## What you need

| Item | Description |
|---|---|
| **Android** | 15 or newer. Root and **LSPosed** (libxposed API 102) only for the phone to PC direction |
| **Windows** | modern Windows with **Python 3.9+** installed |
| **For Internet use** | IPv6 on both ends, a DDNS name pointing at the PC, and inbound TCP 47521 allowed in your router's IPv6 firewall |
| **For LAN-only use** | Nothing extra. Both on the same Wi-Fi, with client isolation off |

You can start LAN-only and add the DDNS path later; the app chooses the fastest path automatically.

## Download

The release contains two files:

| File | What it is |
|---|---|
| `app-release.apk` | The Android app and Xposed module |
| `windows.zip` | The PC side: `clipsync.py`, `install.ps1`, `uninstall.ps1`, `requirements.txt`, `clipsync.ini` |

---

## Setup

### 1. Make a shared key

Run this once, anywhere Python is installed, and keep the output.

```
python -c "import os;print(os.urandom(32).hex())"
```

### 2. Windows

Unzip `windows.zip` somewhere permanent (it runs from where you put it), then:

```powershell
pip install -r requirements.txt
```

> `pillow` is optional. Without it, received images arrive as files instead of pasting as pictures.

Next, open `clipsync.ini`, paste the key into `psk`, and enter your DDNS name at `host` if you have one.

Then, in an **elevated** PowerShell:

```powershell
Set-ExecutionPolicy -Scope Process Bypass
.\install.ps1
```

`install.ps1` opens the firewall ports and registers a task that starts ClipSync when you log in.
It changes nothing else, and `.\uninstall.ps1` reverts exactly those changes. Your config, logs and
received files are left alone.

### 3. Android (on each device)

1. Install `app-release.apk`.
2. *(rooted devices)* In **LSPosed**, enable **ClipSync** with scope **System Framework**, then
   **reboot**. On a device without root, everything except background clipboard *reading* works anyway.
3. Open the app. Fill in the **PSK** and, if you have one, the **DDNS name**, then press
   **Apply** or **start**.
4. If the app shows a battery card, tap **Allow**. It keeps Android from suspending the connection.

The status chip in the top-right corner tells you where you stand: `Stopped`, or the kind of
connection that is live (`mDNS`, `DDNS (LAN)`, `DDNS (Internet)`). Tap it for the PC's name and
address. The **Log** tab shows everything as it happens.

---

## Tips

- **Received files.** They land in `Download/ClipSync` on Android and in the `received` folder next
  to `clipsync.py` on Windows. Files are put on the clipboard so you can paste them straight into
  a file manager. The file receive paths are all configurable.
- **Size limits.** 1 MB of text, 10 MB per file over the Internet, 100 MB per file on the LAN. All
  three are configurable.
- **Big files.** They are split into chunks and sent over 8 connections at once. If a transfer is
  cut off, the rest is resumed rather than restarted, and a file the other side already has is
  never sent twice.
- **A screen that is off is disconnected, by design.** It costs nothing while you are not using it,
  and it catches up the moment you wake it.
- **Housekeeping.** Received files older than 2 hours are deleted, and the folder is capped at
  256 MB. Both are configurable; `0` disables either.

## Settings

Everything below is in the app's **Settings** tab, and most of it also exists in `clipsync.ini` on
the PC. Fields are validated as you type, and the **Apply** button stays disabled until they are all valid.

| Setting | Default | Notes |
|---|---|---|
| Connection mode | `Both` | `Both`, `DDNS only` or `mDNS only` |
| DDNS name | — | The name that resolves to your PC's IPv6 address |
| Port | `47521` | Must match the PC |
| PSK | — | 64 hex characters, identical on both sides |
| mDNS browse time | 4 s | How long to look for the PC on the LAN before giving up |
| Max text | 1 MB | Larger clips are not sent |
| Max file (Internet) | 10 MB | |
| Max file (LAN) | 100 MB | |
| Parallel connections | 8 | 1–16 |
| Received files folder | `Download/ClipSync` | Anywhere under `Download/` or `Documents/` |
| Keep for | 2 h | `0` keeps them forever |
| Keep at most | 256 MB | `0` for no limit |

Settings are saved on the device and survive reboots and updates.

## Troubleshooting

| Symptom | What to check |
|---|---|
| Copies made **while the app is in the background** never arrive, but the PC to phone direction works | Expected on a device without root. On a rooted one it means the module is not active: writing the clipboard needs no hook, so a working PC to phone direction proves nothing. Enable ClipSync in LSPosed with scope **System Framework** and reboot. |
| Status stays `Stopped` or keeps reconnecting | Open the **Log** tab and the logs should name the reason. For example, `ddns path failed` means no IPv6 or no AAAA record on this network; it falls back to mDNS by itself. |
| `mdns: no _clipsync._tcp service found` | The PC is not advertising. Check `clipsync.log` on the PC: missing `zeroconf`, UDP 5353 blocked, or Wi-Fi client isolation. |
| Connected, but nothing arrives | PC firewall or router IPv6 inbound. `clipsync.log` should show the device connecting. |
| `decrypt error` in `clipsync.log` | The PSK differs between the PC and that device. |
| Works on one device, not the other | Both must appear as connected in `clipsync.log`. A device whose screen is off is disconnected on purpose. |
| The app logs `process was suspended for ~N s` | Android froze the sync process. Tap **Allow** on the battery card, and grant autostart / remove background restrictions in your ROM's settings. On a rooted device, also check that LSPosed still has the module enabled as the freezer exemption is part of the module. |

If something still looks wrong, the **Log** tab has a **Copy** button; that log is what to attach to
an issue.

## Privacy and security

- The pre-shared key never leaves your devices. It is not derived from anything, not uploaded, and
  not recoverable. If you lose it, generate a new one and set it on every device.
- Every frame is encrypted and authenticated with ChaCha20-Poly1305 under a key derived per
  connection; a device that cannot prove it holds the PSK is disconnected before anything is read.
- Clipboard contents are held in memory. Files you receive are written to the folder you chose, and
  are deleted by the housekeeping settings above.
- The log records what happened, sizes, file names and peer names, but never clipboard text.
- The DDNS path is only used if you configure a name. Left empty and nothing ever leaves your LAN.

## Building it yourself

Everything here is built from this repository by GitHub Actions; see
[`.github/workflows/build-apk.yml`](.github/workflows/build-apk.yml) for the exact steps, and
[`android/`](android/) for the Gradle project. The PC side is a single Python file with no build
step at all. Read [`windows/clipsync.py`](windows/clipsync.py) before you run it.

## License

MIT
