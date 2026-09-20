# Installs ClipSync: Python packages, firewall rules, and a logon-time scheduled task running
# pythonw.exe (no console window).
#
# Run it however you like: double-click, plain PowerShell, or elevated PowerShell. It needs
# administrator rights and asks for them itself; being unelevated is not a failure, it is just the
# state before the prompt.
#
# Everything it changes is recorded in install-state.json so uninstall.ps1 can revert exactly that
# and nothing else.

# An empty param() is not decoration: without it -Verbose, -ErrorAction and the rest of the common
# parameters are not accepted, and a double-clicked script that is later called from another one has
# no way to be made quiet or loud. [CmdletBinding()] is what turns them on.
[CmdletBinding()]
param(
    # The account ClipSync is installed FOR: the one the logon trigger waits for and the one allowed
    # to read the key file. Defaults to whoever started this, and is passed explicitly to the
    # elevated copy below, because that copy may not be the same person. On a standard user's PC,
    # UAC elevation runs as a DIFFERENT account, so $env:USERNAME inside it names the administrator
    # who typed the password, and a task triggered by that account's logon never fires.
    [string] $ForUser = [Security.Principal.WindowsIdentity]::GetCurrent().Name
)

# Stop on the first error rather than carrying on with a half-install. Every step below is
# idempotent, so the answer to a failure is to fix it and run this again, which is only useful if
# the run stops where it broke instead of continuing past it.
$ErrorActionPreference = 'Stop'

