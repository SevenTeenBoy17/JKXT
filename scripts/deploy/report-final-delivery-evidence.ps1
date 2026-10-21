#requires -Version 5.1
param(
  [ValidateSet("single-host", "vercel-rewrite")]
  [string]$Route = "single-host",

  [string]$AppUrl = "https://jkxt-responsive-v2.vercel.app",
  [string]$BackendOrigin = "",
  [string]$EnvFile = ".env.public",
  [string]$ReleaseZipPath = "",
  [string]$ReleaseManifestPath = "",
  [string]$OutputDir = ".codex-supervisor\final-delivery-evidence",
  [switch]$AllowHttpBackend,
  [switch]$SkipNetwork,
  [switch]$SkipDocker,
  [switch]$SkipCaddyValidate,
  [switch]$BrowserSmoke,
  [switch]$StaticPreviewSmoke,
  [switch]$BusinessSmoke,
  [switch]$AllowRemoteDataWrites,
  [switch]$SkipChildGates,
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
    [string]$Name,
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

  return [ordered]@{
    name = $Name
    exitCode = $process.ExitCode
    script = $ScriptPath
    output = if ($json) { $json } else { $outputText }
  }
}

function Add-Requirement {
  param(
    [System.Collections.ArrayList]$Requirements,
    [string]$Id,
    [string]$Requirement,
    [ValidateSet("proven", "blocked", "missing", "skipped")]
    [string]$Status,
    [string]$Evidence,
    [string]$NextAction
  )
  [void]$Requirements.Add([ordered]@{
    id = $Id
    requirement = $Requirement
    status = $Status
    evidence = $Evidence
    nextAction = $NextAction
  })
}

function Find-Check {
  param(
    [object]$Report,
    [string]$Name
  )
  if (-not $Report -or -not $Report.PSObject.Properties["checks"]) { return $null }
  return @($Report.checks | Where-Object { $_.name -eq $Name } | Select-Object -First 1)[0]
}

function Find-Step {
  param(
    [object]$Gate,
    [string]$Name
  )
  if (-not $Gate -or -not $Gate.output -or -not $Gate.output.PSObject.Properties["steps"]) { return $null }
  return @($Gate.output.steps | Where-Object { $_.name -eq $Name } | Select-Object -First 1)[0]
}

function Find-Gate {
  param(
    [object]$Pack,
    [string]$Name
  )
  if (-not $Pack -or -not $Pack.PSObject.Properties["safeGates"]) { return $null }
  return @($Pack.safeGates | Where-Object { $_.name -eq $Name } | Select-Object -First 1)[0]
}

function Format-StatusDetail {
  param([object]$Item)
  if (-not $Item) { return "missing evidence" }
  $status = if ($Item.PSObject.Properties["status"]) { $Item.status } else { "" }
  $detail = if ($Item.PSObject.Properties["detail"]) { $Item.detail } elseif ($Item.PSObject.Properties["output"]) { [string]$Item.output } else { "" }
  return "$status $detail".Trim()
}

function Write-MarkdownEvidence {
  param(
    [object]$Envelope,
    [string]$Path
  )
  $lines = New-Object System.Collections.Generic.List[string]
  $lines.Add("# JKXT Final Delivery Evidence")
  $lines.Add("")
  $lines.Add("- Checked: $($Envelope.checkedAt)")
  $lines.Add("- App URL: $($Envelope.appUrl)")
  $lines.Add("- Backend origin: $($Envelope.backendOrigin)")
  $lines.Add("- Release ZIP: $($Envelope.releaseZip)")
  $lines.Add("- Production complete: $($Envelope.productionComplete)")
  $lines.Add("- T3 production action performed: $($Envelope.t3ProductionActionPerformed)")
  $lines.Add("")
  $lines.Add("## Requirement Matrix")
  foreach ($req in @($Envelope.requirements)) {
    $lines.Add("- [$($req.status)] $($req.id) - $($req.requirement)")
    $lines.Add("  - Evidence: $($req.evidence)")
    if (-not [string]::IsNullOrWhiteSpace($req.nextAction)) {
      $lines.Add("  - Next: $($req.nextAction)")
    }
  }
  $lines.Add("")
  $lines.Add("## Child Gate Summary")
  foreach ($gate in @($Envelope.childGates)) {
    $lines.Add("- $($gate.name): exit $($gate.exitCode)")
  }
  $lines.Add("")
  $lines.Add("## Guardrail Notes")
  foreach ($note in @($Envelope.guardrails)) {
    $lines.Add("- $note")
  }
  Set-Content -LiteralPath $Path -Value $lines -Encoding UTF8
}

