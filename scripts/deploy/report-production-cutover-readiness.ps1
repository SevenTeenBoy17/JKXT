#requires -Version 5.1
param(
  [string]$AppUrl = "https://jkxt-responsive-v2.vercel.app",
  [string]$BackendOrigin = "",
  [string]$EnvFile = ".env.public",
  [string]$ReleaseZipPath = "",
  [string]$ReleaseManifestPath = "",
  [string]$OutputDir = ".codex-supervisor\cutover-readiness",
  [switch]$AllowHttpBackend,
  [switch]$SkipNetwork,
  [switch]$AllowIncomplete,
  [int]$TimeoutSeconds = 20
)

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..\..")).Path
$VercelProject = Join-Path $Root ".vercel\project.json"
$VercelConfig = Join-Path $Root "vercel.json"

function Resolve-ProjectPath {
  param([string]$Path)
  if ([string]::IsNullOrWhiteSpace($Path)) {
    return ""
  }
  if ([System.IO.Path]::IsPathRooted($Path)) {
    return $Path
  }
  return Join-Path $Root $Path
}

function Get-LatestReleaseZip {
  $releaseDir = Join-Path $Root ".codex-supervisor\releases"
  if (-not (Test-Path -LiteralPath $releaseDir)) {
    return ""
  }
  $latest = Get-ChildItem -LiteralPath $releaseDir -Filter "jkxt-public-*.zip" -File |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
  if ($latest) {
    return $latest.FullName
  }
  return ""
}

function Add-Check {
  param(
    [System.Collections.ArrayList]$Checks,
    [string]$Name,
    [string]$Status,
    [string]$Detail,
    [string]$Category = "general"
  )
  [void]$Checks.Add([ordered]@{
    category = $Category
    name = $Name
    status = $Status
    detail = $Detail
  })
}

function Get-EnvMap {
  param([string]$Path)
  $map = @{}
  foreach ($line in Get-Content -LiteralPath $Path) {
    $trimmed = $line.Trim()
    if ([string]::IsNullOrWhiteSpace($trimmed) -or $trimmed.StartsWith("#")) {
      continue
    }
    if ($trimmed -match "^\s*([^=]+?)\s*=\s*(.*)\s*$") {
      $map[$matches[1].Trim()] = $matches[2].Trim()
    }
  }
  return $map
}

function Get-RedactedEnvState {
  param(
    [hashtable]$EnvMap,
    [string[]]$Keys
  )
  $states = @()
  foreach ($key in $Keys) {
    $present = $EnvMap.ContainsKey($key)
    $value = if ($present) { [string]$EnvMap[$key] } else { "" }
    $secret = $key -match "(PASSWORD|SECRET|TOKEN|KEY)"
    $states += [ordered]@{
      key = $key
      present = $present
      nonEmpty = (-not [string]::IsNullOrWhiteSpace($value))
      secret = $secret
      redacted = $secret
      value = if ($secret) { $null } else { $value }
    }
  }
  return $states
}

function Invoke-HttpProbe {
  param(
    [System.Collections.ArrayList]$Checks,
    [string]$Name,
    [string]$Uri,
    [int]$MinBytes = 0,
    [string]$JsonStatus = ""
  )
  try {
    $response = Invoke-WebRequest -UseBasicParsing -Uri $Uri -TimeoutSec $TimeoutSeconds
    if ($response.StatusCode -lt 200 -or $response.StatusCode -ge 300) {
      Add-Check $Checks $Name "fail" "HTTP $($response.StatusCode) at $Uri" "remote"
      return $false
    }
    if ($MinBytes -gt 0 -and $response.Content.Length -lt $MinBytes) {
      Add-Check $Checks $Name "fail" "HTTP $($response.StatusCode), but body was only $($response.Content.Length) bytes" "remote"
      return $false
    }
    if ($JsonStatus) {
      $json = $response.Content | ConvertFrom-Json
      if ($json.status -ne $JsonStatus) {
        Add-Check $Checks $Name "fail" "Expected status=$JsonStatus, got status=$($json.status)" "remote"
        return $false
      }
      Add-Check $Checks $Name "ok" "HTTP $($response.StatusCode), status=$($json.status)" "remote"
      return $true
    }
    Add-Check $Checks $Name "ok" "HTTP $($response.StatusCode), bytes=$($response.Content.Length)" "remote"
    return $true
  } catch {
    $status = ""
    if ($_.Exception.Response) {
      try {
        $status = " HTTP " + [int]$_.Exception.Response.StatusCode
      } catch {}
    }
    Add-Check $Checks $Name "fail" "$status $($_.Exception.Message)".Trim() "remote"
    return $false
  }
}