# --- elevation -------------------------------------------------------------------------------
# Asking is the first thing, before any work: the alternative, doing what can be done unelevated
# and failing at the first firewall call, leaves a half-install behind and tells the user to start
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
            '-NoExit', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', "`"$PSCommandPath`"",
            '-ForUser', "`"$ForUser`"")
    } catch {
        # The user said no. THAT is the failure, not the lack of rights a moment ago.
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

# Read now rather than at the end, because two sections below need it: the firewall one has to know
# which rules an earlier run created in order to clean up the ones that named a different port, and
# the package list is merged into what was recorded before. $null when there is no file yet, and
# every use of it tolerates that.
#
# -Encoding UTF8 on every read below, config.json included. Windows PowerShell 5.1, the one a
# double-clicked .ps1 runs under, "defaults to Windows-1252 encoding when there's no BOM", and
# config.json is written by Python with no BOM and may hold a files_dir or a device name that is not
# ASCII. Naming the encoding also makes the BOM this script's own Set-Content writes a non-issue on
# the way back in.
# (https://learn.microsoft.com/en-us/powershell/module/microsoft.powershell.core/about/about_character_encoding)
$old = if (Test-Path $stateFile) { Get-Content $stateFile -Raw -Encoding UTF8 | ConvertFrom-Json } else { $null }
$firstInstall = ($null -eq $old)

if (-not (Test-Path (Join-Path $app 'clipsync.py'))) {
    throw "app\clipsync.py not found. Run this from the folder that contains app\ and configurator.py."
}

$python = (Get-Command python -ErrorAction SilentlyContinue)
if (-not $python) { throw "Python is not on PATH. Install it from python.org and tick 'Add to PATH'." }
$python  = $python.Source
$pythonw = Join-Path (Split-Path -Parent $python) "pythonw.exe"

# --- Python packages -------------------------------------------------------------------------
# The list lives here rather than in requirements.txt. One file fewer to ship, one fewer to get out
# of step with this script, and the thing it was for, `pip install -r`, is a line this script can
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
        $p = (Get-Content $conf -Raw -Encoding UTF8 | ConvertFrom-Json).port
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
# One profile per rule, and the differences are deliberate.
#
#   * The service port is the only one that is meant to be reachable from the internet: README's
#     "For Internet use" row is a DDNS name and an inbound hole in the router's IPv6 firewall, and a
#     rule scoped to Private would close that from the inside on every coffee-shop Wi-Fi. Any.
#   * The pairing range carries a window guarded by nine digits and a two-minute clock, and it is
#     only ever used with both devices in the same room. On a public network Any means every other
#     guest can reach it. Private,Domain.
#   * mDNS is link-local by definition: a 5353 rule active on a public profile advertises this PC's
#     name and addresses to a network of strangers and gains nothing at all. Private,Domain.
foreach ($rule in @(
    @{ Name = "ClipSync TCP $port";                 Proto = 'TCP'; Port = "$port";             Profile = 'Any' },
    @{ Name = "ClipSync pairing $pairFrom-$pairTo"; Proto = 'TCP'; Port = "$pairFrom-$pairTo"; Profile = 'Private,Domain' },
    @{ Name = "ClipSync mDNS";                      Proto = 'UDP'; Port = '5353';              Profile = 'Private,Domain' }
)) {
    if (-not (Get-NetFirewallRule -DisplayName $rule.Name -ErrorAction SilentlyContinue)) {
        New-NetFirewallRule -DisplayName $rule.Name -Direction Inbound -Action Allow `
            -Protocol $rule.Proto -LocalPort $rule.Port -Profile $rule.Profile | Out-Null
        Write-Host "firewall: allowed inbound $($rule.Proto) $($rule.Port) on $($rule.Profile)"
    } else {
        # An existing rule is brought up to the profile and port this version wants. Re-running the
        # installer is how a user fixes a firewall, and a rule left at whatever an older version
        # created is one that looks right in the list and is not.
        Set-NetFirewallRule -DisplayName $rule.Name -Protocol $rule.Proto `
            -LocalPort $rule.Port -Profile $rule.Profile | Out-Null
    }
    $state.rules += $rule.Name
}

# Rules an earlier install made for a DIFFERENT port. The port is part of the rule name, so a port
# changed in the settings window leaves the old rule behind, allowing a port nothing listens on,
# forever, with a ClipSync name on it. Recorded names that are not in the set above are the ones
# this installation created and no longer wants, which is exactly the set that is safe to remove.
foreach ($name in @($old.rules)) {
    if ($name -and ($name -notin $state.rules) -and
        (Get-NetFirewallRule -DisplayName $name -ErrorAction SilentlyContinue)) {
        Remove-NetFirewallRule -DisplayName $name
        Write-Host "firewall: removed $name (it named an older port)"
    }
}

# --- the key file ------------------------------------------------------------------------------
# config.json holds the PSK in clear text, and a file under a folder the user unzipped inherits
# whatever that folder allows, which on a machine with several accounts is usually "everyone can
# read". Inheritance is broken and three principals kept: SYSTEM, Administrators (who can take
# ownership regardless, so excluding them only makes the file hard to repair) and the user the
# service runs as, who has to read it to start.
#
# icacls, not Get-Acl/Set-Acl, and the reasons are documented rather than stylistic:
#
#   * Set-Acl "changes the values in the item's security descriptor to match the values in the
#     AclObject parameter": the WHOLE descriptor Get-Acl handed over, owner included, not just the
#     DACL. That is a write nobody here needs and the one that fails when the caller does not own
#     the file. icacls "displays or modifies discretionary access control lists (DACLs)", full stop,
#     and changing a DACL needs only WRITE_DAC, which an owner always has.
#     (https://learn.microsoft.com/en-us/powershell/module/microsoft.powershell.security/set-acl ,
#      https://learn.microsoft.com/en-us/windows-server/administration/windows-commands/icacls)
#   * The principals are given as SIDs. 'NT AUTHORITY\SYSTEM' and 'BUILTIN\Administrators' are
#     English strings that have to be resolved by name; the SIDs are the same everywhere. icacls
#     documents the form: "SIDs may be in either numerical or friendly name form. If you use a
#     numerical form, affix the wildcard character * to the beginning of the SID."
#     S-1-5-18 is SYSTEM, S-1-5-32-544 is the local Administrators group.
#   * /inheritancelevel:r is the documented spelling of "disables inheritance and removes only
#     inherited ACEs"; /grant:r replaces rather than adds to any explicit ACE already there. The two
#     together are the whole of what the four-line Get-Acl dance was doing.
#
# configurator.py does the same thing after every Apply, and that is not a duplicate: see the note
# there: an atomic write replaces the file, and NTFS moves a file's ACL with it unchanged.
if (Test-Path $conf) {
    # $LASTEXITCODE, not $ErrorActionPreference: a native command's failure is not a PowerShell
    # error in Windows PowerShell 5.1, so 'Stop' would sail straight past it. The output is let
    # through to the console rather than captured, because when this does fail the icacls message
    # is the only thing that says why. No `2>&1`: in PowerShell 7 that turns the native stderr into
    # error records, which 'Stop' then throws on, losing the exit code this line is reading.
    & icacls $conf /inheritancelevel:r /grant:r '*S-1-5-18:(F)' '*S-1-5-32-544:(F)' "${ForUser}:(F)"
    if ($LASTEXITCODE -ne 0) { throw "could not lock down config.json (icacls exit $LASTEXITCODE)" }
    Write-Host "config.json: readable only by you, Administrators and SYSTEM"
}

# --- scheduled task --------------------------------------------------------------------------
$taskName = "ClipSync"
$action   = New-ScheduledTaskAction -Execute $pythonw `
                -Argument "`"$(Join-Path $app 'clipsync.py')`"" `
                -WorkingDirectory $app
# $ForUser is DOMAIN\user, and WindowsIdentity.Name "gets the user's Windows logon name", documented
# as being "in the form DOMAIN\USERNAME"
# (https://learn.microsoft.com/en-us/dotnet/api/system.security.principal.windowsidentity.name), and
# on a workgroup machine the domain part is the computer name, which is exactly what Task Scheduler
# wants. It replaces a bare $env:USERNAME, which was wrong twice over: Task Scheduler resolves an
# unqualified name against whatever it takes to be the default authority, and inside an elevated
# copy that name may be an administrator who is not the user at all. Either way the trigger waits
# for a logon that never happens, and the only symptom is that ClipSync does not start.
$trigger  = New-ScheduledTaskTrigger -AtLogOn -User $ForUser
$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
                -ExecutionTimeLimit ([TimeSpan]::Zero) -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1)
