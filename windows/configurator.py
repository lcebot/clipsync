"""
ClipSync - Windows settings window.

Everything config.json holds, editable without a text editor, with the same sections and the same
rules as the Android Settings page. This window is why config.json needs no comments in it. Run it
from this folder:

    python configurator.py

tkinter, so there is nothing to install: it ships with Python on Windows. The layout is plain rather
than styled — this is a settings dialog someone opens twice a year, and every minute spent on its
looks is a minute not spent on the thing it configures.

**No rule is written here.** Defaults, parsing and every check_* come from clipsync_config, which the
service imports too. A settings window that disagreed with the service about what is valid would be
worse than no settings window: it would write files the service then refuses to start on.

Apply writes the file and restarts the service. There is no hot reload — see docs/p2p-plan.md §11 for
why watching the file was not worth a thread that runs forever for an event that happens twice a
year.
"""
import os
import re
import socket
import subprocess
import sys
import tkinter as tk
from tkinter import messagebox, ttk

import clipsync_config as cfgmod

TASK_NAME = "ClipSync"          # the scheduled task install.ps1 registers
PAD = 6

# subprocess flags that keep a console window from flashing up when this runs under pythonw
_NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)
_DETACHED = getattr(subprocess, "DETACHED_PROCESS", 0) | getattr(subprocess, "CREATE_NEW_PROCESS_GROUP", 0)


# ----------------------------------------------------------------------------- restarting the service
def _run(args):
    """A short-lived console command, with no window and no exception on a non-zero exit."""
    return subprocess.run(args, capture_output=True, text=True, creationflags=_NO_WINDOW)


def _task_exists() -> bool:
    return _run(["schtasks", "/Query", "/TN", TASK_NAME]).returncode == 0


def _pythonw() -> str:
    """pythonw.exe beside whichever interpreter is running this, falling back to python.exe. Started
    detached so the service outlives this window."""
    folder = os.path.dirname(sys.executable)
    for name in ("pythonw.exe", "python.exe"):
        path = os.path.join(folder, name)
        if os.path.exists(path):
            return path
    return sys.executable


def restart_service() -> str:
    """
    Restart the running service, and say in one line what was done — the caller shows it.

    Two paths, because the service may or may not have been installed. `schtasks /End` on a task
    that is not running is not an error worth reporting, so its exit code is ignored; `/Run` is the
    one that has to work. Without the task, the process is found by its command line rather than by
    image name: killing every pythonw.exe on the machine to restart one of them is not acceptable.
    """
    if _task_exists():
        _run(["schtasks", "/End", "/TN", TASK_NAME])
        result = _run(["schtasks", "/Run", "/TN", TASK_NAME])
        if result.returncode != 0:
            raise RuntimeError((result.stderr or result.stdout).strip() or "schtasks /Run failed")
        return "Scheduled task restarted."

    script = os.path.join(cfgmod.HERE, "clipsync.py")
    _run(["powershell", "-NoProfile", "-Command",
          "Get-CimInstance Win32_Process -Filter \"Name='pythonw.exe' or Name='python.exe'\" | "
          "Where-Object { $_.CommandLine -like '*clipsync.py*' } | "
          "ForEach-Object { Stop-Process -Id $_.ProcessId -Force }"])
    subprocess.Popen([_pythonw(), script], cwd=cfgmod.HERE, creationflags=_DETACHED, close_fds=True)
    return "ClipSync restarted (no scheduled task installed — run install.ps1 to start it at logon)."


# ----------------------------------------------------------------------------- small widgets
class Field:
    """A labelled entry with an error line under it. The error line is always present, so showing
    one does not shift every row below it."""

    def __init__(self, parent, row, label, *, secret=False, width=44):
        self.var = tk.StringVar()
        ttk.Label(parent, text=label).grid(row=row, column=0, sticky="w", padx=PAD, pady=(PAD, 0))
        self.entry = ttk.Entry(parent, textvariable=self.var, width=width,
                               show="•" if secret else "")
        self.entry.grid(row=row, column=1, sticky="ew", padx=PAD, pady=(PAD, 0))
        self.error = ttk.Label(parent, text="", foreground="#b3261e", wraplength=460)
        self.error.grid(row=row + 1, column=1, sticky="w", padx=PAD)

    def show(self, problem) -> bool:
        self.error.configure(text=problem or "")
        return problem is None

    def get(self) -> str:
        return self.var.get().strip()

    def set(self, value):
        self.var.set("" if value is None else str(value))


