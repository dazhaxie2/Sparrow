[CmdletBinding()]
param(
    [switch]$AuditOnly,
    [string]$RemoteTailnetAddress = '100.114.97.105',
    [string]$PipeName = 'verge-mihomo'
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'
$utf8NoBom = New-Object System.Text.UTF8Encoding($false, $true)
$candidatePath = $null
$changedPaths = New-Object System.Collections.Generic.List[string]

function Assert-Administrator {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = New-Object Security.Principal.WindowsPrincipal($identity)
    if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        throw 'Administrator privileges are required to update host network resilience.'
    }
}

function Get-CommandLinePath {
    param(
        [string]$CommandLine,
        [Parameter(Mandatory = $true)][string]$ArgumentName
    )

    if ([string]::IsNullOrWhiteSpace($CommandLine)) { return $null }
    $pattern = '(?:^|\s)' + [regex]::Escape($ArgumentName) +
        '(?:=|\s+)(?:"([^"]+)"|([^\s]+))'
    if ($CommandLine -match $pattern) {
        if (-not [string]::IsNullOrWhiteSpace($Matches[1])) { return $Matches[1] }
        return $Matches[2]
    }
    return $null
}

function Get-NewLine {
    param([Parameter(Mandatory = $true)][string]$Text)
    if ($Text.Contains("`r`n")) { return "`r`n" }
    return "`n"
}

function Set-YamlScalar {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$Key,
        [Parameter(Mandatory = $true)][string]$Value
    )

    $pattern = '(?m)^' + [regex]::Escape($Key) + '[ \t]*:[^\r\n]*(?=\r?$)'
    $replacement = "$Key`: $Value"
    if ([regex]::IsMatch($Text, $pattern)) {
        return [regex]::Replace($Text, $pattern, $replacement, 1)
    }
    return $replacement + (Get-NewLine -Text $Text) + $Text
}

function Add-YamlListEntries {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$Key,
        [Parameter(Mandatory = $true)][string[]]$Entries
    )

    $missing = New-Object System.Collections.Generic.List[string]
    foreach ($entry in $Entries) {
        $entryPattern = '(?m)^[ \t]*-[ \t]*' + [regex]::Escape($entry) + '[ \t]*(?=\r?$)'
        if (-not [regex]::IsMatch($Text, $entryPattern)) {
            $missing.Add($entry)
        }
    }
    if ($missing.Count -eq 0) { return $Text }

    $newLine = Get-NewLine -Text $Text
    $listLines = ($missing | ForEach-Object { "  - $_" }) -join $newLine
    $keyPattern = '(?m)^' + [regex]::Escape($Key) +
        '[ \t]*:[ \t]*(?<empty>\[[ \t]*\])?[ \t]*(?<lineEnd>\r?\n|\z)'
    $keyMatch = [regex]::Match($Text, $keyPattern)

    if (-not $keyMatch.Success) {
        return "$Key`:$newLine$listLines$newLine$Text"
    }

    if ($keyMatch.Groups['empty'].Success) {
        $replacement = "$Key`:$newLine$listLines"
        return $Text.Remove($keyMatch.Index, $keyMatch.Length).Insert($keyMatch.Index, $replacement)
    }

    $insertAt = $keyMatch.Index + $keyMatch.Length
    if ($keyMatch.Groups['lineEnd'].Length -eq 0) {
        return $Text.Insert($insertAt, $newLine + $listLines + $newLine)
    }
    return $Text.Insert($insertAt, $listLines + $newLine)
}

function Write-AtomicUtf8 {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Content
    )

    $temporaryPath = "$Path.codex-new"
    $backupPath = "$Path.codex-last-known-good"
    [IO.File]::WriteAllText($temporaryPath, $Content, $utf8NoBom)
    [IO.File]::Replace($temporaryPath, $Path, $backupPath, $true)
    $changedPaths.Add($Path)
}

function Restore-ChangedFiles {
    for ($index = $changedPaths.Count - 1; $index -ge 0; $index--) {
        $path = $changedPaths[$index]
        $backupPath = "$path.codex-last-known-good"
        if (Test-Path -LiteralPath $backupPath) {
            Copy-Item -LiteralPath $backupPath -Destination $path -Force
        }
    }
}