function Test-ZipEntry {
  param(
    [object[]]$Entries,
    [string]$Path
  )
  $normalized = $Path -replace "\\", "/"
  return @($Entries | Where-Object { ($_.FullName -replace "\\", "/") -eq $normalized }).Count -gt 0
}

function Write-MarkdownReport {
  param(
    [object]$Report,
    [string]$Path
  )
  $failedChecks = @($Report.checks | Where-Object { $_.status -eq "fail" })
  $lines = New-Object System.Collections.Generic.List[string]
  $lines.Add("# JKXT Production Cutover Readiness")
  $lines.Add("")
  $lines.Add("- Checked: $($Report.checkedAt)")
  $lines.Add("- App URL: $($Report.appUrl)")
  $lines.Add("- Backend origin: $($Report.backendOrigin)")
  $lines.Add("- Redacted: $($Report.redacted)")
  $lines.Add("- Ready for T3 start: $($Report.readyForT3Start)")
  $lines.Add("- Checks: ok $($Report.ok), skipped $($Report.skipped), failed $($Report.failed)")
  $lines.Add("")
  $lines.Add("## Failed Checks")
  if (@($failedChecks).Count -eq 0) {
    $lines.Add("")
    $lines.Add("- None")
  } else {
    foreach ($check in $failedChecks) {
      $lines.Add("- $($check.name) - $($check.detail)")
    }
  }
  $lines.Add("")
  $lines.Add("## Next Actions")
  if (@($Report.nextActions).Count -eq 0) {
    $lines.Add("")
    $lines.Add("- None")
  } else {
    foreach ($action in $Report.nextActions) {
      $lines.Add("- $action")
    }
  }
  $lines.Add("")
  $lines.Add("## Redaction")
  $lines.Add("")
  $lines.Add("Secret-like env keys are reported only as present/non-empty/placeholder checks. Values are not written to this report.")
  Set-Content -LiteralPath $Path -Value $lines -Encoding UTF8
}

$checks = New-Object System.Collections.ArrayList
$normalizedAppUrl = $AppUrl.Trim().TrimEnd("/")
$normalizedBackendOrigin = $BackendOrigin.Trim().TrimEnd("/")
$targetEnv = Resolve-ProjectPath $EnvFile
$releaseZip = Resolve-ProjectPath $ReleaseZipPath
$releaseManifest = Resolve-ProjectPath $ReleaseManifestPath

if ([string]::IsNullOrWhiteSpace($releaseZip)) {
  $releaseZip = Get-LatestReleaseZip
}
if ([string]::IsNullOrWhiteSpace($releaseManifest) -and -not [string]::IsNullOrWhiteSpace($releaseZip)) {
  $releaseManifestDir = Join-Path ([System.IO.Path]::GetDirectoryName($releaseZip)) ([System.IO.Path]::GetFileNameWithoutExtension($releaseZip))
  $releaseManifest = Join-Path $releaseManifestDir "release-manifest.json"
}

$appUri = $null
try {
  $appUri = [Uri]$normalizedAppUrl
  if (-not $appUri.IsAbsoluteUri -or @("http", "https") -notcontains $appUri.Scheme) {
    Add-Check $checks "target:app-url" "fail" "AppUrl must be absolute http(s)" "target"
  } else {
    Add-Check $checks "target:app-url" "ok" $normalizedAppUrl "target"
  }
} catch {
  Add-Check $checks "target:app-url" "fail" $_.Exception.Message "target"
}

