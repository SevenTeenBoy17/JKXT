#requires -Version 5.1
param(
  [string]$AppUrl = "https://jkxt-responsive-v2.vercel.app",
  [string]$BackendOrigin = "",
  [string]$PublicEnvPath = ".env.public",
  [string]$ReleaseZipPath = "",
  [string]$ReleaseManifestPath = "",
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

function Add-Check {
  param(
    [System.Collections.ArrayList]$Checks,
    [string]$Name,
    [string]$Status,
    [string]$Detail
  )
  [void]$Checks.Add([ordered]@{
    name = $Name
    status = $Status
    detail = $Detail
  })
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
      Add-Check $Checks $Name "fail" "HTTP $($response.StatusCode) at $Uri"
      return $null
    }
    if ($MinBytes -gt 0 -and $response.Content.Length -lt $MinBytes) {
      Add-Check $Checks $Name "fail" "HTTP $($response.StatusCode), but body was only $($response.Content.Length) bytes"
      return $null
    }
    if ($JsonStatus) {
      $json = $response.Content | ConvertFrom-Json
      if ($json.status -ne $JsonStatus) {
        Add-Check $Checks $Name "fail" "Expected status=$JsonStatus, got status=$($json.status)"
        return $null
      }
      Add-Check $Checks $Name "ok" "HTTP $($response.StatusCode), status=$($json.status)"
      return $json
    }
    Add-Check $Checks $Name "ok" "HTTP $($response.StatusCode), bytes=$($response.Content.Length)"
    return $response
  } catch {
    $status = ""
    if ($_.Exception.Response) {
      try {
        $status = " HTTP " + [int]$_.Exception.Response.StatusCode
      } catch {}
    }
    Add-Check $Checks $Name "fail" "$status $($_.Exception.Message)".Trim()
    return $null
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

$checks = New-Object System.Collections.ArrayList
$normalizedAppUrl = $AppUrl.Trim().TrimEnd("/")
$publicEnv = Resolve-ProjectPath $PublicEnvPath
$releaseZip = Resolve-ProjectPath $ReleaseZipPath
$releaseManifest = Resolve-ProjectPath $ReleaseManifestPath

if ([string]::IsNullOrWhiteSpace($releaseZip)) {
  $releaseZip = Get-LatestReleaseZip
}
if ([string]::IsNullOrWhiteSpace($releaseManifest) -and -not [string]::IsNullOrWhiteSpace($releaseZip)) {
  $releaseManifestDir = Join-Path ([System.IO.Path]::GetDirectoryName($releaseZip)) ([System.IO.Path]::GetFileNameWithoutExtension($releaseZip))
  $releaseManifest = Join-Path $releaseManifestDir "release-manifest.json"
}

try {
  $appUri = [Uri]$normalizedAppUrl
  if (-not $appUri.IsAbsoluteUri -or @("http", "https") -notcontains $appUri.Scheme) {
    Add-Check $checks "target:app-url" "fail" "AppUrl must be absolute http(s)"
  } else {
    Add-Check $checks "target:app-url" "ok" $normalizedAppUrl
  }
} catch {
  Add-Check $checks "target:app-url" "fail" $_.Exception.Message
  $appUri = $null
}

if ($appUri -and -not $SkipNetwork) {
  Invoke-HttpProbe $checks "remote:root" $normalizedAppUrl 1000 | Out-Null
  Invoke-HttpProbe $checks "remote:dashboard" "$normalizedAppUrl/Dashboard.html" 1000 | Out-Null
  $appHealth = Invoke-HttpProbe $checks "remote:api-health" "$normalizedAppUrl/api/v1/health" 0 "UP"
} else {
  Add-Check $checks "remote:network" "skipped" "SkipNetwork was set or AppUrl is invalid"
  $appHealth = $null
}

if ([string]::IsNullOrWhiteSpace($BackendOrigin)) {
  if ($appHealth) {
    Add-Check $checks "target:backend-origin" "ok" "inferred from AppUrl because /api/v1/health is UP"
  } else {
    Add-Check $checks "target:backend-origin" "fail" "BackendOrigin was not provided and AppUrl /api/v1/health is not UP"
  }
} else {
  $backend = $BackendOrigin.Trim().TrimEnd("/")
  try {
    $backendUri = [Uri]$backend
    if (-not $backendUri.IsAbsoluteUri -or @("http", "https") -notcontains $backendUri.Scheme) {
      Add-Check $checks "target:backend-origin" "fail" "BackendOrigin must be absolute http(s)"
    } elseif ($backendUri.Scheme -ne "https" -and -not $AllowHttpBackend) {
      Add-Check $checks "target:backend-origin" "fail" "BackendOrigin must use https unless -AllowHttpBackend is set"
    } elseif ($backendUri.AbsolutePath.Trim("/")) {
      Add-Check $checks "target:backend-origin" "fail" "BackendOrigin must be an origin root without /api/v1 path"
    } else {
      Add-Check $checks "target:backend-origin" "ok" $backend
      if (-not $SkipNetwork) {
        Invoke-HttpProbe $checks "backend:api-health" "$backend/api/v1/health" 0 "UP" | Out-Null
      }
    }
  } catch {
    Add-Check $checks "target:backend-origin" "fail" $_.Exception.Message
  }
}

if (Test-Path -LiteralPath $publicEnv) {
  Add-Check $checks "env:public" "ok" "found $publicEnv"
} else {
  Add-Check $checks "env:public" "fail" "missing $publicEnv"
}

if (Test-Path -LiteralPath $VercelProject) {
  try {
    $project = Get-Content -Raw -LiteralPath $VercelProject | ConvertFrom-Json
    Add-Check $checks "vercel:project" "ok" "projectId=$($project.projectId), orgId=$($project.orgId)"
  } catch {
    Add-Check $checks "vercel:project" "fail" $_.Exception.Message
  }
} else {
  Add-Check $checks "vercel:project" "skipped" ".vercel/project.json not found"
}

if (Test-Path -LiteralPath $VercelConfig) {
  try {
    $vercel = Get-Content -Raw -LiteralPath $VercelConfig | ConvertFrom-Json
    $rewriteSources = @($vercel.rewrites | ForEach-Object { [string]$_.source })
    if ($appUri -and $appUri.Host -like "*.vercel.app") {
      if ($rewriteSources | Where-Object { $_ -like "/api/v1*" -or $_ -like "/api/*" }) {
        Add-Check $checks "vercel:api-rewrite" "ok" "vercel.json contains API rewrite"
      } else {
        Add-Check $checks "vercel:api-rewrite" "fail" "vercel.app target has no /api/v1 rewrite in vercel.json"
      }
    } else {
      Add-Check $checks "vercel:api-rewrite" "skipped" "AppUrl is not a vercel.app host"
    }
  } catch {
    Add-Check $checks "vercel:config" "fail" $_.Exception.Message
  }
} else {
  Add-Check $checks "vercel:config" "skipped" "vercel.json not found"
}

foreach ($tool in @("docker", "vercel", "supabase", "circleci")) {
  $command = Get-Command $tool -ErrorAction SilentlyContinue
  if ($command) {
    Add-Check $checks "tool:$tool" "ok" $command.Source
  } else {
    Add-Check $checks "tool:$tool" "skipped" "$tool CLI not found locally"
  }
}

if (Test-Path -LiteralPath (Join-Path $Root "supabase")) {
  Add-Check $checks "supabase:local-config" "ok" "supabase directory exists"
} else {
  Add-Check $checks "supabase:local-config" "skipped" "no supabase directory; current public package uses Docker PostgreSQL"
}

if (-not [string]::IsNullOrWhiteSpace($releaseZip) -and (Test-Path -LiteralPath $releaseZip)) {
  Add-Type -AssemblyName System.IO.Compression.FileSystem
  $zip = [System.IO.Compression.ZipFile]::OpenRead($releaseZip)
  try {
    Add-Check $checks "release:zip" "ok" "found $releaseZip ($($zip.Entries.Count) entries)"
    foreach ($required in @(
      "Dashboard.html",
      "docker-compose.public.yml",
      "scripts/deploy/init-public.ps1",
      "scripts/deploy/backup-public.ps1",
      "scripts/deploy/preflight-public-host.ps1",
      "scripts/deploy/discover-production-target.ps1",
      "scripts/deploy/test-vercel-connected-config.ps1",
      "scripts/deploy/verify-connected-remote.ps1",
      "scripts/deploy/verify-remote-browser-ui.ps1",
      "scripts/deploy/verify-remote-business-smoke.ps1",
      "scripts/deploy/audit-production-readiness.ps1"
    )) {
      if (Test-ZipEntry -Entries $zip.Entries -Path $required) {
        Add-Check $checks "release:zip:$required" "ok" "listed"
      } else {
        Add-Check $checks "release:zip:$required" "fail" "missing"
      }
    }
  } finally {
    $zip.Dispose()
  }
} else {
  Add-Check $checks "release:zip" "fail" "release zip not found"
}

if (-not [string]::IsNullOrWhiteSpace($releaseManifest) -and (Test-Path -LiteralPath $releaseManifest)) {
  try {
    $manifest = Get-Content -Raw -LiteralPath $releaseManifest | ConvertFrom-Json
    $paths = @($manifest.files | ForEach-Object { [string]$_.path })
    Add-Check $checks "release:manifest" "ok" "parsed $releaseManifest ($($manifest.fileCount) files)"
    foreach ($required in @(
      "Dashboard.html",
      "docker-compose.public.yml",
      "scripts/deploy/preflight-public-host.ps1",
      "scripts/deploy/backup-public.ps1",
      "scripts/deploy/discover-production-target.ps1",
      "scripts/deploy/test-vercel-connected-config.ps1",
      "scripts/deploy/verify-remote-browser-ui.ps1",
      "scripts/deploy/verify-remote-business-smoke.ps1",
      "scripts/deploy/audit-production-readiness.ps1"
    )) {
      if ($paths -contains $required) {
        Add-Check $checks "release:manifest:$required" "ok" "listed"
      } else {
        Add-Check $checks "release:manifest:$required" "fail" "missing"
      }
    }
  } catch {
    Add-Check $checks "release:manifest" "fail" $_.Exception.Message
  }
} else {
  Add-Check $checks "release:manifest" "fail" "release manifest not found"
}

$failures = @($checks | Where-Object { $_.status -eq "fail" })
$nextActions = @()
foreach ($failure in $failures) {
  switch -Wildcard ($failure.name) {
    "remote:api-health" { $nextActions += "Deploy or approve a public backend so $normalizedAppUrl/api/v1/health returns status=UP." }
    "target:backend-origin" { $nextActions += "Provide a reachable BackendOrigin, or use a single-host AppUrl whose /api/v1/health is UP." }
    "env:public" { $nextActions += "Create .env.public from .env.public.example with real PUBLIC_DOMAIN, URLs, and production secrets on the target host." }
    "vercel:api-rewrite" { $nextActions += "After BackendOrigin is healthy, generate/apply a Vercel /api/v1 rewrite with write-vercel-connected-config.ps1." }
    "release:*discover-production-target.ps1" { $nextActions += "Regenerate the public release so it contains discover-production-target.ps1." }
  }
}
$nextActions = @($nextActions | Select-Object -Unique)

$summary = [ordered]@{
  appUrl = $normalizedAppUrl
  checkedAt = (Get-Date).ToString("yyyy-MM-ddTHH:mm:sszzz")
  connected = [bool]$appHealth
  readyForT3Start = (@($failures).Count -eq 0)
  ok = @($checks | Where-Object { $_.status -eq "ok" }).Count
  skipped = @($checks | Where-Object { $_.status -eq "skipped" }).Count
  failed = @($failures).Count
  nextActions = $nextActions
  checks = $checks
}

[pscustomobject]$summary | ConvertTo-Json -Depth 8

if (@($failures).Count -gt 0 -and -not $AllowIncomplete) {
  exit 1
}
