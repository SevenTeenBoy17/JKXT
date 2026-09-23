#requires -Version 5.1
param(
  [string]$AppUrl = "https://jkxt-responsive-v2.vercel.app",
  [string]$BackendOrigin = "",
  [string]$ReleaseZipPath = "",
  [string]$OutputDir = ".codex-supervisor\delivery-capabilities",
  [switch]$AllowHttpBackend,
  [switch]$SkipNetwork,
  [switch]$AllowIncomplete,
  [int]$TimeoutSeconds = 20
)

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..\..")).Path

function Resolve-ProjectPath {
  param([string]$Path)
  if ([string]::IsNullOrWhiteSpace($Path)) { return "" }
  if ([System.IO.Path]::IsPathRooted($Path)) { return $Path }
  return Join-Path $Root $Path
}

function Add-Check {
  param(
    [System.Collections.ArrayList]$Checks,
    [string]$Category,
    [string]$Name,
    [string]$Status,
    [string]$Detail
  )
  [void]$Checks.Add([ordered]@{
    category = $Category
    name = $Name
    status = $Status
    detail = $Detail
  })
}

function Invoke-HealthProbe {
  param(
    [System.Collections.ArrayList]$Checks,
    [string]$Name,
    [string]$Uri
  )
  if ($SkipNetwork) {
    Add-Check $Checks "runtime" $Name "skipped" "SkipNetwork was set"
    return $false
  }
  try {
    $response = Invoke-WebRequest -UseBasicParsing -Uri $Uri -TimeoutSec $TimeoutSeconds
    $json = $response.Content | ConvertFrom-Json
    if ($response.StatusCode -ge 200 -and $response.StatusCode -lt 300 -and $json.status -eq "UP") {
      Add-Check $Checks "runtime" $Name "ok" "HTTP $($response.StatusCode), status=UP"
      return $true
    }
    Add-Check $Checks "runtime" $Name "fail" "Expected status=UP, got HTTP $($response.StatusCode), status=$($json.status)"
    return $false
  } catch {
    $status = ""
    if ($_.Exception.Response) {
      try { $status = "HTTP " + [int]$_.Exception.Response.StatusCode + " " } catch {}
    }
    Add-Check $Checks "runtime" $Name "fail" "$status$($_.Exception.Message)"
    return $false
  }
}

function Invoke-PageProbe {
  param(
    [System.Collections.ArrayList]$Checks,
    [string]$Name,
    [string]$Uri,
    [int]$MinBytes = 1000
  )
  if ($SkipNetwork) {
    Add-Check $Checks "runtime" $Name "skipped" "SkipNetwork was set"
    return $false
  }
  try {
    $response = Invoke-WebRequest -UseBasicParsing -Uri $Uri -TimeoutSec $TimeoutSeconds
    if ($response.StatusCode -ge 200 -and $response.StatusCode -lt 300 -and $response.Content.Length -ge $MinBytes) {
      Add-Check $Checks "runtime" $Name "ok" "HTTP $($response.StatusCode), bytes=$($response.Content.Length)"
      return $true
    }
    Add-Check $Checks "runtime" $Name "fail" "HTTP $($response.StatusCode), bytes=$($response.Content.Length)"
    return $false
  } catch {
    $status = ""
    if ($_.Exception.Response) {
      try { $status = "HTTP " + [int]$_.Exception.Response.StatusCode + " " } catch {}
    }
    Add-Check $Checks "runtime" $Name "fail" "$status$($_.Exception.Message)"
    return $false
  }
}

function Test-ZipEntry {
  param(
    [object[]]$Entries,
    [string]$Path
  )
  $normalized = $Path -replace '\\', '/'
  return @($Entries | Where-Object { ($_.FullName -replace '\\', '/') -eq $normalized }).Count -gt 0
}

function Get-LatestReleaseZip {
  $releaseDir = Join-Path $Root ".codex-supervisor\releases"
  if (-not (Test-Path -LiteralPath $releaseDir)) { return "" }
  $latest = Get-ChildItem -LiteralPath $releaseDir -Filter "jkxt-public-*.zip" -File |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
  if ($latest) { return $latest.FullName }
  return ""
}