$releaseZip = if ([string]::IsNullOrWhiteSpace($ReleaseZipPath)) { Get-LatestReleaseZip } else { Resolve-ProjectPath $ReleaseZipPath }
if ([string]::IsNullOrWhiteSpace($releaseZip)) {
  throw "Release zip not found. Build a public release first or pass -ReleaseZipPath."
}

$releaseManifest = Resolve-ProjectPath $ReleaseManifestPath
if ([string]::IsNullOrWhiteSpace($releaseManifest)) {
  $manifestDir = Join-Path ([System.IO.Path]::GetDirectoryName($releaseZip)) ([System.IO.Path]::GetFileNameWithoutExtension($releaseZip))
  $releaseManifest = Join-Path $manifestDir "release-manifest.json"
}

$envPath = Resolve-ProjectPath $EnvFile
$outputRoot = Resolve-ProjectPath $OutputDir
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$evidenceDir = Join-Path $outputRoot "final-delivery-$stamp"
New-Item -ItemType Directory -Force -Path $evidenceDir | Out-Null

$scripts = @{
  delivery = Join-Path $PSScriptRoot "discover-delivery-capabilities.ps1"
  cutover = Join-Path $PSScriptRoot "report-production-cutover-readiness.ps1"
  audit = Join-Path $PSScriptRoot "audit-production-readiness.ps1"
  pack = Join-Path $PSScriptRoot "prepare-production-cutover-pack.ps1"
}

foreach ($script in $scripts.Values) {
  if (-not (Test-Path -LiteralPath $script)) { throw "Missing required script: $script" }
}

$childGates = New-Object System.Collections.ArrayList
if (-not $SkipChildGates) {
  [void]$childGates.Add((Invoke-CapturedScript -Name "delivery-capabilities" -ScriptPath $scripts.delivery -Parameters @{
    AppUrl = $AppUrl
    BackendOrigin = $BackendOrigin
    ReleaseZipPath = $releaseZip
    OutputDir = (Join-Path $evidenceDir "delivery-capabilities")
    AllowHttpBackend = [bool]$AllowHttpBackend
    SkipNetwork = [bool]$SkipNetwork
    AllowIncomplete = $true
    TimeoutSeconds = $TimeoutSeconds
  }))

  [void]$childGates.Add((Invoke-CapturedScript -Name "cutover-readiness" -ScriptPath $scripts.cutover -Parameters @{
    AppUrl = $AppUrl
    BackendOrigin = $BackendOrigin
    EnvFile = $envPath
    ReleaseZipPath = $releaseZip
    ReleaseManifestPath = $releaseManifest
    OutputDir = (Join-Path $evidenceDir "cutover-readiness")
    AllowHttpBackend = [bool]$AllowHttpBackend
    SkipNetwork = [bool]$SkipNetwork
    AllowIncomplete = $true
    TimeoutSeconds = $TimeoutSeconds
  }))

  $auditParams = @{
    AppUrl = $AppUrl
    ReleaseZipPath = $releaseZip
    ReleaseManifestPath = $releaseManifest
    SkipLogin = $true
    AllowIncomplete = $true
    TimeoutSeconds = $TimeoutSeconds
  }
  if ($BrowserSmoke) { $auditParams.BrowserSmoke = $true }
  if ($StaticPreviewSmoke) { $auditParams.StaticPreviewSmoke = $true }
  if ($BusinessSmoke) { $auditParams.BusinessSmoke = $true }
  if ($AllowRemoteDataWrites) { $auditParams.AllowRemoteDataWrites = $true }
  [void]$childGates.Add((Invoke-CapturedScript -Name "production-readiness-audit" -ScriptPath $scripts.audit -Parameters $auditParams))

  [void]$childGates.Add((Invoke-CapturedScript -Name "production-cutover-pack" -ScriptPath $scripts.pack -Parameters @{
    Route = $Route
    AppUrl = $AppUrl
    BackendOrigin = $BackendOrigin
    EnvFile = $envPath
    ReleaseZipPath = $releaseZip
    ReleaseManifestPath = $releaseManifest
    OutputDir = (Join-Path $evidenceDir "production-cutover-pack")
    AllowHttpBackend = [bool]$AllowHttpBackend
    SkipNetwork = [bool]$SkipNetwork
    SkipDocker = [bool]$SkipDocker
    SkipCaddyValidate = [bool]$SkipCaddyValidate
    TimeoutSeconds = $TimeoutSeconds
  }))
}