function Invoke-MihomoPipeRequest {
    param(
        [Parameter(Mandatory = $true)][ValidateSet('GET', 'PUT', 'POST')][string]$Method,
        [Parameter(Mandatory = $true)][string]$Target,
        [string]$Body = ''
    )

    $pipe = [IO.Pipes.NamedPipeClientStream]::new(
        '.', $PipeName, [IO.Pipes.PipeDirection]::InOut,
        [IO.Pipes.PipeOptions]::None
    )
    $reader = $null
    try {
        $pipe.Connect(5000)
        $bodyBytes = $utf8NoBom.GetBytes($Body)
        $headers = "$Method $Target HTTP/1.1`r`n" +
            "Host: localhost`r`n" +
            "Content-Type: application/json`r`n" +
            "Content-Length: $($bodyBytes.Length)`r`n" +
            "Connection: close`r`n`r`n"
        $headerBytes = $utf8NoBom.GetBytes($headers)
        $pipe.Write($headerBytes, 0, $headerBytes.Length)
        if ($bodyBytes.Length -gt 0) {
            $pipe.Write($bodyBytes, 0, $bodyBytes.Length)
        }
        $pipe.Flush()

        $reader = New-Object IO.StreamReader($pipe, $utf8NoBom, $false, 4096, $true)
        $statusLine = $reader.ReadLine()
        if ($statusLine -notmatch '^HTTP/\d(?:\.\d)?\s+(?<status>\d{3})') {
            throw "Unexpected Mihomo API response: $statusLine"
        }
        $statusCode = [int]$Matches['status']
        $contentLength = 0
        while ($true) {
            $line = $reader.ReadLine()
            if ($null -eq $line -or $line.Length -eq 0) { break }
            if ($line -match '^(?i:Content-Length):\s*(?<length>\d+)') {
                $contentLength = [int]$Matches['length']
            }
        }

        $responseBody = ''
        if ($contentLength -gt 0) {
            $buffer = New-Object char[] $contentLength
            $read = $reader.ReadBlock($buffer, 0, $buffer.Length)
            if ($read -gt 0) { $responseBody = -join $buffer[0..($read - 1)] }
        }

        [pscustomobject]@{
            StatusCode = $statusCode
            Body = $responseBody
        }
    } finally {
        if ($null -ne $reader) { $reader.Dispose() }
        $pipe.Dispose()
    }
}

function Test-HttpStatus {
    param(
        [Parameter(Mandatory = $true)][string]$Url,
        [int]$TimeoutMilliseconds = 10000
    )

    $response = $null
    try {
        $request = [Net.HttpWebRequest]::Create($Url)
        $request.Method = 'GET'
        $request.Proxy = $null
        $request.Timeout = $TimeoutMilliseconds
        $request.ReadWriteTimeout = $TimeoutMilliseconds
        $response = [Net.HttpWebResponse]$request.GetResponse()
        return [int]$response.StatusCode
    } catch [Net.WebException] {
        if ($null -ne $_.Exception.Response) {
            $response = [Net.HttpWebResponse]$_.Exception.Response
            return [int]$response.StatusCode
        }
        return $null
    } finally {
        if ($null -ne $response) { $response.Close() }
    }
}

Assert-Administrator
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$coreProcess = Get-CimInstance Win32_Process |
    Where-Object { $_.Name -match '^(verge-mihomo|mihomo)(\.exe)?$' } |
    Select-Object -First 1
if ($null -eq $coreProcess) { throw 'The running Mihomo process was not found.' }

$configHome = Get-CommandLinePath -CommandLine $coreProcess.CommandLine -ArgumentName '-d'
$runtimeConfig = Get-CommandLinePath -CommandLine $coreProcess.CommandLine -ArgumentName '-f'
$coreExecutable = $coreProcess.ExecutablePath
if (-not $configHome -or -not (Test-Path -LiteralPath $configHome)) {
    throw 'Could not resolve the running Mihomo config directory.'
}
if (-not $runtimeConfig -or -not (Test-Path -LiteralPath $runtimeConfig)) {
    throw 'Could not resolve the running Mihomo config file.'
}
if (-not $coreExecutable -or -not (Test-Path -LiteralPath $coreExecutable)) {
    throw 'Could not resolve the running Mihomo executable.'
}