function Write-MarkdownReport {
  param(
    [object]$Report,
    [string]$Path
  )
  $failed = @($Report.checks | Where-Object { $_.status -eq "fail" })
  $lines = New-Object System.Collections.Generic.List[string]
  $lines.Add("# JKXT Delivery Capabilities")
  $lines.Add("")
  $lines.Add("- Checked: $($Report.checkedAt)")
  $lines.Add("- App URL: $($Report.appUrl)")
  $lines.Add("- Backend origin: $($Report.backendOrigin)")
  $lines.Add("- Connected production ready: $($Report.connectedProductionReady)")
  $lines.Add("- T3 production action performed: $($Report.t3ProductionActionPerformed)")
  $lines.Add("- Checks: ok $($Report.ok), skipped $($Report.skipped), failed $($Report.failed)")
  $lines.Add("")
  $lines.Add("## Failed Checks")
  if (@($failed).Count -eq 0) {
    $lines.Add("")
    $lines.Add("- None")
  } else {
    foreach ($check in $failed) {
      $lines.Add("- [$($check.category)] $($check.name) - $($check.detail)")
    }
  }
  $lines.Add("")
  $lines.Add("## Next Actions")
  foreach ($action in @($Report.nextActions)) {
    $lines.Add("- $action")
  }
  $lines.Add("")
  $lines.Add("## Notes")
  $lines.Add("- This report is read-only. It does not deploy, log in, write secrets, mutate Supabase, trigger CircleCI cloud, or run remote data-write smoke.")
  Set-Content -LiteralPath $Path -Value $lines -Encoding UTF8
}

$checks = New-Object System.Collections.ArrayList
$normalizedAppUrl = $AppUrl.Trim().TrimEnd("/")
$normalizedBackendOrigin = $BackendOrigin.Trim().TrimEnd("/")
$releaseZip = Resolve-ProjectPath $ReleaseZipPath
if ([string]::IsNullOrWhiteSpace($releaseZip)) {
  $releaseZip = Get-LatestReleaseZip
}

try {
  $appUri = [Uri]$normalizedAppUrl
  if ($appUri.IsAbsoluteUri -and @("http", "https") -contains $appUri.Scheme) {
    Add-Check $checks "target" "app-url" "ok" $normalizedAppUrl
  } else {
    Add-Check $checks "target" "app-url" "fail" "AppUrl must be absolute http(s)"
    $appUri = $null
  }
} catch {
  Add-Check $checks "target" "app-url" "fail" $_.Exception.Message
  $appUri = $null
}

$appHealthUp = $false
if ($appUri) {
  Invoke-PageProbe $checks "app-root" $normalizedAppUrl | Out-Null
  Invoke-PageProbe $checks "dashboard" "$normalizedAppUrl/Dashboard.html" | Out-Null
  $appHealthUp = Invoke-HealthProbe $checks "app-api-health" "$normalizedAppUrl/api/v1/health"
}

if ([string]::IsNullOrWhiteSpace($normalizedBackendOrigin)) {
  if ($appHealthUp) {
    Add-Check $checks "target" "backend-origin" "ok" "inferred from AppUrl because /api/v1/health is UP"
  } else {
    Add-Check $checks "target" "backend-origin" "fail" "BackendOrigin was not provided and AppUrl /api/v1/health is not UP"
  }
} else {
  try {
    $backendUri = [Uri]$normalizedBackendOrigin
    if (-not $backendUri.IsAbsoluteUri -or @("http", "https") -notcontains $backendUri.Scheme) {
      Add-Check $checks "target" "backend-origin" "fail" "BackendOrigin must be absolute http(s)"
    } elseif ($backendUri.Scheme -ne "https" -and -not $AllowHttpBackend) {
      Add-Check $checks "target" "backend-origin" "fail" "BackendOrigin must use https unless -AllowHttpBackend is set"
    } elseif ($backendUri.AbsolutePath.Trim("/")) {
      Add-Check $checks "target" "backend-origin" "fail" "BackendOrigin must be an origin root without /api/v1 path"
    } else {
      Add-Check $checks "target" "backend-origin" "ok" $normalizedBackendOrigin
      Invoke-HealthProbe $checks "backend-api-health" "$normalizedBackendOrigin/api/v1/health" | Out-Null
    }
  } catch {
    Add-Check $checks "target" "backend-origin" "fail" $_.Exception.Message
  }
}

$vercelProject = Join-Path $Root ".vercel\project.json"
if (Test-Path -LiteralPath $vercelProject) {
  try {
    $project = Get-Content -Raw -LiteralPath $vercelProject | ConvertFrom-Json
    Add-Check $checks "vercel" "project-link" "ok" "projectId=$($project.projectId), orgId=$($project.orgId), name=$($project.projectName)"
  } catch {
    Add-Check $checks "vercel" "project-link" "fail" $_.Exception.Message
  }
} else {
  Add-Check $checks "vercel" "project-link" "fail" ".vercel/project.json is missing"
}

