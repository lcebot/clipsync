"""
ClipSync - Windows settings window.

Everything config.json holds, editable without a text editor, with the same sections and the same
rules as the Android Settings page. This window is why config.json needs no comments in it. Run it
from this folder:

    python configurator.py

tkinter, so there is nothing to install: it ships with Python on Windows. Built from ttk's themed
widgets, on the 'vista' theme where it exists, so it follows the PC's own visual style. This is a
settings dialog someone comes back to, so a consistent, native look earns its keep here.

**No rule is written here.** Defaults, parsing and every check_* come from clipsync_config, which the
service imports too. A settings window that disagreed with the service about what is valid would be
worse than no settings window: it would write files the service then refuses to start on.

Apply writes the file and restarts the service. There is no hot reload: watching the file would be a
thread running forever for an event that happens twice a year, and a restart is both simpler and
honest about what it does, because the service re-reads everything, so there is no half-applied state
to reason about.
"""
import json
import os
import re
import socket
import subprocess
import sys
import threading
import time
import tkinter as tk
from tkinter import messagebox, ttk

# The service and everything it imports live in app/, out of the way: this window is the supported
# way to change any of it, and a folder full of .py files beside a shortcut invites editing the one
# thing that must not be hand-edited.  Nothing else needs a path fix, because clipsync_config derives
# config.json and clipsync.log from its OWN location, so they moved with it.
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "app"))

import clipsync_config as cfgmod   # noqa: E402

TASK_NAME = "ClipSync"          # the scheduled task install.ps1 registers
PAD = 6


# ----------------------------------------------------------------------------- look and feel
# A small, named set of semantic styles rather than a "foreground=" on every Label: the same red,
# grey, green and amber recur throughout the window (error text, hint text, a pairing outcome), and
# a ttk.Style is the themed way to say "this label means an error" once instead of forty times.
_FONT_FAMILY = "Segoe UI"


def _apply_theme(root) -> ttk.Style:
    """
    Pick a themed look and lay down the semantic label styles the rest of the window uses.

    'vista' is the ttk theme that follows the current Windows visual style: light or dark, accent
    colour and all. It exists only on Windows, which is the only place this window runs; the
    fallback to 'clam' keeps this from raising on a Python build that lacks 'vista'. Nothing here
    changes what a widget does, only how it is drawn.
    """
    style = ttk.Style(root)
    for theme in ("vista", "clam"):
        if theme in style.theme_names():
            style.theme_use(theme)
            break

    default_font = (_FONT_FAMILY, 9)
    root.option_add("*Font", default_font)
    style.configure(".", font=default_font)
    style.configure("TLabelframe.Label", font=(_FONT_FAMILY, 9, "bold"))
    style.configure("TButton", padding=(10, 4))
    style.configure("TEntry", padding=(4, 2))
    style.configure("TCheckbutton", padding=(0, 2))

    # Semantic label styles, keyed to what the colour MEANS rather than to the widget it happens to
    # sit in, so the same style is reused by the main form and by the pairing dialogs.
    style.configure("Muted.TLabel", foreground="#49454f")
    style.configure("Error.TLabel", foreground="#b3261e")
    style.configure("Success.TLabel", foreground="#1a5e20")
    style.configure("Warning.TLabel", foreground="#7a5900")
    style.configure("Placeholder.TLabel", foreground="#79747e")
    style.configure("CodeReal.TLabel", foreground="#1d192b")
    return style

# subprocess flags that keep a console window from flashing up when this runs under pythonw
_NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)
_DETACHED = getattr(subprocess, "DETACHED_PROCESS", 0) | getattr(subprocess, "CREATE_NEW_PROCESS_GROUP", 0)


HERE = os.path.dirname(os.path.abspath(__file__))
STATE_PATH = os.path.join(HERE, "install-state.json")   # written by install.ps1, read by uninstall.ps1


# ----------------------------------------------------------------------------- restarting the service
def _run(args):
    """A short-lived console command, with no window and no exception on a non-zero exit."""
    return subprocess.run(args, capture_output=True, text=True, creationflags=_NO_WINDOW)


def _powershell(script: str, variables=None):
    """
    Run a PowerShell snippet, passing values into it through the environment.

    Nothing here interpolates a path or a number into the script text, and that is the whole reason
    this function exists. ClipSync lives wherever the user unpacked it, and a folder called `it's
    here`, or one holding a `$`, a backtick or a `"`, is pasted straight into a shell that reads all
    four of those. `$env:NAME` is the one form PowerShell does not re-parse.
    """
    env = dict(os.environ)
    env.update(variables or {})
    return subprocess.run(["powershell", "-NoProfile", "-NonInteractive", "-Command", script],
                          capture_output=True, text=True, creationflags=_NO_WINDOW, env=env)


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


def _service_pids(script: str) -> list:
    """The pids of any service process running THIS copy, using the same match as the kill below."""
    result = _powershell(
        "$s = $env:CLIPSYNC_SCRIPT.ToLower(); "
        "Get-CimInstance Win32_Process -Filter \"Name = 'python.exe' OR Name = 'pythonw.exe'\" | "
        "Where-Object { $_.CommandLine -and $_.CommandLine.ToLower().Contains($s) } | "
        "ForEach-Object { $_.ProcessId }",
        {"CLIPSYNC_SCRIPT": script})
    pids = []
    for line in (result.stdout or "").splitlines():
        line = line.strip()
        if line.isdigit():
            pids.append(int(line))
    return pids


def _wait_gone(script: str, timeout: float = 8.0) -> bool:
    """
    Block until the old service process has actually exited.

    **This is not tidiness, it is the restart working at all.** `schtasks /End` asks the task to
    stop and returns immediately; `Stop-Process` returns once the signal is delivered, not once the
    process is gone. The service holds a named mutex for as long as it lives, and the new instance
    checks that mutex on the first line of main(), so starting the replacement while the old one is
    still shutting down fails it immediately with "another ClipSync service is already running; this
    one is exiting", for no reason a single well-timed Apply should ever hit.

    Returns False on timeout, and the caller starts the new instance anyway: a stuck old process is
    a worse problem than a duplicate, and the mutex check is what stops the duplicate regardless.
    """
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if not _service_pids(script):
            return True
        time.sleep(0.25)
    return not _service_pids(script)


def restart_service() -> str:
    """
    Restart the running service, and say in one line what was done, which the caller shows.

    Two paths, because the service may or may not have been installed. `schtasks /End` on a task
    that is not running is not an error worth reporting, so its exit code is ignored; `/Run` is the
    one that has to work. Without the task, the process is found by its command line rather than by
    image name: killing every pythonw.exe on the machine to restart one of them is not acceptable.

    Both paths wait for the old process to go before starting the new one; see {@link _wait_gone};
    without that the new instance finds the old one's mutex and exits, and Apply has to be pressed
    twice.
    """
    script = os.path.join(cfgmod.HERE, "clipsync.py")
    if _task_exists():
        _run(["schtasks", "/End", "/TN", TASK_NAME])
        _wait_gone(script)
        result = _run(["schtasks", "/Run", "/TN", TASK_NAME])
        if result.returncode != 0:
            raise RuntimeError((result.stderr or result.stdout).strip() or "schtasks /Run failed")
        return "Scheduled task restarted."

    # THIS copy, by its full path, the way uninstall.ps1 does it. `-like '*clipsync.py*'` matched the
    # bare file name, so restarting this installation also killed a second ClipSync unpacked anywhere
    # else on the PC, and any command line that merely mentioned the file. Literal Contains rather
    # than -like, because a folder name may hold [ ] ? or * which -like reads as a pattern;
    # lowercased on both sides because a command line keeps whatever casing it was launched with,
    # which is not necessarily this window's.
    _powershell(
        "$s = $env:CLIPSYNC_SCRIPT.ToLower(); "
        "Get-CimInstance Win32_Process -Filter \"Name = 'python.exe' OR Name = 'pythonw.exe'\" | "
        "Where-Object { $_.CommandLine -and $_.CommandLine.ToLower().Contains($s) } | "
        "ForEach-Object { Stop-Process -Id $_.ProcessId -Force }",
        {"CLIPSYNC_SCRIPT": script})
    _wait_gone(script)
    subprocess.Popen([_pythonw(), script], cwd=cfgmod.HERE, creationflags=_DETACHED, close_fds=True)
    return "ClipSync restarted (no scheduled task installed; run install.ps1 to start it at logon)."