$envMap = @{}
$envStates = @()
if (Test-Path -LiteralPath $targetEnv) {
  Add-Check $checks "env:file" "ok" "found $targetEnv" "env"
  $envMap = Get-EnvMap -Path $targetEnv
} else {
  Add-Check $checks "env:file" "fail" "missing $targetEnv" "env"
}

$requiredEnv = @(
  "PUBLIC_DOMAIN",
  "PUBLIC_APP_URL",
  "APP_BASE_URL",
  "CORS_ALLOWED_ORIGINS",
  "POSTGRES_PASSWORD",
  "JKXT_AUTH_CAPTCHA_DEBUG",
  "JKXT_AUTH_COOKIE_SECURE"
)
$envStates = @(Get-RedactedEnvState -EnvMap $envMap -Keys $requiredEnv)
foreach ($state in $envStates) {
  if ($state.present -and $state.nonEmpty) {
    Add-Check $checks "env:$($state.key)" "ok" "present" "env"
  } else {
    Add-Check $checks "env:$($state.key)" "fail" "missing or empty" "env"
  }
}

$domain = [string]$envMap["PUBLIC_DOMAIN"]
$publicAppUrl = [string]$envMap["PUBLIC_APP_URL"]
$appBaseUrl = [string]$envMap["APP_BASE_URL"]
$cors = [string]$envMap["CORS_ALLOWED_ORIGINS"]
$password = [string]$envMap["POSTGRES_PASSWORD"]
$captchaDebug = [string]$envMap["JKXT_AUTH_CAPTCHA_DEBUG"]
$cookieSecure = [string]$envMap["JKXT_AUTH_COOKIE_SECURE"]

if ($domain -match "app\.example\.jkxt\.cn") {
  Add-Check $checks "env:domain-placeholder" "fail" "PUBLIC_DOMAIN still uses the example domain" "env"
} elseif ($domain) {
  Add-Check $checks "env:domain-placeholder" "ok" "PUBLIC_DOMAIN is not the example domain" "env"
}

if ($password -match "replace_with_secret_manager_value|change_me_private_password") {
  Add-Check $checks "env:secret-placeholder" "fail" "POSTGRES_PASSWORD is still a placeholder" "env"
} elseif ($password) {
  Add-Check $checks "env:secret-placeholder" "ok" "POSTGRES_PASSWORD is non-placeholder" "env"
}

if ($captchaDebug -match "^\s*true\s*$") {
  Add-Check $checks "env:captcha-debug" "fail" "JKXT_AUTH_CAPTCHA_DEBUG must be false for public production" "env"
} elseif ($captchaDebug) {
  Add-Check $checks "env:captcha-debug" "ok" "captcha debug is not true" "env"
}

if ($cookieSecure -match "^\s*true\s*$") {
  Add-Check $checks "env:cookie-secure" "ok" "secure cookies are required" "env"
} elseif ($cookieSecure) {
  Add-Check $checks "env:cookie-secure" "fail" "JKXT_AUTH_COOKIE_SECURE must be true" "env"
}

foreach ($urlItem in @(
  @{ key = "PUBLIC_APP_URL"; value = $publicAppUrl },
  @{ key = "APP_BASE_URL"; value = $appBaseUrl }
)) {
  try {
    $uri = [Uri]$urlItem.value
    if ($uri.Scheme -ne "https") {
      Add-Check $checks "env:$($urlItem.key)-https" "fail" "$($urlItem.key) must use https" "env"
    } elseif ($domain -and $uri.Host -ne $domain) {
      Add-Check $checks "env:$($urlItem.key)-host" "fail" "$($urlItem.key) host does not match PUBLIC_DOMAIN" "env"
    } else {
      Add-Check $checks "env:$($urlItem.key)-url" "ok" "$($urlItem.key) is https and matches PUBLIC_DOMAIN" "env"
    }
  } catch {
    Add-Check $checks "env:$($urlItem.key)-url" "fail" "$($urlItem.key) is not a valid absolute URL" "env"
  }
}