class AddressRow:
    """One address, with its own remove button and its own error line — the list is edited one entry
    at a time and an error belongs to the entry that caused it."""

    def __init__(self, parent, value, on_change, on_remove):
        self.frame = ttk.Frame(parent)
        self.var = tk.StringVar(value=value)
        self.var.trace_add("write", lambda *_: on_change())
        self.entry = ttk.Entry(self.frame, textvariable=self.var, width=40)
        self.entry.grid(row=0, column=0, sticky="ew")
        self.button = ttk.Button(self.frame, text="Remove", width=8, command=lambda: on_remove(self))
        self.button.grid(row=0, column=1, padx=(PAD, 0))
        self.error = ttk.Label(self.frame, text="", foreground="#b3261e", wraplength=440)
        self.error.grid(row=1, column=0, columnspan=2, sticky="w")
        self.frame.columnconfigure(0, weight=1)

    def show(self, problem) -> bool:
        self.error.configure(text=problem or "")
        return problem is None

    def get(self) -> str:
        return self.var.get().strip()

    def destroy(self):
        self.frame.destroy()


class AddressList:
    """
    A list of addresses with add, per-row remove and per-row errors: the peer list and the
    own-addresses list, which are the same widget used twice.

    They accept exactly the same things and must not drift into accepting different ones — the whole
    reason `check_addresses` takes its differences as parameters rather than existing twice. The two
    that reach up here are `allow_empty` (the peer list needs an entry while Direct connections is
    on; the own list never does) and `enabled` (only the peer list has a switch above it).

    One row always remains even when the list is logically empty. Zero rows is a list that looks
    broken, and the one row is also where the "this is required" error has to be shown.
    """

    def __init__(self, parent, *, allow_empty: bool, on_change):
        self.allow_empty = allow_empty
        self.on_change = on_change
        self.rows = []
        self.box = ttk.Frame(parent)
        self.box.columnconfigure(0, weight=1)
        self.add_button = ttk.Button(parent, text="Add address", command=lambda: self.add("", True))
        self.enabled = True

    def grid(self, box_row, button_row):
        self.box.grid(row=box_row, column=0, sticky="ew", padx=PAD)
        self.add_button.grid(row=button_row, column=0, sticky="w", padx=PAD, pady=(0, PAD))

    def add(self, value="", focus=False):
        row = AddressRow(self.box, value, self.on_change, self.remove)
        row.frame.grid(row=len(self.rows), column=0, sticky="ew", pady=(0, 2))
        self.rows.append(row)
        if focus:
            row.entry.focus_set()
        self._refresh()
        self.on_change()

    def remove(self, row):
        if len(self.rows) <= 1:              # the button is disabled, but be certain
            return
        row.destroy()
        self.rows.remove(row)
        self._regrid()
        self._refresh()
        self.on_change()

    def set_values(self, values):
        for row in list(self.rows):
            row.destroy()
        self.rows.clear()
        for value in list(values) or [""]:
            self.add(value)

    def values(self):
        """Blank rows dropped: a blank is either an error the user is looking at or a lone row
        standing in for an empty list, and neither belongs in the file."""
        return [v for v in (row.get() for row in self.rows) if v]

    def set_enabled(self, enabled: bool):
        self.enabled = enabled
        if not enabled:
            # Blank rows go when the list goes out of use, keeping one. Filled ones stay: preserving
            # them is the entire point of having a switch rather than deleting the list.
            for row in list(self.rows):
                if not row.get() and len(self.rows) > 1:
                    row.destroy()
                    self.rows.remove(row)
            self._regrid()
        self._refresh()

    def validate(self, *, own=frozenset(), empty_message=None) -> bool:
        """
        Per row, with a repeat reported on the second one — the first is not wrong, and marking both
        would leave no clue which to change.

        `own` is this device's declared addresses; an entry matching one is refused here rather than
        dialled. `empty_message` is what a blank row says when the list may not be empty.
        """
        if not self.enabled:
            for row in self.rows:
                row.show(None)
            return True
        ok = True
        seen = []
        for row in self.rows:
            value = row.get()
            if not value:
                problem = None if self.allow_empty else empty_message
            else:
                problem = cfgmod.check_peer(value)
                normal = cfgmod.normalise_peer(value)
                if problem is None and normal in seen:
                    problem = "Already listed above"
                elif problem is None and normal in own:
                    problem = "That is this device"
                if problem is None:
                    seen.append(normal)
            ok &= row.show(problem)
        return ok

    def _regrid(self):
        for i, row in enumerate(self.rows):
            row.frame.grid_configure(row=i)

    def _refresh(self):
        removable = len(self.rows) > 1
        for row in self.rows:
            row.entry.configure(state="normal" if self.enabled else "disabled")
            row.button.configure(state="normal" if self.enabled and removable else "disabled")
        self.add_button.configure(state="normal" if self.enabled else "disabled")


