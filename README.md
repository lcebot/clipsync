# ClipSync

**Share one clipboard across your rooted Android devices and your Windows PC.**
Copy & paste anywhere with text, images and files fully supported.

---

## What it does

- Your devices connect to each other directly, with no server in the middle and no hub to route through. Everyone dials everyone they can see and everyone listens, so a phone and a tablet on the same Wi-Fi talk to each other whether or not the PC is switched on.
- ClipSync works like a small-file transfer tool too. It features a robust design that supports multithreaded transfers, resume-from-breakpoint functionality and cache reuse, enabling the easy & rapid transfer of files between devices.
- On the same LAN, devices find each other by mDNS with no DNS setup at all. With a DDNS name pointing at your PC over IPv6, a phone reaches it from mobile data too. When several devices on one network are offered the same file, only one of them downloads it over the expensive link and passes it to the rest.
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
| `windows.zip` | The PC side: `configurator.py`, `install.ps1`, `uninstall.ps1`, and `app\` — the service and everything it imports |

---

## Setup

You do **not** need to make a key by hand. One device makes it and the rest take it over the network;
the eight-digit code shown on the first device opens the channel, and the device holding the key asks
you to confirm before it hands it over.

### 1. Windows

Unzip `windows.zip` somewhere permanent (it runs from where you put it), then:

```powershell
.\install.ps1
```

It asks for administrator rights itself — double-clicking is fine. It installs the Python packages it
needs, opens the firewall ports, registers a task that starts ClipSync when you log in, and opens the
settings window. It changes nothing else, and `.\uninstall.ps1` reverts exactly those changes: your
config, logs, received files and Python packages are left alone (the packages are listed so you can
remove them yourself if nothing else wants them).

In the settings window, either:

- **Generate random PSK key**, if this is your first device — then **Share this key…** when you set
  up the next one; or
- **Pair with a device…**, if a phone is already set up: tap **Pair new devices** there, and type the
  eight digits it shows.

If your devices will reach this PC at an address, turn on **Direct connections** and add it — a DDNS
name, a static address, whatever applies. For a LAN-only setup there is nothing to add: **Local
network discovery** is enough to be found. Press **Apply**.

### 2. Android (on each device)

1. Install `app-release.apk`.
2. *(rooted devices)* In **LSPosed**, enable **ClipSync** with scope **System Framework**, then
   **reboot**. On a device without root, everything except background clipboard *reading* works anyway.
3. Open the app. It walks you through setup the first time — choose **I have another ClipSync
   device** and enter the code the other one is showing, or **This is my first device** to make a key
   and hand it out. **Set up ClipSync** in Settings opens the same thing again later.
4. To reach a PC that is not on this network, turn on **Direct connections** and add its address.
   **Local network discovery** needs nothing configured. Then press **Apply** or **Start**.
5. If the app shows a battery card, tap **Allow**. It keeps Android from suspending the connection.

The status chip in the top-right corner tells you where you stand: `Stopped`, `No network`,
`Connecting…`, `Idle` while the screen is off, or `Connected (n)`. Tap it for a card per device —
its name, id, type and address — and, below those, every configured target that is *not* connected
with the reason why. The **Log** tab shows everything as it happens.

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

Everything below is in the app's **Settings** tab, and most of it also exists on the PC — in
`python configurator.py`, which writes `config.json`. Fields are validated as you type on both, and
**Apply** stays disabled until they are all valid.

| Setting | Default | Notes |
|---|---|---|
| Port | `47521` | Must match the PC |
| PSK | — | 64 hex characters, identical on every device. **Generate random PSK key** under the field makes one |
| Local network discovery | on | Find peers on this network by mDNS. Needs no configuration |
| Direct connections | off | Connect to addresses you list. A host name or a literal IPv4/IPv6 address — dynamic DNS is one option, not a requirement |
| This device's addresses | empty | Names that point at *this* device, if any. ClipSync uses them to recognise itself, so it never dials itself and refuses one of them typed into the list above |
| mDNS browse timeout | 4 s | How long to look for the PC on the LAN before giving up |
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
| Status stays `Stopped` or keeps reconnecting | Open the **Log** tab and the logs should name the reason. For example, `direct path failed (name)` means that address did not resolve or did not answer — no IPv6 or no AAAA record on this network, typically; local discovery is raced alongside it and takes over by itself. |
| `mdns: no _clipsync._tcp service found` | The PC is not advertising. Check `clipsync.log` on the PC: missing `zeroconf`, UDP 5353 blocked, or Wi-Fi client isolation. |
| Connected, but nothing arrives | PC firewall or router IPv6 inbound. `clipsync.log` should show the device connecting. |
| `decrypt error` in `clipsync.log` | The PSK differs between the PC and that device. |
| Works on one device, not the other | Both must appear as connected in `clipsync.log`. A device whose screen is off is disconnected on purpose. |
| The app logs `process was suspended for ~N s` | Android froze the sync process. Tap **Allow** on the battery card, and grant autostart / remove background restrictions in your ROM's settings. On a rooted device, also check that LSPosed still has the module enabled as the freezer exemption is part of the module. |

If something still looks wrong, the **Log** tab has a **Copy** button; that log is what to attach to
an issue. On the PC the same record is `clipsync.log` next to `clipsync.py`. It is capped at 128 KB:
when it fills, it becomes `clipsync.log.old` and a fresh one starts, so there is always roughly the
last two files' worth and never a log that grows without end.

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
step at all. Read [`windows/app/clipsync.py`](windows/app/clipsync.py) before you run it.

## License

MIT
