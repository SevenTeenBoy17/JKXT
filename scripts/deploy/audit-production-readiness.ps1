#requires -Version 5.1
param(
  [string]$AppUrl = "https://jkxt-responsive-v2.vercel.app",
  [string]$ReleaseZipPath = "",
  [string]$ReleaseManifestPath = "",
  [string]$Username = "jwc.demo",
  [string]$Password = "Demo@2025",
  [switch]$SkipLogin,
  [switch]$BrowserSmoke,
  [switch]$StaticPreviewSmoke,
  [switch]$BusinessSmoke,
  [switch]$AllowRemoteDataWrites,
  [switch]$SkipReleaseBundle,
  [switch]$RequirePublicEnv,
  [switch]$AllowIncomplete,
  [int]$TimeoutSeconds = 45
)

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..\..")).Path
$Verifier = Join-Path $PSScriptRoot "verify-connected-remote.ps1"
$BrowserVerifier = Join-Path $PSScriptRoot "verify-remote-browser-ui.ps1"
$BusinessSmokeVerifier = Join-Path $PSScriptRoot "verify-remote-business-smoke.ps1"
$VercelConfig = Join-Path $Root "vercel.json"
$PublicEnv = Join-Path $Root ".env.public"

function Resolve-ProjectPath {
  param([string]$Path)
  if ([System.IO.Path]::IsPathRooted($Path)) {
    return $Path
  }
  return Join-Path $Root $Path
}

$checks = New-Object System.Collections.ArrayList

