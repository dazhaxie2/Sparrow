# Sparrow Phase 2 压测数据生成:批量注册用户 + 批量获取 token
# 用法:
#   powershell -File backend/scripts/seed-tokens.ps1 -Count 10000
#   powershell -File backend/scripts/seed-tokens.ps1 -Count 50000 -BaseUrl "http://localhost:8080"

param(
    [int]$Count = 50000,
    [string]$BaseUrl = "http://localhost:8080",
    [int]$Concurrency = 50,
    [string]$Node = "node"
)
$ErrorActionPreference = "Stop"
$script = Join-Path $PSScriptRoot "seed-tokens.mjs"
& $Node $script --base-url $BaseUrl --count $Count --concurrency $Concurrency
exit $LASTEXITCODE
