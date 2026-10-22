#Requires -RunAsAdministrator
<#
.SYNOPSIS
    Installs the TimeLord Agent as a Windows service.

.DESCRIPTION
    Copies timelord-agent.exe (expected next to this script, as shipped in the
    release zip) into $InstallDir, creates $DataDir with ACLs that only
    SYSTEM and Administrators can touch, and registers/starts the
    "TimeLordAgent" service under LocalSystem with automatic restart on
    failure. Also installs timelord-notifier.exe and a "TimeLord Notifier"
    scheduled task that starts it at every user's logon, so users get
    warnings before the device powers off and see administrator messages.
    Safe to re-run: an existing service, task and notifier are replaced.

.PARAMETER InstallDir
    Where the agent binary is copied to. Defaults to Program Files.

.PARAMETER DataDir
    Where the agent stores its SQLite state and logs. Must match what the
    agent itself defaults to (C:\ProgramData\TimeLord) unless you also set
    TIMELORD_DATA_DIR for the service — this script does not do that for you.

.PARAMETER SkipNotifier
    Install only the service, not the per-user notifier and its logon task.

.EXAMPLE
    .\install.ps1
#>
[CmdletBinding()]
param(
    [string]$InstallDir = "$env:ProgramFiles\TimeLord",
    [string]$DataDir = "$env:ProgramData\TimeLord",
    [switch]$SkipNotifier
)

$ErrorActionPreference = "Stop"

$ServiceName = "TimeLordAgent"
$ServiceDisplayName = "TimeLord Agent"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$SourceExe = Join-Path $ScriptDir "timelord-agent.exe"
$NotifierSourceExe = Join-Path $ScriptDir "timelord-notifier.exe"
$NotifierTaskName = "TimeLord Notifier"

if (-not (Test-Path $SourceExe)) {
    throw "timelord-agent.exe not found next to this script ($ScriptDir). Run install.ps1 from the extracted release package."
}

if (-not $SkipNotifier -and -not (Test-Path $NotifierSourceExe)) {
    throw "timelord-notifier.exe not found next to this script ($ScriptDir). Use the full release package, or pass -SkipNotifier."
}

Write-Host "Installing $ServiceDisplayName..."

# --- Stop any running notifier and drop its old task so an upgrade can replace the binary ---
Unregister-ScheduledTask -TaskName $NotifierTaskName -Confirm:$false -ErrorAction SilentlyContinue
Get-Process -Name "timelord-notifier" -ErrorAction SilentlyContinue | Stop-Process -Force

# --- Stop and remove any existing installation so this script is safe to re-run for upgrades ---
$existing = Get-Service -Name $ServiceName -ErrorAction SilentlyContinue
if ($existing) {
    Write-Host "Existing service found; stopping and removing it first."
    if ($existing.Status -ne "Stopped") {
        Stop-Service -Name $ServiceName -Force
        $existing.WaitForStatus("Stopped", "00:00:30")
    }
    sc.exe delete $ServiceName | Out-Null
    Start-Sleep -Seconds 1
}

# --- Install the binary ---
New-Item -ItemType Directory -Force -Path $InstallDir | Out-Null
Copy-Item -Path $SourceExe -Destination (Join-Path $InstallDir "timelord-agent.exe") -Force

if (-not $SkipNotifier) {
    Copy-Item -Path $NotifierSourceExe -Destination (Join-Path $InstallDir "timelord-notifier.exe") -Force
}

# --- Prepare the data directory with restrictive ACLs ---
# Only SYSTEM (the service identity) and Administrators may read or write
# agent state; the logged-in user being monitored must not be able to
# tamper with session history, config, or (once implemented) the pinned
# controller key and lease.
New-Item -ItemType Directory -Force -Path $DataDir | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $DataDir "logs") | Out-Null

icacls $DataDir /inheritance:r | Out-Null
icacls $DataDir /grant "SYSTEM:(OI)(CI)F" | Out-Null
icacls $DataDir /grant "*S-1-5-32-544:(OI)(CI)F" | Out-Null # BUILTIN\Administrators, SID form avoids locale issues
Write-Host "Locked down $DataDir to SYSTEM and Administrators only."

# --- Register the service ---
$binPath = "`"$InstallDir\timelord-agent.exe`""
sc.exe create $ServiceName binPath= $binPath start= auto obj= LocalSystem DisplayName= $ServiceDisplayName | Out-Null
sc.exe description $ServiceName "Tracks and enforces TimeLord device usage policy." | Out-Null

# Restart on failure: 5s, 5s, then 60s backoff, resetting the failure count
# after a day of stability. Avoids a crash-restart-crash loop hammering the
# machine while still recovering from transient faults.
sc.exe failure $ServiceName reset= 86400 actions= restart/5000/restart/5000/restart/60000 | Out-Null

Start-Service -Name $ServiceName
Write-Host "$ServiceDisplayName installed and started."
Write-Host "Data directory: $DataDir"
Write-Host "Logs: $DataDir\logs"

# --- Per-user notifier: starts at every user's logon, in that user's session ---
# Runs as the logged-on user (BUILTIN\Users, by SID to avoid locale issues) with limited rights; it only
# talks to the service over a local pipe and keeps its own state under %LOCALAPPDATA%. A scheduled task
# is preferred over a Run key because it can restart the notifier if it ever exits.
if (-not $SkipNotifier) {
    $notifierExe = Join-Path $InstallDir "timelord-notifier.exe"
    $action = New-ScheduledTaskAction -Execute $notifierExe
    $trigger = New-ScheduledTaskTrigger -AtLogOn
    $principal = New-ScheduledTaskPrincipal -GroupId "S-1-5-32-545" -RunLevel Limited
    $settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
        -ExecutionTimeLimit ([TimeSpan]::Zero) -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1) `
        -MultipleInstances IgnoreNew
    Register-ScheduledTask -TaskName $NotifierTaskName -Action $action -Trigger $trigger `
        -Principal $principal -Settings $settings -Description "Shows TimeLord warnings and messages to the logged-on user." `
        -Force | Out-Null
    Write-Host "Notifier installed; it starts at each user's next logon."

    # Also start it for anyone already logged on, so an upgrade takes effect without a logoff.
    try {
        Start-ScheduledTask -TaskName $NotifierTaskName
    } catch {
        Write-Warning "Could not start the notifier now ($_). It will start at next logon."
    }
}