if ($domain -and $cors -match [regex]::Escape("https://$domain")) {
  Add-Check $checks "env:cors-domain" "ok" "CORS contains https://PUBLIC_DOMAIN" "env"
} elseif ($cors) {
  Add-Check $checks "env:cors-domain" "fail" "CORS_ALLOWED_ORIGINS does not contain https://PUBLIC_DOMAIN" "env"
}

if (-not $SkipNetwork -and $appUri) {
  Invoke-HttpProbe $checks "remote:root" $normalizedAppUrl 1000 | Out-Null
  Invoke-HttpProbe $checks "remote:dashboard" "$normalizedAppUrl/Dashboard.html" 1000 | Out-Null
  $appHealth = Invoke-HttpProbe $checks "remote:api-health" "$normalizedAppUrl/api/v1/health" 0 "UP"
} else {
  Add-Check $checks "remote:network" "skipped" "SkipNetwork was set or AppUrl is invalid" "remote"
  $appHealth = $false
}

if ([string]::IsNullOrWhiteSpace($normalizedBackendOrigin)) {
  if ($appHealth) {
    Add-Check $checks "target:backend-origin" "ok" "inferred from AppUrl because /api/v1/health is UP" "target"
  } else {
    Add-Check $checks "target:backend-origin" "fail" "BackendOrigin was not provided and AppUrl /api/v1/health is not UP" "target"
  }
} else {
  try {
    $backendUri = [Uri]$normalizedBackendOrigin
    if (-not $backendUri.IsAbsoluteUri -or @("http", "https") -notcontains $backendUri.Scheme) {
      Add-Check $checks "target:backend-origin" "fail" "BackendOrigin must be absolute http(s)" "target"
    } elseif ($backendUri.Scheme -ne "https" -and -not $AllowHttpBackend) {
      Add-Check $checks "target:backend-origin" "fail" "BackendOrigin must use https unless -AllowHttpBackend is set" "target"
    } elseif ($backendUri.AbsolutePath.Trim("/")) {
      Add-Check $checks "target:backend-origin" "fail" "BackendOrigin must be an origin root without /api/v1 path" "target"
    } else {
      Add-Check $checks "target:backend-origin" "ok" $normalizedBackendOrigin "target"
      if (-not $SkipNetwork) {
        Invoke-HttpProbe $checks "backend:api-health" "$normalizedBackendOrigin/api/v1/health" 0 "UP" | Out-Null
      }
    }
  } catch {
    Add-Check $checks "target:backend-origin" "fail" $_.Exception.Message "target"
  }
}

if (Test-Path -LiteralPath $VercelProject) {
  try {
    $project = Get-Content -Raw -LiteralPath $VercelProject | ConvertFrom-Json
    Add-Check $checks "vercel:project" "ok" "projectId=$($project.projectId), orgId=$($project.orgId)" "vercel"
  } catch {
    Add-Check $checks "vercel:project" "fail" $_.Exception.Message "vercel"
  }
} else {
  Add-Check $checks "vercel:project" "skipped" ".vercel/project.json not found" "vercel"
}

if (Test-Path -LiteralPath $VercelConfig) {
  try {
    $vercel = Get-Content -Raw -LiteralPath $VercelConfig | ConvertFrom-Json
    $rewriteSources = @($vercel.rewrites | ForEach-Object { [string]$_.source })
    if ($appUri -and $appUri.Host -like "*.vercel.app") {
      if ($rewriteSources | Where-Object { $_ -like "/api/v1*" -or $_ -like "/api/*" }) {
        Add-Check $checks "vercel:api-rewrite" "ok" "vercel.json contains API rewrite" "vercel"
      } else {
        Add-Check $checks "vercel:api-rewrite" "fail" "vercel.app target has no /api/v1 rewrite in vercel.json" "vercel"
      }
    } else {
      Add-Check $checks "vercel:api-rewrite" "skipped" "AppUrl is not a vercel.app host" "vercel"
    }
  } catch {
    Add-Check $checks "vercel:config" "fail" $_.Exception.Message "vercel"
  }
} else {
  Add-Check $checks "vercel:config" "skipped" "vercel.json not found" "vercel"
}

