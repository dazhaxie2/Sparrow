# Sparrow Phase 1 smoke test: end-to-end functional verification.
# Usage: powershell -File scripts/smoke.ps1 [-BaseUrl http://localhost:8080]
param([string]$BaseUrl = "http://localhost:8080")

$ErrorActionPreference = "Stop"
$passed = 0; $failed = 0

function Step($name, $block) {
    try {
        & $block
        Write-Host "[PASS] $name" -ForegroundColor Green
        $script:passed++
    } catch {
        Write-Host "[FAIL] $name -> $($_.Exception.Message)" -ForegroundColor Red
        $script:failed++
    }
}

function Api($method, $path, $body, $token) {
    $headers = @{}
    if ($token) { $headers["Authorization"] = "Bearer $token" }
    $params = @{ Method = $method; Uri = "$BaseUrl$path"; Headers = $headers; ContentType = "application/json; charset=utf-8" }
    if ($body) { $params.Body = ([System.Text.Encoding]::UTF8.GetBytes(($body | ConvertTo-Json -Compress))) }
    $resp = Invoke-RestMethod @params
    if ($resp.code -ne 0) { throw "code=$($resp.code) msg=$($resp.message)" }
    return $resp.data
}

$user = "smoke$(Get-Random -Maximum 99999)"
$token = $null
$orderNo = $null
$payToken = $null

Step "register new user ($user)" {
    $script:token = (Api POST "/api/user/register" @{ username = $user; password = "smoke123456" }).token
    if (-not $script:token) { throw "no token" }
}

# 内置 77 节点为下限;SparrowSpider 同步会持续追加新节点(sp_*)
Step "load graph tree (77+ nodes)" {
    $tree = Api GET "/api/graph/tree"
    if ($tree.nodes.Count -lt 77) { throw "nodes=$($tree.nodes.Count), expected 77+" }
    if ($tree.edges.Count -lt 100) { throw "edges=$($tree.edges.Count), expected 100+" }
}

Step "node detail: steam engine is locked for non-member" {
    $node = Api GET "/api/graph/node/41" $null $script:token
    if ($node.code -ne "steam_engine") { throw "code=$($node.code)" }
    if (-not $node.locked) { throw "premium detail should be locked" }
    if ($node.prerequisites.Count -lt 1) { throw "missing prerequisites" }
}

Step "steam engine full prerequisite chain" {
    $chain = Api GET "/api/graph/node/41/prerequisites"
    if ($chain.Count -lt 10) { throw "chain=$($chain.Count), expected 10+" }
}

Step "AI ask uses rules fallback" {
    $r = Api POST "/api/ai/ask" @{ question = "steam_engine prerequisites?" } $script:token
    if (-not $r.answer) { throw "empty answer" }
}

Step "anonymous /api/user/me returns body code 401" {
    $resp = Invoke-RestMethod -Method GET -Uri "$BaseUrl/api/user/me"
    if ($resp.code -ne 401) { throw "code=$($resp.code), expected 401" }
}

Step "create member order" {
    $r = Api POST "/api/trade/order" @{ productCode = "MEMBER_MONTH" } $script:token
    $script:orderNo = $r.orderNo
    if (-not $r.payUrl) { throw "missing payUrl" }
    if ($r.payUrl -notmatch "payToken=([^&]+)") { throw "payUrl missing payToken" }
    $script:payToken = [System.Uri]::UnescapeDataString($matches[1])
}

Step "mock payment rejects forged token" {
    $resp = Invoke-RestMethod -Method POST -Uri "$BaseUrl/api/pay/mock/notify" `
        -ContentType "application/json; charset=utf-8" `
        -Body ([System.Text.Encoding]::UTF8.GetBytes((@{ orderNo = $script:orderNo; payToken = "forged" } | ConvertTo-Json -Compress)))
    if ($resp.code -ne 403) { throw "code=$($resp.code), expected 403" }
}

Step "mock payment notify succeeds first time" {
    $r = Api POST "/api/pay/mock/notify" @{ orderNo = $script:orderNo; payToken = $script:payToken }
    if (-not $r.processed) { throw "first notify should be processed=true" }
}

Step "mock payment notify is idempotent" {
    $r = Api POST "/api/pay/mock/notify" @{ orderNo = $script:orderNo; payToken = $script:payToken }
    if ($r.processed) { throw "duplicate notify should be processed=false" }
}

Step "member is active and premium content unlocks" {
    $me = Api GET "/api/user/me" $null $script:token
    if (-not $me.member) { throw "membership not active" }
    $node = Api GET "/api/graph/node/41" $null $script:token
    if ($node.locked) { throw "premium detail still locked" }
    if (-not $node.detail) { throw "premium detail missing" }
}

Step "order list contains paid order" {
    $orders = Api GET "/api/trade/orders" $null $script:token
    $paid = @($orders | Where-Object { $_.orderNo -eq $script:orderNo -and $_.status -eq "PAID" })
    if ($paid.Count -ne 1) { throw "paid order missing" }
}

Write-Host ""
Write-Host ("Result: {0} passed, {1} failed" -f $passed, $failed) -ForegroundColor $(if ($failed -eq 0) { "Green" } else { "Red" })
if ($failed -gt 0) { exit 1 }