# A principal object rather than Register-ScheduledTask's -User/-RunLevel, because it is the only
# way to state the logon type, and the logon type is the difference between this working and not.
#
# `Register-ScheduledTask -User X` with no -Password leaves the choice to Task Scheduler, and the
# choice it may make is S4U: "use an existing interactive token to run a task ... no password is
# stored by the system and there is no access to either the network or encrypted files". That is the
# wrong context for a clipboard: this process opens the clipboard, registers a message-only window
# and listens on a socket, all of which belong to the user's interactive session.
# Interactive == TASK_LOGON_INTERACTIVE_TOKEN: "User must already be logged on. The task will be run
# only in an existing interactive session."
# (https://learn.microsoft.com/en-us/windows/win32/taskschd/principal-logontype). Paired with an
# at-logon trigger, "must already be logged on" is not a restriction at all, since it is the trigger's
# own precondition, and it needs no password, so an administrator can register it for someone else.
#
# RunLevel Limited: the least-privileged token. Nothing here wants elevation, and a clipboard
# listener running as an administrator is a clipboard listener that can be asked to write anywhere.
$principal = New-ScheduledTaskPrincipal -UserId $ForUser -LogonType Interactive -RunLevel Limited

$existing = Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue
if ($existing -and $existing.State -eq 'Running') { Stop-ScheduledTask -TaskName $taskName }
Register-ScheduledTask -TaskName $taskName -Action $action -Trigger $trigger -Settings $settings `
    -Principal $principal -Force | Out-Null
$state.task = $taskName
Start-ScheduledTask -TaskName $taskName

# --- record ------------------------------------------------------------------------------------
# Packages are merged with any previous state: re-running install must not forget what an earlier
# run added, and a package installed once is not installed again for the diff to see.
#
# Rules are NOT merged, and that is the change that makes the pruning above safe: $state.rules is
# the set that exists right now, because anything recorded before and not in it has just been
# removed. Merging would have kept naming rules that are gone, and uninstall.ps1 trusts this list.
if ($old) {
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
