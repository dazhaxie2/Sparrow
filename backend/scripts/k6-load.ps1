# Sparrow Phase 3 - k6 load test wrapper (10k+ QPS)
#
# Runs the k6 engine against the gateway. Tries native k6 first; falls back to
# the grafana/k6 Docker image joined to the sparrow network (sparrow_sparrow_net)
# so it can reach the gateway via internal DNS (sparrow-gateway:8080) instead of
# the unreliable host.docker.internal path.
#
# Usage:
#   powershell -File backend/scripts/k6-load.ps1                                            # default 10000 QPS / 60s / P99 500ms
#   powershell -File backend/scripts/k6-load.ps1 -Qps 1000 -DurationSeconds 60 -P99Ms 300    # Stage A small-sample
#   powershell -File backend/scripts/k6-load.ps1 -Qps 10000 -DurationSeconds 120 -RampUp 15  # Stage B full
#   powershell -File backend/scripts/k6-load.ps1 -K6Mode native                              # force local k6

param(
    [int]$Qps = 10000,
    [int]$DurationSeconds = 60,
    [int]$P99Ms = 500,
    [int]$RampUp = 10,
    [string]$BaseUrl = "http://localhost:8080",
    [string]$TokensFile = "",
    [ValidateSet("auto","native","docker")]
    [string]$K6Mode = "auto",
    [string]$K6Script = "k6-phase3.js",
    [string]$Network = "sparrow_sparrow_net",
    [string]$GatewayDns = "sparrow-gateway"
)

$ErrorActionPreference = "Stop"

$script = Join-Path $PSScriptRoot $K6Script
if (-not (Test-Path $script)) { Write-Error ("k6 script not found: " + $script); exit 1 }

# locate token file
$tokensPath = if ($TokensFile) { $TokensFile } else { Join-Path (Join-Path $PSScriptRoot "..") "tokens.json" }
$tokensPath = [System.IO.Path]::GetFullPath($tokensPath)
if (-not (Test-Path $tokensPath)) {
    Write-Host ""
    Write-Host ("[!] token file not found: " + $tokensPath) -ForegroundColor Yellow
    Write-Host "[!] Phase 3 load test needs pre-seeded tokens (authed endpoints: trade/orders, ai/ask)." -ForegroundColor Yellow
    Write-Host ""
    Write-Host "Generate first:" -ForegroundColor White
    Write-Host "  powershell -File backend/scripts/seed-tokens.ps1 -Count 1000" -ForegroundColor Gray
    Write-Host ""
    exit 1
}
$tokenCount = (Get-Content $tokensPath -Raw | ConvertFrom-Json).Count

function Test-NativeK6 { return [bool](Get-Command k6 -ErrorAction SilentlyContinue) }

$useDocker = $false
if ($K6Mode -eq "docker") {
    $useDocker = $true
} elseif ($K6Mode -eq "native") {
    if (-not (Test-NativeK6)) { Write-Error "K6Mode=native but k6 not installed"; exit 1 }
} else {
    if (-not (Test-NativeK6)) {
        $useDocker = $true
        Write-Host "[i] native k6 not found, using grafana/k6 Docker image" -ForegroundColor Cyan
    }
}

Write-Host "==================================================" -ForegroundColor Cyan
Write-Host "  Sparrow Phase 3 - k6 load test" -ForegroundColor Cyan
Write-Host "==================================================" -ForegroundColor Cyan
Write-Host ("  target QPS:   " + $Qps)
Write-Host ("  duration:     " + $DurationSeconds + "s (ramp-up " + $RampUp + "s)")
Write-Host ("  P99 limit:    " + $P99Ms + "ms")
Write-Host ("  base URL:     " + $BaseUrl)
Write-Host ("  tokens:       " + $tokensPath + " (" + $tokenCount + " tokens)")
$k6rt = if ($useDocker) { "docker (grafana/k6, network=" + $Network + ")" } else { "native" }
Write-Host ("  k6 runtime:   " + $k6rt)
Write-Host "==================================================" -ForegroundColor Cyan
Write-Host ""

# warmup probe
try {
    $probe = Invoke-WebRequest ($BaseUrl + "/api/graph/tree") -UseBasicParsing -TimeoutSec 5
    if ($probe.StatusCode -ne 200) { throw ("status=" + $probe.StatusCode) }
    Write-Host "[warmup] GET /api/graph/tree OK" -ForegroundColor Green
} catch {
    Write-Error ("[warmup] service unreachable (" + $BaseUrl + "): " + $_.Exception.Message + ". Run: docker compose up -d --build")
    exit 1
}

if ($useDocker) {
    # In-network path: gateway DNS is http://sparrow-gateway:8080 inside sparrow_sparrow_net.
    $containerBaseUrl = "http://" + $GatewayDns + ":8080"
    $tokensMount = Split-Path $tokensPath -Parent
    $tokensName = Split-Path $tokensPath -Leaf
    $scriptMount = $PSScriptRoot
    Write-Host ("[i] container BASE_URL=" + $containerBaseUrl) -ForegroundColor Cyan
    Write-Host "[i] starting grafana/k6 container..." -ForegroundColor Cyan
    docker run --rm `
        --network $Network `
        -v "${scriptMount}:/scripts:ro" `
        -v "${tokensMount}:/data:ro" `
        -e BASE_URL=$containerBaseUrl `
        -e TARGET_QPS=$Qps `
        -e DURATION=$DurationSeconds `
        -e RAMP_UP=$RampUp `
        -e P99_LIMIT=$P99Ms `
        -e TOKENS_FILE=/data/$tokensName `
        grafana/k6 run /scripts/$K6Script
} else {
    & k6 run $script `
        -e BASE_URL=$BaseUrl `
        -e TARGET_QPS=$Qps `
        -e DURATION=$DurationSeconds `
        -e RAMP_UP=$RampUp `
        -e P99_LIMIT=$P99Ms `
        -e TOKENS_FILE=$tokensPath
}

$code = $LASTEXITCODE
if ($code -eq 0) {
    Write-Host ""
    Write-Host ("  PASS: " + $Qps + " QPS, P99 <= " + $P99Ms + "ms") -ForegroundColor Green
} else {
    Write-Host ""
    Write-Host ("  FAIL (exit " + $code + "): threshold not met or k6 error") -ForegroundColor Red
}
exit $code
