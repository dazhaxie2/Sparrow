[CmdletBinding()]
param(
    [string]$RemoteTailnetAddress = '100.114.97.105'
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Continue'

function Write-Section {
    param([Parameter(Mandatory = $true)][string]$Name)
    Write-Output "=== $Name ==="
}

function Write-Objects {
    param($InputObject)
    $InputObject | Format-List * | Out-String -Width 240 | Write-Output
}

function Get-CommandLinePath {
    param(
        [string]$CommandLine,
        [Parameter(Mandatory = $true)][string]$ArgumentName
    )

    if ([string]::IsNullOrWhiteSpace($CommandLine)) {
        return $null
    }

    $escapedName = [regex]::Escape($ArgumentName)
    $pattern = '(?:^|\s)' + $escapedName + '(?:=|\s+)(?:"([^"]+)"|([^\s]+))'
    if ($CommandLine -match $pattern) {
        if (-not [string]::IsNullOrWhiteSpace($Matches[1])) { return $Matches[1] }
        return $Matches[2]
    }
    return $null
}

function Protect-LogLine {
    param([Parameter(Mandatory = $true)][string]$Line)

    return $Line `
        -replace '(?i)https?://\S+', '<url-redacted>' `
        -replace '(?i)(token|secret|password)(=|:|\s+)\S+', '$1$2<redacted>' `
        -replace '[A-Za-z0-9_\-]{48,}', '<long-value-redacted>'
}

Write-Section 'IDENTITY'
$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = New-Object Security.Principal.WindowsPrincipal($identity)
Write-Objects ([pscustomobject]@{
    User = $identity.Name
    IsAdministrator = $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
    ComputerName = $env:COMPUTERNAME
    TimeUtc = [DateTimeOffset]::UtcNow.ToString('o')
})

Write-Section 'SERVICES'
Write-Objects (Get-CimInstance Win32_Service |
    Where-Object {
        $_.Name -match 'Cloudflared|tailscale|mihomo|clash|verge' -or
        $_.DisplayName -match 'Cloudflared|tailscale|mihomo|clash|verge'
    } |
    Select-Object Name, DisplayName, State, StartMode, StartName, ProcessId, ExitCode)

Write-Section 'WATCHDOG'
$watchdogDirectory = Join-Path $env:ProgramData 'Sparrow\cloudflared-watchdog'
try {
    Write-Objects (Get-ScheduledTask -TaskName 'Sparrow Cloudflared Watchdog' | Select-Object TaskName, State)
    Write-Objects (Get-ScheduledTaskInfo -TaskName 'Sparrow Cloudflared Watchdog' |
        Select-Object LastRunTime, LastTaskResult, NextRunTime, NumberOfMissedRuns)
} catch {
    Write-Output "watchdog-task-error=$($_.Exception.Message)"
}
if (Test-Path -LiteralPath (Join-Path $watchdogDirectory 'state.json')) {
    Get-Content -LiteralPath (Join-Path $watchdogDirectory 'state.json')
}
if (Test-Path -LiteralPath (Join-Path $watchdogDirectory 'watchdog.log')) {
    Get-Content -LiteralPath (Join-Path $watchdogDirectory 'watchdog.log') -Tail 20
}

Write-Section 'MIHOMO PROCESS'
$coreProcess = Get-CimInstance Win32_Process |
    Where-Object { $_.Name -match '^(verge-mihomo|mihomo)(\.exe)?$' } |
    Select-Object -First 1
$configHome = $null
$runtimeConfig = $null
$coreExecutable = $null
if ($null -eq $coreProcess) {
    Write-Output 'mihomo-process=not-found'
} else {
    $configHome = Get-CommandLinePath -CommandLine $coreProcess.CommandLine -ArgumentName '-d'
    if (-not $configHome) {
        $configHome = Get-CommandLinePath -CommandLine $coreProcess.CommandLine -ArgumentName '--directory'
    }
    $runtimeConfig = Get-CommandLinePath -CommandLine $coreProcess.CommandLine -ArgumentName '-f'
    if (-not $runtimeConfig) {
        $runtimeConfig = Get-CommandLinePath -CommandLine $coreProcess.CommandLine -ArgumentName '--config'
    }
    $coreExecutable = $coreProcess.ExecutablePath
    Write-Objects ([pscustomobject]@{
        ProcessId = $coreProcess.ProcessId
        ExecutablePath = $coreExecutable
        ConfigHome = $configHome
        RuntimeConfig = $runtimeConfig
        CreationDate = $coreProcess.CreationDate
    })
}

if ($configHome -and (Test-Path -LiteralPath $configHome)) {
    Write-Section 'PROFILE BINDINGS'
    $profilesIndex = Join-Path $configHome 'profiles.yaml'
    if (Test-Path -LiteralPath $profilesIndex) {
        Write-Output "profiles-index=$profilesIndex"
        Select-String -LiteralPath $profilesIndex -Pattern '^\s*(current|merge|rules)\s*:' |
            ForEach-Object Line
    } else {
        Write-Output 'profiles-index=not-found'
    }

    Write-Section 'PROFILE OVERRIDES'
    $profileDirectory = Join-Path $configHome 'profiles'
    if (Test-Path -LiteralPath $profileDirectory) {
        Get-ChildItem -LiteralPath $profileDirectory -File -Filter '*.yaml' |
            Where-Object { $_.Length -lt 1MB } |
            Sort-Object Name |
            ForEach-Object {
                $matchingLines = Select-String -LiteralPath $_.FullName -Pattern `
                    '^\s*(prepend|prepend-rules|append|append-rules|delete)\s*:|PROCESS-NAME.*tailscale|ts\.net|100\.64\.0\.0/10|fd7a:115c:a1e0' `
                    -ErrorAction SilentlyContinue
                if ($matchingLines) {
                    Write-Output "profile-file=$($_.Name) size=$($_.Length)"
                    $matchingLines | ForEach-Object Line
                }
            }
    } else {
        Write-Output 'profile-directory=not-found'
    }

    Write-Section 'RUNTIME RELEVANT CONFIG'
    if ($runtimeConfig -and (Test-Path -LiteralPath $runtimeConfig)) {
        Select-String -LiteralPath $runtimeConfig -Pattern `
            '^\s*(mixed-port|external-controller|process-mode|auto-route|strict-route|interface-name|route-exclude-address)\s*:|PROCESS-NAME.*tailscale|ts\.net|100\.64\.0\.0/10|fd7a:115c:a1e0' `
            -ErrorAction SilentlyContinue | ForEach-Object Line
    } else {
        Write-Output 'runtime-config=not-found'
    }

    Write-Section 'RECENT MIHOMO NETWORK ERRORS'
    $recentLogs = Get-ChildItem -LiteralPath $configHome -File -Recurse -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match '\.log$' } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 5
    $errorLines = foreach ($log in $recentLogs) {
        Select-String -LiteralPath $log.FullName -Pattern 'tailscale|41641|3478|context deadline exceeded' `
            -ErrorAction SilentlyContinue | Select-Object -Last 20
    }
    $selectedErrors = @($errorLines | Sort-Object { $_.Path }, { $_.LineNumber } | Select-Object -Last 20)
    Write-Output "matching-error-lines=$($selectedErrors.Count)"
    $selectedErrors | ForEach-Object {
        Write-Output (Protect-LogLine -Line ("{0}:{1}: {2}" -f $_.Path, $_.LineNumber, $_.Line))
    }
}

