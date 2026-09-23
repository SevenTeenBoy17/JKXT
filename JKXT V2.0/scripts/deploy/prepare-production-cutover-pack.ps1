#requires -Version 5.1
param(
  [ValidateSet("single-host", "vercel-rewrite")]
  [string]$Route = "single-host",

  [string]$AppUrl = "https://jkxt-responsive-v2.vercel.app",
  [string]$BackendOrigin = "",
  [string]$EnvFile = ".env.public",
  [string]$ReleaseZipPath = "",
  [string]$ReleaseManifestPath = "",
  [string]$OutputDir = ".codex-supervisor\production-cutover-pack",
  [switch]$AllowHttpBackend,
  [switch]$SkipNetwork,
  [switch]$SkipDocker,
  [switch]$SkipCaddyValidate,
  [switch]$SkipSafeGates,
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

  $status = if ($process.ExitCode -eq 0) { "ok" } else { "fail" }
  if ($json) {
    if ($json.PSObject.Properties["failed"] -and [int]$json.failed -gt 0) { $status = "blocked" }
    if ($json.PSObject.Properties["ready"] -and -not [bool]$json.ready) { $status = "blocked" }
    if ($json.PSObject.Properties["readyForT3Start"] -and -not [bool]$json.readyForT3Start) { $status = "blocked" }
    if ($json.PSObject.Properties["connectedProductionReady"] -and -not [bool]$json.connectedProductionReady) { $status = "blocked" }
  }

  return [ordered]@{
    name = $Name
    status = $status
    exitCode = $process.ExitCode
    script = $ScriptPath
    output = if ($json) { $json } else { $outputText }
  }
}

function New-StaticSafeGate {
  param(
    [string]$Name,
    [string]$Status,
    [string]$Detail,
    [string]$ScriptPath = ""
  )
  return [ordered]@{
    name = $Name
    status = $Status
    exitCode = $null
    script = $ScriptPath
    output = $Detail
  }
}

function Get-EnvState {
  param([string]$Path)
  $required = @(
    "PUBLIC_DOMAIN",
    "PUBLIC_APP_URL",
    "APP_BASE_URL",
    "CORS_ALLOWED_ORIGINS",
    "POSTGRES_PASSWORD",
    "JKXT_AUTH_CAPTCHA_DEBUG",
    "JKXT_AUTH_COOKIE_SECURE"
  )
  $map = @{}
  if (Test-Path -LiteralPath $Path) {
    foreach ($line in Get-Content -LiteralPath $Path) {
      $trimmed = $line.Trim()
      if ([string]::IsNullOrWhiteSpace($trimmed) -or $trimmed.StartsWith("#")) { continue }
      if ($trimmed -match "^\s*([^=]+?)\s*=\s*(.*)\s*$") {
        $map[$matches[1].Trim()] = $matches[2].Trim()
      }
    }
  }
  foreach ($key in $required) {
    $value = if ($map.ContainsKey($key)) { [string]$map[$key] } else { "" }
    $placeholder = $value -match "example|replace_with|change_me|<|>"
    [ordered]@{
      key = $key
      present = -not [string]::IsNullOrWhiteSpace($value)
      placeholder = [bool]$placeholder
      valueRedacted = if ([string]::IsNullOrWhiteSpace($value)) { "missing" } elseif ($placeholder) { "placeholder" } else { "present" }
    }
  }
}

function Write-MarkdownPack {
  param(
    [object]$Pack,
    [string]$Path
  )
  $lines = New-Object System.Collections.Generic.List[string]
  $lines.Add("# JKXT Production Cutover Approval Pack")
  $lines.Add("")
  $lines.Add("- Generated: $($Pack.generatedAt)")
  $lines.Add("- Route: $($Pack.route)")
  $lines.Add("- App URL: $($Pack.appUrl)")
  $lines.Add("- Backend origin: $($Pack.backendOrigin)")
  $lines.Add("- Release ZIP: $($Pack.releaseZip)")
  $lines.Add("- Ready for T3 approval: $($Pack.readyForT3Approval)")
  $lines.Add("- T3 production action performed: $($Pack.t3ProductionActionPerformed)")
  $lines.Add("")
  $lines.Add("## Safe Gate Summary")
  foreach ($gate in @($Pack.safeGates)) {
    $lines.Add("- $($gate.name): $($gate.status) (exit $($gate.exitCode))")
  }
  if (@($Pack.safeGates).Count -eq 0) {
    $lines.Add("- Skipped by request")
  }
  $lines.Add("")
  $lines.Add("## Required Human Approvals")
  foreach ($approval in @($Pack.requiredApprovals)) {
    $lines.Add("- [$($approval.status)] $($approval.id) - $($approval.detail)")
  }
  $lines.Add("")
  $lines.Add("## Environment Readiness")
  foreach ($state in @($Pack.envState)) {
    $lines.Add("- $($state.key): $($state.valueRedacted)")
  }
  $lines.Add("")
  $lines.Add("## Generated Files")
  $lines.Add("- JSON: $($Pack.jsonPath)")
  $lines.Add("- Markdown: $($Pack.markdownPath)")
  $lines.Add("- Command checklist: $($Pack.commandPath)")
  $lines.Add("")
  $lines.Add("## Notes")
  $lines.Add("- This pack does not deploy, start containers, write secrets, mutate Supabase, trigger CircleCI cloud, create a tunnel, or run remote data-write smoke.")
  $lines.Add("- The command checklist is intentionally inert by default and contains a top-level throw.")
  Set-Content -LiteralPath $Path -Value $lines -Encoding UTF8
}