# ----------------------------------------------------------------------------- the window
class App:
    """Not a Frame subclass: every widget below is a child of the toplevel, and an unmapped frame in
    the middle would only be one more thing for a dialog to take as its parent."""

    def __init__(self, master):
        self.root = master
        master.title("ClipSync settings")
        master.minsize(600, 480)

        # The button bar is packed first, before the scrollable body, so that a window shorter than
        # the form squeezes the form and not the row holding Apply and Close. The packer hands out
        # space in declaration order, and the expanding child is the one that should absorb the loss.
        bar = ttk.Frame(master)
        bar.pack(fill="x", side="bottom")

        outer = ttk.Frame(master)
        outer.pack(fill="both", expand=True)

        # A canvas, because the form is taller than a small laptop screen and tkinter has no
        # scrolling container of its own.
        canvas = tk.Canvas(outer, highlightthickness=0)
        scroll = ttk.Scrollbar(outer, orient="vertical", command=canvas.yview)
        body = ttk.Frame(canvas)
        body.bind("<Configure>", lambda e: canvas.configure(scrollregion=canvas.bbox("all")))
        window = canvas.create_window((0, 0), window=body, anchor="nw")
        canvas.bind("<Configure>", lambda e: canvas.itemconfigure(window, width=e.width))
        canvas.configure(yscrollcommand=scroll.set)
        canvas.pack(side="left", fill="both", expand=True)
        scroll.pack(side="right", fill="y")
        canvas.bind_all("<MouseWheel>", lambda e: canvas.yview_scroll(-e.delta // 120, "units"))

        # The bar's widgets are created before _build, because the lists it builds fire on_change as
        # soon as they have a row, and revalidate touches apply_button and paths_error.
        self.status = ttk.Label(bar, text="")
        self.status.pack(side="left", padx=PAD, pady=PAD)
        ttk.Button(bar, text="Close", command=master.destroy).pack(side="right", padx=PAD, pady=PAD)
        self.apply_button = ttk.Button(bar, text="Apply", command=self.apply)
        self.apply_button.pack(side="right", pady=PAD)

        self._build(body)
        self.load()

    # ------------------------------------------------------------------ layout
    def _build(self, body):
        body.columnconfigure(0, weight=1)
        section_row = 0

        # --- Connection
        conn = ttk.LabelFrame(body, text="Connection")
        conn.grid(row=section_row, column=0, sticky="ew", padx=PAD, pady=(PAD, 0))
        conn.columnconfigure(1, weight=1)
        self.port = Field(conn, 0, "Port")
        self.psk = Field(conn, 2, "PSK key (64 hex)", secret=True)

        psk_bar = ttk.Frame(conn)
        psk_bar.grid(row=4, column=1, sticky="w", padx=PAD, pady=(0, PAD))
        ttk.Button(psk_bar, text="Generate random PSK key", command=self.new_psk).pack(side="left")
        self.psk_shown = tk.BooleanVar(value=False)
        ttk.Checkbutton(psk_bar, text="Show", variable=self.psk_shown,
                        command=self._toggle_psk).pack(side="left", padx=(PAD, 0))
        section_row += 1

        # --- This PC's own addresses (§4a)
        # Above the two switches, not below: the peer list validates against this one, so it has to
        # be the thing already on screen when the user starts typing addresses into the list below.
        # No switch — an empty list already means "this PC has no name of its own", and turning such
        # a list off could only cause the mistakes it exists to prevent.
        own = ttk.LabelFrame(body, text="This PC's own addresses")
        own.grid(row=section_row, column=0, sticky="ew", padx=PAD, pady=(PAD, 0))
        own.columnconfigure(0, weight=1)
        ttk.Label(own, wraplength=460, foreground="#49454f",
                  text="Names that point at this PC — typically a domain a dynamic DNS client here "
                       "keeps pointed at it. ClipSync uses them to recognise itself, so it will not "
                       "dial this machine, and so that an address of its own typed into the peer "
                       "list below is refused rather than connected to."
                  ).grid(row=0, column=0, sticky="w", padx=PAD, pady=(PAD, 0))
        self.own_list = AddressList(own, allow_empty=True, on_change=self.revalidate)
        self.own_list.grid(box_row=1, button_row=2)
        section_row += 1

        # --- Local network discovery
        lan = ttk.LabelFrame(body, text="Local network discovery")
        lan.grid(row=section_row, column=0, sticky="ew", padx=PAD, pady=(PAD, 0))
        lan.columnconfigure(1, weight=1)
        self.discovery = tk.BooleanVar(value=True)
        # One direction, and said so: today the PC advertises and never browses, because it is the
        # only node that listens. Phase 3 makes it dial too and this becomes the symmetric thing the
        # Android label already describes — at which point this line changes with it. Writing the
        # symmetric wording early would describe a PC that does not exist yet.
        ttk.Checkbutton(lan, text="Advertise this PC on the local network (mDNS)",
                        variable=self.discovery, command=self.revalidate
                        ).grid(row=0, column=0, columnspan=2, sticky="w", padx=PAD, pady=(PAD, 0))
        self.mdns_name = Field(lan, 1, "Service name (optional)")
        ttk.Label(lan, text="Empty means “ClipSync on %s”." % _hostname(), foreground="#49454f"
                  ).grid(row=3, column=1, sticky="w", padx=PAD, pady=(0, PAD))
        section_row += 1

        # --- Direct connections
        dire = ttk.LabelFrame(body, text="Direct connections")
        dire.grid(row=section_row, column=0, sticky="ew", padx=PAD, pady=(PAD, 0))
        dire.columnconfigure(0, weight=1)
        self.direct = tk.BooleanVar(value=False)
        # Not "Reach this PC at the addresses below": the switch does not make the PC reachable —
        # DNS and the router do that — and claiming otherwise would have someone ticking it and
        # waiting for a connection that was never going to arrive. What it actually controls is
        # whether the list is used at all, so that is what it says.
        ttk.Checkbutton(dire, text="Use the addresses below",
                        variable=self.direct, command=self._direct_toggled
                        ).grid(row=0, column=0, sticky="w", padx=PAD, pady=(PAD, 0))
        ttk.Label(dire, wraplength=460, foreground="#49454f",
                  text="The addresses your devices use to reach this PC. This PC uses them to "
                       "recognise itself: when several PCs share one configuration, the ones that "
                       "own none of these names stop advertising on the LAN, so a device cannot "
                       "reach the wrong one."
                  ).grid(row=1, column=0, sticky="w", padx=PAD, pady=(0, PAD))
        self.peer_list = AddressList(dire, allow_empty=False, on_change=self.revalidate)
        self.peer_list.grid(box_row=2, button_row=3)
        self.paths_error = ttk.Label(dire, text="", foreground="#b3261e", wraplength=460)
        self.paths_error.grid(row=4, column=0, sticky="w", padx=PAD, pady=(0, PAD))
        section_row += 1

        # --- Transfer limits
        lim = ttk.LabelFrame(body, text="Transfer limits")
        lim.grid(row=section_row, column=0, sticky="ew", padx=PAD, pady=(PAD, 0))
        lim.columnconfigure(1, weight=1)
        self.max_bytes = Field(lim, 0, "Text (KB)")
        self.max_file = Field(lim, 2, "File over the internet (MB)")
        self.max_file_local = Field(lim, 4, "File on the LAN (MB)")
        section_row += 1

        # --- Received files
        rec = ttk.LabelFrame(body, text="Received files")
        rec.grid(row=section_row, column=0, sticky="ew", padx=PAD, pady=(PAD, 0))
        rec.columnconfigure(1, weight=1)
        self.files_dir = Field(rec, 0, "Folder")
        ttk.Label(rec, text="Relative paths are resolved next to clipsync.py.", foreground="#49454f"
                  ).grid(row=2, column=1, sticky="w", padx=PAD)
        self.keep_hours = Field(rec, 3, "Keep unused for (hours, 0 = forever)")
        self.keep_max_mb = Field(rec, 5, "Keep at most (MB, 0 = unlimited)")
        section_row += 1

        # --- Startup
        start = ttk.LabelFrame(body, text="Startup")
        start.grid(row=section_row, column=0, sticky="ew", padx=PAD, pady=(PAD, PAD))
        start.columnconfigure(1, weight=1)
        self.start_delay = Field(start, 0, "Delay after logon (seconds)")
        ttk.Label(start, text="0 is right for a single PC. Raise it only when several PCs share this "
                              "config file.", foreground="#49454f", wraplength=460
                  ).grid(row=2, column=1, sticky="w", padx=PAD, pady=(0, PAD))

        self.fields = {
            "port": self.port, "psk": self.psk, "mdns_name": self.mdns_name,
            "max_bytes": self.max_bytes, "max_file_bytes": self.max_file,
            "max_file_bytes_local": self.max_file_local, "files_dir": self.files_dir,
            "keep_hours": self.keep_hours, "keep_max_mb": self.keep_max_mb,
            "start_delay": self.start_delay,
        }
        for field in self.fields.values():
            field.var.trace_add("write", self.revalidate)

    def _toggle_psk(self):
        self.psk.entry.configure(show="" if self.psk_shown.get() else "•")

    def _direct_toggled(self):
        self.peer_list.set_enabled(self.direct.get())
        self.revalidate()

    # ------------------------------------------------------------------ load / collect / validate
    def load(self):
        fresh = not os.path.exists(cfgmod.CONFIG_PATH)
        try:
            raw = cfgmod.read_config()
        except (ValueError, OSError) as e:
            messagebox.showerror(
                "Could not read the configuration",
                "{}\n\n{}\n\nThe window has opened on the defaults. Pressing Apply will overwrite "
                "the file with them.".format(os.path.basename(cfgmod.CONFIG_PATH), e),
                parent=self.root)
            raw = dict(cfgmod.DEFAULTS)

        self.port.set(raw["port"])
        self.psk.set(raw["psk"])
        self.mdns_name.set(raw["mdns_name"])
        self.discovery.set(cfgmod.as_bool(raw["discovery"]))
        self.direct.set(cfgmod.as_bool(raw["direct"]))
        # Bytes in the file, friendlier units in the window — the same trade the app makes.
        self.max_bytes.set(_to_unit(raw["max_bytes"], 1024))
        self.max_file.set(_to_unit(raw["max_file_bytes"], 1024 * 1024))
        self.max_file_local.set(_to_unit(raw["max_file_bytes_local"], 1024 * 1024))
        self.files_dir.set(raw["files_dir"])
        self.keep_hours.set(raw["keep_hours"])
        self.keep_max_mb.set(raw["keep_max_mb"])
        self.start_delay.set(raw["start_delay"])

        self.own_list.set_values(cfgmod.as_list(raw["own_addresses"]))
        self.peer_list.set_values(cfgmod.as_list(raw["peers"]))
        self.peer_list.set_enabled(self.direct.get())
        self.revalidate()
        if fresh:
            # Nothing is committed to the repository and nothing ships in the zip: this window is
            # where config.json comes from. Saying so is the difference between "the defaults are
            # loaded" and "there is no file yet and Apply is what creates it".
            self.status.configure(text="No configuration yet — Apply will create %s."
                                       % os.path.basename(cfgmod.CONFIG_PATH))

    def collect(self) -> dict:
        """
        The values in the types config.json stores them as. Numbers that do not parse are left as the
        string the user typed rather than coerced or dropped: check_all is what reports them, in its
        own words, and a field cannot be fixed if Apply has already silently replaced it with 0.
        """
        return {
            "port": _int(self.port.get()),
            "psk": self.psk.get().lower(),
            "max_bytes": _int(_from_unit(self.max_bytes.get(), 1024)),
            "max_file_bytes": _int(_from_unit(self.max_file.get(), 1024 * 1024)),
            "max_file_bytes_local": _int(_from_unit(self.max_file_local.get(), 1024 * 1024)),
            "files_dir": self.files_dir.get(),
            "keep_hours": _int(self.keep_hours.get()),
            "keep_max_mb": _int(self.keep_max_mb.get()),
            "discovery": bool(self.discovery.get()),
            "mdns_name": self.mdns_name.get(),
            "direct": bool(self.direct.get()),
            "peers": self.peer_list.values(),
            "own_addresses": self.own_list.values(),
            "start_delay": _int(self.start_delay.get()),
        }

    def revalidate(self, *_):
        raw = self.collect()
        problems = cfgmod.check_all(raw, discovery=self.discovery.get(), direct=self.direct.get())

        ok = True
        for key, field in self.fields.items():
            # The size fields are entered in KB/MB, so a byte-range message would name numbers the
            # user cannot see. Their own unit is substituted back in.
            ok &= field.show(_in_unit(problems.get(key), key))

        # The own list first: the peer list is checked against it, so it has to be current.
        own = {cfgmod.normalise_peer(v) for v in self.own_list.values()}
        ok &= self.own_list.validate()
        ok &= self.peer_list.validate(
            own=own,
            empty_message="Enter a host name or IP address, or turn off Direct connections"
            if len(self.peer_list.rows) == 1 else
            "Enter a host name or IP address, or remove this row")

        if not self.discovery.get() and not self.direct.get():
            self.paths_error.configure(
                text="Turn on at least one of these. With both off there is no way to reach another "
                     "device.")
            ok = False
        else:
            self.paths_error.configure(text="")

        self.apply_button.configure(state="normal" if ok else "disabled")
        return ok


    # ------------------------------------------------------------------ actions
    def new_psk(self):
        """A fresh key. Asked about first when one is already there: it invalidates every other
        device at once, and a mis-click that costs re-pairing the household is not something to
        discover afterwards."""
        if cfgmod.check_psk(self.psk.get()) is None:
            if not messagebox.askokcancel(
                    "Replace the key?",
                    "Every other device is using the current key and will stop connecting until you "
                    "set the new one there too.", icon="warning", parent=self.root):
                return
        self.psk.set(os.urandom(32).hex())
        self.psk_shown.set(True)
        self._toggle_psk()

    def apply(self):
        if not self.revalidate():
            return
        try:
            cfgmod.write_config(self.collect())
        except OSError as e:
            messagebox.showerror("Could not save", str(e), parent=self.root)
            return
        try:
            note = restart_service()
        except Exception as e:                      # noqa: BLE001 - the save already succeeded
            messagebox.showwarning(
                "Saved, but not restarted",
                "config.json was written. Restarting the service failed:\n\n{}\n\nIt will pick the "
                "new settings up at the next logon.".format(e), parent=self.root)
            self.status.configure(text="Saved. Restart failed.")
            return
        self.status.configure(text="Saved. " + note)


def _hostname() -> str:
    try:
        return socket.gethostname()
    except OSError:
        return "this PC"


def _int(value):
    """A whole number where one parses, otherwise the text untouched — see collect()."""
    try:
        return int(str(value).strip())
    except ValueError:
        return str(value).strip()


def _to_unit(value: str, unit: int) -> str:
    """
    Bytes from the file into KB or MB for display, and back again losslessly.

    A byte count that is not a whole number of units is shown as a fraction rather than as the raw
    byte count. Showing the bytes was tried and is a trap: the box is labelled KB, `_from_unit`
    multiplies whatever is in it, and `max_bytes = 2000` would come back as 2048000 — a silent
    thousand-fold rewrite, still inside the valid range, on an Apply the user thought changed
    nothing else. repr() of the quotient round-trips exactly through float.
    """
    try:
        n = int(str(value).strip())
    except ValueError:
        return str(value)
    return str(n // unit) if n % unit == 0 else repr(n / unit)


def _from_unit(value: str, unit: int) -> str:
    """Back to bytes. Anything unparseable is passed through untouched so that check_range is the
    one that reports it, in its own words."""
    s = str(value).strip()
    try:
        return str(int(round(float(s) * unit)))
    except ValueError:
        return s


def _in_unit(problem, key):
    """Re-express a byte range in the unit its field is typed in, so "must be 1048576–4294967296
    bytes" under a box labelled MB becomes "must be 1–4096 MB"."""
    units = {"max_bytes": (1024, "KB"), "max_file_bytes": (1024 * 1024, "MB"),
             "max_file_bytes_local": (1024 * 1024, "MB")}
    if problem is None or key not in units:
        return problem
    unit, name = units[key]
    match = re.fullmatch(r"must be (\d+)–(\d+) bytes", problem)
    if not match:
        return problem
    return "must be {}–{} {}".format(int(match.group(1)) // unit, int(match.group(2)) // unit, name)


def main():
    root = tk.Tk()
    try:
        root.tk.call("tk", "scaling", root.winfo_fpixels("1i") / 72.0)
    except tk.TclError:
        pass
    App(root)
    root.mainloop()


if __name__ == "__main__":
    main()