# ----------------------------------------------------------------------------- config.json's ACL
# config.json holds the PSK in clear, and a file created under %LOCALAPPDATA% or Program Files
# inherits an ACL that lets every account on the PC read it. Three principals are kept: SYSTEM
# because services and backup software expect it, Administrators because an administrator can take
# ownership anyway and pretending otherwise only makes the file hard to repair, and the current user
# because the service runs as them and cannot start without reading this.
#
# It runs after every write and not only at install time, and that is a documented consequence of
# how `write_config` saves: it writes config.json.tmp *in the same directory* and calls os.replace.
# The tmp file is a NEW file, so at creation it picks up that directory's inheritable ACEs; the
# rename then carries them over untouched, because on NTFS "when you move a file or folder, the ACL
# is also moved and is not changed in any way"
# (https://learn.microsoft.com/en-us/troubleshoot/windows-client/windows-security/permissions-on-copying-moving-files).
# So the hardened descriptor does not survive an Apply, because it was never on the file that ends up
# in place, and hardening once at install time would last exactly until the first save. Necessary,
# and sufficient: the window between the rename and this call is the only moment config.json is
# readable, and it is the same window install.ps1 leaves open too.
# (This is also why it cannot live in clipsync_config.write_config: that module is imported by the
# service and is required to be free of side effects beyond the write itself.)
#
# icacls rather than Get-Acl/Set-Acl, run directly rather than through PowerShell. Three reasons,
# and the first is the one that matters here:
#
#   * This window does NOT run elevated, and Set-Acl "changes the values in the item's security
#     descriptor to match the values in the AclObject parameter", the whole descriptor Get-Acl
#     returned, owner included. Writing an owner is a privileged operation that this process has no
#     business attempting and no need for. icacls "displays or modifies discretionary access control
#     lists (DACLs)" and nothing else, and changing a DACL needs only WRITE_DAC, which the file's
#     owner, this user who created it, always has.
#     (https://learn.microsoft.com/en-us/powershell/module/microsoft.powershell.security/set-acl ,
#      https://learn.microsoft.com/en-us/windows-server/administration/windows-commands/icacls)
#   * No PowerShell means no `-Command` string to build, which is the hazard _powershell() exists to
#     work around. subprocess passes argv, so a path holding a quote, a `$` or a backtick is a path.
#   * SIDs, not 'NT AUTHORITY\SYSTEM' and 'BUILTIN\Administrators'. Those are English names that have
#     to be looked up; S-1-5-18 (SYSTEM) and S-1-5-32-544 (Administrators) are the same on every
#     installation in every language. icacls documents the form: "SIDs may be in either numerical or
#     friendly name form. If you use a numerical form, affix the wildcard character * to the
#     beginning of the SID."
#
# /inheritancelevel:r is "disables inheritance and removes only inherited ACEs"; /grant:r replaces
# any explicit ACE for that principal instead of adding to it. Together they are the whole of what
# the old four-line Get-Acl / SetAccessRuleProtection / RemoveAccessRule / AddAccessRule dance did.
_SID_SYSTEM = "*S-1-5-18"
_SID_ADMINISTRATORS = "*S-1-5-32-544"


def _current_account() -> str:
    """This user as DOMAIN\\user, which is what icacls wants and what the SID lookup will resolve.

    %USERDOMAIN% is the computer name on a workgroup PC and the domain on a joined one, the same
    two cases WindowsIdentity.Name covers, which is the form install.ps1 passes as -ForUser. The
    fallback to a bare %USERNAME% is for the case where the variable is missing: icacls resolves an
    unqualified name against the local machine, which is right far more often than it is wrong.
    """
    user = os.environ.get("USERNAME", "")
    domain = os.environ.get("USERDOMAIN") or os.environ.get("COMPUTERNAME")
    return "{}\\{}".format(domain, user) if domain and user else user


def protect_config() -> str:
    """
    Lock config.json down to SYSTEM, Administrators and this user.

    :return: "" when it worked, otherwise the reason. Never raises and never fails an Apply: a
             readable config.json is a working config.json, and refusing to save settings because
             an ACL could not be tightened would trade a real problem for a theoretical one. The
             caller says so in the status line instead.
    """
    if not os.path.exists(cfgmod.CONFIG_PATH):
        return "config.json is not there"
    me = _current_account()
    if not me:
        return "cannot tell which account this is"
    result = _run(["icacls", cfgmod.CONFIG_PATH, "/inheritancelevel:r",
                   "/grant:r", _SID_SYSTEM + ":(F)",
                   "/grant:r", _SID_ADMINISTRATORS + ":(F)",
                   "/grant:r", me + ":(F)"])
    if result.returncode != 0:
        # icacls writes its failures to stderr and still prints a summary on stdout, so both are
        # worth looking at before giving up on a message.
        return (result.stderr or result.stdout).strip() or "icacls failed"
    return ""


# ----------------------------------------------------------------------------- firewall, when the port moves
# The rule names carry the port, exactly as install.ps1 writes them, so this renames as well as
# re-ports: a rule called "ClipSync TCP 47521" that allows 47600 is a lie the next person to read the
# firewall list has no way to catch. Exit code 2 means the rules are not there at all, the usual
# reason being that install.ps1 was never run.
#
# One call does both. -NewDisplayName and -LocalPort sit in the same parameter set (ByDisplayName) in
# Set-NetFirewallRule's published syntax, so renaming and re-porting need neither two passes nor the
# `Get-NetFirewallRule | Set-NetFirewallPortFilter` detour
# (https://learn.microsoft.com/en-us/powershell/module/netsecurity/set-netfirewallrule). The port
# filter is a separate object only for *querying*: that cmdlet's own text says a port "can be queried
# for this condition, modified by using the [filter] object, or both", and modification through
# Set-NetFirewallRule is the documented path, and the one taken here.
#
# The Get-NetFirewallRule guard above each Set is not belt-and-braces. Asking Set-NetFirewallRule for
# a DisplayName that does not exist is an error, and with $ErrorActionPreference = 'Stop' it would
# abort the loop on the first missing rule instead of reporting "none of them are there" as exit 2,
# which is the answer the caller actually turns into a sentence.
_FIREWALL_SCRIPT = (
    # 'Stop' first, because the interesting failure here, access denied since this window is not
    # elevated, is a NON-terminating PowerShell error: without this the cmdlet prints it, the
    # script carries on and exits 0, and the caller, reading the exit code, reports a firewall rule
    # moved that was not. (The ACL path needs no equivalent line because it does not go through
    # PowerShell at all; see protect_config.)
    "$ErrorActionPreference = 'Stop'; "
    "$old = [int]$env:CLIPSYNC_OLD_PORT;$new = [int]$env:CLIPSYNC_NEW_PORT; "
    "$span = [int]$env:CLIPSYNC_PAIR_SPAN; $done = 0; "
    "foreach ($r in @("
    "@{ From = \"ClipSync TCP $old\"; To = \"ClipSync TCP $new\"; Port = \"$new\" }, "
    "@{ From = \"ClipSync pairing $($old + 1)-$($old + $span)\"; "
    "To = \"ClipSync pairing $($new + 1)-$($new + $span)\"; "
    "Port = \"$($new + 1)-$($new + $span)\" })) { "
    "if (-not (Get-NetFirewallRule -DisplayName $r.From -ErrorAction SilentlyContinue)) { continue } "
    "Set-NetFirewallRule -DisplayName $r.From -NewDisplayName $r.To -LocalPort $r.Port; "
    "$done++ } "
    "if ($done -eq 0) { exit 2 }"
)


