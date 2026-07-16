[CmdletBinding()]
param(
    [string]$ServiceName = 'Cloudflared',
    [string]$SiteUrl = 'https://dazhaxie75.top/',
    [string]$OriginUrl = 'http://127.0.0.1:8080/',
    [string]$EgressUrl = 'https://www.cloudflare.com/cdn-cgi/trace',
    [string]$StateDirectory = "$env:ProgramData\Sparrow\cloudflared-watchdog",
    [ValidateRange(1, 10)]
    [int]$FailureThreshold = 2,
    [ValidateRange(60, 3600)]
    [int]$CooldownSeconds = 300,
    [ValidateRange(1000, 30000)]
    [int]$ProbeTimeoutMilliseconds = 8000
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
$statePath = Join-Path $StateDirectory 'state.json'
$logPath = Join-Path $StateDirectory 'watchdog.log'
$mutex = $null
$hasMutex = $false

function Write-WatchdogLog {
    param([Parameter(Mandatory = $true)][string]$Message)

    if (-not (Test-Path -LiteralPath $StateDirectory)) {
        New-Item -ItemType Directory -Path $StateDirectory -Force | Out-Null
    }

    if ((Test-Path -LiteralPath $logPath) -and
        (Get-Item -LiteralPath $logPath).Length -ge 5MB) {
        Move-Item -LiteralPath $logPath -Destination "$logPath.1" -Force
    }

    $line = '{0} {1}{2}' -f (Get-Date).ToUniversalTime().ToString('o'), $Message, [Environment]::NewLine
    [IO.File]::AppendAllText($logPath, $line, $utf8NoBom)
}

function New-WatchdogState {
    [pscustomobject]@{
        ConsecutiveFailures = 0
        NetworkWasDown = $false
        LastRestartUtc = ''
        LastObservation = ''
    }
}

function Get-WatchdogState {
    if (-not (Test-Path -LiteralPath $statePath)) {
        return New-WatchdogState
    }

    try {
        $state = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
        foreach ($property in @('ConsecutiveFailures', 'NetworkWasDown', 'LastRestartUtc', 'LastObservation')) {
            if ($null -eq $state.PSObject.Properties[$property]) {
                $defaultState = New-WatchdogState
                $state | Add-Member -NotePropertyName $property -NotePropertyValue $defaultState.$property
            }
        }
        return $state
    } catch {
        Write-WatchdogLog "state-invalid reset=true error=$($_.Exception.Message)"
        return New-WatchdogState
    }
}

function Save-WatchdogState {
    param([Parameter(Mandatory = $true)]$State)

    $json = $State | ConvertTo-Json -Depth 3
    $temporaryPath = "$statePath.tmp"
    [IO.File]::WriteAllText($temporaryPath, $json, $utf8NoBom)
    Move-Item -LiteralPath $temporaryPath -Destination $statePath -Force
}

function Invoke-HttpProbe {
    param([Parameter(Mandatory = $true)][string]$Url)

    $response = $null
    try {
        $request = [Net.HttpWebRequest]::Create($Url)
        $request.Method = 'GET'
        $request.AllowAutoRedirect = $true
        $request.Timeout = $ProbeTimeoutMilliseconds
        $request.ReadWriteTimeout = $ProbeTimeoutMilliseconds
        $request.Proxy = $null
        $request.UserAgent = 'Sparrow-Cloudflared-Watchdog/1.0'
        $response = [Net.HttpWebResponse]$request.GetResponse()
        return [int]$response.StatusCode
    } catch [Net.WebException] {
        if ($null -ne $_.Exception.Response) {
            $response = [Net.HttpWebResponse]$_.Exception.Response
            return [int]$response.StatusCode
        }
        return $null
    } catch {
        return $null
    } finally {
        if ($null -ne $response) {
            $response.Close()
        }
    }
}

function Format-ProbeStatus {
    param($StatusCode)
    if ($null -eq $StatusCode) { return 'unreachable' }
    return [string]$StatusCode
}

function Test-CooldownActive {
    param([Parameter(Mandatory = $true)]$State)

    if ([string]::IsNullOrWhiteSpace([string]$State.LastRestartUtc)) {
        return $false
    }

    $lastRestart = [DateTimeOffset]::MinValue
    if (-not [DateTimeOffset]::TryParse([string]$State.LastRestartUtc, [ref]$lastRestart)) {
        return $false
    }

    return (([DateTimeOffset]::UtcNow - $lastRestart).TotalSeconds -lt $CooldownSeconds)
}

function Restart-CloudflaredTunnel {
    param(
        [Parameter(Mandatory = $true)]$State,
        [Parameter(Mandatory = $true)][string]$Reason
    )

    if (Test-CooldownActive -State $State) {
        $State.LastObservation = "cooldown:$Reason"
        Save-WatchdogState -State $State
        Write-WatchdogLog "restart-suppressed reason=$Reason cooldown_seconds=$CooldownSeconds"
        return
    }

    Restart-Service -Name $ServiceName -Force
    $State.ConsecutiveFailures = 0
    $State.NetworkWasDown = $false
    $State.LastRestartUtc = [DateTimeOffset]::UtcNow.ToString('o')
    $State.LastObservation = "restarted:$Reason"
    Save-WatchdogState -State $State

    Start-Sleep -Seconds 12
    $postRestartStatus = Invoke-HttpProbe -Url $SiteUrl
    Write-WatchdogLog "service-restarted reason=$Reason post_status=$(Format-ProbeStatus $postRestartStatus)"
}

function Invoke-CloudflaredWatchdog {
    $service = Get-Service -Name $ServiceName -ErrorAction Stop
    $state = Get-WatchdogState

    if ($service.Status -ne [ServiceProcess.ServiceControllerStatus]::Running) {
        Start-Service -Name $ServiceName
        $state.ConsecutiveFailures = 0
        $state.NetworkWasDown = $false
        $state.LastRestartUtc = [DateTimeOffset]::UtcNow.ToString('o')
        $state.LastObservation = 'service-started'
        Save-WatchdogState -State $state
        Write-WatchdogLog "service-started previous_status=$($service.Status)"
        return
    }

    $originStatus = Invoke-HttpProbe -Url $OriginUrl
    if ($null -eq $originStatus) {
        $state.ConsecutiveFailures = 0
        $state.LastObservation = 'origin-unreachable'
        Save-WatchdogState -State $state
        Write-WatchdogLog 'origin-unreachable tunnel_restart_suppressed=true'
        return
    }

    $siteStatus = Invoke-HttpProbe -Url $SiteUrl
    if ($null -ne $siteStatus -and $siteStatus -ge 200 -and $siteStatus -lt 500) {
        $wasUnhealthy = ($state.ConsecutiveFailures -gt 0 -or $state.NetworkWasDown)
        $state.ConsecutiveFailures = 0
        $state.NetworkWasDown = $false
        $state.LastObservation = "healthy:$siteStatus"
        Save-WatchdogState -State $state
        if ($wasUnhealthy) {
            Write-WatchdogLog "tunnel-recovered status=$siteStatus"
        }
        return
    }

    $egressStatus = Invoke-HttpProbe -Url $EgressUrl
    if ($null -eq $egressStatus -or $egressStatus -ge 500) {
        $state.ConsecutiveFailures = 0
        $state.NetworkWasDown = $true
        $state.LastObservation = "network-down:site=$(Format-ProbeStatus $siteStatus):egress=$(Format-ProbeStatus $egressStatus)"
        Save-WatchdogState -State $state
        Write-WatchdogLog "network-down site_status=$(Format-ProbeStatus $siteStatus) egress_status=$(Format-ProbeStatus $egressStatus) waiting_for_recovery=true"
        return
    }

    $networkJustRecovered = [bool]$state.NetworkWasDown
    $state.NetworkWasDown = $false
    $state.ConsecutiveFailures = [int]$state.ConsecutiveFailures + 1
    $state.LastObservation = "tunnel-down:site=$(Format-ProbeStatus $siteStatus):egress=$egressStatus"
    Save-WatchdogState -State $state

    if ($networkJustRecovered) {
        Restart-CloudflaredTunnel -State $state -Reason 'network-recovered-tunnel-stale'
        return
    }

    if ($state.ConsecutiveFailures -ge $FailureThreshold) {
        Restart-CloudflaredTunnel -State $state -Reason "tunnel-failed-$($state.ConsecutiveFailures)-probes"
        return
    }

    Write-WatchdogLog "tunnel-unhealthy site_status=$(Format-ProbeStatus $siteStatus) egress_status=$egressStatus failures=$($state.ConsecutiveFailures)/$FailureThreshold"
}

try {
    if (-not (Test-Path -LiteralPath $StateDirectory)) {
        New-Item -ItemType Directory -Path $StateDirectory -Force | Out-Null
    }

    $mutex = New-Object Threading.Mutex($false, 'Global\SparrowCloudflaredWatchdog')
    try {
        $hasMutex = $mutex.WaitOne(0, $false)
    } catch [Threading.AbandonedMutexException] {
        $hasMutex = $true
    }

    if (-not $hasMutex) {
        Write-WatchdogLog 'overlapping-run skipped=true'
        exit 0
    }

    Invoke-CloudflaredWatchdog
    exit 0
} catch {
    try {
        Write-WatchdogLog "watchdog-error type=$($_.Exception.GetType().FullName) message=$($_.Exception.Message)"
    } catch {
        Write-Error $_.Exception.Message
    }
    exit 1
} finally {
    if ($hasMutex -and $null -ne $mutex) {
        $mutex.ReleaseMutex()
    }
    if ($null -ne $mutex) {
        $mutex.Dispose()
    }
}