$delivery = (Find-Gate ([pscustomobject]@{ safeGates = $childGates }) "delivery-capabilities").output
$cutover = (Find-Gate ([pscustomobject]@{ safeGates = $childGates }) "cutover-readiness").output
$audit = (Find-Gate ([pscustomobject]@{ safeGates = $childGates }) "production-readiness-audit").output
$pack = (Find-Gate ([pscustomobject]@{ safeGates = $childGates }) "production-cutover-pack").output

$requirements = New-Object System.Collections.ArrayList

$healthCheck = Find-Check $cutover "remote:api-health"
if ($healthCheck -and $healthCheck.status -eq "ok") {
  Add-Requirement $requirements "public-api-health" "Public AppUrl must serve /api/v1/health with status=UP." "proven" (Format-StatusDetail $healthCheck) ""
} else {
  Add-Requirement $requirements "public-api-health" "Public AppUrl must serve /api/v1/health with status=UP." "blocked" (Format-StatusDetail $healthCheck) "Expose or proxy a real public backend before go-live."
}

$connectedCheck = Find-Check $audit "remote:connected-verifier"
if ($connectedCheck -and $connectedCheck.status -eq "ok") {
  Add-Requirement $requirements "frontend-backend-connected" "Frontend must be connected to backend APIs, not a static-only preview." "proven" (Format-StatusDetail $connectedCheck) ""
} else {
  Add-Requirement $requirements "frontend-backend-connected" "Frontend must be connected to backend APIs, not a static-only preview." "blocked" (Format-StatusDetail $connectedCheck) "Make /api/v1 reachable from the public AppUrl and rerun the connected verifier."
}

$browserCheck = Find-Check $audit "remote:browser-ui"
if ($browserCheck -and $browserCheck.status -eq "ok") {
  Add-Requirement $requirements "browser-ui-smoke" "Browser smoke must prove the deployed UI renders without backend-disconnected/static-preview failure states." "proven" (Format-StatusDetail $browserCheck) ""
} elseif ($BrowserSmoke) {
  Add-Requirement $requirements "browser-ui-smoke" "Browser smoke must prove the deployed UI renders without backend-disconnected/static-preview failure states." "blocked" (Format-StatusDetail $browserCheck) "Fix public runtime/backend connectivity and rerun browser smoke."
} else {
  Add-Requirement $requirements "browser-ui-smoke" "Browser smoke must prove the deployed UI renders without backend-disconnected/static-preview failure states." "missing" (Format-StatusDetail $browserCheck) "Run with -BrowserSmoke after the public backend is reachable."
}

