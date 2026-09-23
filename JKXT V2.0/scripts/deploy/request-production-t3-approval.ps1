#requires -Version 5.1
param(
  [ValidateSet("single-host", "vercel-rewrite")]
  [string]$Route = "single-host",

  [string]$AppUrl = "https://jkxt-responsive-v2.vercel.app",
  [string]$BackendOrigin = "",
  [string]$EnvFile = ".env.public",
  [string]$ReleaseZipPath = "",
  [string]$OutputDir = ".codex-supervisor\production-t3-approval-request",
  [string]$ApprovalTicket = "",
  [string]$TargetOwner = "",
  [string]$RollbackOwner = "",
  [string]$SmokeWindow = "",
  [switch]$AllowHttpBackend,
  [switch]$SkipNetwork,
  [switch]$SkipDocker,
  [switch]$SkipCaddyValidate,
  [switch]$AllowIncomplete,
  [int]$TimeoutSeconds = 60
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"
$Root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..\..")).Path

function Resolve-ProjectPath {
  param([string]$Path)
  if ([string]::IsNullOrWhiteSpace($Path)) { return "" }
  if ([System.IO.Path]::IsPathRooted($Path)) { return $Path }
  return Join-Path $Root $Path
}

function Get-LatestReleaseZip {
  $releaseDir = Join-Path $Root ".codex-supervisor\releases"
  if (-not (Test-Path -LiteralPath $releaseDir)) { return "" }
  $latest = Get-ChildItem -LiteralPath $releaseDir -Filter "jkxt-public-*.zip" -File |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
  if ($latest) { return $latest.FullName }
  return ""
}

function ConvertTo-CommandLineArgument {
  param([string]$Argument)
  if ($Argument -notmatch '[\s"]') { return $Argument }
  return '"' + $Argument.Replace('"', '\"') + '"'
}

function ConvertTo-ArgumentList {
  param([hashtable]$Parameters)
  $args = @()
  foreach ($key in ($Parameters.Keys | Sort-Object)) {
    $value = $Parameters[$key]
    if ($null -eq $value) { continue }
    if ($value -is [bool]) {
      if ($value) { $args += "-$key" }
      continue
    }
    if ($value -is [System.Management.Automation.SwitchParameter]) {
      if ($value.IsPresent) { $args += "-$key" }
      continue
    }
    if ([string]::IsNullOrWhiteSpace([string]$value)) { continue }
    $args += "-$key"
    $args += [string]$value
  }
  return $args
}

function Invoke-CapturedScript {
  param(
    [string]$ScriptPath,
    [hashtable]$Parameters
  )

  $psExe = (Get-Process -Id $PID).Path
  $arguments = @("-NoProfile")
  if ($PSVersionTable.PSEdition -eq "Desktop" -or $IsWindows) {
    $arguments += @("-ExecutionPolicy", "Bypass")
  }
  $arguments += @("-File", $ScriptPath)
  $arguments += ConvertTo-ArgumentList -Parameters $Parameters

  $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
  $startInfo.FileName = $psExe
  $startInfo.UseShellExecute = $false
  $startInfo.RedirectStandardOutput = $true
  $startInfo.RedirectStandardError = $true
  $startInfo.CreateNoWindow = $true
  $argumentListProperty = $startInfo.GetType().GetProperty("ArgumentList")
  if ($argumentListProperty) {
    foreach ($arg in $arguments) {
      [void]$startInfo.ArgumentList.Add($arg)
    }
  } else {
    $startInfo.Arguments = ($arguments | ForEach-Object { ConvertTo-CommandLineArgument $_ }) -join " "
  }

  $process = [System.Diagnostics.Process]::Start($startInfo)
  $stdout = $process.StandardOutput.ReadToEnd()
  $stderr = $process.StandardError.ReadToEnd()
  $process.WaitForExit()

  $combined = @()
  if (-not [string]::IsNullOrWhiteSpace($stdout)) { $combined += $stdout.Trim() }
  if (-not [string]::IsNullOrWhiteSpace($stderr)) { $combined += $stderr.Trim() }
  $outputText = ($combined -join [Environment]::NewLine).Trim()
  $json = $null
  if ($outputText.StartsWith("{") -or $outputText.StartsWith("[")) {
    try { $json = $outputText | ConvertFrom-Json } catch {}
  }

  [pscustomobject][ordered]@{
    exitCode = $process.ExitCode
    output = if ($json) { $json } else { $outputText }
  }
}

function Find-Requirement {
  param(
    [object]$Envelope,
    [string]$Id
  )
  if (-not $Envelope -or -not $Envelope.PSObject.Properties["requirements"]) { return $null }
  return @($Envelope.requirements | Where-Object { $_.id -eq $Id } | Select-Object -First 1)[0]
}

function Add-ApprovalItem {
  param(
    [System.Collections.ArrayList]$Items,
    [string]$Id,
    [ValidateSet("ready", "required", "blocked")]
    [string]$Status,
    [string]$Detail,
    [string]$Evidence
  )
  [void]$Items.Add([ordered]@{
    id = $Id
    status = $Status
    detail = $Detail
    evidence = $Evidence
  })
}

function Test-Placeholder {
  param([string]$Value)
  return ([string]::IsNullOrWhiteSpace($Value) -or $Value -match "<|>|example|replace_with|change_me")
}

function Write-MarkdownRequest {
  param(
    [object]$Request,
    [string]$Path
  )
  $lines = New-Object System.Collections.Generic.List[string]
  $lines.Add("# JKXT Production T3 Approval Request")
  $lines.Add("")
  $lines.Add("- Generated: $($Request.generatedAt)")
  $lines.Add("- Route: $($Request.route)")
  $lines.Add("- App URL: $($Request.appUrl)")
  $lines.Add("- Backend origin: $($Request.backendOrigin)")
  $lines.Add("- Release ZIP: $($Request.releaseZip)")
  $lines.Add("- T3 approval ready: $($Request.t3ApprovalReady)")
  $lines.Add("- Production complete from evidence: $($Request.productionCompleteFromEvidence)")
  $lines.Add("- T3 production action performed: $($Request.t3ProductionActionPerformed)")
  $lines.Add("")
  $lines.Add("## Approval Phrase")
  $lines.Add("")
  $lines.Add('```text')
  $lines.Add($Request.approvalPhrase)
  $lines.Add('```')
  $lines.Add("")
  $lines.Add("## Approval Matrix")
  foreach ($item in @($Request.approvalItems)) {
    $lines.Add("- [$($item.status)] $($item.id) - $($item.detail)")
    $lines.Add("  - Evidence: $($item.evidence)")
  }
  $lines.Add("")
  $lines.Add("## Evidence Files")
  $lines.Add("- Final evidence JSON: $($Request.finalEvidenceJson)")
  $lines.Add("- Approval request JSON: $($Request.requestJson)")
  $lines.Add("- Approval request Markdown: $($Request.requestMarkdown)")
  $lines.Add("")
  $lines.Add("## Guardrails")
  foreach ($note in @($Request.guardrails)) {
    $lines.Add("- $note")
  }
  Set-Content -LiteralPath $Path -Value $lines -Encoding UTF8
}

$releaseZip = if ([string]::IsNullOrWhiteSpace($ReleaseZipPath)) { Get-LatestReleaseZip } else { Resolve-ProjectPath $ReleaseZipPath }
if ([string]::IsNullOrWhiteSpace($releaseZip)) {
  throw "Release zip not found. Build a public release first or pass -ReleaseZipPath."
}

$envPath = Resolve-ProjectPath $EnvFile
$outputRoot = Resolve-ProjectPath $OutputDir
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$requestDir = Join-Path $outputRoot "approval-request-$stamp"
New-Item -ItemType Directory -Force -Path $requestDir | Out-Null

$finalEvidenceScript = Join-Path $PSScriptRoot "report-final-delivery-evidence.ps1"
if (-not (Test-Path -LiteralPath $finalEvidenceScript)) {
  throw "Missing required script: $finalEvidenceScript"
}

$final = Invoke-CapturedScript -ScriptPath $finalEvidenceScript -Parameters @{
  Route = $Route
  AppUrl = $AppUrl
  BackendOrigin = $BackendOrigin
  EnvFile = $envPath
  ReleaseZipPath = $releaseZip
  OutputDir = (Join-Path $requestDir "final-delivery-evidence")
  AllowHttpBackend = [bool]$AllowHttpBackend
  SkipNetwork = [bool]$SkipNetwork
  SkipDocker = [bool]$SkipDocker
  SkipCaddyValidate = [bool]$SkipCaddyValidate
  AllowIncomplete = $true
  TimeoutSeconds = $TimeoutSeconds
}

$envelope = $final.output
$approvalItems = New-Object System.Collections.ArrayList
$appUrlIsPlaceholder = Test-Placeholder $AppUrl
$backendOriginIsPlaceholder = Test-Placeholder $BackendOrigin
$backendOriginPresent = -not [string]::IsNullOrWhiteSpace($BackendOrigin)

if ($appUrlIsPlaceholder) {
  Add-ApprovalItem $approvalItems "target-app-url" "required" "Approve a concrete public AppUrl." "AppUrl is missing or placeholder."
} else {
  Add-ApprovalItem $approvalItems "target-app-url" "ready" "Approve a concrete public AppUrl." $AppUrl
}

if ($Route -eq "vercel-rewrite" -and $backendOriginIsPlaceholder) {
  Add-ApprovalItem $approvalItems "backend-origin" "required" "Approve a concrete public BackendOrigin for the Vercel rewrite." "BackendOrigin is missing or placeholder."
} elseif ($backendOriginPresent) {
  Add-ApprovalItem $approvalItems "backend-origin" "ready" "Approve the BackendOrigin used by the cutover." $BackendOrigin
} else {
  Add-ApprovalItem $approvalItems "backend-origin" "required" "Approve the BackendOrigin or confirm single-host same-origin API." "BackendOrigin is empty."
}

foreach ($entry in @(
  @{ id = "approval-ticket"; value = $ApprovalTicket; detail = "Record the ticket/change id that authorizes the T3 production action." },
  @{ id = "target-owner"; value = $TargetOwner; detail = "Name the person accountable for the target host/project." },
  @{ id = "rollback-owner"; value = $RollbackOwner; detail = "Name the person accountable for rollback/stop approval." },
  @{ id = "remote-data-write-smoke-window"; value = $SmokeWindow; detail = "Record the approved window for remote smoke-test writes." }
)) {
  if ([string]::IsNullOrWhiteSpace([string]$entry.value)) {
    Add-ApprovalItem $approvalItems $entry.id "required" $entry.detail "missing"
  } else {
    Add-ApprovalItem $approvalItems $entry.id "ready" $entry.detail ([string]$entry.value)
  }
}

foreach ($reqId in @("release-integrity", "live-release-match", "production-env", "approval-pack", "public-backup-readiness", "public-api-health", "frontend-backend-connected", "no-unapproved-t3")) {
  $req = Find-Requirement $envelope $reqId
  if ($req -and ($req.status -eq "proven" -or ($reqId -eq "public-backup-readiness" -and $req.status -eq "skipped"))) {
    Add-ApprovalItem $approvalItems $reqId "ready" $req.requirement $req.evidence
  } elseif ($req) {
    Add-ApprovalItem $approvalItems $reqId "blocked" $req.requirement "$($req.status): $($req.evidence)"
  } else {
    Add-ApprovalItem $approvalItems $reqId "blocked" "Requirement evidence must be present." "missing"
  }
}

$blocked = @($approvalItems | Where-Object { $_.status -eq "blocked" })
$required = @($approvalItems | Where-Object { $_.status -eq "required" })
$t3ApprovalReady = (@($blocked).Count -eq 0 -and @($required).Count -eq 0)
$requestJson = Join-Path $requestDir "production-t3-approval-request.json"
$requestMarkdown = Join-Path $requestDir "production-t3-approval-request.md"
$approvalPhrase = "APPROVE JKXT PUBLIC PRODUCTION T3 route=$Route appUrl=$AppUrl backendOrigin=$BackendOrigin release=$(Split-Path -Leaf $releaseZip) smokeWindow=$SmokeWindow"

$request = [pscustomobject][ordered]@{
  route = $Route
  appUrl = $AppUrl
  backendOrigin = $BackendOrigin
  envFile = $envPath
  releaseZip = $releaseZip
  generatedAt = (Get-Date).ToString("yyyy-MM-ddTHH:mm:sszzz")
  approvalTicket = $ApprovalTicket
  targetOwner = $TargetOwner
  rollbackOwner = $RollbackOwner
  smokeWindow = $SmokeWindow
  t3ApprovalReady = $t3ApprovalReady
  productionCompleteFromEvidence = if ($envelope -and $envelope.PSObject.Properties["productionComplete"]) { [bool]$envelope.productionComplete } else { $false }
  t3ProductionActionPerformed = if ($envelope -and $envelope.PSObject.Properties["t3ProductionActionPerformed"]) { [bool]$envelope.t3ProductionActionPerformed } else { $false }
  finalEvidenceExitCode = $final.exitCode
  finalEvidenceJson = if ($envelope -and $envelope.PSObject.Properties["reportJson"]) { $envelope.reportJson } else { "" }
  requestJson = $requestJson
  requestMarkdown = $requestMarkdown
  approvalPhrase = $approvalPhrase
  approvalItems = $approvalItems
  guardrails = @(
    "This request is read-only. It does not deploy, start containers, apply Vercel config, write secrets, mutate Supabase, trigger CircleCI cloud, create a public tunnel, or run remote data-write smoke.",
    "A human approval phrase is not sufficient unless all approval matrix items are ready and the final evidence target is the exact production target.",
    "After approval, keep the generated command checklist's top-level throw until the approved operator intentionally removes it for the exact target."
  )
}

$request | ConvertTo-Json -Depth 14 | Set-Content -LiteralPath $requestJson -Encoding UTF8
Write-MarkdownRequest -Request $request -Path $requestMarkdown
$request | ConvertTo-Json -Depth 14

if (-not $t3ApprovalReady -and -not $AllowIncomplete) {
  exit 1
}
