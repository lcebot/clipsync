# ClipSync

ClipSync keeps one clipboard in sync across your Android devices and your Windows PC, with text,
images and files all supported.

---

## What it does

- Your devices connect to each other directly. Everyone dials everyone they can see and everyone listens, so a phone and a tablet on the same Wi-Fi talk to each other whether or not the PC is switched on.
- ClipSync also works as a simple file transfer tool: files are split into chunks and sent over several connections at once, an interrupted transfer resumes instead of starting over, and a file the other side already has is not sent again.
- On the same LAN, devices find each other by mDNS that requires no additional setup. With a DDNS name pointing at your PC over IPv6, a phone reaches it from mobile data too. When several devices on one network are offered the same file, only one of them downloads it over the expensive link and passes it to the rest.
- With root privileges, sync process is exempted from the system freezer and is restarted if the system kills it. Also usable without root in one direction. See below.
- Messages are encrypted with a pre-shared key that rotates automatically every couple of days, so nothing is readable in transit and a key that leaked stops being useful on its own.

## Root, and what you get without root

Root and LSPosed buy exactly one thing: **uploading** while the app is not on screen.

Since Android 10, an app that is not in the foreground cannot read the clipboard, which is what
upload needs, and there is no permission that grants it back. Download does not read the clipboard,
so it is not restricted the same way. So on a device with no root:

| Clipboard content direction | Without root | With root + LSPosed |
|---|---|---|
| Download | Works | Works |
| Upload | **Won't work in the background** | Works |

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
| `ClipboardService.setPrimaryClipInternalLocked` | Pushes every new clip straight to the sync service as soon as it changes, even while the app is in the background. |
| `ClipboardService.showAccessNotificationLocked` | Suppresses the "ClipSync pasted from your clipboard" toast for our own uploads. |
| The app freezer (`ProcessRecord` / `Process.setProcessFrozen`) | Keeps the sync process from being frozen while it holds the connection, and restarts it if the system kills it. |

## What you need

| Item | Description |
|---|---|
| **Android** | 15 or newer. Root and **LSPosed** (libxposed API 102) only for uploading |
| **Windows** | modern Windows with **Python 3.9+** installed |
| **For Internet use** | IPv6 on both ends, a DDNS name pointing at the PC, and inbound TCP 47521 allowed in your router's IPv6 firewall |
| **For LAN-only use** | Nothing extra. Both on the same Wi-Fi, with client isolation off |

You can start LAN-only and add the DDNS path later; the app chooses the fastest path automatically.

## Download

The release contains two files:

| File | What it is |
|---|---|
| `app-release.apk` | The Android app and Xposed module |
| `windows.zip` | The PC side: `configurator.py`, `install.ps1`, `uninstall.ps1`, and `app\`, which holds the service and everything it imports |

---

## Setup

You do **not** need to make a key by hand. One device makes it and the rest take it over the network;
the nine-digit code shown on the first device opens the channel, and the device holding the key asks
you to confirm before it hands it over.

### 1. Windows

Unzip `windows.zip` somewhere permanent (it runs from where you put it), then:

```powershell
.\install.ps1
```

It asks for administrator rights itself, so double-clicking is fine. It installs the Python packages it
needs, opens the firewall ports, registers a task that starts ClipSync when you log in, and opens the
settings window. It changes nothing else, and `.\uninstall.ps1` reverts exactly those changes. your
config, logs, received files and Python packages are left alone (the packages are listed so you can
remove them yourself if nothing else wants them).

In the settings window, either:

- **Generate random PSK key**, if this is your first device, then **Share this key…** when you set
  up the next one; or
- **Pair with a device…**, if a phone is already set up: tap **Pair new devices** there, and type the
  nine digits it shows. It displays them in threes, as "123 456 789", and the spaces are only there
  to be read; type them or leave them out, either works.

If your devices will reach this PC at an address, turn on **Direct connections** and add it: a DDNS
name, a static address, whatever applies. For a LAN-only setup there is nothing to add: **Local
network discovery** is enough to be found. Press **Apply**.

### 2. Android (on each device)

1. Install `app-release.apk`.
2. *(rooted devices)* In **LSPosed**, enable **ClipSync** with scope **System Framework**, then
   **reboot**. On a device without root, everything except background upload works anyway.
3. Open the app. It walks you through setup the first time: choose **I have another ClipSync
   device** and enter the code the other one is showing, or **This is my first device** to make a key
   and hand it out. **Set up ClipSync** in Settings opens the same thing again later.
4. To reach a PC that is not on this network, turn on **Direct connections** and add its address.
   **Local network discovery** needs nothing configured. Then press **Apply** or **Start**.
5. If the app shows a battery card, tap **Allow**. It keeps Android from suspending the connection.

The status chip in the top-right corner tells you where you stand: `Stopped`, `No network`,
`Connecting…`, `Idle` while the screen is off, or `Connected (n)`. Tap it for a card per device,
showing its name, id, type and address, and below those, every configured target that is *not*
connected with the reason why. The **Log** tab shows everything as it happens.

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

Everything below is in the app's **Settings** tab, and most of it also exists on the PC, in
`python configurator.py`, which writes `config.json`. Fields are validated as you type on both, and
**Apply** stays disabled until they are all valid.

| Setting | Default | Notes |
|---|---|---|
| Port | `47521` | Must match the PC |
| PSK | none | 64 hex characters, identical on every device. **Generate random PSK key** under the field makes one |
| Local network discovery | on | Find peers on this network by mDNS. Needs no configuration |
| Direct connections | off | Connect to addresses you list. A host name or a literal IPv4/IPv6 address, with dynamic DNS being one option, not a requirement |
| This device's addresses | empty | Names that point at *this* device, if any. ClipSync uses them to recognise itself, so it never dials itself and refuses one of them typed into the list above |
| mDNS browse timeout | 4 s | How long to look for the PC on the LAN before giving up |
| Max text | 1 MB | Larger clips are not sent |
| Max file (Internet) | 10 MB | |
| Max file (LAN) | 100 MB | |
| Parallel connections | 8 | 1-16 |
| Received files folder | `Download/ClipSync` | Anywhere under `Download/` or `Documents/` |
| Keep for | 2 h | `0` keeps them forever |
| Keep at most | 256 MB | `0` for no limit |

Settings are saved on the device and survive reboots and updates.

## Troubleshooting

| Symptom | What to check |
|---|---|
| Copies made **while the app is in the background** never arrive, but the PC to phone direction works | Expected on a device without root. On a rooted one it means the module is not active: download needs no hook, so a working PC to phone direction proves nothing. Enable ClipSync in LSPosed with scope **System Framework** and reboot. |
| Status stays `Stopped` or keeps reconnecting | Open the **Log** tab and the logs should name the reason. For example, `direct path failed (name)` means that address did not resolve or did not answer, typically because there is no IPv6 or no AAAA record on this network; local discovery is raced alongside it and takes over by itself. |
| `mdns: no _clipsync._tcp service found` | The PC is not advertising. Check `clipsync.log` on the PC: missing `zeroconf`, UDP 5353 blocked, or Wi-Fi client isolation. |
| Connected, but nothing arrives | PC firewall or router IPv6 inbound. `clipsync.log` should show the device connecting. |
| `decrypt error` in `clipsync.log` | The PSK differs between the PC and that device. This also happens on its own if a device has been off for more than about a week: the key has rotated past what it still has, and it needs to be paired again. |
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
- The key rotates itself every 48 hours and the devices agree on the new one automatically; you do
  not need to do anything. A device that was switched off still connects when it comes back, since
  recent past keys keep working for about a week. Left off longer than that, it has to be paired
  again.
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
