# Installs ClipSync: Python packages, firewall rules, and a logon-time scheduled task running
# pythonw.exe (no console window).
#
# Run it however you like -- double-click, plain PowerShell, elevated PowerShell. It needs
# administrator rights and asks for them itself; being unelevated is not a failure, it is just the
# state before the prompt.
#
# Everything it changes is recorded in install-state.json so uninstall.ps1 can revert exactly that
# and nothing else.

$ErrorActionPreference = "Stop"

# --- elevation -------------------------------------------------------------------------------
# Asking is the first thing, before any work: the alternative -- doing what can be done unelevated
# and failing at the first firewall call -- leaves a half-install behind and tells the user to start
# again in a different kind of window, which is a thing a script can simply do for them.
#
# -NoExit on the child, because the elevated window is a NEW window: without it everything this
# prints scrolls past and vanishes at the moment the user most wants to read it.
$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$isAdmin = ([Security.Principal.WindowsPrincipal]$identity).IsInRole(
    [Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) {
    Write-Host "ClipSync needs administrator rights to add firewall rules and a scheduled task."
    try {
        Start-Process -FilePath (Get-Process -Id $PID).Path -Verb RunAs -ArgumentList @(
            '-NoExit', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', "`"$PSCommandPath`"")
    } catch {
        # The user said no. THAT is the failure -- not the lack of rights a moment ago.
        Write-Host ""
        Write-Host "Cancelled: without administrator rights the firewall rules and the scheduled" -ForegroundColor Red
        Write-Host "task cannot be created, so nothing was installed." -ForegroundColor Red
        exit 1
    }
    exit 0
}

$here  = $PSScriptRoot
$app   = Join-Path $here 'app'
$stateFile = Join-Path $here 'install-state.json'
$state = @{ rules = @(); task = $null; packages = @() }

if (-not (Test-Path (Join-Path $app 'clipsync.py'))) {
    throw "app\clipsync.py not found. Run this from the folder that contains app\ and configurator.py."
}

$python = (Get-Command python -ErrorAction SilentlyContinue)
if (-not $python) { throw "Python is not on PATH. Install it from python.org and tick 'Add to PATH'." }
$python  = $python.Source
$pythonw = Join-Path (Split-Path -Parent $python) "pythonw.exe"

# --- Python packages -------------------------------------------------------------------------
# The list lives here rather than in requirements.txt. One file fewer to ship, one fewer to get out
# of step with this script -- and the thing it was for, `pip install -r`, is a line this script can
# simply run.
#
# pillow is genuinely optional: without it, received images are still saved as files, they just
# cannot be pasted as pictures. It is installed with the rest because "optional" is a fact about the
# code, not a decision the user was asked to make.
$packages = @('cryptography>=42', 'zeroconf>=0.132', 'pillow>=10')

# What pip ADDS, not what was asked for: the difference is the dependencies, and those are most of
# what an uninstall would have to name. Taken as a before/after diff because pip has no "what would
# this pull in" that is worth trusting.
function Get-PipNames {
    try { & $python -m pip list --format=json 2>$null | ConvertFrom-Json | ForEach-Object { $_.name } }
    catch { @() }
}
Write-Host "Installing Python packages..."
$before = @(Get-PipNames)
& $python -m pip install --disable-pip-version-check @packages
if ($LASTEXITCODE -ne 0) { throw "pip install failed. Fix the error above and run this again." }
$after = @(Get-PipNames)
$state.packages = @($after | Where-Object { $_ -notin $before })
if ($state.packages.Count) {
    Write-Host "packages added: $($state.packages -join ', ')"
} else {
    Write-Host "packages: everything was already installed"
}

# --- firewall --------------------------------------------------------------------------------
# IPv6 temporary (privacy) addresses are deliberately left as they are: a dynamic DNS client
# registers the temporary address on purpose. When it rotates and the record lags, the phone reaches
# the PC over mDNS on the LAN instead, and the server keeps advertising regardless.
$port = 47521
$conf = Join-Path $app 'config.json'
if (Test-Path $conf) {
    try {
        $p = (Get-Content $conf -Raw | ConvertFrom-Json).port
        if ($p) { $port = [int]$p }
    } catch {
        Write-Host "config.json is not valid JSON; opening the default port $port"
    }
}
# The pairing range, and it has to match clipsync_pair.PAIR_PORT_SPAN: the provider searches these
# ports and nothing else, precisely so that a port it finds free is a port that is open. A wider
# search would find one the firewall has never heard of, bind it, advertise it, and then be
# unreachable in a way that looks like a pairing bug.
$pairFrom = $port + 1
$pairTo   = $port + 8
foreach ($rule in @(
    @{ Name = "ClipSync TCP $port";                 Proto = 'TCP'; Port = "$port" },
    @{ Name = "ClipSync pairing $pairFrom-$pairTo"; Proto = 'TCP'; Port = "$pairFrom-$pairTo" },
    @{ Name = "ClipSync mDNS";                      Proto = 'UDP'; Port = '5353' }
)) {
    if (-not (Get-NetFirewallRule -DisplayName $rule.Name -ErrorAction SilentlyContinue)) {
        New-NetFirewallRule -DisplayName $rule.Name -Direction Inbound -Action Allow `
            -Protocol $rule.Proto -LocalPort $rule.Port -Profile Any | Out-Null
        Write-Host "firewall: allowed inbound $($rule.Proto) $($rule.Port)"
    }
    $state.rules += $rule.Name
}

# --- scheduled task --------------------------------------------------------------------------
$taskName = "ClipSync"
$action   = New-ScheduledTaskAction -Execute $pythonw `
                -Argument "`"$(Join-Path $app 'clipsync.py')`"" `
                -WorkingDirectory $app
$trigger  = New-ScheduledTaskTrigger -AtLogOn -User $env:USERNAME
$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
                -ExecutionTimeLimit ([TimeSpan]::Zero) -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1)

$existing = Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue
if ($existing -and $existing.State -eq 'Running') { Stop-ScheduledTask -TaskName $taskName }
Register-ScheduledTask -TaskName $taskName -Action $action -Trigger $trigger -Settings $settings `
    -RunLevel Limited -Force | Out-Null
$state.task = $taskName
Start-ScheduledTask -TaskName $taskName

# --- record ------------------------------------------------------------------------------------
# Merged with any previous state: re-running install must not forget what an earlier run added.
$firstInstall = -not (Test-Path $stateFile)
if (Test-Path $stateFile) {
    $old = Get-Content $stateFile -Raw | ConvertFrom-Json
    $state.rules    = @(@($old.rules)    + $state.rules    | Where-Object { $_ } | Select-Object -Unique)
    $state.packages = @(@($old.packages) + $state.packages | Where-Object { $_ } | Select-Object -Unique)
}
$state | ConvertTo-Json | Set-Content $stateFile -Encoding UTF8
Write-Host ""
Write-Host "ClipSync is installed and running. Changes are recorded in install-state.json."

# --- first run -----------------------------------------------------------------------------------
# Only the first time. There is nothing useful to say to someone re-running the installer, and a
# window that opens itself on every run is a window that gets closed without being read.
if ($firstInstall) {
    Write-Host "Opening the settings window so you can set a key..."
    Start-Process -FilePath $pythonw -ArgumentList "`"$(Join-Path $here 'configurator.py')`"" `
        -WorkingDirectory $here
}