$profilesIndex = Join-Path $configHome 'profiles.yaml'
if (-not (Test-Path -LiteralPath $profilesIndex)) {
    throw "Clash Verge profile index not found: $profilesIndex"
}
$indexText = [IO.File]::ReadAllText($profilesIndex, $utf8NoBom)
$currentMatch = [regex]::Match(
    $indexText,
    '(?m)^current:[ \t]*(?<uid>[A-Za-z0-9_-]+)[ \t]*(?=\r?$)'
)
if (-not $currentMatch.Success) { throw 'The current Clash Verge profile UID was not found.' }
$currentUid = $currentMatch.Groups['uid'].Value
$profilePattern = '(?ms)^-[ \t]+uid:[ \t]*' + [regex]::Escape($currentUid) +
    '[ \t]*(?:\r?\n)' + '(?<body>.*?)(?=^-[ \t]+uid:|\z)'
$profileMatch = [regex]::Match($indexText, $profilePattern)
if (-not $profileMatch.Success) { throw "Current profile entry not found: $currentUid" }
$profileBody = $profileMatch.Groups['body'].Value

$mergeMatch = [regex]::Match(
    $profileBody,
    '(?m)^[ \t]+merge:[ \t]*(?<uid>[A-Za-z0-9_-]+)[ \t]*(?=\r?$)'
)
$rulesMatch = [regex]::Match(
    $profileBody,
    '(?m)^[ \t]+rules:[ \t]*(?<uid>[A-Za-z0-9_-]+)[ \t]*(?=\r?$)'
)
if (-not $mergeMatch.Success -or -not $rulesMatch.Success) {
    throw "Current profile $currentUid is not bound to both merge and rules overrides."
}

$mergeUid = $mergeMatch.Groups['uid'].Value
$rulesUid = $rulesMatch.Groups['uid'].Value
$mergePath = Join-Path $configHome "profiles\$mergeUid.yaml"
$rulesPath = Join-Path $configHome "profiles\$rulesUid.yaml"
foreach ($path in @($mergePath, $rulesPath)) {
    if (-not (Test-Path -LiteralPath $path)) { throw "Bound override not found: $path" }
}

$processRules = @(
    'PROCESS-NAME,tailscaled.exe,DIRECT',
    'PROCESS-NAME,tailscale-ipn.exe,DIRECT',
    'PROCESS-NAME,tailscale.exe,DIRECT'
)
$originalMerge = [IO.File]::ReadAllText($mergePath, $utf8NoBom)
$originalRules = [IO.File]::ReadAllText($rulesPath, $utf8NoBom)
$originalRuntime = [IO.File]::ReadAllText($runtimeConfig, $utf8NoBom)

$desiredMerge = Set-YamlScalar -Text $originalMerge -Key 'find-process-mode' -Value 'always'
$desiredMerge = Add-YamlListEntries -Text $desiredMerge -Key 'prepend-rules' -Entries $processRules
$desiredRules = Add-YamlListEntries -Text $originalRules -Key 'prepend' -Entries $processRules
$desiredRuntime = Set-YamlScalar -Text $originalRuntime -Key 'find-process-mode' -Value 'always'
$desiredRuntime = Add-YamlListEntries -Text $desiredRuntime -Key 'rules' -Entries $processRules

$candidatePath = Join-Path $configHome 'clash-verge.codex-candidate.yaml'
[IO.File]::WriteAllText($candidatePath, $desiredRuntime, $utf8NoBom)
if ($AuditOnly) {
    $previewLine = 0
    Get-Content -LiteralPath $candidatePath -TotalCount 12 | ForEach-Object {
        $previewLine++
        $safeLine = if ($_ -match '(?i)^\s*[^:]*?(secret|password|token|uuid|server|url)\s*:') {
            '<sensitive-key-redacted>'
        } else {
            [string]$_
        }
        if ($safeLine.Length -gt 200) { $safeLine = $safeLine.Substring(0, 200) + '<truncated>' }
        Write-Output ("candidate-line-{0}={1}" -f $previewLine, $safeLine)
    }
}
$validationOutput = & $coreExecutable -d $configHome -f $candidatePath -t 2>&1
$validationExitCode = $LASTEXITCODE
if ($validationExitCode -ne 0) {
    $validationOutput | Select-Object -Last 20 | ForEach-Object { Write-Output "validation: $_" }
    Remove-Item -LiteralPath $candidatePath -Force -ErrorAction SilentlyContinue
    throw "Mihomo rejected the candidate config (exit $validationExitCode)."
}

