#Requires -RunAsAdministrator
<#
.SYNOPSIS
    Removes the TimeLord Agent service and installed binary.

.DESCRIPTION
    Stops and deletes the "TimeLordAgent" service and removes the installed
    binary. Session history and logs under the data directory are kept by
    default so an uninstall doesn't silently destroy usage records — pass
    -RemoveData to purge them too.

.PARAMETER InstallDir
    Where the agent binary was installed. Must match what install.ps1 used.

.PARAMETER DataDir
    Where the agent stores its SQLite state and logs.

.PARAMETER RemoveData
    Also delete $DataDir (session history, logs, and once implemented,
    device identity and pinned controller key). Irreversible.

.EXAMPLE
    .\uninstall.ps1
.EXAMPLE
    .\uninstall.ps1 -RemoveData
#>
[CmdletBinding()]
param(
    [string]$InstallDir = "$env:ProgramFiles\TimeLord",
    [string]$DataDir = "$env:ProgramData\TimeLord",
    [switch]$RemoveData
)

$ErrorActionPreference = "Stop"

$ServiceName = "TimeLordAgent"

$existing = Get-Service -Name $ServiceName -ErrorAction SilentlyContinue
if ($existing) {
    Write-Host "Stopping $ServiceName..."
    if ($existing.Status -ne "Stopped") {
        Stop-Service -Name $ServiceName -Force
        $existing.WaitForStatus("Stopped", "00:00:30")
    }
    sc.exe delete $ServiceName | Out-Null
    Write-Host "Service removed."
} else {
    Write-Host "$ServiceName service not found; nothing to stop."
}

if (Test-Path $InstallDir) {
    Remove-Item -Path $InstallDir -Recurse -Force
    Write-Host "Removed $InstallDir."
}

if ($RemoveData) {
    if (Test-Path $DataDir) {
        Remove-Item -Path $DataDir -Recurse -Force
        Write-Host "Removed $DataDir (session history and logs deleted)."
    }
} else {
    Write-Host "Data directory kept: $DataDir (rerun with -RemoveData to delete it)."
}