foreach ($tool in @("docker", "vercel", "supabase", "circleci")) {
  if (Get-Command $tool -ErrorAction SilentlyContinue) {
    Add-Check $checks "tool:$tool" "ok" "$tool CLI found" "tools"
  } else {
    Add-Check $checks "tool:$tool" "skipped" "$tool CLI not found locally" "tools"
  }
}

if (Test-Path -LiteralPath (Join-Path $Root "supabase")) {
  Add-Check $checks "supabase:local-config" "ok" "supabase directory exists" "supabase"
} else {
  Add-Check $checks "supabase:local-config" "skipped" "no supabase directory; current public package uses Docker PostgreSQL" "supabase"
}

foreach ($requiredPath in @(
  "Dashboard.html",
  "docker-compose.public.yml",
  "assets\vendor\tailwindcss-cdn.js",
  "assets\vendor\chart.umd.4.4.1.min.js",
  "assets\vendor\xlsx.full.0.18.5.min.js",
  "assets\vendor\react.18.3.1.development.js",
  "assets\vendor\react-dom.18.3.1.development.js",
  "assets\vendor\babel-standalone.7.29.0.min.js",
  "deploy\caddy\Caddyfile",
  "deploy\nginx\private.conf",
  "backend\Dockerfile",
  "backend\pom.xml",
  "scripts\deploy\init-public.ps1",
  "scripts\deploy\backup-public.ps1",
  "scripts\deploy\stop-public.ps1",
  "scripts\deploy\invoke-public-launch-gates.ps1",
  "scripts\deploy\report-production-cutover-readiness.ps1",
  "scripts\deploy\discover-production-target.ps1",
  "scripts\deploy\discover-delivery-capabilities.ps1",
  "scripts\deploy\prepare-production-cutover-pack.ps1",
  "scripts\deploy\report-final-delivery-evidence.ps1",
  "scripts\deploy\assert-final-delivery-evidence.ps1",
  "scripts\deploy\request-production-t3-approval.ps1",
  "scripts\deploy\plan-vercel-backend-rewrite.ps1",
  "scripts\deploy\test-backend-origin-contract.ps1",
  "scripts\deploy\test-vercel-connected-config.ps1"
)) {
  $candidate = Join-Path $Root $requiredPath
  if (Test-Path -LiteralPath $candidate) {
    Add-Check $checks "path:$requiredPath" "ok" "exists" "files"
  } else {
    Add-Check $checks "path:$requiredPath" "fail" "missing" "files"
  }
}