def move_firewall(old_port: int, new_port: int) -> str:
    """
    Follow a port change in the inbound rules install.ps1 created.

    Without this the service listens on the new port and the firewall still allows the old one, which
    is the worst shape a networking bug takes: everything starts, nothing logs, and the PC is simply
    unreachable from every device that is not on the LAN already.

    :return: "" when the rules were moved, otherwise a line for the user. Changing firewall rules
             needs administrator rights and this window does not have them (the scheduled task runs
             Limited), so "access denied" is an expected answer, not a bug, and the answer to it is
             install.ps1, which elevates and rebuilds the rules from config.json.
    """
    # The span the provider searches and the span install.ps1 opened are one number, so it is taken
    # from the module that owns it rather than written down a third time. Imported here and not at
    # the top: clipsync_pair pulls in cryptography, and a PC missing it must still be able to open
    # this window and fix its settings.
    try:
        from clipsync_pair import PAIR_PORT_SPAN
    except ImportError:
        return "the pairing module could not be loaded, so the rules were left alone"
    result = _powershell(_FIREWALL_SCRIPT, {
        "CLIPSYNC_OLD_PORT": str(old_port), "CLIPSYNC_NEW_PORT": str(new_port),
        "CLIPSYNC_PAIR_SPAN": str(PAIR_PORT_SPAN)})
    if result.returncode == 2:
        return "no ClipSync firewall rules were found for port %d" % old_port
    if result.returncode != 0:
        return (result.stderr or result.stdout).strip() or "Set-NetFirewallRule failed"
    _rename_recorded_rules(old_port, new_port, PAIR_PORT_SPAN)
    return ""


def _rename_recorded_rules(old_port: int, new_port: int, span: int):
    """
    Keep install-state.json naming the rules that now exist.

    uninstall.ps1 removes rules by the names recorded here and nothing else; that precision is the
    point of the state file, so a rename it does not hear about leaves a rule behind forever.
    Best-effort: a state file that is missing or unreadable belongs to install.ps1, and this window
    is not the place to start repairing it.
    """
    renames = {"ClipSync TCP %d" % old_port: "ClipSync TCP %d" % new_port,
               "ClipSync pairing %d-%d" % (old_port + 1, old_port + span):
                   "ClipSync pairing %d-%d" % (new_port + 1, new_port + span)}
    try:
        # utf-8-sig, not utf-8, and this was a silent bug rather than a precaution. install.ps1
        # writes this file with `Set-Content -Encoding UTF8`, and in Windows PowerShell 5.1, the
        # PowerShell that ships with Windows and the one a double-clicked .ps1 gets, "any Unicode
        # encoding, except UTF7, always creates a BOM"; `utf8NoBOM` does not exist before PowerShell
        # 6 (https://learn.microsoft.com/en-us/powershell/module/microsoft.powershell.core/about/about_character_encoding).
        # So the first character is U+FEFF, json.load raises ValueError, the except below swallows
        # it, and the recorded rule names quietly stayed at the old port for ever. utf-8-sig reads
        # the file with or without the mark; the write below stays plain utf-8, which both
        # PowerShell scripts read back happily.
        with open(STATE_PATH, "r", encoding="utf-8-sig") as f:
            state = json.load(f)
        state["rules"] = [renames.get(name, name) for name in state.get("rules") or []]
        with open(STATE_PATH, "w", encoding="utf-8") as f:
            json.dump(state, f, indent=2)
    except (OSError, ValueError, AttributeError, TypeError):
        pass


# ----------------------------------------------------------------------------- small widgets
class Field:
    """A labelled entry with an error line under it. The line takes no space until there is
    something to say: it is gridded normally, then immediately collapsed with grid_remove(), and
    show() toggles it back with a bare grid() call, which restores the same row/column/sticky it
    was given here rather than needing them repeated at every call site."""

    def __init__(self, parent, row, label, *, secret=False, width=44, side_widget=None):
        """`side_widget`, when given, is called with a Frame and is expected to pack a companion
        widget into it with side="right": the entry is packed into the same frame with
        fill="x", expand=True, so the two share column 1's stretched width instead of the entry
        alone claiming all of it, and the companion widget's right edge lands exactly where the
        entry's would have on its own, the same right edge every other field in the column has."""
        self.var = tk.StringVar()
        ttk.Label(parent, text=label).grid(row=row, column=0, sticky="w", padx=PAD, pady=(PAD, 0))
        if side_widget is None:
            self.entry = ttk.Entry(parent, textvariable=self.var, width=width,
                                   show="•" if secret else "")
            self.entry.grid(row=row, column=1, sticky="ew", padx=PAD, pady=(PAD, 0))
        else:
            box = ttk.Frame(parent)
            box.grid(row=row, column=1, sticky="ew", padx=PAD, pady=(PAD, 0))
            self.entry = ttk.Entry(box, textvariable=self.var, width=width,
                                   show="•" if secret else "")
            self.entry.pack(side="left", fill="x", expand=True)
            side_widget(box)
        self.error = ttk.Label(parent, text="", style="Error.TLabel", wraplength=460)
        self.error.grid(row=row + 1, column=1, sticky="w", padx=PAD)
        self.error.grid_remove()

    def show(self, problem) -> bool:
        self.error.configure(text=problem or "")
        (self.error.grid if problem else self.error.grid_remove)()
        return problem is None

    def get(self) -> str:
        return self.var.get().strip()

    def set(self, value):
        self.var.set("" if value is None else str(value))


class AddressRow:
    """One address, with its own remove button and its own error line, because the list is edited one
    entry at a time and an error belongs to the entry that caused it."""

    def __init__(self, parent, value, on_change, on_remove):
        self.frame = ttk.Frame(parent)
        self.var = tk.StringVar(value=value)
        self.var.trace_add("write", lambda *_: on_change())
        self.entry = ttk.Entry(self.frame, textvariable=self.var, width=40)
        self.entry.grid(row=0, column=0, sticky="ew")
        self.button = ttk.Button(self.frame, text="Remove", width=8, command=lambda: on_remove(self))
        self.button.grid(row=0, column=1, padx=(PAD, 0))
        self.error = ttk.Label(self.frame, text="", style="Error.TLabel", wraplength=440)
        self.error.grid(row=1, column=0, columnspan=2, sticky="w")
        self.error.grid_remove()
        self.frame.columnconfigure(0, weight=1)

    def show(self, problem) -> bool:
        self.error.configure(text=problem or "")
        (self.error.grid if problem else self.error.grid_remove)()
        return problem is None

    def get(self) -> str:
        return self.var.get().strip()

    def destroy(self):
        self.frame.destroy()