$staticPreviewCheck = Find-Check $audit "remote:static-preview-ui"
if ($staticPreviewCheck -and $staticPreviewCheck.status -eq "ok") {
  Add-Requirement $requirements "static-preview-diagnostic" "When a target is backend-disconnected, the UI should visibly identify the static-preview state." "proven" (Format-StatusDetail $staticPreviewCheck) ""
} elseif ($StaticPreviewSmoke) {
  Add-Requirement $requirements "static-preview-diagnostic" "When a target is backend-disconnected, the UI should visibly identify the static-preview state." "blocked" (Format-StatusDetail $staticPreviewCheck) "Make the static-preview disconnected state visible and rerun -StaticPreviewSmoke."
} else {
  Add-Requirement $requirements "static-preview-diagnostic" "When a target is backend-disconnected, the UI should visibly identify the static-preview state." "skipped" (Format-StatusDetail $staticPreviewCheck) "Run with -StaticPreviewSmoke when auditing a known backend-disconnected preview."
}

$businessCheck = Find-Check $audit "remote:business-smoke"
if ($businessCheck -and $businessCheck.status -eq "ok") {
  Add-Requirement $requirements "business-smoke" "Remote business smoke must prove task import, scheduling, assignments, and exports." "proven" (Format-StatusDetail $businessCheck) ""
} elseif ($BusinessSmoke) {
  Add-Requirement $requirements "business-smoke" "Remote business smoke must prove task import, scheduling, assignments, and exports." "blocked" (Format-StatusDetail $businessCheck) "Approve data writes on the target and fix any failed business smoke step."
} else {
  Add-Requirement $requirements "business-smoke" "Remote business smoke must prove task import, scheduling, assignments, and exports." "missing" (Format-StatusDetail $businessCheck) "Run with -BusinessSmoke -AllowRemoteDataWrites during an approved smoke window."
}

$releaseFailures = @()
if ($cutover -and $cutover.PSObject.Properties["checks"]) {
  $releaseFailures = @($cutover.checks | Where-Object { $_.category -eq "release" -and $_.status -eq "fail" })
}
if (@($releaseFailures).Count -eq 0 -and $cutover) {
  Add-Requirement $requirements "release-integrity" "Release bundle must contain frontend, backend, deployment gates, docs, and no real env files." "proven" "cutover release checks had no failures" ""
} else {
  Add-Requirement $requirements "release-integrity" "Release bundle must contain frontend, backend, deployment gates, docs, and no real env files." "blocked" "$(@($releaseFailures).Count) release failure(s)" "Regenerate and self-test the release bundle."
}

$releaseEntryPointCheck = Find-Check $audit "remote:release-entrypoint-match"
if ($releaseEntryPointCheck -and $releaseEntryPointCheck.status -eq "ok") {
  Add-Requirement $requirements "live-release-match" "The public Dashboard.html entrypoint must match the release bundle being audited." "proven" (Format-StatusDetail $releaseEntryPointCheck) ""
} else {
  Add-Requirement $requirements "live-release-match" "The public Dashboard.html entrypoint must match the release bundle being audited." "blocked" (Format-StatusDetail $releaseEntryPointCheck) "Deploy the audited release bundle to the public target and rerun final evidence."
}

$projectLinkCheck = Find-Check $delivery "project-link"
$projectZipCheck = Find-Check $delivery "zip:.vercel/project.json"
if ($projectLinkCheck -and $projectLinkCheck.status -eq "ok" -and $projectZipCheck -and $projectZipCheck.status -eq "ok") {
  Add-Requirement $requirements "vercel-project-link" "Vercel handoff must preserve the intended project-link metadata in the release bundle." "proven" "$($projectLinkCheck.detail); release zip entry listed" ""
} else {
  Add-Requirement $requirements "vercel-project-link" "Vercel handoff must preserve the intended project-link metadata in the release bundle." "blocked" "project=$((Format-StatusDetail $projectLinkCheck)); zip=$((Format-StatusDetail $projectZipCheck))" "Include non-secret .vercel/project.json in the release and rerun delivery capability discovery."
}