$versionResponse = try {
    Invoke-MihomoPipeRequest -Method GET -Target '/version'
} catch {
    Remove-Item -LiteralPath $candidatePath -Force -ErrorAction SilentlyContinue
    throw
}
if ($versionResponse.StatusCode -lt 200 -or $versionResponse.StatusCode -ge 300) {
    Remove-Item -LiteralPath $candidatePath -Force -ErrorAction SilentlyContinue
    throw "Mihomo named-pipe API health check returned HTTP $($versionResponse.StatusCode)."
}

$mergeNeedsChange = ($desiredMerge -cne $originalMerge)
$rulesNeedChange = ($desiredRules -cne $originalRules)
$runtimeNeedsChange = ($desiredRuntime -cne $originalRuntime)
Write-Output "current-profile=$currentUid merge=$mergeUid rules=$rulesUid"
Write-Output "candidate-validation=success pipe-api-status=$($versionResponse.StatusCode)"
Write-Output "changes-required merge=$mergeNeedsChange rules=$rulesNeedChange runtime=$runtimeNeedsChange"

if ($AuditOnly) {
    Remove-Item -LiteralPath $candidatePath -Force -ErrorAction SilentlyContinue
    Write-Output 'audit-only=true no-files-changed=true'
    exit 0
}

if (-not ($mergeNeedsChange -or $rulesNeedChange -or $runtimeNeedsChange)) {
    Remove-Item -LiteralPath $candidatePath -Force -ErrorAction SilentlyContinue
    Write-Output 'host-network-resilience=already-configured'
    exit 0
}

try {
    if ($mergeNeedsChange) { Write-AtomicUtf8 -Path $mergePath -Content $desiredMerge }
    if ($rulesNeedChange) { Write-AtomicUtf8 -Path $rulesPath -Content $desiredRules }
    if ($runtimeNeedsChange) { Write-AtomicUtf8 -Path $runtimeConfig -Content $desiredRuntime }

    $reloadBody = @{ path = $runtimeConfig; payload = '' } | ConvertTo-Json -Compress
    $reloadResponse = Invoke-MihomoPipeRequest -Method PUT -Target '/configs?force=true' -Body $reloadBody
    if ($reloadResponse.StatusCode -lt 200 -or $reloadResponse.StatusCode -ge 300) {
        throw "Mihomo config reload returned HTTP $($reloadResponse.StatusCode)."
    }
    Start-Sleep -Seconds 3

    $egressStatus = Test-HttpStatus -Url 'https://www.cloudflare.com/cdn-cgi/trace'
    if ($null -eq $egressStatus -or $egressStatus -ge 500) {
        throw "Egress failed after Mihomo reload (status=$egressStatus)."
    }

    Write-Output "reload-status=$($reloadResponse.StatusCode) egress-status=$egressStatus"
    tailscale ping --size=1400 --c=5 --timeout=5s $RemoteTailnetAddress
    $tailnetPingExit = $LASTEXITCODE
    Write-Output "tailnet-ping-exit=$tailnetPingExit"

    $siteStatus = Test-HttpStatus -Url 'https://dazhaxie75.top/'
    if ($null -eq $siteStatus -or $siteStatus -ge 500) {
        Restart-Service -Name Cloudflared -Force
        Start-Sleep -Seconds 12
        $siteStatus = Test-HttpStatus -Url 'https://dazhaxie75.top/'
    }
    Write-Output "public-site-status=$siteStatus"
    Write-Output 'host-network-resilience=installed'
} catch {
    $failure = $_
    Restore-ChangedFiles
    try {
        if (Test-Path -LiteralPath "$runtimeConfig.codex-last-known-good") {
            $rollbackBody = @{ path = $runtimeConfig; payload = '' } | ConvertTo-Json -Compress
            $null = Invoke-MihomoPipeRequest -Method PUT -Target '/configs?force=true' -Body $rollbackBody
        }
    } catch {
        Write-Warning "Mihomo rollback reload failed: $($_.Exception.Message)"
    }
    throw $failure
} finally {
    if ($candidatePath -and (Test-Path -LiteralPath $candidatePath)) {
        Remove-Item -LiteralPath $candidatePath -Force -ErrorAction SilentlyContinue
    }
}
