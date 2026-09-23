param(
  [string]$AppUrl = "http://127.0.0.1:18180/Dashboard.html",
  [string]$ApiBase = "http://127.0.0.1:18180/api/v1",
  [int]$Rounds = 3,
  [string]$NodePath = "C:\Users\nuoya\.cache\codex-runtimes\codex-primary-runtime\dependencies\node\node_modules\.pnpm\playwright@1.60.0\node_modules",
  [string]$ChromeExecutable = "C:\Program Files\Google\Chrome\Application\chrome.exe"
)

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$env:NODE_PATH = $NodePath
$env:APP_URL = $AppUrl
$env:API_BASE = $ApiBase
$env:ROUNDS = [string]$Rounds
$env:PLAYWRIGHT_EXECUTABLE_PATH = $ChromeExecutable
$env:SCREENSHOT_DIR = Join-Path $Root "screenshots"
$env:RUN_ID = "20260610-three-global-fullflow"

& node (Join-Path $PSScriptRoot "global-fullflow-e2e.cjs")
if ($LASTEXITCODE -ne 0) {
  throw "Global full-flow E2E failed"
}
