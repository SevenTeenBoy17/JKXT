param(
  [string]$BaseUrl = "http://127.0.0.1:18180/api/v1",
  [string]$Username = "jwc.demo",
  [string]$Password = "Demo@2025",
  [int]$TimeoutSeconds = 75
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Net.Http
$ProjectRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..\..")).Path
$SmokeDataDir = Join-Path $ProjectRoot "test-data\import-backend-columns"
function Get-SmokeDataFile {
  param([string]$Pattern)
  $file = Get-ChildItem -LiteralPath $SmokeDataDir -Filter $Pattern -File -ErrorAction SilentlyContinue | Select-Object -First 1
  if ($file) { return $file.FullName }
  return $null
}
$SmokeDataFiles = @{
  teacher = Get-SmokeDataFile "01-*.xlsx"
  classroom = Get-SmokeDataFile "02-*.xlsx"
  exam = Get-SmokeDataFile "03-*.xlsx"
}
$ExportXlsxFile = Join-Path ([System.IO.Path]::GetTempPath()) ("jkxt-private-export-" + [System.Guid]::NewGuid() + ".xlsx")
$ExportPdfFile = Join-Path ([System.IO.Path]::GetTempPath()) ("jkxt-private-export-" + [System.Guid]::NewGuid() + ".pdf")
$script:SmokeSession = New-Object Microsoft.PowerShell.Commands.WebRequestSession

function Invoke-JsonPost {
  param(
    [string]$Uri,
    [object]$Body,
    [Microsoft.PowerShell.Commands.WebRequestSession]$Session = $null
  )
  $json = $Body | ConvertTo-Json -Compress -Depth 8
  if ($Session) {
    return Invoke-RestMethod -Method Post -Uri $Uri -WebSession $Session -ContentType "application/json" -Body $json
  }
  return Invoke-RestMethod -Method Post -Uri $Uri -WebSession $script:SmokeSession -ContentType "application/json" -Body $json
}

function Invoke-SampleUpload {
  param(
    [string]$TaskId,
    [string]$Kind,
    [Microsoft.PowerShell.Commands.WebRequestSession]$Session
  )
  $handler = [System.Net.Http.HttpClientHandler]::new()
  $handler.CookieContainer = [System.Net.CookieContainer]::new()
  foreach ($cookie in $Session.Cookies.GetCookies([Uri]$BaseUrl)) {
    $handler.CookieContainer.Add([Uri]$BaseUrl, $cookie)
  }
  $client = [System.Net.Http.HttpClient]::new($handler)
  $form = [System.Net.Http.MultipartFormDataContent]::new()
  try {
    $form.Add([System.Net.Http.StringContent]::new($Kind), "kind")
    $filePath = $SmokeDataFiles[$Kind]
    if ($filePath -and (Test-Path -LiteralPath $filePath)) {
      $fileContent = [System.Net.Http.ByteArrayContent]::new([System.IO.File]::ReadAllBytes($filePath))
      $fileContent.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::Parse("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
      $form.Add($fileContent, "file", [System.IO.Path]::GetFileName($filePath))
    } else {
      $form.Add([System.Net.Http.StringContent]::new("true"), "sample")
    }
    $response = $client.PostAsync("$BaseUrl/exam-tasks/$TaskId/uploads", $form).GetAwaiter().GetResult()
    $text = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
    if (-not $response.IsSuccessStatusCode) {
      throw "Sample upload for $Kind failed with HTTP $([int]$response.StatusCode): $text"
    }
    return $text | ConvertFrom-Json
  } finally {
    $form.Dispose()
    $client.Dispose()
    $handler.Dispose()
  }
}

try {
  $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
  do {
    try {
      $health = Invoke-RestMethod -Method Get -Uri "$BaseUrl/health"
      if ($health.status -eq "UP") { break }
    } catch {
      Start-Sleep -Seconds 2
    }
  } while ((Get-Date) -lt $deadline)

  if (-not $health -or $health.status -ne "UP") {
    throw "Health check did not return UP within $TimeoutSeconds seconds"
  }

  $captcha = Invoke-JsonPost -Uri "$BaseUrl/auth/captcha" -Body @{}
  if (-not $captcha.debugAnswer) {
    throw "Captcha debugAnswer is missing. For UAT smoke set JKXT_AUTH_CAPTCHA_DEBUG=true."
  }

  $login = Invoke-JsonPost -Uri "$BaseUrl/auth/login" -Session $script:SmokeSession -Body @{
    username = $Username
    password = $Password
    captchaId = $captcha.captchaId
    captcha = $captcha.debugAnswer
  }
  if ($login.user.username -ne $Username) {
    throw "Login user mismatch: $($login.user.username)"
  }

  $task = Invoke-JsonPost -Uri "$BaseUrl/exam-tasks" -Session $script:SmokeSession -Body @{
    name = "P12 private delivery smoke"
    source = "quick"
  }
  $taskId = $task.id
  if (-not $taskId) {
    throw "Task creation did not return id"
  }

  foreach ($kind in @("teacher", "classroom", "exam")) {
    $upload = Invoke-SampleUpload -TaskId $taskId -Kind $kind -Session $script:SmokeSession
    if (-not $upload.sha256) {
      throw "Sample upload for $kind did not return sha256"
    }
  }

  $validation = Invoke-RestMethod -Method Post -Uri "$BaseUrl/exam-tasks/$taskId/imports/validate" -WebSession $script:SmokeSession
  if ($validation.status -ne "ready") {
    throw "Validation status was $($validation.status)"
  }

  $jobStart = Invoke-RestMethod -Method Post -Uri "$BaseUrl/exam-tasks/$taskId/schedule-jobs" -WebSession $script:SmokeSession
  $jobId = $jobStart.jobId
  if (-not $jobId) {
    throw "Schedule job did not return jobId"
  }

  $jobDeadline = (Get-Date).AddSeconds($TimeoutSeconds)
  do {
    Start-Sleep -Milliseconds 700
    $job = Invoke-RestMethod -Method Get -Uri "$BaseUrl/schedule-jobs/$jobId" -WebSession $script:SmokeSession
    if (@("succeeded", "failed", "infeasible") -contains $job.status) { break }
  } while ((Get-Date) -lt $jobDeadline)

  if ($job.status -ne "succeeded") {
    throw "Schedule job ended with status $($job.status)"
  }
  if (-not $job.assignments -or $job.assignments.Count -lt 1) {
    throw "Schedule job succeeded without assignments"
  }
  if (-not $job.fairness -or $job.fairness.Count -lt 5) {
    throw "Schedule job did not return fairness 5"
  }

  Invoke-WebRequest -Method Get -Uri "$BaseUrl/exam-tasks/$taskId/export?format=xlsx" -WebSession $script:SmokeSession -OutFile $ExportXlsxFile | Out-Null
  $xlsxSize = (Get-Item -LiteralPath $ExportXlsxFile).Length
  if ($xlsxSize -lt 200) {
    throw "XLSX export file is too small: $xlsxSize bytes"
  }

  Invoke-WebRequest -Method Get -Uri "$BaseUrl/exam-tasks/$taskId/export?format=pdf" -WebSession $script:SmokeSession -OutFile $ExportPdfFile | Out-Null
  $pdfSize = (Get-Item -LiteralPath $ExportPdfFile).Length
  if ($pdfSize -lt 20) {
    throw "PDF export file is too small: $pdfSize bytes"
  }
  $pdfMagic = [System.Text.Encoding]::ASCII.GetString([System.IO.File]::ReadAllBytes($ExportPdfFile), 0, 5)
  if ($pdfMagic -ne "%PDF-") {
    throw "PDF export did not start with %PDF-"
  }

  [pscustomobject]@{
    health = $health.status
    user = $login.user.username
    org = $login.org.slug
    taskId = $taskId
    jobId = $jobId
    jobStatus = $job.status
    assignments = $job.assignments.Count
    fairness = $job.fairness.Count
    xlsxBytes = $xlsxSize
    pdfBytes = $pdfSize
  } | ConvertTo-Json -Depth 5
} finally {
  Remove-Item -LiteralPath $ExportXlsxFile -ErrorAction SilentlyContinue
  Remove-Item -LiteralPath $ExportPdfFile -ErrorAction SilentlyContinue
}
