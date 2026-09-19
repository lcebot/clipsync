# Reverts exactly what install.ps1 did, as recorded in install-state.json: the scheduled task and
# the firewall rules it created, plus any clipsync.py still running.
#
# Python packages are NOT removed, only listed. See the note further down -- that is a decision, not
# an omission. Config and log are left alone too.
#
# Run it however you like; it asks for administrator rights itself.

# See install.ps1: an empty param() with [CmdletBinding()] is what makes the common parameters
# (-Verbose, -ErrorAction, ...) work on a script that is otherwise double-clicked.
[CmdletBinding()]
param()

# Stop at the first failure. Unlike the installer there is nothing to roll back here, but there is
# something worse to avoid: deleting install-state.json at the end of a run that did not actually
# remove what the file records, which would leave rules nothing knows about any more.
$ErrorActionPreference = 'Stop'

# --- elevation (see install.ps1 for why this is first) -----------------------------------------
$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$isAdmin = ([Security.Principal.WindowsPrincipal]$identity).IsInRole(
    [Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) {
    Write-Host "ClipSync needs administrator rights to remove its firewall rules and scheduled task."
    try {
        Start-Process -FilePath (Get-Process -Id $PID).Path -Verb RunAs -ArgumentList @(
            '-NoExit', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', "`"$PSCommandPath`"")
    } catch {
        Write-Host ""
        Write-Host "Cancelled: nothing was removed." -ForegroundColor Red
        exit 1
    }
    exit 0
}

$here      = $PSScriptRoot
$app       = Join-Path $here 'app'
$stateFile = Join-Path $here 'install-state.json'

if (-not (Test-Path $stateFile)) {
    # No record, so the names install.ps1 uses are reconstructed instead -- from config.json, which
    # is where the port in two of the three names came from in the first place.
    #
    # Named exactly, never 'ClipSync *'. That wildcard was this script's one unbounded action: a
    # rule somebody else created called "ClipSync Remote Desktop", or one an earlier ClipSync made
    # for a port this folder has nothing to do with, matched it and was deleted without being shown.
    # A fallback path is the wrong place to be generous, because it runs precisely when there is no
    # record of what this installation owns.
    $port = 47521
    $conf = Join-Path $app 'config.json'
    if (Test-Path $conf) {
        try {
            $p = (Get-Content $conf -Raw -Encoding UTF8 | ConvertFrom-Json).port
            if ($p) { $port = [int]$p }
        } catch {
            Write-Host "config.json is not valid JSON; assuming the default port $port"
        }
    }
    $names = @("ClipSync TCP $port", "ClipSync pairing $($port + 1)-$($port + 8)", "ClipSync mDNS")
    Write-Host "install-state.json not found - install.ps1 was not run from this folder."
    Write-Host "Falling back to: task 'ClipSync' + the rules it would have made: $($names -join ', ')."
    $state = @{ rules = $names; task = 'ClipSync'; packages = @() }
} else {
    # -Encoding UTF8 for the reason install.ps1 spells out: Windows PowerShell 5.1 falls back to
    # Windows-1252 for a file with no BOM, and this one may have been rewritten by configurator.py,
    # which writes plain UTF-8.
    $state = Get-Content $stateFile -Raw -Encoding UTF8 | ConvertFrom-Json
}

# --- scheduled task ----------------------------------------------------------------------------
if ($state.task) {
    $task = Get-ScheduledTask -TaskName $state.task -ErrorAction SilentlyContinue
    if ($task) {
        if ($task.State -eq 'Running') { Stop-ScheduledTask -TaskName $state.task }
        Unregister-ScheduledTask -TaskName $state.task -Confirm:$false
        Write-Host "task: removed $($state.task)"
    }
}

# --- any clipsync.py still running (started by hand, or a task whose stop did not take) ---------
# Scoped to this folder, so a second copy of ClipSync elsewhere on the PC is not touched. Literal
# Contains rather than -like, because a folder name may hold characters ([ ] ? *) that -like would
# read as a pattern; lowercased on both sides because a command line preserves whatever casing it
# was launched with, which is not necessarily $PSScriptRoot's.
$script = (Join-Path $app 'clipsync.py').ToLower()
$procs = Get-CimInstance Win32_Process -Filter "Name = 'python.exe' OR Name = 'pythonw.exe'" |
         Where-Object { $_.CommandLine -and $_.CommandLine.ToLower().Contains($script) }
foreach ($p in $procs) {
    Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue
    Write-Host "process: killed $($p.Name) pid $($p.ProcessId)"
}

# --- firewall rules ----------------------------------------------------------------------------
# One path now: recorded or reconstructed, the names are exact either way.
$rules = @($state.rules) | Where-Object { $_ } |
         ForEach-Object { Get-NetFirewallRule -DisplayName $_ -ErrorAction SilentlyContinue }
foreach ($r in $rules) {
    Remove-NetFirewallRule -DisplayName $r.DisplayName
    Write-Host "firewall: removed $($r.DisplayName)"
}

if (Test-Path $stateFile) { Remove-Item $stateFile }
Write-Host ""
Write-Host "ClipSync uninstalled."

# --- Python packages: named, never removed -----------------------------------------------------
# install.ps1 recorded what pip added, so this CAN uninstall them. It does not, and the reason is
# that the record is about this machine's pip, not about ClipSync: cryptography and pillow are two
# of the most widely depended-on packages in the ecosystem, and something else on this PC almost
# certainly wants them by now. A cleanup that quietly breaks an unrelated tool is worse than one
# that leaves a few megabytes behind.
#
# So the list is printed as a command the user can copy if they want it, and read if they do not.
# `pip uninstall` asks per package anyway, which is the second chance this deliberately leaves in.
if ($state.packages -and @($state.packages).Count) {
    Write-Host ""
    Write-Host "These Python packages were installed for ClipSync and have been LEFT IN PLACE:"
    Write-Host "  $(@($state.packages) -join ', ')"
    Write-Host ""
    Write-Host "Other programs may use them. To remove them anyway, copy this:"
    Write-Host ""
    Write-Host "    python -m pip uninstall $(@($state.packages) -join ' ')" -ForegroundColor Yellow
    Write-Host ""
}
