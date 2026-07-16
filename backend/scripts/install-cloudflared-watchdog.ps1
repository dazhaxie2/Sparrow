[CmdletBinding()]
param(
    [string]$ServiceName = 'Cloudflared',
    [string]$TaskName = 'Sparrow Cloudflared Watchdog',
    [string]$SiteUrl = 'https://dazhaxie75.top/',
    [string]$OriginUrl = 'http://127.0.0.1:8080/',
    [string]$EgressUrl = 'https://www.cloudflare.com/cdn-cgi/trace'
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'

function Assert-Administrator {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = New-Object Security.Principal.WindowsPrincipal($identity)
    if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        throw 'Administrator privileges are required to install the Cloudflared watchdog.'
    }
}

Assert-Administrator
Get-Service -Name $ServiceName -ErrorAction Stop | Out-Null

$sourceScript = Join-Path $PSScriptRoot 'cloudflared-watchdog.ps1'
if (-not (Test-Path -LiteralPath $sourceScript)) {
    throw "Watchdog source script not found: $sourceScript"
}

$installDirectory = Join-Path $env:ProgramData 'Sparrow\cloudflared-watchdog'
$installedScript = Join-Path $installDirectory 'cloudflared-watchdog.ps1'
New-Item -ItemType Directory -Path $installDirectory -Force | Out-Null
Copy-Item -LiteralPath $sourceScript -Destination $installedScript -Force
Unblock-File -LiteralPath $installedScript -ErrorAction SilentlyContinue

& sc.exe failure $ServiceName 'reset=' 86400 'actions=' 'restart/5000/restart/10000/restart/30000' | Out-Host
if ($LASTEXITCODE -ne 0) {
    throw "Could not configure service recovery for $ServiceName (sc.exe exit $LASTEXITCODE)."
}

& sc.exe failureflag $ServiceName 1 | Out-Host
if ($LASTEXITCODE -ne 0) {
    throw "Could not configure service failure flag for $ServiceName (sc.exe exit $LASTEXITCODE)."
}

$powerShellPath = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
$arguments = '-NoProfile -NonInteractive -ExecutionPolicy Bypass -File "{0}" -ServiceName "{1}" -SiteUrl "{2}" -OriginUrl "{3}" -EgressUrl "{4}"' -f `
    $installedScript, $ServiceName, $SiteUrl, $OriginUrl, $EgressUrl

$action = New-ScheduledTaskAction -Execute $powerShellPath -Argument $arguments
$minuteTrigger = New-ScheduledTaskTrigger -Once -At (Get-Date).AddMinutes(1) `
    -RepetitionInterval (New-TimeSpan -Minutes 1) `
    -RepetitionDuration (New-TimeSpan -Days 3650)
$startupTrigger = New-ScheduledTaskTrigger -AtStartup
$principal = New-ScheduledTaskPrincipal -UserId 'SYSTEM' -LogonType ServiceAccount -RunLevel Highest
$settings = New-ScheduledTaskSettingsSet `
    -StartWhenAvailable `
    -AllowStartIfOnBatteries `
    -DontStopIfGoingOnBatteries `
    -MultipleInstances IgnoreNew `
    -ExecutionTimeLimit (New-TimeSpan -Seconds 55) `
    -RestartCount 2 `
    -RestartInterval (New-TimeSpan -Minutes 1)

Register-ScheduledTask -TaskName $TaskName -Action $action `
    -Trigger @($minuteTrigger, $startupTrigger) -Principal $principal `
    -Settings $settings -Description 'Restarts Cloudflared after the VPN recovers if the tunnel remains disconnected.' `
    -Force | Out-Null

Start-ScheduledTask -TaskName $TaskName
Start-Sleep -Seconds 3

$task = Get-ScheduledTask -TaskName $TaskName
$taskInfo = Get-ScheduledTaskInfo -TaskName $TaskName
[pscustomobject]@{
    TaskName = $TaskName
    State = $task.State
    LastRunTime = $taskInfo.LastRunTime
    LastTaskResult = $taskInfo.LastTaskResult
    InstalledScript = $installedScript
    ServiceName = $ServiceName
}