$envFailures = @()
if ($cutover -and $cutover.PSObject.Properties["checks"]) {
  $envFailures = @($cutover.checks | Where-Object { $_.category -eq "env" -and $_.status -eq "fail" })
}
if (@($envFailures).Count -eq 0 -and $cutover) {
  Add-Requirement $requirements "production-env" "Production env must use real domain, non-placeholder secrets, secure cookies, and captcha debug off." "proven" "env checks had no failures" ""
} else {
  Add-Requirement $requirements "production-env" "Production env must use real domain, non-placeholder secrets, secure cookies, and captcha debug off." "blocked" "$(@($envFailures).Count) env failure(s)" "Complete .env.public on the real host before T3 startup/apply."
}

if ($pack -and $pack.readyForT3Approval -and -not $pack.t3ProductionActionPerformed) {
  Add-Requirement $requirements "approval-pack" "Cutover approval pack must be generated and inert until explicit T3 approval." "proven" "readyForT3Approval=true, t3ProductionActionPerformed=false" ""
} elseif ($pack) {
  Add-Requirement $requirements "approval-pack" "Cutover approval pack must be generated and inert until explicit T3 approval." "blocked" "readyForT3Approval=$($pack.readyForT3Approval), t3ProductionActionPerformed=$($pack.t3ProductionActionPerformed)" "Resolve blocked safe gates before requesting T3 approval."
} else {
  Add-Requirement $requirements "approval-pack" "Cutover approval pack must be generated and inert until explicit T3 approval." "missing" "pack gate did not run" "Run without -SkipChildGates."
}

$backupGate = Find-Gate $pack "backup-dry-run"
if ($Route -eq "single-host") {
  $backupOutput = if ($backupGate -and $backupGate.PSObject.Properties["output"]) { $backupGate.output } else { $null }
  $backupStatus = if ($backupOutput -and $backupOutput.PSObject.Properties["status"]) { [string]$backupOutput.status } else { "" }
  $backupT3 = if ($backupOutput -and $backupOutput.PSObject.Properties["t3ProductionActionPerformed"]) { [bool]$backupOutput.t3ProductionActionPerformed } else { $false }
  if ($backupGate -and $backupGate.status -eq "ok" -and $backupStatus -eq "dry-run" -and -not $backupT3) {
    Add-Requirement $requirements "public-backup-readiness" "Single-host public cutover must prove the database backup command is available before T3 startup." "proven" "backup-dry-run status=dry-run, t3ProductionActionPerformed=false" ""
  } else {
    Add-Requirement $requirements "public-backup-readiness" "Single-host public cutover must prove the database backup command is available before T3 startup." "blocked" "gate=$((Format-StatusDetail $backupGate)); outputStatus=$backupStatus; t3ProductionActionPerformed=$backupT3" "Run backup-public.ps1 -DryRun successfully on the target host before T3 startup."
  }
} else {
  Add-Requirement $requirements "public-backup-readiness" "Single-host public cutover must prove the database backup command is available before T3 startup." "skipped" "route=$Route does not use the packaged Docker PostgreSQL single-host backup path" "For Vercel rewrite, require the approved BackendOrigin owner's backup evidence outside this package."
}

$vercelRewriteCheck = Find-Check $cutover "vercel:api-rewrite"
if ($vercelRewriteCheck -and $vercelRewriteCheck.status -eq "ok") {
  Add-Requirement $requirements "vercel-readiness" "If using Vercel, /api/v1 must be rewritten to a healthy backend." "proven" (Format-StatusDetail $vercelRewriteCheck) ""
} elseif ($vercelRewriteCheck -and $vercelRewriteCheck.status -eq "skipped") {
  Add-Requirement $requirements "vercel-readiness" "If using Vercel, /api/v1 must be rewritten to a healthy backend." "skipped" (Format-StatusDetail $vercelRewriteCheck) "Not required for a single-host non-Vercel target."
} else {
  Add-Requirement $requirements "vercel-readiness" "If using Vercel, /api/v1 must be rewritten to a healthy backend." "blocked" (Format-StatusDetail $vercelRewriteCheck) "Generate/apply Vercel rewrite only after BackendOrigin is healthy and T3 is approved."
}

