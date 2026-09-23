param(
  [string]$AppUrl = "http://127.0.0.1:18180/Dashboard.html",
  [string]$ApiBase = "http://127.0.0.1:18180/api/v1",
  [string]$NodePath = "C:\Users\nuoya\.cache\codex-runtimes\codex-primary-runtime\dependencies\node\node_modules\.pnpm\playwright@1.60.0\node_modules",
  [string]$ChromeChannel = "chrome"
)

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$env:NODE_PATH = $NodePath
$env:APP_URL = $AppUrl
$env:API_BASE = $ApiBase
$env:PLAYWRIGHT_CHROME_CHANNEL = $ChromeChannel
$env:SCREENSHOT_DIR = Join-Path $Root "screenshots"

& node (Join-Path $PSScriptRoot "playwright-e2e.cjs")
if ($LASTEXITCODE -ne 0) {
  throw "P11 Playwright E2E failed"
}
