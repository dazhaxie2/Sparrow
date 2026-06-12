# Sparrow Phase 1 冒烟测试:全链路验证
# 用法: powershell -File scripts/smoke.ps1 [-BaseUrl http://localhost:8080]
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

Step "注册新用户 ($user)" {
    $script:token = (Api POST "/api/user/register" @{ username = $user; password = "smoke123456" }).token
    if (-not $script:token) { throw "no token" }
}

Step "获取科技树(77 节点)" {
    $tree = Api GET "/api/graph/tree"
    if ($tree.nodes.Count -ne 77) { throw "nodes=$($tree.nodes.Count), expected 77" }
    if ($tree.edges.Count -lt 100) { throw "edges=$($tree.edges.Count), expected 100+" }
}

Step "节点详情:蒸汽机(id=41, 会员内容应锁定)" {
    $node = Api GET "/api/graph/node/41" $null $script:token
    if ($node.name -ne "蒸汽机") { throw "name=$($node.name)" }
    if (-not $node.locked) { throw "premium 内容对非会员应锁定" }
    if ($node.prerequisites.Count -lt 1) { throw "无前置" }
}

Step "蒸汽机完整前置链(反向 BFS)" {
    $chain = Api GET "/api/graph/node/41/prerequisites"
    if ($chain.Count -lt 10) { throw "chain=$($chain.Count), 应包含 10+ 项" }
}

Step "AI 问答(规则降级模式)" {
    $r = Api POST "/api/ai/ask" @{ question = "蒸汽机的前置技术有哪些?" } $script:token
    if (-not $r.answer) { throw "无回答" }
    if ($r.sources.Count -lt 1) { throw "无来源" }
}

Step "未登录访问 /api/user/me 应 401" {
    $resp = Invoke-RestMethod -Method GET -Uri "$BaseUrl/api/user/me"
    if ($resp.code -ne 401) { throw "code=$($resp.code), expected 401" }
}

Step "创建会员订单" {
    $r = Api POST "/api/trade/order" @{ productCode = "MEMBER_MONTH" } $script:token
    $script:orderNo = $r.orderNo
    if (-not $r.payUrl) { throw "无 payUrl" }
}

Step "模拟支付回调(首次应核销)" {
    $r = Api POST "/api/pay/mock/notify" @{ orderNo = $script:orderNo }
    if (-not $r.processed) { throw "首次回调应 processed=true" }
}

Step "重复回调应幂等跳过" {
    $r = Api POST "/api/pay/mock/notify" @{ orderNo = $script:orderNo }
    if ($r.processed) { throw "重复回调应 processed=false" }
}

Step "会员已生效 + 锁定内容解锁" {
    $me = Api GET "/api/user/me" $null $script:token
    if (-not $me.member) { throw "会员未生效" }
    $node = Api GET "/api/graph/node/41" $null $script:token
    if ($node.locked) { throw "会员仍被锁定" }
    if (-not $node.detail) { throw "会员未返回 detail" }
}

Step "订单列表含已支付订单" {
    $orders = Api GET "/api/trade/orders" $null $script:token
    $paid = @($orders | Where-Object { $_.orderNo -eq $script:orderNo -and $_.status -eq "PAID" })
    if ($paid.Count -ne 1) { throw "订单状态异常" }
}

Write-Host ""
Write-Host ("结果: {0} 通过, {1} 失败" -f $passed, $failed) -ForegroundColor $(if ($failed -eq 0) { "Green" } else { "Red" })
if ($failed -gt 0) { exit 1 }
