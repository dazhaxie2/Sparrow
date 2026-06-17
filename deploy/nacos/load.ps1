# 把 deploy/nacos 下的全部 yaml 灌入 Nacos(DEFAULT_GROUP)。
# 防止 docker compose down -v 后 Nacos Derby 数据丢失。
# 用法:.\deploy\nacos\load.ps1

param(
    [string]$NacosAddr = "localhost:8848"
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Web

Get-ChildItem -Path $PSScriptRoot -Filter "*.yaml" | ForEach-Object {
    $dataId = $_.Name
    $content = Get-Content $_.FullName -Raw -Encoding UTF8
    $body = "dataId=$dataId&group=DEFAULT_GROUP&type=yaml&content=" + [System.Web.HttpUtility]::UrlEncode($content)
    $resp = Invoke-RestMethod -Method Post `
        -Uri "http://$NacosAddr/nacos/v1/cs/configs" `
        -Body $body `
        -ContentType "application/x-www-form-urlencoded;charset=UTF-8"
    Write-Host "[$dataId] -> $resp"
}

Write-Host "Done."
