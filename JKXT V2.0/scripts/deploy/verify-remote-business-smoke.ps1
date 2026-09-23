#requires -Version 5.1
param(
  [Parameter(Mandatory = $true)]
  [string]$AppUrl,

  [string]$Username = "jwc.demo",

  [string]$Password = "Demo@2025",

  [switch]$AllowRemoteDataWrites,

  [int]$TimeoutSeconds = 90
)

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..\..")).Path
$PrivateSmoke = Join-Path $PSScriptRoot "private-smoke.ps1"

function Test-LocalHost {
  param([string]$HostName)
  $normalized = $HostName.Trim('[', ']').ToLowerInvariant()
  return (
    $normalized -eq "localhost" -or
    $normalized -eq "::1" -or
    $normalized -like "127.*"
  )
}

if (-not (Test-Path -LiteralPath $PrivateSmoke)) {
  throw "Missing private smoke script: $PrivateSmoke"
}

$origin = $AppUrl.Trim().TrimEnd("/")
$uri = [Uri]$origin
if (-not $uri.IsAbsoluteUri -or @("http", "https") -notcontains $uri.Scheme) {
  throw "AppUrl must be an absolute http(s) URL, for example https://app.example.com"
}

$isLocalTarget = Test-LocalHost -HostName $uri.Host
if (-not $isLocalTarget -and -not $AllowRemoteDataWrites) {
  throw "Remote business smoke creates a task, imports workbook data, starts a schedule job, and downloads exports. Re-run with -AllowRemoteDataWrites only after the target is an approved staging/UAT/production smoke environment."
}

$baseUrl = "$origin/api/v1"
$smokeOutput = & $PrivateSmoke -BaseUrl $baseUrl -Username $Username -Password $Password -TimeoutSeconds $TimeoutSeconds
$smoke = ($smokeOutput | Out-String) | ConvertFrom-Json

[pscustomobject]@{
  appUrl = $origin
  apiBase = $baseUrl
  remoteDataWritesAllowed = [bool]$AllowRemoteDataWrites
  localTarget = [bool]$isLocalTarget
  health = $smoke.health
  user = $smoke.user
  org = $smoke.org
  taskId = $smoke.taskId
  jobId = $smoke.jobId
  jobStatus = $smoke.jobStatus
  assignments = $smoke.assignments
  fairness = $smoke.fairness
  xlsxBytes = $smoke.xlsxBytes
  pdfBytes = $smoke.pdfBytes
} | ConvertTo-Json -Depth 5
