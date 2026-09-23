#requires -Version 5.1
param(
  [Parameter(Mandatory = $true)]
  [string]$BackendOrigin,

  [switch]$AllowHttp,
  [switch]$AllowPrivateBackend,
  [switch]$SkipNetwork,
  [switch]$AllowIncomplete,
  [int]$TimeoutSeconds = 20
)

$ErrorActionPreference = "Stop"

function Add-Check {
  param(
    [System.Collections.ArrayList]$Checks,
    [string]$Name,
    [ValidateSet("ok", "fail", "skipped")]
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

function Invoke-HealthProbe {
  param(
    [System.Collections.ArrayList]$Checks,
    [string]$BaseUrl
  )
  if ($SkipNetwork) {
    Add-Check $Checks "backend:api-health" "skipped" "SkipNetwork was set"
    return $false
  }
  $uri = "$BaseUrl/api/v1/health"
  try {
    $response = Invoke-WebRequest -UseBasicParsing -Uri $uri -TimeoutSec $TimeoutSeconds
    $json = $response.Content | ConvertFrom-Json
    if ($response.StatusCode -ge 200 -and $response.StatusCode -lt 300 -and $json.status -eq "UP") {
      Add-Check $Checks "backend:api-health" "ok" "HTTP $($response.StatusCode), status=UP"
      return $true
    }
    Add-Check $Checks "backend:api-health" "fail" "Expected status=UP, got HTTP $($response.StatusCode), status=$($json.status)"
    return $false
  } catch {
    $status = ""
    if ($_.Exception.Response) {
      try { $status = "HTTP " + [int]$_.Exception.Response.StatusCode + " " } catch {}
    }
    Add-Check $Checks "backend:api-health" "fail" "$status$($_.Exception.Message)"
    return $false
  }
}

function Invoke-CaptchaProbe {
  param(
    [System.Collections.ArrayList]$Checks,
    [string]$BaseUrl
  )
  if ($SkipNetwork) {
    Add-Check $Checks "backend:auth-captcha" "skipped" "SkipNetwork was set"
    return $false
  }
  $uri = "$BaseUrl/api/v1/auth/captcha"
  try {
    $response = Invoke-WebRequest `
      -UseBasicParsing `
      -Method Post `
      -Uri $uri `
      -ContentType "application/json" `
      -Body "{}" `
      -TimeoutSec $TimeoutSeconds
    $json = $response.Content | ConvertFrom-Json
    $captchaId = [string]$json.captchaId
    if ([string]::IsNullOrWhiteSpace($captchaId)) {
      Add-Check $Checks "backend:auth-captcha" "fail" "HTTP $($response.StatusCode), captchaId missing"
      return $false
    }
    if ($captchaId.StartsWith("local-", [System.StringComparison]::OrdinalIgnoreCase)) {
      Add-Check $Checks "backend:auth-captcha" "fail" "captchaId uses local preview fallback"
      return $false
    }
    Add-Check $Checks "backend:auth-captcha" "ok" "HTTP $($response.StatusCode), captchaId returned"
    return $true
  } catch {
    $status = ""
    if ($_.Exception.Response) {
      try { $status = "HTTP " + [int]$_.Exception.Response.StatusCode + " " } catch {}
    }
    Add-Check $Checks "backend:auth-captcha" "fail" "$status$($_.Exception.Message)"
    return $false
  }
}

$checks = New-Object System.Collections.ArrayList
$origin = $BackendOrigin.Trim().TrimEnd("/")
$uri = $null
$usesHttps = $false
$isPrivate = $true
$healthUp = $false
$captchaReady = $false

try {
  $uri = [Uri]$origin
  if (-not $uri.IsAbsoluteUri -or @("http", "https") -notcontains $uri.Scheme) {
    Add-Check $checks "backend:url" "fail" "BackendOrigin must be absolute http(s)"
  } elseif ($uri.AbsolutePath.Trim("/")) {
    Add-Check $checks "backend:path" "fail" "BackendOrigin must be an origin root without /api/v1 path"
  } else {
    Add-Check $checks "backend:url" "ok" $origin
    $usesHttps = $uri.Scheme -eq "https"
    if ($usesHttps) {
      Add-Check $checks "backend:https" "ok" "https"
    } elseif ($AllowHttp) {
      Add-Check $checks "backend:https" "skipped" "AllowHttp was set for local/staging proof only"
    } else {
      Add-Check $checks "backend:https" "fail" "BackendOrigin must use https for production"
    }

    $isPrivate = Test-PrivateHost -HostName $uri.Host
    if (-not $isPrivate) {
      Add-Check $checks "backend:public-origin" "ok" "host appears public"
    } elseif ($AllowPrivateBackend) {
      Add-Check $checks "backend:public-origin" "skipped" "AllowPrivateBackend was set for local/staging proof only"
    } else {
      Add-Check $checks "backend:public-origin" "fail" "BackendOrigin host is local/private and cannot be reached by Vercel production"
    }

    $healthUp = Invoke-HealthProbe -Checks $checks -BaseUrl $origin
    $captchaReady = Invoke-CaptchaProbe -Checks $checks -BaseUrl $origin
  }
} catch {
  Add-Check $checks "backend:url" "fail" $_.Exception.Message
}

$failures = @($checks | Where-Object { $_.status -eq "fail" })
$skippedRuntime = @($checks | Where-Object { $_.name -in @("backend:api-health", "backend:auth-captcha") -and $_.status -eq "skipped" })
$contractReady = (@($failures).Count -eq 0 -and @($skippedRuntime).Count -eq 0 -and $healthUp -and $captchaReady)

$result = [pscustomobject][ordered]@{
  backendOrigin = $origin
  checkedAt = (Get-Date).ToString("yyyy-MM-ddTHH:mm:sszzz")
  contractReady = $contractReady
  healthUp = $healthUp
  captchaReady = $captchaReady
  usesHttps = $usesHttps
  privateOrigin = $isPrivate
  t3ProductionActionPerformed = $false
  ok = @($checks | Where-Object { $_.status -eq "ok" }).Count
  skipped = @($checks | Where-Object { $_.status -eq "skipped" }).Count
  failed = @($failures).Count
  checks = $checks
}

$result | ConvertTo-Json -Depth 8
if (-not $contractReady -and -not $AllowIncomplete) {
  exit 1
}
