param(
  [string]$EnvFile = ".env.private",
  [switch]$NoBuild
)

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$ComposeFile = Join-Path $Root "docker-compose.private.yml"
$ExampleEnv = Join-Path $Root ".env.private.example"
$TargetEnv = Join-Path $Root $EnvFile

if (-not (Test-Path -LiteralPath $TargetEnv)) {
  Copy-Item -LiteralPath $ExampleEnv -Destination $TargetEnv
  Write-Host "Created $TargetEnv from .env.private.example. Review passwords before production use."
}

$composeArgs = @("compose", "-f", $ComposeFile, "--env-file", $TargetEnv, "up", "-d")
if (-not $NoBuild) {
  $composeArgs += "--build"
}

& docker @composeArgs
if ($LASTEXITCODE -ne 0) {
  throw "docker compose private startup failed"
}

& docker compose -f $ComposeFile --env-file $TargetEnv ps

Write-Host ""
Write-Host "Private JKXT is starting at http://127.0.0.1:18180/Dashboard.html"
Write-Host "UAT demo admin: jwc.demo / Demo@2025"
Write-Host "UAT super admin: ops.demo / Demo@2025"
Write-Host "Run scripts/deploy/private-smoke.ps1 after health is UP."
