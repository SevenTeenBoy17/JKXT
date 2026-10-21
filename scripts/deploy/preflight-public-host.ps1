#requires -Version 5.1
param(
  [string]$EnvFile = ".env.public",
  [string]$ManifestPath = "",
  [string]$ExpectedHostIp = "",
  [ValidateSet("single-host", "vercel-rewrite")]
  [string]$DeploymentRoute = "single-host",
  [switch]$RequireManifest,
  [switch]$VerifyManifestHashes,
  [switch]$SkipNetwork,
  [switch]$SkipDocker,
  [switch]$SkipCaddyValidate
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"
$Root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..\..")).Path
$ComposeFile = Join-Path $Root "docker-compose.public.yml"
$CaddyFile = Join-Path $Root "deploy\caddy\Caddyfile"
$TargetEnv = if ([System.IO.Path]::IsPathRooted($EnvFile)) { $EnvFile } else { Join-Path $Root $EnvFile }
if ([string]::IsNullOrWhiteSpace($ManifestPath)) {
  $ManifestPath = Join-Path $Root "release-manifest.json"
} elseif (-not [System.IO.Path]::IsPathRooted($ManifestPath)) {
  $ManifestPath = Join-Path $Root $ManifestPath
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

function Get-EnvMap {
  param([string]$Path)
  $map = @{}
  foreach ($line in Get-Content -LiteralPath $Path) {
    $trimmed = $line.Trim()
    if ([string]::IsNullOrWhiteSpace($trimmed) -or $trimmed.StartsWith("#")) {
      continue
    }
    if ($trimmed -match "^\s*([^=]+?)\s*=\s*(.*)\s*$") {
      $key = $matches[1].Trim()
      $value = $matches[2].Trim()
      $map[$key] = $value
    }
  }
  return $map
}

function Test-RequiredPath {
  param([string]$RelativePath, [string]$Kind)
  $path = Join-Path $Root $RelativePath
  if (Test-Path -LiteralPath $path) {
    Add-Check "path:$RelativePath" "ok" "$Kind exists"
  } else {
    Add-Check "path:$RelativePath" "fail" "$Kind is missing"
  }
}

function Invoke-NativeCheck {
  param([string]$Name, [scriptblock]$Command)
  $previousErrorActionPreference = $ErrorActionPreference
  try {
    $ErrorActionPreference = "Continue"
    & $Command *> $null
    $exitCode = $LASTEXITCODE
    if ($exitCode -eq 0) {
      Add-Check $Name "ok" "command exited 0"
    } else {
      Add-Check $Name "fail" "command exited $exitCode"
    }
  } catch {
    Add-Check $Name "fail" $_.Exception.Message
  } finally {
    $ErrorActionPreference = $previousErrorActionPreference
    $global:LASTEXITCODE = 0
  }
}

if (Test-Path -LiteralPath $TargetEnv) {
  Add-Check "env:file" "ok" "found $TargetEnv"
  $envMap = Get-EnvMap -Path $TargetEnv
} else {
  Add-Check "env:file" "fail" "missing $TargetEnv"
  $envMap = @{}
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

foreach ($key in $requiredEnv) {
  if ($envMap.ContainsKey($key) -and -not [string]::IsNullOrWhiteSpace([string]$envMap[$key])) {
    Add-Check "env:$key" "ok" "present"
  } else {
    Add-Check "env:$key" "fail" "missing or empty"
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
  Add-Check "env:domain-placeholder" "fail" "PUBLIC_DOMAIN still uses the example domain"
} elseif ($domain) {
  Add-Check "env:domain-placeholder" "ok" "PUBLIC_DOMAIN is not the example domain"
}

if ($password -match "replace_with_secret_manager_value|change_me_private_password") {
  Add-Check "env:secret-placeholder" "fail" "POSTGRES_PASSWORD is still a placeholder"
} elseif ($password) {
  Add-Check "env:secret-placeholder" "ok" "POSTGRES_PASSWORD is non-placeholder"
}

if ($captchaDebug -match "^\s*true\s*$") {
  Add-Check "env:captcha-debug" "fail" "JKXT_AUTH_CAPTCHA_DEBUG must be false for public production"
} elseif ($captchaDebug) {
  Add-Check "env:captcha-debug" "ok" "captcha debug is not true"
}

if ($cookieSecure -match "^\s*true\s*$") {
  Add-Check "env:cookie-secure" "ok" "secure cookies are required"
} elseif ($cookieSecure) {
  Add-Check "env:cookie-secure" "fail" "JKXT_AUTH_COOKIE_SECURE must be true"
}

foreach ($urlItem in @(
  @{ key = "PUBLIC_APP_URL"; value = $publicAppUrl },
  @{ key = "APP_BASE_URL"; value = $appBaseUrl }
)) {
  try {
    $uri = [Uri]$urlItem.value
    if ($uri.Scheme -ne "https") {
      Add-Check "env:$($urlItem.key)-https" "fail" "$($urlItem.key) must use https"
    } elseif ($domain -and $uri.Host -ne $domain) {
      Add-Check "env:$($urlItem.key)-host" "fail" "$($urlItem.key) host $($uri.Host) does not match PUBLIC_DOMAIN $domain"
    } else {
      Add-Check "env:$($urlItem.key)-url" "ok" "$($urlItem.key) is https and matches PUBLIC_DOMAIN"
    }
  } catch {
    Add-Check "env:$($urlItem.key)-url" "fail" "$($urlItem.key) is not a valid absolute URL"
  }
}

if ($domain -and $cors -match [regex]::Escape("https://$domain")) {
  Add-Check "env:cors-domain" "ok" "CORS contains https://$domain"
} elseif ($cors) {
  Add-Check "env:cors-domain" "fail" "CORS_ALLOWED_ORIGINS does not contain https://$domain"
}

# Caller-selected route, never inferred from an untrusted manifest. Vercel/CI
# workstation identity is not a prerequisite for the single-host Compose bundle.
$routeRequiredPaths = @()
if ($DeploymentRoute -eq "vercel-rewrite") {
  $routeRequiredPaths = @(".vercel/project.json", ".circleci/config.yml", ".circleci/public-preflight.env")
}
foreach ($requiredPath in $routeRequiredPaths) {
  Test-RequiredPath -RelativePath ($requiredPath -replace '/', '\') -Kind "file"
}

foreach ($pathInfo in @(
  @{ path = "Dashboard.html"; kind = "file" },
  @{ path = "docker-compose.public.yml"; kind = "file" },
  @{ path = "deploy\caddy\Caddyfile"; kind = "file" },
  @{ path = "deploy\nginx\private.conf"; kind = "file" },
  @{ path = "backend\Dockerfile"; kind = "file" },
  @{ path = "backend\pom.xml"; kind = "file" },
  @{ path = "src"; kind = "directory" },
  @{ path = "assets"; kind = "directory" },
  @{ path = "assets\vendor\tailwindcss-cdn.js"; kind = "file" },
  @{ path = "assets\vendor\chart.umd.4.4.1.min.js"; kind = "file" },
  @{ path = "assets\vendor\xlsx.full.0.20.3.min.js"; kind = "file" },
  @{ path = "assets\vendor\react.18.3.1.development.js"; kind = "file" },
  @{ path = "assets\vendor\react-dom.18.3.1.development.js"; kind = "file" },
  @{ path = "assets\vendor\babel-standalone.7.29.0.min.js"; kind = "file" },
  @{ path = "uploads"; kind = "directory" },
  @{ path = "scripts\deploy\init-public.ps1"; kind = "file" },
  @{ path = "scripts\deploy\backup-public.ps1"; kind = "file" },
  @{ path = "scripts\deploy\stop-public.ps1"; kind = "file" },
  @{ path = "scripts\deploy\invoke-public-launch-gates.ps1"; kind = "file" },
  @{ path = "scripts\deploy\report-production-cutover-readiness.ps1"; kind = "file" },
  @{ path = "scripts\deploy\discover-production-target.ps1"; kind = "file" },
  @{ path = "scripts\deploy\discover-delivery-capabilities.ps1"; kind = "file" },
  @{ path = "scripts\deploy\prepare-production-cutover-pack.ps1"; kind = "file" },
  @{ path = "scripts\deploy\report-final-delivery-evidence.ps1"; kind = "file" },
  @{ path = "scripts\deploy\assert-final-delivery-evidence.ps1"; kind = "file" },
  @{ path = "scripts\deploy\request-production-t3-approval.ps1"; kind = "file" },
  @{ path = "scripts\deploy\plan-vercel-backend-rewrite.ps1"; kind = "file" },
  @{ path = "scripts\deploy\test-backend-origin-contract.ps1"; kind = "file" },
  @{ path = "scripts\deploy\test-vercel-connected-config.ps1"; kind = "file" },
  @{ path = "scripts\deploy\verify-connected-remote.ps1"; kind = "file" },
  @{ path = "scripts\deploy\verify-remote-browser-ui.ps1"; kind = "file" },
  @{ path = "scripts\deploy\verify-remote-browser-ui.cjs"; kind = "file" },
  @{ path = "scripts\deploy\verify-remote-business-smoke.ps1"; kind = "file" },
  @{ path = "scripts\deploy\audit-production-readiness.ps1"; kind = "file" },
  @{ path = "scripts\deploy\test-public-release.ps1"; kind = "file" }
)) {
  Test-RequiredPath -RelativePath $pathInfo.path -Kind $pathInfo.kind
}

$dashboardPath = Join-Path $Root "Dashboard.html"
if (Test-Path -LiteralPath $dashboardPath) {
  try {
    $dashboardHtml = Get-Content -Raw -LiteralPath $dashboardPath
    $externalScripts = [regex]::Matches($dashboardHtml, '<script[^>]+src=["'']https?://', [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)
    if ($externalScripts.Count -eq 0) {
      Add-Check "frontend:external-scripts" "ok" "Dashboard.html has no external script src values"
    } else {
      Add-Check "frontend:external-scripts" "fail" "Dashboard.html still has $($externalScripts.Count) external script src value(s)"
    }
    foreach ($vendorScript in @(
      "assets/vendor/tailwindcss-cdn.js",
      "assets/vendor/chart.umd.4.4.1.min.js",
      "assets/vendor/xlsx.full.0.20.3.min.js",
      "assets/vendor/react.18.3.1.development.js",
      "assets/vendor/react-dom.18.3.1.development.js",
      "assets/vendor/babel-standalone.7.29.0.min.js"
    )) {
      if ($dashboardHtml.Contains($vendorScript)) {
        Add-Check "frontend:dashboard:$vendorScript" "ok" "referenced"
      } else {
        Add-Check "frontend:dashboard:$vendorScript" "fail" "not referenced"
      }
    }
    if ($dashboardHtml -match "fonts\.googleapis\.com") {
      Add-Check "frontend:external-fonts" "skipped" "Google Fonts remains optional; system font fallbacks are present"
    }
  } catch {
    Add-Check "frontend:dashboard" "fail" $_.Exception.Message
  }
}

if (Test-Path -LiteralPath $ManifestPath) {
  try {
    $manifestRoot = Split-Path -Parent (Resolve-Path -LiteralPath $ManifestPath).Path
    $manifest = Get-Content -Raw -LiteralPath $ManifestPath | ConvertFrom-Json
    Add-Check "manifest:json" "ok" "parsed $ManifestPath"
    if ($manifest.deploymentRoute -and $manifest.deploymentRoute -ne $DeploymentRoute) {
      Add-Check "manifest:deployment-route" "fail" "manifest route does not match selected $DeploymentRoute"
    } elseif ($manifest.deploymentRoute) {
      Add-Check "manifest:deployment-route" "ok" "manifest route matches selected $DeploymentRoute"
    } else {
      Add-Check "manifest:deployment-route" "ok" "legacy manifest without route; enforcing selected $DeploymentRoute requirements"
    }

    $manifestPaths = @($manifest.files | ForEach-Object { [string]$_.path })
    foreach ($requiredPath in (@(
      "Dashboard.html",
      "docker-compose.public.yml",
      "assets/vendor/tailwindcss-cdn.js",
      "assets/vendor/chart.umd.4.4.1.min.js",
      "assets/vendor/xlsx.full.0.20.3.min.js",
      "assets/vendor/react.18.3.1.development.js",
      "assets/vendor/react-dom.18.3.1.development.js",
      "assets/vendor/babel-standalone.7.29.0.min.js",
      "backend/Dockerfile",
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
      "scripts/deploy/verify-remote-browser-ui.ps1",
      "scripts/deploy/verify-remote-browser-ui.cjs",
      "scripts/deploy/verify-remote-business-smoke.ps1",
      "scripts/deploy/audit-production-readiness.ps1",
      "scripts/deploy/test-public-release.ps1"
    ) + $routeRequiredPaths)) {
      if ($manifestPaths -contains $requiredPath) {
        Add-Check "manifest:$requiredPath" "ok" "listed"
      } else {
        Add-Check "manifest:$requiredPath" "fail" "missing from manifest"
      }
    }
    foreach ($forbiddenPath in @(".env.public", ".env.private", ".env.saas")) {
      if ($manifestPaths -contains $forbiddenPath) {
        Add-Check "manifest:forbidden:$forbiddenPath" "fail" "forbidden file listed"
      } else {
        Add-Check "manifest:forbidden:$forbiddenPath" "ok" "not listed"
      }
    }
    if ($manifestPaths | Where-Object { $_ -like "backend/target/*" }) {
      Add-Check "manifest:forbidden:backend-target" "fail" "backend/target files listed"
    } else {
      Add-Check "manifest:forbidden:backend-target" "ok" "backend/target not listed"
    }

    if ($VerifyManifestHashes) {
      $hashFailures = 0
      foreach ($file in @($manifest.files)) {
        $relative = ([string]$file.path) -replace '/', [System.IO.Path]::DirectorySeparatorChar
        $candidate = Join-Path $manifestRoot $relative
        if (-not (Test-Path -LiteralPath $candidate)) {
          $hashFailures++
          continue
        }
        $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $candidate).Hash.ToLowerInvariant()
        if ($hash -ne ([string]$file.sha256).ToLowerInvariant()) {
          $hashFailures++
        }
      }
      if ($hashFailures -eq 0) {
        Add-Check "manifest:hashes" "ok" "all listed files match SHA-256"
      } else {
        Add-Check "manifest:hashes" "fail" "$hashFailures manifest file(s) missing or hash-mismatched"
      }
    } else {
      Add-Check "manifest:hashes" "skipped" "use -VerifyManifestHashes to check every SHA-256"
    }
  } catch {
    Add-Check "manifest:json" "fail" $_.Exception.Message
  }
} elseif ($RequireManifest) {
  Add-Check "manifest:file" "fail" "required manifest not found at $ManifestPath"
} else {
  Add-Check "manifest:file" "skipped" "no release-manifest.json found; pass -RequireManifest on extracted release bundles"
}

if ($SkipDocker) {
  Add-Check "docker:available" "skipped" "SkipDocker was set"
} else {
  if (Get-Command docker -ErrorAction SilentlyContinue) {
    Add-Check "docker:available" "ok" "docker command found"
    Invoke-NativeCheck "docker:version" { docker --version }
    Invoke-NativeCheck "docker:compose-version" { docker compose version }
    Invoke-NativeCheck "docker:compose-config" { docker compose -f $ComposeFile --env-file $TargetEnv config --quiet }
    if ($SkipCaddyValidate) {
      Add-Check "caddy:validate" "skipped" "SkipCaddyValidate was set"
    } else {
      $caddyDir = Split-Path -Parent $CaddyFile
      $volume = "${caddyDir}:/etc/caddy:ro"
      Invoke-NativeCheck "caddy:validate" { docker run --rm -e "PUBLIC_DOMAIN=$domain" -v $volume caddy:2.8-alpine caddy validate --config /etc/caddy/Caddyfile }
    }
  } else {
    Add-Check "docker:available" "fail" "docker command not found"
  }
}

if ($SkipNetwork) {
  Add-Check "network:dns" "skipped" "SkipNetwork was set"
} elseif ($domain) {
  try {
    $addresses = [System.Net.Dns]::GetHostAddresses($domain) | ForEach-Object { $_.IPAddressToString }
    if (@($addresses).Count -eq 0) {
      Add-Check "network:dns" "fail" "no DNS addresses resolved for $domain"
    } elseif ($ExpectedHostIp -and (@($addresses) -notcontains $ExpectedHostIp)) {
      Add-Check "network:dns" "fail" "$domain resolved to $($addresses -join ', '), expected $ExpectedHostIp"
    } else {
      Add-Check "network:dns" "ok" "$domain resolved to $($addresses -join ', ')"
    }
  } catch {
    Add-Check "network:dns" "fail" $_.Exception.Message
  }
}

$failures = @($checks | Where-Object { $_.status -eq "fail" })
$summary = [ordered]@{
  envFile = $TargetEnv
  manifestPath = $ManifestPath
  deploymentRoute = $DeploymentRoute
  checkedAt = (Get-Date).ToString("yyyy-MM-ddTHH:mm:sszzz")
  ok = @($checks | Where-Object { $_.status -eq "ok" }).Count
  skipped = @($checks | Where-Object { $_.status -eq "skipped" }).Count
  failed = @($failures).Count
  checks = $checks
}

[pscustomobject]$summary | ConvertTo-Json -Depth 8
if (@($failures).Count -gt 0) {
  exit 1
}
