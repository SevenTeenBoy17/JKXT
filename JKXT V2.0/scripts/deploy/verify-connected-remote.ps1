param(
  [Parameter(Mandatory = $true)]
  [string]$AppUrl,

  [string]$Username = "jwc.demo",

  [string]$Password = "Demo@2025",

  [switch]$SkipLogin,

  [int]$TimeoutSeconds = 45
)

$ErrorActionPreference = "Stop"

$origin = $AppUrl.Trim().TrimEnd("/")
$uri = [Uri]$origin
if (-not $uri.IsAbsoluteUri) {
  throw "AppUrl must be an absolute URL, for example https://app.example.com"
}
$baseUrl = "$origin/api/v1"
$session = New-Object Microsoft.PowerShell.Commands.WebRequestSession

function Invoke-JsonPost {
  param(
    [string]$Uri,
    [object]$Body
  )
  $json = $Body | ConvertTo-Json -Compress -Depth 8
  return Invoke-RestMethod -Method Post -Uri $Uri -WebSession $session -ContentType "application/json" -Body $json -TimeoutSec $TimeoutSeconds
}

try {
  $health = Invoke-RestMethod -Method Get -Uri "$baseUrl/health" -TimeoutSec $TimeoutSeconds
} catch {
  throw "Health check failed at $baseUrl/health. The app is not connected to a backend /api/v1 origin. $($_.Exception.Message)"
}
if ($health.status -ne "UP") {
  throw "Health check failed: expected status=UP."
}

$dashboard = Invoke-WebRequest -Method Get -Uri "$origin/Dashboard.html" -TimeoutSec $TimeoutSeconds -UseBasicParsing
if ($dashboard.StatusCode -ne 200 -or $dashboard.Content.Length -lt 1000) {
  throw "Dashboard.html did not load as a real frontend asset."
}

$result = [ordered]@{
  appUrl = $origin
  apiBase = $baseUrl
  health = $health.status
  dashboardStatus = $dashboard.StatusCode
  dashboardBytes = $dashboard.Content.Length
  loginChecked = $false
}

if (-not $SkipLogin) {
  $captcha = Invoke-JsonPost -Uri "$baseUrl/auth/captcha" -Body @{}
  if (-not $captcha.captchaId) {
    throw "Captcha endpoint did not return captchaId."
  }
  if (-not $captcha.debugAnswer) {
    throw "Captcha debugAnswer is missing. Automated remote login smoke needs a staging/UAT backend with JKXT_AUTH_CAPTCHA_DEBUG=true, or run with -SkipLogin and perform manual login verification."
  }
  $login = Invoke-JsonPost -Uri "$baseUrl/auth/login" -Body @{
    username = $Username
    password = $Password
    captchaId = $captcha.captchaId
    captcha = $captcha.debugAnswer
  }
  if ($login.user.username -ne $Username) {
    throw "Login user mismatch: $($login.user.username)"
  }
  $me = Invoke-RestMethod -Method Get -Uri "$baseUrl/auth/me" -WebSession $session -TimeoutSec $TimeoutSeconds
  if ($me.user.username -ne $Username) {
    throw "auth/me user mismatch: $($me.user.username)"
  }
  $result.loginChecked = $true
  $result.user = $login.user.username
  $result.org = $login.org.slug
}

[pscustomobject]$result | ConvertTo-Json -Depth 5