if (-not [string]::IsNullOrWhiteSpace($releaseZip) -and (Test-Path -LiteralPath $releaseZip)) {
  Add-Type -AssemblyName System.IO.Compression.FileSystem
  $zip = [System.IO.Compression.ZipFile]::OpenRead($releaseZip)
  try {
    Add-Check $checks "release:zip" "ok" "found $releaseZip ($($zip.Entries.Count) entries)" "release"
    foreach ($required in @(
      "Dashboard.html",
      "docker-compose.public.yml",
      "assets/vendor/tailwindcss-cdn.js",
      "assets/vendor/chart.umd.4.4.1.min.js",
      "assets/vendor/xlsx.full.0.18.5.min.js",
      "assets/vendor/react.18.3.1.development.js",
      "assets/vendor/react-dom.18.3.1.development.js",
      "assets/vendor/babel-standalone.7.29.0.min.js",
      ".vercel/project.json",
      "scripts/deploy/init-public.ps1",
      "scripts/deploy/backup-public.ps1",
      "scripts/deploy/stop-public.ps1",
      "scripts/deploy/invoke-public-launch-gates.ps1",
      "scripts/deploy/report-production-cutover-readiness.ps1",
      "scripts/deploy/discover-production-target.ps1",
      "scripts/deploy/discover-delivery-capabilities.ps1",
      "scripts/deploy/prepare-production-cutover-pack.ps1",
      "scripts/deploy/report-final-delivery-evidence.ps1",
      "scripts/deploy/assert-final-delivery-evidence.ps1",
      "scripts/deploy/request-production-t3-approval.ps1",
      "scripts/deploy/plan-vercel-backend-rewrite.ps1",
      "scripts/deploy/test-backend-origin-contract.ps1",
      "scripts/deploy/test-vercel-connected-config.ps1",
      "scripts/deploy/preflight-public-host.ps1",
      "scripts/deploy/verify-connected-remote.ps1",
      "scripts/deploy/verify-remote-browser-ui.ps1",
      "scripts/deploy/verify-remote-business-smoke.ps1",
      "scripts/deploy/audit-production-readiness.ps1"
    )) {
      if (Test-ZipEntry -Entries $zip.Entries -Path $required) {
        Add-Check $checks "release:zip:$required" "ok" "listed" "release"
      } else {
        Add-Check $checks "release:zip:$required" "fail" "missing" "release"
      }
    }
    foreach ($forbidden in @(".env.public", ".env.private", ".env.saas")) {
      if (Test-ZipEntry -Entries $zip.Entries -Path $forbidden) {
        Add-Check $checks "release:zip:forbidden:$forbidden" "fail" "forbidden env file is present" "release"
      } else {
        Add-Check $checks "release:zip:forbidden:$forbidden" "ok" "not present" "release"
      }
    }
  } finally {
    $zip.Dispose()
  }
} else {
  Add-Check $checks "release:zip" "fail" "release zip not found" "release"
}

if (-not [string]::IsNullOrWhiteSpace($releaseManifest) -and (Test-Path -LiteralPath $releaseManifest)) {
  try {
    $manifest = Get-Content -Raw -LiteralPath $releaseManifest | ConvertFrom-Json
    $paths = @($manifest.files | ForEach-Object { [string]$_.path })
    Add-Check $checks "release:manifest" "ok" "parsed $releaseManifest ($($manifest.fileCount) files)" "release"
    foreach ($required in @(
      "Dashboard.html",
      "docker-compose.public.yml",
      "assets/vendor/tailwindcss-cdn.js",
      "assets/vendor/chart.umd.4.4.1.min.js",
      "assets/vendor/xlsx.full.0.18.5.min.js",
      "assets/vendor/react.18.3.1.development.js",
        "assets/vendor/react-dom.18.3.1.development.js",
        "assets/vendor/babel-standalone.7.29.0.min.js",
        ".vercel/project.json",
        "scripts/deploy/backup-public.ps1",
        "scripts/deploy/stop-public.ps1",
      "scripts/deploy/invoke-public-launch-gates.ps1",
      "scripts/deploy/report-production-cutover-readiness.ps1",
      "scripts/deploy/discover-production-target.ps1",
      "scripts/deploy/discover-delivery-capabilities.ps1",
      "scripts/deploy/prepare-production-cutover-pack.ps1",
      "scripts/deploy/report-final-delivery-evidence.ps1",
      "scripts/deploy/assert-final-delivery-evidence.ps1",
      "scripts/deploy/request-production-t3-approval.ps1",
      "scripts/deploy/plan-vercel-backend-rewrite.ps1",
      "scripts/deploy/test-backend-origin-contract.ps1",
      "scripts/deploy/test-vercel-connected-config.ps1",
      "scripts/deploy/preflight-public-host.ps1",
      "scripts/deploy/verify-remote-browser-ui.ps1",
      "scripts/deploy/verify-remote-business-smoke.ps1",
      "scripts/deploy/audit-production-readiness.ps1"
    )) {
      if ($paths -contains $required) {
        Add-Check $checks "release:manifest:$required" "ok" "listed" "release"
      } else {
        Add-Check $checks "release:manifest:$required" "fail" "missing" "release"
      }
    }
  } catch {
    Add-Check $checks "release:manifest" "fail" $_.Exception.Message "release"
  }
} else {
  Add-Check $checks "release:manifest" "fail" "release manifest not found" "release"
}

