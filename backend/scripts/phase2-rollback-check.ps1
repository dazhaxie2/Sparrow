# Sparrow Phase 2 M2 全局事务回滚回归脚本。
# 用法:
#   powershell -ExecutionPolicy Bypass -File backend/scripts/phase2-rollback-check.ps1
#
# 行为:
# - 在 sparrow_phase2_check 独立 compose 项目里拉起 mysql/redis/nacos/seata/kafka/neo4j/user/trade/graph/ai,
#   且 sparrow-trade 上注入 TRADE_FAIL_AFTER_MEMBERSHIP_GRANT=true。
# - 通过 gateway:18080 注册用户、下单、伪造支付回调,期望回调以 code=500 失败。
# - 直连 mysql:13307,断言 sparrow_trade.t_order 仍为 CREATED/paid_at IS NULL,
#   并断言 sparrow_user.t_user.member_expire_at IS NULL。
# - 始终在退出前 docker compose down -v 清理。
param(
    [string]$ProjectName = "sparrow_phase2_check",
    [int]$GatewayPort = 18080,
    [int]$MysqlPort = 13307,
    [string]$MysqlRootPassword = "root123"
)

$ErrorActionPreference = "Stop"
$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")

function Compose([string[]]$ExtraArgs) {
    & docker compose `
        -p $ProjectName `
        -f (Join-Path $repoRoot "docker-compose.yml") `
        -f (Join-Path $repoRoot "docker-compose.phase2-check.yml") `
        --profile ai `
        @ExtraArgs
    if ($LASTEXITCODE -ne 0) { throw "docker compose 退出码 $LASTEXITCODE" }
}

function MysqlScalar([string]$Sql) {
    $output = & docker exec "${ProjectName}-mysql-1" `
        mysql -uroot -p$MysqlRootPassword -N -B -e $Sql
    if ($LASTEXITCODE -ne 0) { throw "mysql 查询失败: $Sql" }
    return ($output | Select-Object -First 1).Trim()
}

function ApiCall([string]$Method, [string]$Path, $Body, [string]$Token) {
    $headers = @{}
    if ($Token) { $headers["Authorization"] = "Bearer $Token" }
    $params = @{
        Method = $Method
        Uri = "http://localhost:${GatewayPort}${Path}"
        Headers = $headers
        ContentType = "application/json; charset=utf-8"
    }
    if ($Body) {
        $params.Body = ([System.Text.Encoding]::UTF8.GetBytes(($Body | ConvertTo-Json -Compress)))
    }
    return Invoke-RestMethod @params
}

try {
    Write-Host "==> 启动 phase2-check 环境(TRADE_FAIL_AFTER_MEMBERSHIP_GRANT=true)"
    $env:TRADE_FAIL_AFTER_MEMBERSHIP_GRANT = "true"
    Compose @("up", "-d", "--build",
        "mysql", "redis", "nacos", "seata-server", "kafka", "neo4j",
        "sparrow-user", "sparrow-trade", "sparrow-graph", "sparrow-ai", "sparrow-gateway")

    Write-Host "==> 等待 gateway 健康(最多 180s)"
    $deadline = (Get-Date).AddSeconds(180)
    do {
        Start-Sleep -Seconds 5
        try {
            $health = Invoke-WebRequest -UseBasicParsing -Uri "http://localhost:${GatewayPort}/actuator/health" -TimeoutSec 5
            if ($health.StatusCode -eq 200) { break }
        } catch { }
    } while ((Get-Date) -lt $deadline)

    $user = "rollback$(Get-Random -Maximum 99999)"
    Write-Host "==> 注册并下单: $user"
    $register = ApiCall POST "/api/user/register" @{ username = $user; password = "rollback123" } $null
    if ($register.code -ne 0) { throw "注册失败: $($register.message)" }
    $token = $register.data.token

    $order = ApiCall POST "/api/trade/order" @{ productCode = "MEMBER_MONTH" } $token
    if ($order.code -ne 0) { throw "下单失败: $($order.message)" }
    $orderNo = $order.data.orderNo

    Write-Host "==> 触发支付回调,期望注入故障导致 code=500"
    $notify = $null
    try {
        $notify = ApiCall POST "/api/pay/mock/notify" @{ orderNo = $orderNo; payToken = "ignored" } $null
    } catch {
        Write-Host "回调按预期抛错: $($_.Exception.Message)"
    }
    if ($null -ne $notify -and $notify.code -eq 0) {
        throw "期望全局事务回滚,但回调返回 code=0(故障注入未生效?)"
    }

    Write-Host "==> 校验数据库状态"
    $orderStatus = MysqlScalar "USE sparrow_trade; SELECT status FROM t_order WHERE order_no='$orderNo';"
    $orderPaidAt = MysqlScalar "USE sparrow_trade; SELECT COALESCE(paid_at,'NULL') FROM t_order WHERE order_no='$orderNo';"
    $memberExpire = MysqlScalar "USE sparrow_user; SELECT COALESCE(member_expire_at,'NULL') FROM t_user WHERE username='$user';"

    if ($orderStatus -ne "CREATED") { throw "订单状态应为 CREATED,实际=$orderStatus" }
    if ($orderPaidAt -ne "NULL") { throw "paid_at 应为 NULL,实际=$orderPaidAt" }
    if ($memberExpire -ne "NULL") { throw "member_expire_at 应为 NULL,实际=$memberExpire" }

    Write-Host "[PASS] 全局事务回滚演练通过: order=$orderNo status=CREATED paid_at=NULL member_expire_at=NULL" -ForegroundColor Green
}
finally {
    Write-Host "==> 清理 phase2-check 资源"
    try { Compose @("down", "-v") } catch { Write-Host "down -v 失败: $($_.Exception.Message)" -ForegroundColor Yellow }
    Remove-Item Env:TRADE_FAIL_AFTER_MEMBERSHIP_GRANT -ErrorAction SilentlyContinue
}
