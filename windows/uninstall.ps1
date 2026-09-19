# Reverts exactly what install.ps1 did, as recorded in install-state.json: the scheduled task and
# the firewall rules it created, plus any clipsync.py still running.
#
# Python packages are NOT removed, only listed. See the note further down -- that is a decision, not
# an omission. Config and log are left alone too.
#
# Run it however you like; it asks for administrator rights itself.

$ErrorActionPreference = "Stop"

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
    Write-Host "install-state.json not found - install.ps1 was not run from this folder."
    Write-Host "Falling back to: task 'ClipSync' + firewall rules named 'ClipSync *'."
    $state = @{ rules = @(); task = 'ClipSync'; packages = @(); legacy = $true }
} else {
    $state = Get-Content $stateFile -Raw | ConvertFrom-Json
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
$rules = if ($state.legacy) { Get-NetFirewallRule -DisplayName 'ClipSync *' -ErrorAction SilentlyContinue }
         else { $state.rules | ForEach-Object { Get-NetFirewallRule -DisplayName $_ -ErrorAction SilentlyContinue } }
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
