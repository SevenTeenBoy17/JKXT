#requires -Version 5.1
param(
  [Parameter(Mandatory = $true)]
  [string]$AppUrl,

  [string]$ApiBase = "",

  [string]$Username = "jwc.demo",

  [string]$Password = "Demo@2025",

  [switch]$SkipLogin,

  [switch]$ExpectStaticPreview,

  [string]$ScreenshotDir = ".codex-supervisor\browser-smoke",

  [string]$NodeExe = "C:\Users\nuoya\.cache\codex-runtimes\codex-primary-runtime\dependencies\node\bin\node.exe",

  [string]$NodeModules = "C:\Users\nuoya\.cache\codex-runtimes\codex-primary-runtime\dependencies\node\node_modules",

  [string]$ChromeChannel = "chrome",

  [int]$TimeoutSeconds = 45
)

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..\..")).Path
$Script = Join-Path $PSScriptRoot "verify-remote-browser-ui.cjs"

if (-not (Test-Path -LiteralPath $Script)) {
  throw "Missing browser UI verifier: $Script"
}
if (-not (Test-Path -LiteralPath $NodeExe)) {
  throw "Node executable not found: $NodeExe"
}
if (-not (Test-Path -LiteralPath $NodeModules)) {
  throw "Node modules path not found: $NodeModules"
}

if ([System.IO.Path]::IsPathRooted($ScreenshotDir)) {
  $outDir = $ScreenshotDir
} else {
  $outDir = Join-Path $Root $ScreenshotDir
}
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

$pnpmNodeModules = Join-Path $NodeModules ".pnpm\node_modules"
$env:NODE_PATH = if (Test-Path -LiteralPath $pnpmNodeModules) { "$NodeModules;$pnpmNodeModules" } else { $NodeModules }
$env:APP_URL = $AppUrl
$env:API_BASE = $ApiBase
$env:USERNAME = $Username
$env:PASSWORD = $Password
$env:SKIP_LOGIN = if ($SkipLogin) { "1" } else { "0" }
$env:EXPECT_STATIC_PREVIEW = if ($ExpectStaticPreview) { "1" } else { "0" }
$env:SCREENSHOT_DIR = $outDir
$env:PLAYWRIGHT_CHROME_CHANNEL = $ChromeChannel
$env:TIMEOUT_MS = [string]($TimeoutSeconds * 1000)

& $NodeExe $Script
if ($LASTEXITCODE -ne 0) {
  throw "Remote browser UI smoke failed"
}
