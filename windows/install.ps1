# Opens the firewall and registers a logon-time scheduled task running pythonw.exe (no console
# window). Run once in an elevated PowerShell from this folder.
# Python packages are NOT managed here: `pip install -r requirements.txt` yourself beforehand.
#
# Everything this script changes is recorded in install-state.json so uninstall.ps1 can
# revert exactly that and nothing else.

$ErrorActionPreference = "Stop"
$here  = Split-Path -Parent $MyInvocation.MyCommand.Path
$state = @{ rules = @(); task = $null }
$stateFile = Join-Path $here 'install-state.json'

$python  = (Get-Command python).Source
$pythonw = Join-Path (Split-Path -Parent $python) "pythonw.exe"

# IPv6 temporary (privacy) addresses are deliberately left as they are: the DDNS client registers
# the temporary address on purpose (privacy). When it rotates and the record lags, the phone falls
# back to mDNS on the LAN and the server keeps advertising (see is_ddns_host in clipsync.py).

# --- firewall: TCP port for clients, UDP 5353 so python can answer mDNS queries
$port = 47521
$conf = Join-Path $here 'clipsync.ini'
if (Test-Path $conf) {
    $m = Select-String -Path $conf -Pattern '^\s*port\s*=\s*(\d+)' | Select-Object -First 1
    if ($m) { $port = [int]$m.Matches[0].Groups[1].Value }
}
foreach ($rule in @(
    @{ Name = "ClipSync TCP $port"; Proto = 'TCP'; Port = $port },
    @{ Name = "ClipSync mDNS";      Proto = 'UDP'; Port = 5353 }
)) {
    if (-not (Get-NetFirewallRule -DisplayName $rule.Name -ErrorAction SilentlyContinue)) {
        New-NetFirewallRule -DisplayName $rule.Name -Direction Inbound -Action Allow `
            -Protocol $rule.Proto -LocalPort $rule.Port -Profile Any | Out-Null
        $state.rules += $rule.Name
        Write-Host "firewall: allowed inbound $($rule.Proto) $($rule.Port)"
    }
}

# --- scheduled task
$taskName = "ClipSync"
$action   = New-ScheduledTaskAction -Execute $pythonw `
                -Argument "`"$(Join-Path $here 'clipsync.py')`"" `
                -WorkingDirectory $here
$trigger  = New-ScheduledTaskTrigger -AtLogOn -User $env:USERNAME
$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
                -ExecutionTimeLimit ([TimeSpan]::Zero) -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1)

$existing = Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue
if ($existing -and $existing.State -eq 'Running') { Stop-ScheduledTask -TaskName $taskName }
Register-ScheduledTask -TaskName $taskName -Action $action -Trigger $trigger -Settings $settings `
    -RunLevel Limited -Force | Out-Null
$state.task = $taskName
Start-ScheduledTask -TaskName $taskName

# merge with a previous state file (re-running install must not forget earlier additions)
if (Test-Path $stateFile) {
    $old = Get-Content $stateFile -Raw | ConvertFrom-Json
    $state.rules = @($old.rules + $state.rules | Select-Object -Unique)
}
$state | ConvertTo-Json | Set-Content $stateFile -Encoding UTF8
Write-Host "ClipSync task registered and started. Changes recorded in install-state.json."
