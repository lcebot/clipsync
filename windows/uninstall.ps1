# Reverts exactly what install.ps1 did, as recorded in install-state.json:
#   the scheduled task and the firewall rules it created — and kills any running clipsync.py.
# Python packages, config and log are not touched.
# Run once in an elevated PowerShell from this folder.

$ErrorActionPreference = "Stop"
$here      = Split-Path -Parent $MyInvocation.MyCommand.Path
$stateFile = Join-Path $here 'install-state.json'

if (-not (Test-Path $stateFile)) {
    Write-Host "install-state.json not found - install.ps1 was not run from here (or was an older version)."
    Write-Host "Falling back to: task 'ClipSync' + firewall rules named 'ClipSync *'."
    $state = @{ rules = @(); task = 'ClipSync'; legacy = $true }
} else {
    $state = Get-Content $stateFile -Raw | ConvertFrom-Json
}

# --- scheduled task
if ($state.task) {
    $task = Get-ScheduledTask -TaskName $state.task -ErrorAction SilentlyContinue
    if ($task) {
        if ($task.State -eq 'Running') { Stop-ScheduledTask -TaskName $state.task }
        Unregister-ScheduledTask -TaskName $state.task -Confirm:$false
        Write-Host "task: removed $($state.task)"
    }
}

# --- any clipsync.py still running (started by hand, or a task whose stop did not take)
$script = Join-Path $here 'clipsync.py'
$procs = Get-CimInstance Win32_Process -Filter "Name = 'python.exe' OR Name = 'pythonw.exe'" |
         Where-Object { $_.CommandLine -and $_.CommandLine -like "*$script*" }
foreach ($p in $procs) {
    Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue
    Write-Host "process: killed $($p.Name) pid $($p.ProcessId)"
}

# --- firewall rules
$rules = if ($state.legacy) { Get-NetFirewallRule -DisplayName 'ClipSync *' -ErrorAction SilentlyContinue }
         else { $state.rules | ForEach-Object { Get-NetFirewallRule -DisplayName $_ -ErrorAction SilentlyContinue } }
foreach ($r in $rules) {
    Remove-NetFirewallRule -DisplayName $r.DisplayName
    Write-Host "firewall: removed $($r.DisplayName)"
}

if (Test-Path $stateFile) { Remove-Item $stateFile }
Write-Host "ClipSync uninstalled."