function Write-CommandChecklist {
  param(
    [object]$Pack,
    [string]$Path
  )
  $app = if ([string]::IsNullOrWhiteSpace($Pack.appUrl)) { "https://<PUBLIC_DOMAIN>" } else { $Pack.appUrl }
  $backend = if ([string]::IsNullOrWhiteSpace($Pack.backendOrigin)) { "https://<PUBLIC_BACKEND_ORIGIN>" } else { $Pack.backendOrigin }
  $release = if ([string]::IsNullOrWhiteSpace($Pack.releaseZip)) { ".\.codex-supervisor\releases\jkxt-public-<release>.zip" } else { $Pack.releaseZip }
  $env = if ([string]::IsNullOrWhiteSpace($Pack.envFile)) { ".env.public" } else { $Pack.envFile }

  $lines = New-Object System.Collections.Generic.List[string]
  $lines.Add("# Generated by prepare-production-cutover-pack.ps1")
  $lines.Add("throw 'T3 approval required. Remove this line only after explicit approval for the exact target, env, rollback owner, and data-write smoke window.'")
  $lines.Add("")
  $lines.Add("# Safe preflight gates")
  $lines.Add("powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy\discover-delivery-capabilities.ps1 -AppUrl `"$app`" -BackendOrigin `"$backend`" -ReleaseZipPath `"$release`" -AllowIncomplete")
  $lines.Add("powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy\report-production-cutover-readiness.ps1 -AppUrl `"$app`" -BackendOrigin `"$backend`" -EnvFile `"$env`" -ReleaseZipPath `"$release`" -AllowIncomplete")
  $lines.Add("powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy\invoke-public-launch-gates.ps1 -AppUrl `"$app`" -BackendOrigin `"$backend`" -EnvFile `"$env`" -ReleaseZipPath `"$release`"")
  $lines.Add("")
  if ($Pack.route -eq "vercel-rewrite") {
    $vercelDraft = ".\.codex-supervisor\vercel.connected.review.json"
    $lines.Add("# Vercel rewrite path")
    $lines.Add("powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy\plan-vercel-backend-rewrite.ps1 -AppUrl `"$app`" -BackendOrigin `"$backend`"")
    $lines.Add("powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy\write-vercel-connected-config.ps1 -BackendOrigin `"$backend`" -OutputPath `"$vercelDraft`"")
    $lines.Add("powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy\test-vercel-connected-config.ps1 -ConfigPath `"$vercelDraft`" -BackendOrigin `"$backend`"")
    $lines.Add("powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy\write-vercel-connected-config.ps1 -BackendOrigin `"$backend`" -Apply")
    $lines.Add("# T3: deploy the reviewed vercel.json through the approved Vercel production flow, then verify.")
  } else {
    $lines.Add("# Single-host Docker path")
    $lines.Add("powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy\preflight-public-host.ps1 -EnvFile `"$env`" -RequireManifest -VerifyManifestHashes")
    $lines.Add("powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy\invoke-public-launch-gates.ps1 -AppUrl `"$app`" -BackendOrigin `"$backend`" -EnvFile `"$env`" -ReleaseZipPath `"$release`" -StartProduction -ConfirmStartProduction `"START JKXT PUBLIC PRODUCTION`"")
    $lines.Add("powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy\backup-public.ps1 -EnvFile `"$env`" -ConfirmBackupProduction `"BACKUP JKXT PUBLIC PRODUCTION`"")
  }
  $lines.Add("")
  $lines.Add("# Post-start evidence")
  $lines.Add("powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy\verify-connected-remote.ps1 -AppUrl `"$app`" -SkipLogin")
  $lines.Add("powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy\verify-remote-browser-ui.ps1 -AppUrl `"$app`" -SkipLogin")
  $lines.Add("# T3 data writes: run only after approval for smoke-test records.")
  $lines.Add("powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy\verify-remote-business-smoke.ps1 -AppUrl `"$app`" -AllowRemoteDataWrites")
  $lines.Add("powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy\audit-production-readiness.ps1 -AppUrl `"$app`" -RequirePublicEnv -BrowserSmoke -BusinessSmoke -AllowRemoteDataWrites")
  $lines.Add("")
  $lines.Add("# Rollback / stop dry-run and approved stop")
  $lines.Add("powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy\backup-public.ps1 -EnvFile `"$env`" -DryRun")
  $lines.Add("powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy\stop-public.ps1 -EnvFile `"$env`" -DryRun")
  $lines.Add("powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy\stop-public.ps1 -EnvFile `"$env`" -ConfirmStopProduction `"STOP JKXT PUBLIC PRODUCTION`"")
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
$packDir = Join-Path $outputRoot "cutover-pack-$stamp"
New-Item -ItemType Directory -Force -Path $packDir | Out-Null

$scripts = @{
  delivery = Join-Path $PSScriptRoot "discover-delivery-capabilities.ps1"
  cutover = Join-Path $PSScriptRoot "report-production-cutover-readiness.ps1"
  launch = Join-Path $PSScriptRoot "invoke-public-launch-gates.ps1"
  vercelPlan = Join-Path $PSScriptRoot "plan-vercel-backend-rewrite.ps1"
  vercelConfigWrite = Join-Path $PSScriptRoot "write-vercel-connected-config.ps1"
  vercelConfigTest = Join-Path $PSScriptRoot "test-vercel-connected-config.ps1"
  backup = Join-Path $PSScriptRoot "backup-public.ps1"
  stop = Join-Path $PSScriptRoot "stop-public.ps1"
}

foreach ($script in $scripts.Values) {
  if (-not (Test-Path -LiteralPath $script)) { throw "Missing required script: $script" }
}

$safeGates = New-Object System.Collections.ArrayList
if (-not $SkipSafeGates) {
  [void]$safeGates.Add((Invoke-CapturedScript -Name "delivery-capabilities" -ScriptPath $scripts.delivery -Parameters @{
    AppUrl = $AppUrl
    BackendOrigin = $BackendOrigin
    ReleaseZipPath = $releaseZip
    OutputDir = (Join-Path $packDir "delivery-capabilities")
    AllowHttpBackend = [bool]$AllowHttpBackend
    SkipNetwork = [bool]$SkipNetwork
    AllowIncomplete = $true
    TimeoutSeconds = $TimeoutSeconds
  }))

  [void]$safeGates.Add((Invoke-CapturedScript -Name "cutover-readiness" -ScriptPath $scripts.cutover -Parameters @{
    AppUrl = $AppUrl
    BackendOrigin = $BackendOrigin
    EnvFile = $envPath
    ReleaseZipPath = $releaseZip
    ReleaseManifestPath = $releaseManifest
    OutputDir = (Join-Path $packDir "cutover-readiness")
    AllowHttpBackend = [bool]$AllowHttpBackend
    SkipNetwork = [bool]$SkipNetwork
    AllowIncomplete = $true
  }))

  [void]$safeGates.Add((Invoke-CapturedScript -Name "launch-gate-safe-mode" -ScriptPath $scripts.launch -Parameters @{
    AppUrl = $AppUrl
    BackendOrigin = $BackendOrigin
    EnvFile = $envPath
    ReleaseZipPath = $releaseZip
    ReleaseManifestPath = $releaseManifest
    AllowHttpBackend = [bool]$AllowHttpBackend
    SkipNetwork = [bool]$SkipNetwork
    SkipDocker = [bool]$SkipDocker
    SkipCaddyValidate = [bool]$SkipCaddyValidate
    TimeoutSeconds = $TimeoutSeconds
  }))

  if ($Route -eq "vercel-rewrite") {
    $vercelDraftDir = Join-Path $packDir "vercel-connected-plan"
    $vercelDraftPath = Join-Path $vercelDraftDir "vercel.connected.review.json"
    $vercelDraftReport = Join-Path $vercelDraftDir "vercel.connected.review.validation.json"

    [void]$safeGates.Add((Invoke-CapturedScript -Name "vercel-rewrite-plan" -ScriptPath $scripts.vercelPlan -Parameters @{
      AppUrl = $AppUrl
      BackendOrigin = $BackendOrigin
      OutputDir = (Join-Path $packDir "vercel-connected-plan")
      AllowHttpBackend = [bool]$AllowHttpBackend
      SkipNetwork = [bool]$SkipNetwork
      AllowIncomplete = $true
      TimeoutSeconds = $TimeoutSeconds
    }))

    if ([string]::IsNullOrWhiteSpace($BackendOrigin)) {
      [void]$safeGates.Add((New-StaticSafeGate -Name "vercel-connected-config-draft" -Status "blocked" -ScriptPath $scripts.vercelConfigWrite -Detail "BackendOrigin is required before a review-only connected Vercel config can be generated."))
      [void]$safeGates.Add((New-StaticSafeGate -Name "vercel-connected-config-validation" -Status "blocked" -ScriptPath $scripts.vercelConfigTest -Detail "Skipped because the connected Vercel config draft was not generated."))
    } else {
      [void]$safeGates.Add((Invoke-CapturedScript -Name "vercel-connected-config-draft" -ScriptPath $scripts.vercelConfigWrite -Parameters @{
        BackendOrigin = $BackendOrigin
        OutputPath = $vercelDraftPath
        AllowHttp = [bool]$AllowHttpBackend
        SkipProbe = [bool]$SkipNetwork
      }))

      [void]$safeGates.Add((Invoke-CapturedScript -Name "vercel-connected-config-validation" -ScriptPath $scripts.vercelConfigTest -Parameters @{
        ConfigPath = $vercelDraftPath
        BackendOrigin = $BackendOrigin
        OutputPath = $vercelDraftReport
        AllowHttp = [bool]$AllowHttpBackend
        AllowIncomplete = $true
      }))
    }
  } else {
    [void]$safeGates.Add((Invoke-CapturedScript -Name "backup-dry-run" -ScriptPath $scripts.backup -Parameters @{
      EnvFile = $envPath
      DryRun = $true
    }))
    [void]$safeGates.Add((Invoke-CapturedScript -Name "rollback-stop-dry-run" -ScriptPath $scripts.stop -Parameters @{
      EnvFile = $envPath
      DryRun = $true
    }))
  }
}

$blockedOrFailed = @($safeGates | Where-Object { $_.status -ne "ok" })
$hasSafeGateEvidence = (-not $SkipSafeGates -and @($safeGates).Count -gt 0)
$requiredApprovals = @(
  [ordered]@{ id = "target-owner"; status = "required"; detail = "Approve the exact public AppUrl and BackendOrigin for this cutover." },
  [ordered]@{ id = "production-env"; status = "required"; detail = "Provide real .env.public values outside source control; no placeholder domain or secrets." },
  [ordered]@{ id = "rollback-owner"; status = "required"; detail = "Name the person who can approve rollback/stop and confirm volume preservation expectations." },
  [ordered]@{ id = "t3-start-or-deploy"; status = "required"; detail = "Approve either Vercel production deploy/apply or single-host production startup." },
  [ordered]@{ id = "remote-data-write-smoke"; status = "required"; detail = "Approve remote smoke-test task/import/job/export records before running business smoke." }
)

$jsonPath = Join-Path $packDir "cutover-pack.json"
$markdownPath = Join-Path $packDir "cutover-pack.md"
$commandPath = Join-Path $packDir "approved-commands.ps1"

$pack = [pscustomobject][ordered]@{
  route = $Route
  appUrl = $AppUrl
  backendOrigin = $BackendOrigin
  envFile = $envPath
  releaseZip = $releaseZip
  releaseManifest = $releaseManifest
  generatedAt = (Get-Date).ToString("yyyy-MM-ddTHH:mm:sszzz")
  readyForT3Approval = ($hasSafeGateEvidence -and @($blockedOrFailed).Count -eq 0)
  t3ProductionActionPerformed = $false
  requiredApprovals = $requiredApprovals
  envState = @(Get-EnvState -Path $envPath)
  safeGates = $safeGates
  jsonPath = $jsonPath
  markdownPath = $markdownPath
  commandPath = $commandPath
}

Write-CommandChecklist -Pack $pack -Path $commandPath
Write-MarkdownPack -Pack $pack -Path $markdownPath
$pack | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $jsonPath -Encoding UTF8
$pack | ConvertTo-Json -Depth 12
