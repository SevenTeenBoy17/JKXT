param(
  [string]$BaseUrl = "http://127.0.0.1:18180/api/v1",
  [string]$Username = "jwc.demo",
  [string]$Password = "Demo@2025"
)

$ErrorActionPreference = "Stop"
$Session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$checks = New-Object System.Collections.Generic.List[object]

function Add-Check {
  param([string]$Name, [bool]$Pass, [string]$Detail)
  $checks.Add([pscustomobject]@{ name = $Name; pass = $Pass; detail = $Detail }) | Out-Null
  if (-not $Pass) {
    throw "Security check failed: $Name - $Detail"
  }
}

function Invoke-Captured {
  param(
    [string]$Method,
    [string]$Uri,
    [object]$Body = $null,
    [Microsoft.PowerShell.Commands.WebRequestSession]$WebSession = $null,
    [string]$ContentType = "application/json"
  )
  try {
    $params = @{ Method = $Method; Uri = $Uri; Headers = @{ "X-Trace-Id" = "p11-security-scan" } }
    if ($WebSession) { $params.WebSession = $WebSession }
    if ($Body -ne $null) {
      $params.ContentType = $ContentType
      $params.Body = if ($ContentType -eq "application/json" -and -not ($Body -is [string])) { $Body | ConvertTo-Json -Compress -Depth 8 } else { $Body }
    }
    $response = Invoke-WebRequest -UseBasicParsing @params
    return [pscustomobject]@{
      status = [int]$response.StatusCode
      body = $response.Content
      traceId = [string]$response.Headers["X-Trace-Id"]
    }
  } catch {
    $response = $_.Exception.Response
    if (-not $response) { throw }
    $body = $_.ErrorDetails.Message
    if (-not $body) {
      $stream = $response.GetResponseStream()
      if ($stream) {
        $reader = New-Object System.IO.StreamReader($stream)
        $body = $reader.ReadToEnd()
      }
    }
    return [pscustomobject]@{
      status = [int]$response.StatusCode
      body = [string]$body
      traceId = [string]$response.Headers["X-Trace-Id"]
    }
  }
}

function Post-Json {
  param([string]$Path, [object]$Body, [Microsoft.PowerShell.Commands.WebRequestSession]$WebSession = $Session)
  return Invoke-Captured -Method "POST" -Uri "$BaseUrl$Path" -Body $Body -WebSession $WebSession
}

$health = Invoke-Captured -Method "GET" -Uri "$BaseUrl/health"
Add-Check "health_trace_header" ($health.status -eq 200 -and $health.traceId) "status=$($health.status), traceId=$($health.traceId)"

$unauth = Invoke-Captured -Method "GET" -Uri "$BaseUrl/exam-tasks"
Add-Check "auth_required" ($unauth.status -eq 401 -and $unauth.body.Contains("AUTH_UNAUTHENTICATED") -and $unauth.body.Contains("traceId")) "status=$($unauth.status), body=$($unauth.body)"

$badCaptcha = Post-Json -Path "/auth/login" -Body @{
  username = $Username
  password = $Password
  captchaId = "missing"
  captcha = "WRONG"
} -WebSession (New-Object Microsoft.PowerShell.Commands.WebRequestSession)
Add-Check "captcha_rejects_invalid" ($badCaptcha.status -eq 400 -and $badCaptcha.body.Contains("AUTH_CAPTCHA_FAILED")) "status=$($badCaptcha.status)"

$captcha = Post-Json -Path "/auth/captcha" -Body @{}
$captchaJson = $captcha.body | ConvertFrom-Json
$login = Post-Json -Path "/auth/login" -Body @{
  username = $Username
  password = $Password
  captchaId = $captchaJson.captchaId
  captcha = $captchaJson.debugAnswer
}
Add-Check "valid_login" ($login.status -eq 200 -and $login.body.Contains($Username)) "status=$($login.status)"

$tenantOverreach = Post-Json -Path "/admin/accounts" -Body @{
  tenantId = "00000000-0000-0000-0000-000000010002"
  email = "p11-overreach@jkxt.local"
  name = "P11 Overreach"
}
Add-Check "private_tenant_overreach_blocked" ($tenantOverreach.status -eq 403 -and $tenantOverreach.body.Contains("traceId")) "status=$($tenantOverreach.status)"

$task = Post-Json -Path "/exam-tasks" -Body @{ name = "P11 security scan upload guard"; source = "quick" }
$taskJson = $task.body | ConvertFrom-Json
$boundary = "----JKXTP11" + [System.Guid]::NewGuid().ToString("N")
$parts = @(
  "--$boundary",
  "Content-Disposition: form-data; name=""kind""",
  "",
  "malicious-kind",
  "--$boundary",
  "Content-Disposition: form-data; name=""sample""",
  "",
  "true",
  "--$boundary--",
  ""
)
$multipart = [System.Text.Encoding]::UTF8.GetBytes(($parts -join "`r`n"))
$uploadGuard = Invoke-Captured -Method "POST" -Uri "$BaseUrl/exam-tasks/$($taskJson.id)/uploads" -Body $multipart -WebSession $Session -ContentType "multipart/form-data; boundary=$boundary"
Add-Check "upload_kind_guard" ($uploadGuard.status -eq 400 -and $uploadGuard.body.Contains("BAD_REQUEST")) "status=$($uploadGuard.status)"

[pscustomobject]@{
  baseUrl = $BaseUrl
  status = "passed"
  checks = $checks
} | ConvertTo-Json -Depth 6
