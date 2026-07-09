# Runs backend Maven commands with Java 21 without depending on the host JDK.
# Usage:
#   powershell -ExecutionPolicy Bypass -File scripts/mvn17.ps1 test
#   powershell -ExecutionPolicy Bypass -File scripts/mvn17.ps1 -pl sparrow-ai -am compile
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$MavenArgs = @("test")
)

$ErrorActionPreference = "Stop"

$backendRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$image = "maven:3.9-eclipse-temurin-21"
$cacheVolume = "sparrow_m2"

$commonArgs = @(
    "-s", "docker/maven-settings.xml",
    "-B",
    "-ntp",
    "-Daether.connector.basic.threads=1",
    "-Daether.connector.connectTimeout=10000",
    "-Daether.connector.requestTimeout=30000"
)

docker run --rm `
    -v "${backendRoot}:/workspace" `
    -v "${cacheVolume}:/root/.m2" `
    -w /workspace `
    $image `
    mvn @commonArgs @MavenArgs

exit $LASTEXITCODE