$vercelConfig = Join-Path $Root "vercel.json"
if (Test-Path -LiteralPath $vercelConfig) {
  try {
    $vercel = Get-Content -Raw -LiteralPath $vercelConfig | ConvertFrom-Json
    $rewriteSources = @($vercel.rewrites | ForEach-Object { [string]$_.source })
    if ($appUri -and $appUri.Host -like "*.vercel.app") {
      if ($rewriteSources | Where-Object { $_ -like "/api/v1*" -or $_ -like "/api/*" }) {
        Add-Check $checks "vercel" "api-rewrite" "ok" "vercel.json contains API rewrite"
      } else {
        Add-Check $checks "vercel" "api-rewrite" "fail" "vercel.app target has no /api/v1 rewrite in vercel.json"
      }
    } else {
      Add-Check $checks "vercel" "api-rewrite" "skipped" "AppUrl is not a vercel.app host"
    }
  } catch {
    Add-Check $checks "vercel" "config" "fail" $_.Exception.Message
  }
} else {
  Add-Check $checks "vercel" "config" "skipped" "vercel.json not found"
}

foreach ($tool in @("vercel", "supabase", "circleci", "expo", "eas", "coderabbit", "node", "docker")) {
  $command = Get-Command $tool -ErrorAction SilentlyContinue
  if ($command) {
    Add-Check $checks "tools" $tool "ok" $command.Source
  } else {
    Add-Check $checks "tools" $tool "skipped" "$tool CLI not found locally"
  }
}

if (Test-Path -LiteralPath (Join-Path $Root ".circleci\config.yml")) {
  Add-Check $checks "circleci" "config" "ok" ".circleci/config.yml exists"
} else {
  Add-Check $checks "circleci" "config" "fail" ".circleci/config.yml is missing"
}
if (Test-Path -LiteralPath (Join-Path $Root ".circleci\public-preflight.env")) {
  Add-Check $checks "circleci" "public-preflight-env" "ok" ".circleci/public-preflight.env exists"
} else {
  Add-Check $checks "circleci" "public-preflight-env" "fail" ".circleci/public-preflight.env is missing"
}

if (Test-Path -LiteralPath (Join-Path $Root "supabase")) {
  Add-Check $checks "supabase" "local-config" "ok" "supabase directory exists"
} else {
  Add-Check $checks "supabase" "local-config" "skipped" "no supabase directory; current package uses Docker PostgreSQL"
}

$packageJson = Join-Path $Root "package.json"
$expoFiles = @("app.json", "app.config.js", "app.config.ts", "eas.json") | ForEach-Object { Join-Path $Root $_ }
if (Test-Path -LiteralPath $packageJson) {
  try {
    $package = Get-Content -Raw -LiteralPath $packageJson | ConvertFrom-Json
    $deps = @()
    if ($package.dependencies) { $deps += $package.dependencies.PSObject.Properties.Name }
    if ($package.devDependencies) { $deps += $package.devDependencies.PSObject.Properties.Name }
    if ($deps -contains "expo") {
      Add-Check $checks "expo" "project" "ok" "package.json depends on expo"
    } else {
      Add-Check $checks "expo" "project" "skipped" "package.json exists but no expo dependency"
    }
  } catch {
    Add-Check $checks "expo" "package-json" "fail" $_.Exception.Message
  }
} elseif (@($expoFiles | Where-Object { Test-Path -LiteralPath $_ }).Count -gt 0) {
  Add-Check $checks "expo" "project" "ok" "Expo/EAS config file exists"
} else {
  Add-Check $checks "expo" "project" "skipped" "no package.json, app config, or eas.json; current deliverable is web/Spring Boot"
}

Add-Check $checks "jam" "session" "skipped" "no Jam session id was provided for recorded browser diagnostics"
Add-Check $checks "coderabbit" "review-context" "skipped" "no PR number/CodeRabbit CLI auth context available; use code-review-graph fallback"

foreach ($browserFile in @("scripts\deploy\verify-remote-browser-ui.ps1", "scripts\deploy\verify-remote-browser-ui.cjs")) {
  if (Test-Path -LiteralPath (Join-Path $Root $browserFile)) {
    Add-Check $checks "browser" $browserFile "ok" "exists"
  } else {
    Add-Check $checks "browser" $browserFile "fail" "missing"
  }
}