class AddressList:
    """
    A list of addresses with add, per-row remove and per-row errors: the peer list and the
    own-addresses list, which are the same widget used twice.

    They accept exactly the same things and must not drift into accepting different ones, which is the
    whole reason `check_addresses` takes its differences as parameters rather than existing twice. The two
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
        Per row, with a repeat reported on the second one; the first is not wrong, and marking both
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

    def __init__(self, master, style=None):
        self.root = master
        self.style = style or ttk.Style(master)
        master.title("ClipSync settings")
        # Wider than tall: every row in this form is a label and a field side by side, so width is
        # what the content actually wants, and a square window wraps hint text and button rows that
        # a wider one shows on one line.
        master.minsize(760, 480)
        master.geometry("760x560")

        # The button bar is packed first, before the scrollable body, so that a window shorter than
        # the form squeezes the form and not the row holding Apply and Close. The packer hands out
        # space in declaration order, and the expanding child is the one that should absorb the loss.
        bar = ttk.Frame(master)
        bar.pack(fill="x", side="bottom")

        outer = ttk.Frame(master)
        outer.pack(fill="both", expand=True)

        # A canvas, because the form is taller than a small laptop screen and tkinter has no
        # scrolling container of its own. Its background is read from the active ttk theme rather
        # than left at tk's own default grey, which is a different shade on every theme and would
        # otherwise show as a border around the themed body sitting inside it.
        canvas_bg = self.style.lookup("TFrame", "background") or None
        canvas = tk.Canvas(outer, highlightthickness=0, background=canvas_bg)
        scroll = ttk.Scrollbar(outer, orient="vertical", command=canvas.yview)
        body = ttk.Frame(canvas)
        body.bind("<Configure>", lambda e: canvas.configure(scrollregion=canvas.bbox("all")))
        window = canvas.create_window((0, 0), window=body, anchor="nw")
        canvas.bind("<Configure>", lambda e: canvas.itemconfigure(window, width=e.width))
        canvas.configure(yscrollcommand=scroll.set)
        canvas.pack(side="left", fill="both", expand=True)
        scroll.pack(side="right", fill="y")
        # One line per event, not a magnitude computed from event.delta: a physical wheel reports
        # delta in multiples of 120, but Windows delivers a touchpad's two-finger scroll as the same
        # <MouseWheel> event with much smaller deltas, several times a second, and "-delta // 120"
        # floors most of those to zero. Reading only the sign and moving one unit per event scrolls
        # correctly for both, and the touchpad's own event rate is what makes it feel continuous.
        canvas.bind_all("<MouseWheel>",
                        lambda e: canvas.yview_scroll(-1 if e.delta > 0 else 1, "units"))

        # The bar's widgets are created before _build, because the lists it builds fire on_change as
        # soon as they have a row, and revalidate touches apply_button, paths_error and
        # discovery_error.
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
        # Whether the key on screen is readable is a property of the key field itself, so Show goes
        # beside the entry rather than down with the buttons that act on the key's value. It is
        # passed in as `side_widget` rather than gridded separately in some column of its own,
        # because a separately gridded checkbox is exactly what put it at the WRONG right edge the
        # first time: column 1 stretches to fill the row, so the entry alone had claimed that whole
        # width already, and a checkbox appended after it in another column landed further right
        # still, past where Port's own field ends. Sharing one frame, with the entry set to expand
        # and the checkbox not, gives the two of them together the same right edge Port's field has
        # on its own.
        self.psk_shown = tk.BooleanVar(value=False)
        self.psk = Field(conn, 2, "PSK key (64 hex)", secret=True, width=36,
                         side_widget=lambda box: ttk.Checkbutton(
                             box, text="Show", variable=self.psk_shown, command=self._toggle_psk
                         ).pack(side="right", padx=(4, 0)))

        psk_bar = ttk.Frame(conn)
        psk_bar.grid(row=4, column=1, sticky="w", padx=PAD, pady=(0, PAD))
        ttk.Button(psk_bar, text="Generate random PSK key", command=self.new_psk).pack(side="left")
        # Beside Generate, because they are the two ways to end up with a key and the choice between
        # them is the one the user is making at this field: make one here and hand it out, or take
        # the one a device already has.  Both directions are supported: a provider has to listen,
        # and a listening port on Windows is a firewall rule somebody has to create, but that cost is
        # not a reason to leave out the case where the PC is the device that was set up first.
        ttk.Button(psk_bar, text="Pair with a device…", command=self.pair).pack(side="left", padx=(PAD, 0))
        ttk.Button(psk_bar, text="Share this key…", command=self.share).pack(side="left", padx=(PAD, 0))

        # A single checkbox is the whole of the rotation UI on both platforms, and it controls less
        # than it looks like: rotation runs regardless once a successor exists, because a key set on
        # one device can rotate while the other has this unticked. The flag only decides whether
        # this device *initiates* a new cycle, never whether it finishes one a peer started.
        self.psk_rotate = tk.BooleanVar(value=False)
        ttk.Checkbutton(conn, text="Rotate key automatically",
                        variable=self.psk_rotate, command=self._rotate_toggled
                        ).grid(row=5, column=1, sticky="w", padx=PAD, pady=(0, 0))
        section_row += 1

        # --- This PC's own addresses
        # Above the two switches, not below: the peer list validates against this one, so it has to
        # be the thing already on screen when the user starts typing addresses into the list below.
        # No switch, because an empty list already means "this PC has no name of its own", and turning
        # such a list off could only cause the mistakes it exists to prevent.
        own = ttk.LabelFrame(body, text="This PC's own addresses")
        own.grid(row=section_row, column=0, sticky="ew", padx=PAD, pady=(PAD, 0))
        own.columnconfigure(0, weight=1)
        ttk.Label(own, wraplength=460, style="Muted.TLabel",
                  text="Used to recognise itself and avoid a network loop"
                  ).grid(row=0, column=0, sticky="w", padx=PAD, pady=(PAD, 0))
        self.own_list = AddressList(own, allow_empty=True, on_change=self.revalidate)
        self.own_list.grid(box_row=1, button_row=2)
        section_row += 1

        # --- Local network discovery
        lan = ttk.LabelFrame(body, text="Local network discovery")
        lan.grid(row=section_row, column=0, sticky="ew", padx=PAD, pady=(PAD, 0))
        lan.columnconfigure(1, weight=1)
        self.discovery = tk.BooleanVar(value=True)
        # One direction, and said so: this PC advertises and does not browse. It dials now, using the
        # listed addresses on its own thread each, but it does not look for peers on the LAN, so
        # a phone on the same network is found by finding *us*. That is enough for the pair to
        # connect, since only one of two nodes has to do the finding, and it is the last remaining
        # asymmetry between the two platforms. The label says advertise because that is what the
        # switch does here; claiming the symmetric wording would describe a PC that does not exist.
        ttk.Checkbutton(lan, text="Advertise this PC on the local network (mDNS)",
                        variable=self.discovery, command=self.revalidate
                        ).grid(row=0, column=0, columnspan=2, sticky="w", padx=PAD, pady=(PAD, 0))
        self.mdns_name = Field(lan, 1, "Service name (optional)")
        # The hostname bare, because that is now the default: Android advertises its device name and
        # this end advertised "ClipSync on HOSTNAME", so one LAN showed two conventions in one list.
        ttk.Label(lan, text="Empty means “%s”, this PC's name" % _hostname(), style="Muted.TLabel"
                  ).grid(row=3, column=1, sticky="w", padx=PAD, pady=(0, PAD))
        # The same "turn on at least one" problem as paths_error below: it is one condition on two
        # switches in two different sections, so it is shown next to each switch rather than only
        # next to one, which is the only placement that reads as a problem with THIS control no
        # matter which of the two the user is looking at.
        self.discovery_error = ttk.Label(lan, text="", style="Error.TLabel", wraplength=460)
        self.discovery_error.grid(row=4, column=0, columnspan=2, sticky="w", padx=PAD, pady=(0, PAD))
        self.discovery_error.grid_remove()
        section_row += 1

        # --- Direct connections
        # No supporting paragraph under the switch, matching the Android page: the switch names what
        # it does, each address row explains itself when it is wrong (own address, duplicate, empty),
        # and a static paragraph repeating that in advance is a paragraph nobody reads twice.
        dire = ttk.LabelFrame(body, text="Direct connections")
        dire.grid(row=section_row, column=0, sticky="ew", padx=PAD, pady=(PAD, 0))
        dire.columnconfigure(0, weight=1)
        self.direct = tk.BooleanVar(value=False)
        # Not "Reach this PC at the addresses below": the switch does not make the PC reachable, since
        # DNS and the router do that, and claiming otherwise would have someone ticking it and
        # waiting for a connection that was never going to arrive. What it actually controls is
        # whether the list is used at all, so that is what it says.
        ttk.Checkbutton(dire, text="Use the addresses below",
                        variable=self.direct, command=self._direct_toggled
                        ).grid(row=0, column=0, sticky="w", padx=PAD, pady=(PAD, 0))
        # Everything the switch governs lives in one frame, so turning it off can hide the whole
        # thing in one call instead of hiding each row separately: the Android page does the same,
        # collapsing its own equivalent card away entirely rather than merely greying it out.
        self.direct_body = ttk.Frame(dire)
        self.direct_body.grid(row=1, column=0, sticky="ew")
        self.direct_body.columnconfigure(0, weight=1)
        self.peer_list = AddressList(self.direct_body, allow_empty=False, on_change=self.revalidate)
        self.peer_list.grid(box_row=0, button_row=1)
        self.paths_error = ttk.Label(dire, text="", style="Error.TLabel", wraplength=460)
        self.paths_error.grid(row=2, column=0, sticky="w", padx=PAD, pady=(0, PAD))
        self.paths_error.grid_remove()
        section_row += 1

        # --- Relay: its own section, not folded into Direct connections, because declining a relay
        # is not about the address list at all. A device found only through LAN discovery can still
        # be asked to relay for a peer it can see and another peer cannot, so this switch matters
        # regardless of whether Direct connections is on, and stays visible either way, the same as
        # on the Android page, where it lives in its own card rather than inside the address list's.
        relay = ttk.LabelFrame(body, text="Relay")
        relay.grid(row=section_row, column=0, sticky="ew", padx=PAD, pady=(PAD, 0))
        relay.columnconfigure(0, weight=1)
        # The power-saving control, restated for a machine that has no battery. It matters here
        # anyway, because "the LAN's relay" is not a role anyone is given; it is whichever node the
        # priority order puts first, and a PC on mains wins that every time. This is how a PC
        # declines it: the service declares `persistent: false`, which takes it out of everyone
        # else's election, and refuses any request that arrives regardless.
        self.relay_opt_out = tk.BooleanVar(value=False)
        ttk.Checkbutton(relay, text="Decline relay requests",
                        variable=self.relay_opt_out, command=self.revalidate
                        ).grid(row=0, column=0, sticky="w", padx=PAD, pady=PAD)
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
        ttk.Label(rec, text="Relative paths are resolved next to clipsync.py", style="Muted.TLabel"
                  ).grid(row=2, column=1, sticky="w", padx=PAD)
        self.keep_hours = Field(rec, 3, "Keep unused for (hours, 0 = forever)")
        self.keep_max_mb = Field(rec, 5, "Keep at most (MB, 0 = unlimited)")
        section_row += 1

        # --- Startup
        start = ttk.LabelFrame(body, text="Startup")
        start.grid(row=section_row, column=0, sticky="ew", padx=PAD, pady=(PAD, PAD))
        start.columnconfigure(1, weight=1)
        self.start_delay = Field(start, 0, "Delay after logon (seconds)")
        ttk.Label(start, text="0 is right for a single PC, raise it only if several PCs share this "
                              "config file", style="Muted.TLabel", wraplength=460
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

    _ROTATE_HELP = (
        "Every 48 hours this device makes a new key and passes it to the others, "
        "so a key that leaks is only useful for a few days. Devices that were "
        "switched off still connect, because the last few keys keep working for about "
        "a week. A device left off longer than that has to be paired again."
    )

    def _rotate_toggled(self):
        if self.psk_rotate.get():
            messagebox.showinfo("Rotate key automatically", self._ROTATE_HELP)

    def _direct_toggled(self):
        on = self.direct.get()
        self.peer_list.set_enabled(on)
        (self.direct_body.grid if on else self.direct_body.grid_remove)()
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
        self.psk_rotate.set(cfgmod.as_bool(raw.get("psk_rotate", False)))
        self.mdns_name.set(raw["mdns_name"])
        self.discovery.set(cfgmod.as_bool(raw["discovery"]))
        self.direct.set(cfgmod.as_bool(raw["direct"]))
        # Bytes in the file, friendlier units in the window; the same trade the app makes.
        self.max_bytes.set(_to_unit(raw["max_bytes"], 1024))
        self.max_file.set(_to_unit(raw["max_file_bytes"], 1024 * 1024))
        self.max_file_local.set(_to_unit(raw["max_file_bytes_local"], 1024 * 1024))
        self.files_dir.set(raw["files_dir"])
        self.keep_hours.set(raw["keep_hours"])
        self.keep_max_mb.set(raw["keep_max_mb"])
        self.start_delay.set(raw["start_delay"])
        self.relay_opt_out.set(cfgmod.as_bool(raw.get("relay_opt_out", False)))

        self.own_list.set_values(cfgmod.as_list(raw["own_addresses"]))
        self.peer_list.set_values(cfgmod.as_list(raw["peers"]))
        # Sets the body's visibility to match the switch just loaded, the same call the checkbox
        # itself uses, so a config file loaded with direct off opens with the list already collapsed
        # rather than needing a click to discover that it can be.
        self._direct_toggled()
        if fresh:
            # Nothing is committed to the repository and nothing ships in the zip: this window is
            # where config.json comes from. Saying so is the difference between "the defaults are
            # loaded" and "there is no file yet and Apply is what creates it".
            self.status.configure(text="No configuration yet; Apply will create %s."
                                       % os.path.basename(cfgmod.CONFIG_PATH))

    def collect(self) -> dict:
        """
        The values in the types config.json stores them as. Numbers that do not parse are left as the
        string the user typed rather than coerced or dropped: check_all is what reports them, in its
        own words, and a field cannot be fixed if Apply has already silently replaced it with 0.
        """
        d = {
            "port": _int(self.port.get()),
            "psk": self.psk.get().lower(),
            "psk_rotate": bool(self.psk_rotate.get()),
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
            "relay_opt_out": bool(self.relay_opt_out.get()),
        }
        # A key typed or generated here is a NEW key, so its clock starts now. Without this,
        # Apply would write a fresh key over an old activation time, and rotation would
        # pre-retire it within minutes. Only when the key actually changed.
        try:
            old_raw = cfgmod.read_config()
            old_psk = str(old_raw.get("psk", "")).strip().lower()
        except (ValueError, OSError):
            old_psk = ""
        if d["psk"] != old_psk:
            d["psk_since"] = int(time.time() * 1000)
            d["psk_next"] = ""
            d["psk_old"] = []
            d["psk_retire"] = 0
            d["psk_agreed"] = 0
        return d

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
            message = ("Turn on at least one of these. With both off there is no way to reach "
                       "another device.")
            self._set_error(self.discovery_error, message)
            self._set_error(self.paths_error, message)
            ok = False
        else:
            self._set_error(self.discovery_error, "")
            self._set_error(self.paths_error, "")

        self.apply_button.configure(state="normal" if ok else "disabled")
        return ok

    @staticmethod
    def _set_error(label, text):
        """Set a top-level error label's text and, like Field/AddressRow, collapse it to no height
        at all when there is nothing to say rather than leaving a blank line in its place."""
        label.configure(text=text)
        (label.grid if text else label.grid_remove)()


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

    def _busy(self, note):
        """
        Say what this window is about to block on, and get that onto the screen before it does.

        The update() is the whole point and is not a formality: tk repaints from its event loop, and
        the next thing the caller does is stop returning to it for several seconds.  Without a flush
        the label would be set and drawn afterwards, which is to say drawn once the wait it was
        describing had finished.

        Restores rather than clears, because the bar is also where apply() leaves its result, and a
        pairing that ran afterwards should not silently erase "Saved."
        """
        if note is None:
            self.status.configure(text=getattr(self, "_status_was", ""))
            self.root.config(cursor="")
            return
        self._status_was = self.status.cget("text")
        self.status.configure(text=note)
        self.root.config(cursor="watch")
        self.root.update()

    def pair(self):
        """
        Take the key from a device that already has it.

        Three things here block, tkinter has one thread, and they are not the same length, so each
        one says what it is before it starts, in the status bar, with an explicit update() to get
        that repaint out before the thread stops answering.  A progress bar would need a second
        thread to drive it and would still be indeterminate; naming the step is worth more.

        The browse is a fixed four seconds.  The key derivation is well under a second here, native
        scrypt on both ends (see clipsync_pair.channel_key), but that is still too long to spend
        without saying so, which is why this end announces it rather than relying on the wait being
        short; Android runs it on a worker for the same reason.  The third is the one
        that is easy to miss: after PAIR_ASK, `join` waits up to ASK_TIMEOUT + CONNECT_TIMEOUT for a
        person at the *other* device to approve this PC, which is twenty-five seconds of a window
        that cannot repaint.  Unattended it will run the whole way.  That is why it is announced
        rather than hidden behind a watch cursor: a frozen window with no explanation is the one
        outcome where the user's reasonable next move is to kill the process mid-pairing.
        """
        if cfgmod.check_psk(self.psk.get()) is None and not messagebox.askokcancel(
                "Replace the key?",
                "This PC already has a key. Pairing replaces it, and this PC will stop connecting "
                "to anything still using the old one.", icon="warning", parent=self.root):
            return
        try:
            import clipsync_pair
        except ImportError as e:                    # pragma: no cover - a broken install
            messagebox.showerror("Cannot pair", str(e), parent=self.root)
            return

        self._busy("Looking for devices offering to pair…")
        try:
            found = clipsync_pair.find()
        except RuntimeError as e:
            messagebox.showerror("Cannot pair", str(e), parent=self.root)
            return
        finally:
            self._busy(None)

        if not found:
            # Two causes, both named. `find` hides devices whose advertised protocol version this
            # build cannot speak, so "nothing is offering" is not the only way to get here, and a
            # message that only mentions the window would send someone to re-open a window that was
            # open all along.
            messagebox.showinfo(
                "Nothing found",
                "No device is offering to pair on this network.\n\nOn the other device open "
                "Settings and tap “Pair new devices”, then try again while its code is showing.\n\n"
                "If it is showing a code already, the two are probably running different versions "
                "of ClipSync, so update both and try again.",
                parent=self.root)
            return

        name, addrs, salt = found[0] if len(found) == 1 else self._choose(found)
        if name is None:
            return
        code = self._ask_code(name)
        if not code:
            return

        # Named for the part of this that takes the time, which is not the cryptography: the
        # derivation is sub-second on a PC, and then this thread sits on a socket for as long as
        # ASK_TIMEOUT allows while somebody at the other device decides. The user of *this* window
        # should be looking at that device, not at this one.
        self._busy("Waiting for %s to allow it…" % name)
        try:
            answer = clipsync_pair.join(addrs, salt, code, socket.gethostname().split(".")[0])
        except Exception as e:                      # noqa: BLE001 - every failure is the user's to read
            messagebox.showerror("Pairing failed", str(e), parent=self.root)
            return
        finally:
            self._busy(None)

        bad = cfgmod.check_psk(answer.get("psk", ""))
        if bad is not None:
            messagebox.showerror("Pairing failed", "The key it sent is not usable: %s" % bad, parent=self.root)
            return
        # Into the fields, not straight to disk: Apply is what writes, everywhere else in this
        # window, and pairing is a configuration change like any other.  It also leaves the user one
        # visible step from undoing it.
        #
        # NOT revealed, unlike `new_psk`: a generated key has to be shown because the user is about
        # to copy it onto another device by hand, and a paired one arrived over the wire with nobody
        # needing to read it.  Putting a key on screen that nobody asked to see is a shoulder and a
        # screen recording away from giving it away, in a window that may well be shared while
        # somebody is being talked through setting this up.
        self.psk.set(answer["psk"])
        self.discovery.set(True)
        # The port comes with the key, and is taken only if it is one: a provider that sends nonsense
        # must not be able to point this PC at a port nothing is listening on, and leaving the
        # current value alone is the failure that is easy to see and easy to fix.
        shared = str(answer.get("port", "")).strip()
        if shared and cfgmod.check_port(shared) is None:
            self.port.set(shared)
        self.revalidate()
        messagebox.showinfo(
            "Paired",
            "Got the key from {}.\n\nPress Apply to save it and restart the service.".format(
                answer.get("device", name)),
            parent=self.root)

    def share(self):
        """
        Offer this PC's key to a device that does not have one.

        The other half of `pair`, and the reason the original "Windows only ever joins" did not
        survive contact: the PC is perfectly capable of being the device that was set up first, and
        the only thing standing in the way was a firewall rule, which install.ps1 now makes.
        """
        psk = self.psk.get().strip()
        if cfgmod.check_psk(psk) is not None:
            messagebox.showinfo(
                "No key yet",
                "Generate a key first, or pair with a device that already has one; there is "
                "nothing to share until then.", parent=self.root)
            return
        try:
            import clipsync_pair
        except ImportError as e:                    # pragma: no cover - a broken install
            messagebox.showerror("Cannot share", str(e), parent=self.root)
            return

        win = tk.Toplevel(self.root)
        win.title("Share this key")
        win.transient(self.root)
        state = {"provider": None, "tick": None, "over": False}

        ttk.Label(win, wraplength=380, text="On each new device, choose “I have another ClipSync "
                                           "device”, pick this PC, and enter this code").grid(
            row=0, column=0, sticky="w", padx=PAD, pady=(PAD, 0))
        # Big, monospaced and grouped three-and-three: it is read across a room and typed on a phone
        # in the other hand, and nine digits that run together are nine digits typed wrong.  The two
        # spaces are put in by clipsync_pair.format_code and go no further than this label: what
        # the provider derived its channel key from, and what the phone must end up with, is the
        # nine digits.
        #
        # Greyed DIGITS to start, not dashes: the same length in the same font, so the window does
        # not resize when the real code arrives. (The key derivation is a deliberately slow scrypt
        # and cannot have finished by now.) Digits, because a dash and a digit do not draw to
        # the same height.  Grouped here too, for the same reason the count matches: eleven
        # characters against nine is a fifth of the line.
        # The same placeholder digits as the Android sheet (strings.xml, pair_code_placeholder), so
        # that a screenshot of either end is recognisable as the same state rather than looking like
        # two different bugs.  Sliced to CODE_DIGITS rather than written out, so it stays the right
        # length even if that constant moves again, which it has, three times.
        code_label = ttk.Label(win, text=clipsync_pair.format_code(PLACEHOLDER_CODE[:_code_digits()]),
                               font=("Consolas", 28), style="Placeholder.TLabel")
        code_label.grid(row=1, column=0, padx=PAD, pady=(PAD, 0))
        status = ttk.Label(win, text="Starting", style="Muted.TLabel")
        status.grid(row=2, column=0, sticky="w", padx=PAD, pady=(0, PAD))
        # One line per device, appended under the status while the window stays open.
        given = ttk.Label(win, text="", style="Success.TLabel", justify="left")
        given.grid(row=3, column=0, sticky="w", padx=PAD, pady=(0, PAD))
        state["given"] = []
        # Why the last caller did not get the key. Its own line, because the one above it is
        # rewritten every second by the countdown, and because "a device with a different version
        # tried" and "somebody is guessing codes" are two different pieces of news that deserve to
        # be told apart rather than surfaced only once, silently, when the fifth failure closes the
        # window.
        trouble = ttk.Label(win, text="", style="Warning.TLabel", wraplength=380, justify="left")
        trouble.grid(row=4, column=0, sticky="w", padx=PAD, pady=(0, PAD))

        def finish(message, over=True):
            state["over"] = over
            if state["tick"] is not None:
                win.after_cancel(state["tick"])
                state["tick"] = None
            status.config(text=message)
            code_label.config(text="")

        def close():
            if state["provider"] is not None:
                state["provider"].close()
            if state["tick"] is not None:
                win.after_cancel(state["tick"])
            win.destroy()

        bar = ttk.Frame(win)
        bar.grid(row=5, column=0, sticky="e", padx=PAD, pady=(0, PAD))
        ttk.Button(bar, text="Close", command=close).pack(side="left")
        win.protocol("WM_DELETE_WINDOW", close)
        self._centre(win)
        win.update()

        # Callbacks arrive on the provider's own thread; `after(0, ...)` is what hands them to tk,
        # which has exactly one and does not forgive being touched from another.
        def gave(device):
            # Appended, not replacing: the code is still valid and still on screen, so the window
            # goes on serving whoever else walks up to it.
            state["given"].append(device)
            given.config(text="\n".join("%s has the key" % d for d in state["given"]))

        def paired(device, _type):
            win.after(0, lambda: gave(device))

        def ask(device, kind):
            """
            The confirmation the key is held back for. Runs on the provider's thread; the dialog it
            needs can only run on tk's, so it is posted there and this waits for the answer.

            Knowing the code is not the same as being invited: a caller that guessed it, or the
            neighbour's phone that happened to be pointed at this PC, gets as far as a device name
            on screen. That is the whole change to the threat model, because the code stops being an
            authorisation and goes back to being a channel key.
            """
            answer = {"ok": False}
            done = threading.Event()

            def prompt():
                try:
                    answer["ok"] = messagebox.askokcancel(
                        "Give the key to this device?",
                        "“{}” ({}) has entered the code and is asking for this PC's key.\n\n"
                        "If that is not a device you are setting up right now, choose Cancel: "
                        "the key is everything, and anyone holding it can read what you copy."
                        .format(device, kind), icon="warning", parent=win)
                finally:
                    done.set()

            try:
                win.after(0, prompt)
            except tk.TclError:
                return False            # the window has been closed: there is nobody to approve
            # A caller must not be able to hold the key hostage while nobody is at the PC, so an
            # unanswered prompt is a refusal. The dialog itself stays up, since tkinter has no way to
            # take back a modal box, and an answer that arrives after this returns simply lands
            # nowhere, by which time the socket is closed and the line below has said so.
            if not done.wait(clipsync_pair.ASK_TIMEOUT):
                return False
            return answer["ok"]

        # Keyed by clipsync_pair's ATTEMPT_* outcomes. Each one is a different thing to do next,
        # which is the reason they are told apart at all.
        trouble_text = {
            clipsync_pair.ATTEMPT_CODE:
                "Somebody entered a wrong code. After %d this window closes." % clipsync_pair.MAX_TRIES,
            clipsync_pair.ATTEMPT_VERSION:
                "A device with a different ClipSync version tried to pair; its code was right. "
                "Update both ends to the same release.",
            clipsync_pair.ATTEMPT_NETWORK:
                "A device started pairing and its connection dropped. It can simply try again; "
                "this did not count against the code.",
            clipsync_pair.ATTEMPT_PROTOCOL:
                "Something connected that did not speak ClipSync pairing.",
            clipsync_pair.ATTEMPT_DECLINED:
                "You refused a device. The key was not sent.",
        }

        def attempted(outcome, detail):
            text = trouble_text.get(outcome, detail)
            win.after(0, lambda: trouble.config(text=text))

        def closed(burned):
            win.after(0, lambda: finish(
                "Too many wrong codes, so the window was closed. Try again for a new code." if burned
                else "Finished. Every device that took the key is connecting now."
                if state["given"] else "Nobody joined before the code expired."))

        # The field, if it holds a valid port, rather than the saved file: the user may be mid-edit,
        # and the range install.ps1 opened is keyed to whatever port they are about to apply.
        typed = self.port.get()
        base = int(typed) if cfgmod.check_port(typed) is None else 47521
        try:
            p = clipsync_pair.Provider(psk, base, socket.gethostname().split(".")[0],
                                       ask, paired, closed, attempted)
        except Exception as e:                      # noqa: BLE001 - the message is the user's to read
            finish("Could not open a pairing window: %s" % e)
            return
        state["provider"] = p
        # Real now, and no longer greyed.  format_code and not p.code: p.code is the nine digits the
        # channel key was derived from, and the spaces are added here, for this label, only.
        code_label.config(text=clipsync_pair.format_code(p.code), style="CodeReal.TLabel")

        def tick():
            left = max(0.0, p.closes_at - time.monotonic())
            # Rounded UP and scheduled to the boundary: a truncating countdown skips a second on its
            # very first tick (0.99 s of the window has gone, so it shows one fewer than the number
            # the user just read), and a flat one-second interval drifts against the real deadline,
            # so the last few seconds stop agreeing with the device at the other end of the code.
            secs = int(left) + (1 if left % 1 else 0)
            status.config(text="Waiting, %d s left" % secs)
            if secs <= 0 or state["over"]:
                return
            state["tick"] = win.after(int((left - (secs - 1)) * 1000), tick)

        tick()

    def _centre(self, win):
        """
        Put a child window over the middle of this one.

        `transient` alone does not place it: it only ties the two together for stacking and the
        taskbar, so a Toplevel lands wherever the window manager feels like, which on Windows is
        the top-left of the desktop.  Which is nowhere near the window the user is looking at.

        update_idletasks first, because a window that has not been laid out reports 1x1 and would be
        centred as though it were a point.
        """
        win.update_idletasks()
        w, h = win.winfo_width(), win.winfo_height()
        x = self.root.winfo_rootx() + (self.root.winfo_width() - w) // 2
        y = self.root.winfo_rooty() + (self.root.winfo_height() - h) // 3   # a third: above centre reads better
        win.geometry("+%d+%d" % (max(0, x), max(0, y)))

    def _choose(self, found):
        """Which device, when more than one is offering. Returns (None, None, None) if cancelled."""
        win = tk.Toplevel(self.root)
        win.title("Pair with which device?")
        win.transient(self.root)
        picked = {"i": None}
        ttk.Label(win, text="Found these. Pick the one showing a code.").grid(
            row=0, column=0, sticky="w", padx=PAD, pady=(PAD, 0))
        # ttk has no listbox. A single-column Treeview with the tree column as the only column
        # ("show='tree'") serves the same purpose: one row per device, one selectable at a time,
        # drawn in the theme's own selection colour.
        box = ttk.Treeview(win, columns=(), show="tree", height=min(6, len(found)),
                           selectmode="browse")
        box.column("#0", width=320, stretch=True)
        for i, (name, _, _) in enumerate(found):
            box.insert("", "end", iid=str(i), text=name)
        if found:
            box.selection_set("0")
            box.focus("0")
        box.grid(row=1, column=0, sticky="ew", padx=PAD, pady=PAD)

        def ok():
            sel = box.selection()
            picked["i"] = int(sel[0]) if sel else None
            win.destroy()

        bar = ttk.Frame(win)
        bar.grid(row=2, column=0, sticky="e", padx=PAD, pady=(0, PAD))
        ttk.Button(bar, text="Cancel", command=win.destroy).pack(side="left")
        ttk.Button(bar, text="Continue", command=ok).pack(side="left", padx=(PAD, 0))
        win.columnconfigure(0, weight=1)
        # Placed, then made modal, then waited on, in that order.  grab_set() before the window has
        # been laid out takes the pointer to wherever it currently is, which with no placement is the
        # corner of the desktop.
        self._centre(win)
        win.grab_set()
        box.focus_set()
        self.root.wait_window(win)
        return found[picked["i"]] if picked["i"] is not None else (None, None, None)

    def _ask_code(self, name):
        """
        The digits, typed here.

        Its own window rather than simpledialog, for one reason: the count has to be exact. The code
        is the whole authentication, and a field that accepts one digit short and fails at the
        handshake would spend one of the provider's five attempts on a typo this could have caught.

        The length comes from clipsync_pair rather than being written out here, because it is a
        protocol constant shared with Pairing.java: a window that asks for eight digits while the
        provider generates nine is a pairing that cannot succeed and says "wrong code" about it.
        (That is not hypothetical; the count has been six, then nine, then eight, and is nine
        again.)

        The count is of DIGITS.  The other device shows the code grouped, as "123 456 789", so what
        gets typed in here usually has two spaces in it, and `ok` takes every space back out before
        it counts or returns anything.  Nothing downstream of this window ever sees the grouping:
        clipsync_pair.join refuses a code that is not exactly nine ASCII digits, on purpose.
        """
        digits = _code_digits()
        win = tk.Toplevel(self.root)
        win.title("Enter the code")
        win.transient(self.root)
        out = {"code": None}
        ttk.Label(win, wraplength=360,
                  text="Enter the %d-digit code shown on %s" % (digits, name)).grid(
            row=0, column=0, columnspan=2, sticky="w", padx=PAD, pady=(PAD, 0))
        var = tk.StringVar()
        # Wide enough for the grouped form the user is looking at (digits + 2 spaces) and then some,
        # not for the bare digits: a field that visibly runs out halfway through reads as a field
        # that is refusing the thing being typed into it.
        entry = ttk.Entry(win, textvariable=var, width=digits + 6, font=("Consolas", 16))
        entry.grid(row=1, column=0, columnspan=2, sticky="w", padx=PAD, pady=PAD)
        entry.focus_set()
        note = ttk.Label(win, style="Error.TLabel", text="")
        note.grid(row=2, column=0, columnspan=2, sticky="w", padx=PAD)

        def ok():
            # Every space dropped rather than refused: the other device SHOWS the code as
            # "123 456 789", so the grouped form is the form the user is copying, and refusing it
            # would be refusing what we told them to type.  All
            # whitespace, not just U+0020, because a code pasted out of a chat message can arrive with
            # a newline or a non-breaking space on it.
            #
            # What leaves this function is the nine digits.  The grouping never goes any further:
            # clipsync_pair.channel_key would derive a different key from the spaced string and the
            # only symptom would be "wrong code" for a code that was right.
            code = re.sub(r"\s+", "", var.get())
            if not re.fullmatch(r"\d{%d}" % digits, code):
                note.config(text="%d digits" % digits)
                return
            out["code"] = code
            win.destroy()

        bar = ttk.Frame(win)
        bar.grid(row=3, column=0, columnspan=2, sticky="e", padx=PAD, pady=PAD)
        ttk.Button(bar, text="Cancel", command=win.destroy).pack(side="left")
        ttk.Button(bar, text="Pair", command=ok).pack(side="left", padx=(PAD, 0))
        win.bind("<Return>", lambda _e: ok())
        self._centre(win)
        win.grab_set()
        entry.focus_set()
        self.root.wait_window(win)
        return out["code"]

    def apply(self):
        if not self.revalidate():
            return
        # Read before the write, because after it there is nothing left to compare against: the
        # firewall rules name the OLD port and that name is the only handle on them.
        old_port = self._saved_port()
        try:
            # collect() returns only the fields this window owns, which is exactly what
            # write_config expects: it merges its argument over the file rather than replacing it,
            # so the key rotation state this form cannot show is preserved by the function itself,
            # not by this call site remembering to carry it along.
            cfgmod.write_config(self.collect())
        except (OSError, ValueError) as e:
            # ValueError as well as OSError: write_config reads the existing file before writing, so
            # a config.json that is not valid JSON stops the save rather than being silently replaced
            # by whatever this window happens to be showing. That is the right way round, since the
            # file holds a PSK and a rotation state this form cannot reconstruct, but it has to arrive
            # as a dialog rather than as a traceback nobody sees under pythonw.
            messagebox.showerror("Could not save", str(e), parent=self.root)
            return
        acl = protect_config()

        new_port = _int(self.port.get())
        if old_port is not None and isinstance(new_port, int) and new_port != old_port:
            problem = move_firewall(old_port, new_port)
            if problem:
                messagebox.showwarning(
                    "The firewall still allows the old port",
                    "The port was changed from {} to {}, but the inbound rules could not be "
                    "moved:\n\n{}\n\nDevices on this LAN and on the internet will not reach this PC "
                    "until they are. Run install.ps1, which asks for administrator rights and "
                    "rebuilds the rules from config.json.".format(old_port, new_port, problem),
                    parent=self.root)
        try:
            note = restart_service()
        except Exception as e:                      # noqa: BLE001 - the save already succeeded
            messagebox.showwarning(
                "Saved, but not restarted",
                "config.json was written. Restarting the service failed:\n\n{}\n\nIt will pick the "
                "new settings up at the next logon.".format(e), parent=self.root)
            self.status.configure(text="Saved. Restart failed.")
            return
        # The ACL is reported here and not in a dialog: it is the difference between a key only this
        # account can read and one every account can, which is worth a line and is not worth an OK
        # button in front of a save that worked.
        self.status.configure(text="Saved. " + note + ("" if not acl else " Key file not locked down: " + acl))

    def _saved_port(self):
        """The port in the file on disk, or None if it cannot be read, which is not an error here:
        a first Apply has no old port, and nothing that follows from one applies."""
        try:
            value = cfgmod.read_config().get("port")
            return int(str(value).strip())
        except (ValueError, TypeError, OSError, AttributeError):
            return None


# The greyed digits shown where a pairing code will appear, sliced to CODE_DIGITS at the point of
# use. Kept in step with pair_code_placeholder in the Android strings.xml, because it is the same
# screen in the same state, and two different placeholders would read as two different states.
# Digits and not dashes for the reason given at the label itself: a dash and a digit do not draw to
# the same height.
PLACEHOLDER_CODE = "1145141919810"


def _code_digits() -> int:
    """
    How many digits a pairing code has, from the module that also generates and derives from them.

    No fallback value: every caller is inside a pairing flow that already imported clipsync_pair, so
    the import cannot fail here, and a hardcoded default is exactly the second copy of a protocol
    constant that this window exists to avoid having.
    """
    from clipsync_pair import CODE_DIGITS
    return CODE_DIGITS


def _hostname() -> str:
    try:
        return socket.gethostname()
    except OSError:
        return "this PC"


def _int(value):
    """A whole number where one parses, otherwise the text untouched; see collect()."""
    try:
        return int(str(value).strip())
    except ValueError:
        return str(value).strip()


def _to_unit(value: str, unit: int) -> str:
    """
    Bytes from the file into KB or MB for display, and back again losslessly.

    A byte count that is not a whole number of units is shown as a fraction rather than as the raw
    byte count. Showing the raw bytes would be a trap: the box is labelled KB, `_from_unit`
    multiplies whatever is in it, and `max_bytes = 2000` would come back as 2048000, a silent
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
    """Re-express a byte range in the unit its field is typed in, so "must be 1048576-4294967296
    bytes" under a box labelled MB becomes "must be 1-4096 MB"."""
    units = {"max_bytes": (1024, "KB"), "max_file_bytes": (1024 * 1024, "MB"),
             "max_file_bytes_local": (1024 * 1024, "MB")}
    if problem is None or key not in units:
        return problem
    unit, name = units[key]
    match = re.fullmatch(r"must be (\d+)-(\d+) bytes", problem)
    if not match:
        return problem
    return "must be {}-{} {}".format(int(match.group(1)) // unit, int(match.group(2)) // unit, name)


def main():
    root = tk.Tk()
    try:
        root.tk.call("tk", "scaling", root.winfo_fpixels("1i") / 72.0)
    except tk.TclError:
        pass
    style = _apply_theme(root)
    App(root, style)
    root.mainloop()


if __name__ == "__main__":
    main()
