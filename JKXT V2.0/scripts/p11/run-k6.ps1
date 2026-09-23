param(
  [string]$BaseUrl = "http://host.docker.internal:18180/api/v1",
  [string]$K6Image = "grafana/k6:0.49.0"
)

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$ScriptDir = Join-Path $Root "scripts\p11"

& docker run --rm `
  -v "${ScriptDir}:/scripts" `
  -e "BASE_URL=$BaseUrl" `
  $K6Image run /scripts/k6-smoke.js

if ($LASTEXITCODE -ne 0) {
  throw "P11 k6 smoke failed"
}