if (-not [string]::IsNullOrWhiteSpace($releaseZip) -and (Test-Path -LiteralPath $releaseZip)) {
  Add-Type -AssemblyName System.IO.Compression.FileSystem
  $zip = [System.IO.Compression.ZipFile]::OpenRead($releaseZip)
  try {
    Add-Check $checks "release" "zip" "ok" "found $releaseZip ($($zip.Entries.Count) entries)"
    foreach ($required in @(
      "scripts/deploy/discover-delivery-capabilities.ps1",
      "scripts/deploy/discover-production-target.ps1",
      "scripts/deploy/report-final-delivery-evidence.ps1",
      "scripts/deploy/assert-final-delivery-evidence.ps1",
      "scripts/deploy/request-production-t3-approval.ps1",
      "scripts/deploy/plan-vercel-backend-rewrite.ps1",
      "scripts/deploy/test-backend-origin-contract.ps1",
      "scripts/deploy/test-vercel-connected-config.ps1",
      "scripts/deploy/verify-remote-browser-ui.ps1",
      "scripts/deploy/verify-remote-business-smoke.ps1",
      ".vercel/project.json",
      ".circleci/config.yml",
      ".circleci/public-preflight.env",
      "docs/DEPLOYMENT.md",
      "docs/CONNECTED-PRODUCTION-DEPLOYMENT.md"
    )) {
      if (Test-ZipEntry -Entries $zip.Entries -Path $required) {
        Add-Check $checks "release" "zip:$required" "ok" "listed"
      } else {
        Add-Check $checks "release" "zip:$required" "fail" "missing"
      }
    }
  } finally {
    $zip.Dispose()
  }
} else {
  Add-Check $checks "release" "zip" "fail" "release zip not found"
}

$failures = @($checks | Where-Object { $_.status -eq "fail" })
$nextActions = New-Object System.Collections.Generic.List[string]
foreach ($failure in $failures) {
  switch -Wildcard ($failure.category + ":" + $failure.name) {
    "runtime:app-api-health" { $nextActions.Add("Expose a connected public /api/v1 backend so AppUrl /api/v1/health returns status=UP.") }
    "target:backend-origin" { $nextActions.Add("Provide a public HTTPS BackendOrigin, or use a single-host AppUrl whose /api/v1/health is UP.") }
    "vercel:api-rewrite" { $nextActions.Add("After BackendOrigin is healthy, generate/apply the Vercel /api/v1 rewrite before production deploy.") }
    "circleci:*" { $nextActions.Add("Restore CircleCI local readiness config before relying on CI release gates.") }
    "browser:*" { $nextActions.Add("Restore browser verification scripts before production handoff.") }
    "release:*" { $nextActions.Add("Regenerate the release bundle so it contains the latest delivery capability gate.") }
  }
}
if (@($checks | Where-Object { $_.category -eq "supabase" -and $_.status -ne "ok" }).Count -gt 0) {
  $nextActions.Add("Use Docker PostgreSQL as packaged, or provision/activate a Supabase project only after explicit cost and T3 approval.")
}
if (@($checks | Where-Object { $_.category -eq "coderabbit" -and $_.status -ne "ok" }).Count -gt 0) {
  $nextActions.Add("Use code-review-graph/manual review unless a PR and authenticated CodeRabbit CLI are available.")
}
$uniqueNextActions = @($nextActions | Select-Object -Unique)

$outputRoot = Resolve-ProjectPath $OutputDir
New-Item -ItemType Directory -Force -Path $outputRoot | Out-Null
$stamp = (Get-Date).ToString("yyyyMMdd-HHmmss")
$jsonPath = Join-Path $outputRoot "delivery-capabilities-$stamp.json"
$markdownPath = Join-Path $outputRoot "delivery-capabilities-$stamp.md"

$report = [pscustomobject][ordered]@{
  appUrl = $normalizedAppUrl
  backendOrigin = $normalizedBackendOrigin
  checkedAt = (Get-Date).ToString("yyyy-MM-ddTHH:mm:sszzz")
  connectedProductionReady = (@($failures).Count -eq 0 -and $appHealthUp)
  t3ProductionActionPerformed = $false
  ok = @($checks | Where-Object { $_.status -eq "ok" }).Count
  skipped = @($checks | Where-Object { $_.status -eq "skipped" }).Count
  failed = @($failures).Count
  reportJson = $jsonPath
  reportMarkdown = $markdownPath
  nextActions = $uniqueNextActions
  checks = $checks
}

$report | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $jsonPath -Encoding UTF8
Write-MarkdownReport -Report $report -Path $markdownPath
$report | ConvertTo-Json -Depth 8

if (@($failures).Count -gt 0 -and -not $AllowIncomplete) {
  exit 1
}