$capabilityFailures = if ($delivery -and $delivery.PSObject.Properties["checks"]) { @($delivery.checks | Where-Object { $_.status -eq "fail" }) } else { @() }
$connectorSkipped = if ($delivery -and $delivery.PSObject.Properties["checks"]) { @($delivery.checks | Where-Object { $_.category -in @("supabase", "expo", "jam", "coderabbit") -and $_.status -eq "skipped" }) } else { @() }
if (@($capabilityFailures).Count -eq 0 -and $delivery -and $delivery.connectedProductionReady) {
  Add-Requirement $requirements "platform-capabilities" "Requested platform lanes must be either verified or explicitly not applicable/degraded." "proven" "connectedProductionReady=true; skipped connector lanes=$(@($connectorSkipped).Count)" ""
} else {
  Add-Requirement $requirements "platform-capabilities" "Requested platform lanes must be either verified or explicitly not applicable/degraded." "blocked" "delivery failures=$(@($capabilityFailures).Count), skipped connector lanes=$(@($connectorSkipped).Count)" "Resolve runtime/backend failures; keep CodeRabbit/Jam/Expo/Supabase as degraded/not-applicable unless project context is provided."
}

$t3Performed = $false
if ($delivery -and $delivery.PSObject.Properties["t3ProductionActionPerformed"] -and [bool]$delivery.t3ProductionActionPerformed) { $t3Performed = $true }
if ($pack -and $pack.PSObject.Properties["t3ProductionActionPerformed"] -and [bool]$pack.t3ProductionActionPerformed) { $t3Performed = $true }
if (-not $t3Performed) {
  Add-Requirement $requirements "no-unapproved-t3" "No production deploy/start/backup/stop, secret write, Supabase mutation, CircleCI cloud trigger, public tunnel, or data-write smoke may run without approval." "proven" "child gates report t3ProductionActionPerformed=false" ""
} else {
  Add-Requirement $requirements "no-unapproved-t3" "No production deploy/start/backup/stop, secret write, Supabase mutation, CircleCI cloud trigger, public tunnel, or data-write smoke may run without approval." "blocked" "a child gate reported a T3 action" "Audit and roll back any unapproved production action."
}

$blockingStatuses = @("blocked", "missing")
$productionComplete = @($requirements | Where-Object { $_.status -in $blockingStatuses }).Count -eq 0
$jsonPath = Join-Path $evidenceDir "final-delivery-evidence.json"
$markdownPath = Join-Path $evidenceDir "final-delivery-evidence.md"

$envelope = [pscustomobject][ordered]@{
  appUrl = $AppUrl
  backendOrigin = $BackendOrigin
  route = $Route
  releaseZip = $releaseZip
  releaseManifest = $releaseManifest
  envFile = $envPath
  checkedAt = (Get-Date).ToString("yyyy-MM-ddTHH:mm:sszzz")
  productionComplete = $productionComplete
  t3ProductionActionPerformed = $t3Performed
  reportJson = $jsonPath
  reportMarkdown = $markdownPath
  requirements = $requirements
  childGates = $childGates
  guardrails = @(
    "This evidence envelope is read-only and does not deploy, start containers, write secrets, mutate Supabase, trigger CircleCI cloud, create a public tunnel, or run remote data-write smoke unless explicitly requested through child-gate flags.",
    "A green release bundle or approval pack is not enough to mark production complete; remote runtime and smoke evidence must be proven."
  )
}

$envelope | ConvertTo-Json -Depth 14 | Set-Content -LiteralPath $jsonPath -Encoding UTF8
Write-MarkdownEvidence -Envelope $envelope -Path $markdownPath
$envelope | ConvertTo-Json -Depth 14

if (-not $productionComplete -and -not $AllowIncomplete) {
  exit 1
}