Write-Section 'ROUTING'
Write-Objects (Find-NetRoute -RemoteIPAddress $RemoteTailnetAddress -ErrorAction SilentlyContinue |
    Select-Object InterfaceAlias, InterfaceIndex, DestinationPrefix, NextHop, RouteMetric)
Write-Objects (Get-NetRoute -AddressFamily IPv4 -ErrorAction SilentlyContinue |
    Where-Object {
        $_.DestinationPrefix -eq '100.64.0.0/10' -or
        $_.InterfaceAlias -match 'Tailscale|Meta|Mihomo'
    } |
    Select-Object InterfaceAlias, InterfaceIndex, DestinationPrefix, NextHop, RouteMetric, State)

Write-Section 'TAILSCALE'
try {
    $tailscaleStatus = tailscale status --json | ConvertFrom-Json
    Write-Objects ([pscustomobject]@{
        BackendState = $tailscaleStatus.BackendState
        SelfOnline = $tailscaleStatus.Self.Online
        SelfRelay = $tailscaleStatus.Self.Relay
        SelfTailscaleIPs = ($tailscaleStatus.Self.TailscaleIPs -join ',')
        Health = ($tailscaleStatus.Health -join ';')
    })
} catch {
    Write-Output "tailscale-status-error=$($_.Exception.Message)"
}

Write-Section 'PROXY STATE'
foreach ($path in @(
    'Registry::HKEY_USERS\S-1-5-18\Software\Microsoft\Windows\CurrentVersion\Internet Settings',
    'HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings'
)) {
    if (Test-Path $path) {
        $proxy = Get-ItemProperty $path
        Write-Objects ([pscustomobject]@{
            Path = $path
            ProxyEnable = $proxy.ProxyEnable
            ProxyServer = $proxy.ProxyServer
            AutoConfigURL = $proxy.AutoConfigURL
        })
    }
}
netsh winhttp show proxy
