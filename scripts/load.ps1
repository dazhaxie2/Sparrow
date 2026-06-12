# Sparrow Phase 1 load test: 50 QPS / P99 < 200ms by default.
# Usage: powershell -File scripts/load.ps1 [-BaseUrl http://localhost:8080] [-Qps 50] [-DurationSeconds 30] [-P99Ms 200]
param(
    [string]$BaseUrl = "http://localhost:8080",
    [int]$Qps = 50,
    [int]$DurationSeconds = 30,
    [int]$P99Ms = 200,
    [string]$Node = "node"
)

$ErrorActionPreference = "Stop"
$script = Join-Path $PSScriptRoot "phase1-load.mjs"

& $Node $script --base-url $BaseUrl --qps $Qps --duration $DurationSeconds --p99 $P99Ms
exit $LASTEXITCODE
