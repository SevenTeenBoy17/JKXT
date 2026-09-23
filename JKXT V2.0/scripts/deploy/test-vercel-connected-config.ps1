#requires -Version 5.1
param(
  [string]$ConfigPath = "vercel.connected.json",
  [string]$BackendOrigin = "",
  [string]$OutputPath = "",
  [switch]$AllowHttp,
  [switch]$AllowPrivateBackend,
  [switch]$AllowIncomplete
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

function Test-PrivateHost {
  param([string]$HostName)
  if ([string]::IsNullOrWhiteSpace($HostName)) { return $true }
  $normalized = $HostName.Trim().ToLowerInvariant()
  if (@("localhost", "127.0.0.1", "::1") -contains $normalized) { return $true }
  if ($normalized.EndsWith(".local") -or $normalized.EndsWith(".internal")) { return $true }

  $ip = $null
  if ([System.Net.IPAddress]::TryParse($normalized, [ref]$ip)) {
    if ([System.Net.IPAddress]::IsLoopback($ip)) { return $true }
    $bytes = $ip.GetAddressBytes()
    if ($bytes.Length -eq 4) {
      return (
        $bytes[0] -eq 10 -or
        ($bytes[0] -eq 172 -and $bytes[1] -ge 16 -and $bytes[1] -le 31) -or
        ($bytes[0] -eq 192 -and $bytes[1] -eq 168) -or
        $bytes[0] -eq 127 -or
        ($bytes[0] -eq 169 -and $bytes[1] -eq 254)
      )
    }
    if ($bytes.Length -eq 16) {
      return (
        (($bytes[0] -band 0xfe) -eq 0xfc) -or
        ($bytes[0] -eq 0xfe -and (($bytes[1] -band 0xc0) -eq 0x80))
      )
    }
  }
  return $false
}

function Get-Rewrite {
  param(
    [object]$Config,
    [string]$Source
  )
  if (-not $Config.PSObject.Properties["rewrites"]) { return $null }
  return @($Config.rewrites | Where-Object { [string]$_.source -eq $Source } | Select-Object -First 1)[0]
}

function Get-HeaderBlock {
  param(
    [object]$Config,
    [string]$Source
  )
  if (-not $Config.PSObject.Properties["headers"]) { return $null }
  return @($Config.headers | Where-Object { [string]$_.source -eq $Source } | Select-Object -First 1)[0]
}

function Get-OriginFromDestination {
  param([string]$Destination)
  if ([string]::IsNullOrWhiteSpace($Destination)) { return "" }
  $suffix = "/api/v1/:path*"
  if (-not $Destination.EndsWith($suffix, [System.StringComparison]::Ordinal)) { return "" }
  return $Destination.Substring(0, $Destination.Length - $suffix.Length).TrimEnd("/")
}

$resolvedConfig = Resolve-ProjectPath $ConfigPath
$resolvedOutput = Resolve-ProjectPath $OutputPath
$checks = New-Object System.Collections.ArrayList
$config = $null
$destinationOrigin = ""
$normalizedBackendOrigin = $BackendOrigin.Trim().TrimEnd("/")

if (Test-Path -LiteralPath $resolvedConfig) {
  Add-Check $checks "config:file" "ok" "found $resolvedConfig"
  try {
    $config = Get-Content -Raw -LiteralPath $resolvedConfig | ConvertFrom-Json
    Add-Check $checks "config:json" "ok" "parsed"
  } catch {
    Add-Check $checks "config:json" "fail" $_.Exception.Message
  }
} else {
  Add-Check $checks "config:file" "fail" "missing $resolvedConfig"
}

if ($config) {
  if ($config.version -eq 2) {
    Add-Check $checks "config:version" "ok" "version=2"
  } else {
    Add-Check $checks "config:version" "fail" "expected version=2"
  }

  $apiRewrite = Get-Rewrite -Config $config -Source "/api/v1/:path*"
  if ($apiRewrite) {
    Add-Check $checks "rewrite:api-source" "ok" "/api/v1/:path*"
    $destination = [string]$apiRewrite.destination
    $destinationOrigin = Get-OriginFromDestination -Destination $destination
    if ($destinationOrigin) {
      Add-Check $checks "rewrite:api-destination-shape" "ok" $destination
    } else {
      Add-Check $checks "rewrite:api-destination-shape" "fail" "destination must end with /api/v1/:path*"
    }
  } else {
    Add-Check $checks "rewrite:api-source" "fail" "missing /api/v1/:path* rewrite"
  }

  $rootRewrite = Get-Rewrite -Config $config -Source "/"
  if ($rootRewrite -and [string]$rootRewrite.destination -eq "/Dashboard.html") {
    Add-Check $checks "rewrite:root-dashboard" "ok" "/ -> /Dashboard.html"
  } else {
    Add-Check $checks "rewrite:root-dashboard" "fail" "missing / -> /Dashboard.html rewrite"
  }

  if ($destinationOrigin) {
    try {
      $destinationUri = [Uri]$destinationOrigin
      if (-not $destinationUri.IsAbsoluteUri -or @("http", "https") -notcontains $destinationUri.Scheme) {
        Add-Check $checks "backend:url" "fail" "rewrite destination origin must be absolute http(s)"
      } else {
        Add-Check $checks "backend:url" "ok" $destinationOrigin
        if ($destinationUri.Scheme -eq "https") {
          Add-Check $checks "backend:https" "ok" "https"
        } elseif ($AllowHttp) {
          Add-Check $checks "backend:https" "skipped" "AllowHttp set for local/staging draft"
        } else {
          Add-Check $checks "backend:https" "fail" "production rewrite destination must use https"
        }

        $private = Test-PrivateHost -HostName $destinationUri.Host
        if (-not $private) {
          Add-Check $checks "backend:public-origin" "ok" "host appears public"
        } elseif ($AllowPrivateBackend) {
          Add-Check $checks "backend:public-origin" "skipped" "AllowPrivateBackend set for local/staging draft"
        } else {
          Add-Check $checks "backend:public-origin" "fail" "rewrite destination host is local/private"
        }
      }
    } catch {
      Add-Check $checks "backend:url" "fail" $_.Exception.Message
    }
  }

  if (-not [string]::IsNullOrWhiteSpace($normalizedBackendOrigin) -and $destinationOrigin) {
    if ($destinationOrigin -eq $normalizedBackendOrigin) {
      Add-Check $checks "backend:expected-origin" "ok" $normalizedBackendOrigin
    } else {
      Add-Check $checks "backend:expected-origin" "fail" "expected $normalizedBackendOrigin, got $destinationOrigin"
    }
  } elseif ([string]::IsNullOrWhiteSpace($normalizedBackendOrigin)) {
    Add-Check $checks "backend:expected-origin" "skipped" "BackendOrigin not provided; checked destination shape only"
  }

  $apiHeader = Get-HeaderBlock -Config $config -Source "/api/v1/:path*"
  if ($apiHeader) {
    $headers = @($apiHeader.headers)
    $cache = @($headers | Where-Object { [string]$_.key -eq "Cache-Control" } | Select-Object -First 1)[0]
    $rewriteCache = @($headers | Where-Object { [string]$_.key -eq "x-vercel-enable-rewrite-caching" } | Select-Object -First 1)[0]
    if ($cache -and [string]$cache.value -eq "no-store") {
      Add-Check $checks "headers:api-cache-control" "ok" "no-store"
    } else {
      Add-Check $checks "headers:api-cache-control" "fail" "API rewrite must set Cache-Control=no-store"
    }
    if ($rewriteCache -and [string]$rewriteCache.value -eq "0") {
      Add-Check $checks "headers:api-rewrite-cache" "ok" "0"
    } else {
      Add-Check $checks "headers:api-rewrite-cache" "fail" "API rewrite caching header must be 0"
    }
  } else {
    Add-Check $checks "headers:api" "fail" "missing /api/v1/:path* header block"
  }

  $securityHeader = Get-HeaderBlock -Config $config -Source "/(.*)"
  if ($securityHeader -and @($securityHeader.headers | Where-Object { [string]$_.key -eq "X-Content-Type-Options" -and [string]$_.value -eq "nosniff" }).Count -gt 0) {
    Add-Check $checks "headers:nosniff" "ok" "present"
  } else {
    Add-Check $checks "headers:nosniff" "fail" "missing X-Content-Type-Options=nosniff"
  }
}

$failures = @($checks | Where-Object { $_.status -eq "fail" })
$ready = @($failures).Count -eq 0
$productionSafe = $ready -and (-not $AllowHttp) -and (-not $AllowPrivateBackend)

$report = [pscustomobject][ordered]@{
  configPath = $resolvedConfig
  backendOrigin = $normalizedBackendOrigin
  destinationOrigin = $destinationOrigin
  checkedAt = (Get-Date).ToString("yyyy-MM-ddTHH:mm:sszzz")
  ready = $ready
  productionSafeShape = $productionSafe
  t3ProductionActionPerformed = $false
  ok = @($checks | Where-Object { $_.status -eq "ok" }).Count
  skipped = @($checks | Where-Object { $_.status -eq "skipped" }).Count
  failed = @($failures).Count
  checks = $checks
}

if (-not [string]::IsNullOrWhiteSpace($resolvedOutput)) {
  $parent = Split-Path -Parent $resolvedOutput
  if ($parent -and -not (Test-Path -LiteralPath $parent)) {
    New-Item -ItemType Directory -Force -Path $parent | Out-Null
  }
  $report | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $resolvedOutput -Encoding UTF8
}

$report | ConvertTo-Json -Depth 8

if (-not $ready -and -not $AllowIncomplete) {
  exit 1
}