$failures = @($checks | Where-Object { $_.status -eq "fail" })
$nextActions = New-Object System.Collections.Generic.List[string]
foreach ($failure in $failures) {
  switch -Wildcard ($failure.name) {
    "remote:api-health" { $nextActions.Add("Deploy or approve a public backend so AppUrl /api/v1/health returns status=UP.") }
    "backend:api-health" { $nextActions.Add("Fix or provision the BackendOrigin so BackendOrigin /api/v1/health returns status=UP.") }
    "target:backend-origin" { $nextActions.Add("Provide a reachable BackendOrigin, or use a single-host AppUrl whose /api/v1/health is UP.") }
    "env:domain-placeholder" { $nextActions.Add("Replace PUBLIC_DOMAIN/PUBLIC_APP_URL/APP_BASE_URL example values on the target host.") }
    "env:secret-placeholder" { $nextActions.Add("Replace placeholder production secrets on the target host.") }
    "env:captcha-debug" { $nextActions.Add("Set JKXT_AUTH_CAPTCHA_DEBUG=false for production go-live.") }
    "env:cookie-secure" { $nextActions.Add("Set JKXT_AUTH_COOKIE_SECURE=true for HTTPS production go-live.") }
    "env:*" { $nextActions.Add("Complete .env.public with required public URLs, CORS origin, and production-safe auth settings.") }
    "vercel:api-rewrite" { $nextActions.Add("After BackendOrigin is healthy, generate/apply a Vercel /api/v1 rewrite with write-vercel-connected-config.ps1.") }
    "release:*" { $nextActions.Add("Regenerate the public release bundle so the required deployment scripts and manifest entries are present.") }
  }
}
$uniqueNextActions = @($nextActions | Select-Object -Unique)

$outputPath = Resolve-ProjectPath $OutputDir
if (-not [string]::IsNullOrWhiteSpace($outputPath)) {
  New-Item -ItemType Directory -Force -Path $outputPath | Out-Null
}
$checkedAt = (Get-Date).ToString("yyyy-MM-ddTHH:mm:sszzz")
$safeStamp = (Get-Date).ToString("yyyyMMdd-HHmmss")
$jsonPath = if ($outputPath) { Join-Path $outputPath "cutover-readiness-$safeStamp.json" } else { "" }
$markdownPath = if ($outputPath) { Join-Path $outputPath "cutover-readiness-$safeStamp.md" } else { "" }

$summary = [ordered]@{
  appUrl = $normalizedAppUrl
  backendOrigin = $normalizedBackendOrigin
  envFile = $targetEnv
  releaseZip = $releaseZip
  releaseManifest = $releaseManifest
  checkedAt = $checkedAt
  redacted = $true
  readyForT3Start = (@($failures).Count -eq 0)
  ok = @($checks | Where-Object { $_.status -eq "ok" }).Count
  skipped = @($checks | Where-Object { $_.status -eq "skipped" }).Count
  failed = @($failures).Count
  env = $envStates
  nextActions = $uniqueNextActions
  reportJson = $jsonPath
  reportMarkdown = $markdownPath
  checks = $checks
}

$report = [pscustomobject]$summary
if ($jsonPath) {
  $report | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $jsonPath -Encoding UTF8
  Write-MarkdownReport -Report $report -Path $markdownPath
}

$report | ConvertTo-Json -Depth 8
if (@($failures).Count -gt 0 -and -not $AllowIncomplete) {
  exit 1
}