function Add-Check {
  param([string]$Name, [string]$Status, [string]$Detail)
  [void]$script:checks.Add([ordered]@{
    name = $Name
    status = $Status
    detail = $Detail
  })
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

function Get-ScriptSources {
  param([string]$Html)
  if ([string]::IsNullOrWhiteSpace($Html)) {
    return @()
  }
  $pattern = '<script\b[^>]*\bsrc\s*=\s*["'']([^"'']+)["'']'
  return @([regex]::Matches($Html, $pattern, [System.Text.RegularExpressions.RegexOptions]::IgnoreCase) |
    ForEach-Object { [string]$_.Groups[1].Value })
}

function Get-ReleaseDashboardHtml {
  param([string]$ZipPath)
  if ([string]::IsNullOrWhiteSpace($ZipPath)) {
    return ""
  }

  $stageDir = Join-Path ([System.IO.Path]::GetDirectoryName($ZipPath)) ([System.IO.Path]::GetFileNameWithoutExtension($ZipPath))
  $stageDashboard = Join-Path $stageDir "Dashboard.html"
  if (Test-Path -LiteralPath $stageDashboard) {
    return Get-Content -Raw -LiteralPath $stageDashboard
  }

  if (-not (Test-Path -LiteralPath $ZipPath)) {
    return ""
  }

  Add-Type -AssemblyName System.IO.Compression.FileSystem
  $zip = [System.IO.Compression.ZipFile]::OpenRead($ZipPath)
  try {
    $entry = $zip.Entries | Where-Object { ($_.FullName -replace '\\', '/') -eq "Dashboard.html" } | Select-Object -First 1
    if (-not $entry) {
      return ""
    }
    $stream = $entry.Open()
    try {
      $reader = [System.IO.StreamReader]::new($stream, [System.Text.Encoding]::UTF8, $true)
      try { return $reader.ReadToEnd() } finally { $reader.Dispose() }
    } finally {
      $stream.Dispose()
    }
  } finally {
    $zip.Dispose()
  }
}

function Get-RemoteDashboardHtml {
  param(
    [string]$Url,
    [int]$TimeoutSeconds
  )
  $uri = [Uri]$Url.Trim().TrimEnd("/")
  $builder = [UriBuilder]::new($uri)
  if ([string]::IsNullOrWhiteSpace($builder.Path) -or $builder.Path -eq "/") {
    $builder.Path = "/Dashboard.html"
  }
  $response = Invoke-WebRequest -UseBasicParsing -Uri $builder.Uri.AbsoluteUri -TimeoutSec $TimeoutSeconds
  return [string]$response.Content
}

function Compare-ScriptSources {
  param(
    [string[]]$Expected,
    [string[]]$Actual
  )
  $expectedText = ($Expected -join "`n")
  $actualText = ($Actual -join "`n")
  if ($expectedText -eq $actualText) {
    return "match"
  }

  $expectedOnly = @($Expected | Where-Object { $Actual -notcontains $_ })
  $actualOnly = @($Actual | Where-Object { $Expected -notcontains $_ })
  return "mismatch; release-only=[$($expectedOnly -join ', ')]; remote-only=[$($actualOnly -join ', ')]"
}

try {
  $uri = [Uri]$AppUrl.Trim().TrimEnd("/")
  if (-not $uri.IsAbsoluteUri -or @("http", "https") -notcontains $uri.Scheme) {
    Add-Check "app:url" "fail" "AppUrl must be an absolute http(s) URL"
  } else {
    Add-Check "app:url" "ok" "$($uri.AbsoluteUri.TrimEnd('/'))"
  }
} catch {
  Add-Check "app:url" "fail" $_.Exception.Message
}

if (Test-Path -LiteralPath $Verifier) {
  Add-Check "script:verify-connected-remote" "ok" "found $Verifier"
  $verifierArgs = @{
    AppUrl = $AppUrl
    TimeoutSeconds = $TimeoutSeconds
  }
  if ($SkipLogin) {
    $verifierArgs.SkipLogin = $true
  }
  try {
    $verifierOutput = & $Verifier @verifierArgs 2>&1
    Add-Check "remote:connected-verifier" "ok" (($verifierOutput | Out-String).Trim())
  } catch {
    Add-Check "remote:connected-verifier" "fail" $_.Exception.Message
  }
} else {
  Add-Check "script:verify-connected-remote" "fail" "missing $Verifier"
}

if (Test-Path -LiteralPath $BrowserVerifier) {
  Add-Check "script:verify-remote-browser-ui" "ok" "found $BrowserVerifier"
  if ($StaticPreviewSmoke) {
    $staticBrowserArgs = @{
      AppUrl = $AppUrl
      SkipLogin = $true
      ExpectStaticPreview = $true
      ScreenshotDir = ".codex-supervisor\browser-smoke-static-preview"
      TimeoutSeconds = $TimeoutSeconds
    }
    try {
      $staticBrowserOutput = & $BrowserVerifier @staticBrowserArgs 2>&1
      Add-Check "remote:static-preview-ui" "ok" (($staticBrowserOutput | Out-String).Trim())
    } catch {
      Add-Check "remote:static-preview-ui" "fail" $_.Exception.Message
    }
  } else {
    Add-Check "remote:static-preview-ui" "skipped" "use -StaticPreviewSmoke to prove visible backend-disconnected preview state"
  }
  if ($BrowserSmoke) {
    $browserArgs = @{
      AppUrl = $AppUrl
      Username = $Username
      Password = $Password
      TimeoutSeconds = $TimeoutSeconds
    }
    if ($SkipLogin) {
      $browserArgs.SkipLogin = $true
    }
    try {
      $browserOutput = & $BrowserVerifier @browserArgs 2>&1
      Add-Check "remote:browser-ui" "ok" (($browserOutput | Out-String).Trim())
    } catch {
      Add-Check "remote:browser-ui" "fail" $_.Exception.Message
    }
  } else {
    Add-Check "remote:browser-ui" "skipped" "use -BrowserSmoke for browser UI verification"
  }
} else {
  Add-Check "script:verify-remote-browser-ui" "fail" "missing $BrowserVerifier"
}

if (Test-Path -LiteralPath $BusinessSmokeVerifier) {
  Add-Check "script:verify-remote-business-smoke" "ok" "found $BusinessSmokeVerifier"
  if ($BusinessSmoke) {
    $businessArgs = @{
      AppUrl = $AppUrl
      Username = $Username
      Password = $Password
      TimeoutSeconds = $TimeoutSeconds
    }
    if ($AllowRemoteDataWrites) {
      $businessArgs.AllowRemoteDataWrites = $true
    }
    try {
      $businessOutput = & $BusinessSmokeVerifier @businessArgs 2>&1
      Add-Check "remote:business-smoke" "ok" (($businessOutput | Out-String).Trim())
    } catch {
      Add-Check "remote:business-smoke" "fail" $_.Exception.Message
    }
  } else {
    Add-Check "remote:business-smoke" "skipped" "use -BusinessSmoke with -AllowRemoteDataWrites after approving test data writes"
  }
} else {
  Add-Check "script:verify-remote-business-smoke" "fail" "missing $BusinessSmokeVerifier"
}

if (Test-Path -LiteralPath $VercelConfig) {
  try {
    $vercel = Get-Content -Raw -LiteralPath $VercelConfig | ConvertFrom-Json
    $rewriteSources = @($vercel.rewrites | ForEach-Object { [string]$_.source })
    if ($AppUrl -match "\.vercel\.app/?$") {
      if ($rewriteSources | Where-Object { $_ -like "/api/v1*" -or $_ -like "/api/*" }) {
        Add-Check "vercel:api-rewrite" "ok" "vercel.json contains an API rewrite"
      } else {
        Add-Check "vercel:api-rewrite" "fail" "AppUrl is a Vercel alias but vercel.json has no /api/v1 rewrite"
      }
    } else {
      Add-Check "vercel:api-rewrite" "skipped" "AppUrl is not a vercel.app alias"
    }
  } catch {
    Add-Check "vercel:config" "fail" $_.Exception.Message
  }
} else {
  Add-Check "vercel:config" "skipped" "vercel.json not found"
}

if ($RequirePublicEnv) {
  if (Test-Path -LiteralPath $PublicEnv) {
    Add-Check "env:public" "ok" ".env.public exists for host audit"
  } else {
    Add-Check "env:public" "fail" ".env.public is required for host audit but missing"
  }
} else {
  Add-Check "env:public" "skipped" "use -RequirePublicEnv on the public host"
}

if ($SkipReleaseBundle) {
  Add-Check "release:bundle" "skipped" "SkipReleaseBundle was set"
} else {
  if ([string]::IsNullOrWhiteSpace($ReleaseZipPath)) {
    $zipPath = Get-LatestReleaseZip
  } else {
    $zipPath = Resolve-ProjectPath $ReleaseZipPath
  }
  if ([string]::IsNullOrWhiteSpace($ReleaseManifestPath) -and -not [string]::IsNullOrWhiteSpace($zipPath)) {
    $manifestPath = Join-Path ([System.IO.Path]::GetDirectoryName($zipPath)) ([System.IO.Path]::GetFileNameWithoutExtension($zipPath))
    $manifestPath = Join-Path $manifestPath "release-manifest.json"
  } else {
    $manifestPath = Resolve-ProjectPath $ReleaseManifestPath
  }

  if (-not [string]::IsNullOrWhiteSpace($zipPath) -and (Test-Path -LiteralPath $zipPath)) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [System.IO.Compression.ZipFile]::OpenRead($zipPath)
    try {
      Add-Check "release:zip" "ok" "found $zipPath ($($zip.Entries.Count) entries)"
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
        "backend/pom.xml",
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
        "scripts/deploy/verify-remote-browser-ui.cjs",
        "scripts/deploy/verify-remote-business-smoke.ps1",
        "scripts/deploy/audit-production-readiness.ps1",
        "scripts/deploy/test-public-release.ps1"
      )) {
        if (Test-ZipEntry -Entries $zip.Entries -Path $required) {
          Add-Check "release:zip:$required" "ok" "listed"
        } else {
          Add-Check "release:zip:$required" "fail" "missing"
        }
      }
      foreach ($forbidden in @(".env.public", ".env.private", ".env.saas")) {
        if (Test-ZipEntry -Entries $zip.Entries -Path $forbidden) {
          Add-Check "release:zip:forbidden:$forbidden" "fail" "forbidden file present"
        } else {
          Add-Check "release:zip:forbidden:$forbidden" "ok" "not present"
        }
      }
      if ($zip.Entries | Where-Object { ($_.FullName -replace '\\', '/') -like "backend/target/*" }) {
        Add-Check "release:zip:forbidden:backend-target" "fail" "backend/target files present"
      } else {
        Add-Check "release:zip:forbidden:backend-target" "ok" "not present"
      }
    } finally {
      $zip.Dispose()
    }
  } else {
    Add-Check "release:zip" "fail" "missing release zip"
  }

  if (-not [string]::IsNullOrWhiteSpace($manifestPath) -and (Test-Path -LiteralPath $manifestPath)) {
    try {
      $manifest = Get-Content -Raw -LiteralPath $manifestPath | ConvertFrom-Json
      $paths = @($manifest.files | ForEach-Object { [string]$_.path })
      Add-Check "release:manifest" "ok" "parsed $manifestPath ($($manifest.fileCount) files)"
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
        "scripts/deploy/stop-public.ps1",
        "scripts/deploy/backup-public.ps1",
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
        "scripts/deploy/verify-remote-browser-ui.cjs",
        "scripts/deploy/verify-remote-business-smoke.ps1",
        "scripts/deploy/audit-production-readiness.ps1",
        "scripts/deploy/test-public-release.ps1"
      )) {
        if ($paths -contains $required) {
          Add-Check "release:manifest:$required" "ok" "listed"
        } else {
          Add-Check "release:manifest:$required" "fail" "missing"
        }
      }
    } catch {
      Add-Check "release:manifest" "fail" $_.Exception.Message
    }
  } else {
    Add-Check "release:manifest" "fail" "missing release manifest"
  }

  try {
    $releaseDashboardHtml = Get-ReleaseDashboardHtml -ZipPath $zipPath
    $releaseScripts = Get-ScriptSources -Html $releaseDashboardHtml
    if (@($releaseScripts).Count -eq 0) {
      Add-Check "remote:release-entrypoint-match" "fail" "release Dashboard.html script refs were not found"
    } else {
      $remoteDashboardHtml = Get-RemoteDashboardHtml -Url $AppUrl -TimeoutSeconds $TimeoutSeconds
      $remoteScripts = Get-ScriptSources -Html $remoteDashboardHtml
      $comparison = Compare-ScriptSources -Expected $releaseScripts -Actual $remoteScripts
      if ($comparison -eq "match") {
        Add-Check "remote:release-entrypoint-match" "ok" "remote Dashboard.html script refs match release bundle ($(@($releaseScripts).Count) refs)"
      } else {
        Add-Check "remote:release-entrypoint-match" "fail" $comparison
      }
    }
  } catch {
    Add-Check "remote:release-entrypoint-match" "fail" $_.Exception.Message
  }
}

$failures = @($checks | Where-Object { $_.status -eq "fail" })
$summary = [ordered]@{
  appUrl = $AppUrl
  checkedAt = (Get-Date).ToString("yyyy-MM-ddTHH:mm:sszzz")
  ready = (@($failures).Count -eq 0)
  ok = @($checks | Where-Object { $_.status -eq "ok" }).Count
  skipped = @($checks | Where-Object { $_.status -eq "skipped" }).Count
  failed = @($failures).Count
  checks = $checks
}

[pscustomobject]$summary | ConvertTo-Json -Depth 8

if (@($failures).Count -gt 0 -and -not $AllowIncomplete) {
  exit 1
}
