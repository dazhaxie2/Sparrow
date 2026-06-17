# Sparrow load test — Phase 1 / Phase 2 统一入口
#
# Phase 1: 50 QPS / P99 < 200ms(单端点 /api/graph/tree)
# Phase 2: 1000 QPS / P99 < 300ms(多端点混合,需先准备 token)
#
# 用法:
#   # Phase 1(默认)
#   powershell -File backend/scripts/load.ps1
#
#   # Phase 2
#   powershell -File backend/scripts/load.ps1 -Phase 2 -Qps 1000 -DurationSeconds 60
#
#   # Phase 2 自定义
#   powershell -File backend/scripts/load.ps1 -Phase 2 -Qps 500 -DurationSeconds 30 -P99Ms 300 -RampUp 5

param(
    [int]$Phase = 1,
    [string]$BaseUrl = "http://localhost:8080",
    [int]$Qps = 0,
    [int]$DurationSeconds = 0,
    [int]$P99Ms = 0,
    [int]$RampUp = 5,
    [string]$TokensFile = "",
    [string]$Node = "node"
)

$ErrorActionPreference = "Stop"

if ($Phase -eq 1) {
    $targetQps = if ($Qps -gt 0) { $Qps } else { 50 }
    $duration = if ($DurationSeconds -gt 0) { $DurationSeconds } else { 30 }
    $p99 = if ($P99Ms -gt 0) { $P99Ms } else { 200 }

    Write-Host "=== Phase 1 Load Test: ${targetQps} QPS / P99 < ${p99}ms ===" -ForegroundColor Cyan

    $script = Join-Path $PSScriptRoot "phase1-load.mjs"
    & $Node $script --base-url $BaseUrl --qps $targetQps --duration $duration --p99 $p99
}
elseif ($Phase -eq 2) {
    $targetQps = if ($Qps -gt 0) { $Qps } else { 1000 }
    $duration = if ($DurationSeconds -gt 0) { $DurationSeconds } else { 60 }
    $p99 = if ($P99Ms -gt 0) { $P99Ms } else { 300 }

    Write-Host "=== Phase 2 Load Test: ${targetQps} QPS / P99 < ${p99}ms ===" -ForegroundColor Cyan

    # 检查 token 文件
    $tokensPath = if ($TokensFile) { $TokensFile } else { Join-Path $PSScriptRoot ".." "tokens.json" }
    $tokensPath = [System.IO.Path]::GetFullPath($tokensPath)

    if (-not (Test-Path $tokensPath)) {
        Write-Host ""
        Write-Host "[!] 未找到 token 文件: $tokensPath" -ForegroundColor Yellow
        Write-Host "[!] Phase 2 压测需要预置登录 token。" -ForegroundColor Yellow
        Write-Host ""
        Write-Host "请先生成压测用户和 token:" -ForegroundColor White
        Write-Host "  1. 灌入种子用户:" -ForegroundColor White
        Write-Host "       docker exec -i sparrow-mysql mysql -uroot -proot123 sparrow_user < backend/scripts/seed-users.sql" -ForegroundColor Gray
        Write-Host "  2. 批量获取 token:" -ForegroundColor White
        Write-Host "       powershell -File backend/scripts/seed-tokens.ps1 -Count 10000" -ForegroundColor Gray
        Write-Host ""
        exit 1
    }

    $tokenCount = (Get-Content $tokensPath -Raw | ConvertFrom-Json).Count
    Write-Host "[i] Token 文件: $tokensPath ($tokenCount tokens)" -ForegroundColor Green

    $script = Join-Path $PSScriptRoot "phase2-load.mjs"
    $args = @(
        $script,
        "--base-url", $BaseUrl,
        "--qps", $targetQps,
        "--duration", $duration,
        "--p99", $p99,
        "--rampup", $RampUp,
        "--tokens", $tokensPath
    )
    & $Node @args
}
else {
    Write-Error "Phase 必须是 1 或 2"
    exit 1
}

exit $LASTEXITCODE
