#requires -Version 5.1
param(
  [string]$EnvFile = ".env.public",
  [switch]$NoBuild,
  [switch]$DryRun,
  [string]$ConfirmStartProduction = "",
  [ValidateRange(1, 86400000)][int]$LongOperationTimeoutMilliseconds = 900000
)

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..\..")).Path
$ComposeFile = Join-Path $Root "docker-compose.public.yml"
$ExampleEnv = Join-Path $Root ".env.public.example"
$TargetEnv = if ([System.IO.Path]::IsPathRooted($EnvFile)) { $EnvFile } else { Join-Path $Root $EnvFile }
$ConfirmPhrase = "START JKXT PUBLIC PRODUCTION"
$script:JkxtPublicStartupAttempted = $false
$script:JkxtPublicCommandStarted = $false
$script:JkxtPublicWorkMayStillBeRunning = $false
. (Join-Path $PSScriptRoot 'native-command.ps1')

function ConvertTo-CommandLine {
  param([string[]]$CommandArgs)
  return ($CommandArgs | ForEach-Object {
    if ($_ -match '[\s"]') {
      '"' + $_.Replace('"', '\"') + '"'
    } else {
      $_
    }
  }) -join " "
}

function Invoke-DockerCommand {
  param([string[]]$DockerArgs, [int]$TimeoutMilliseconds = 900000)
  $result = Invoke-JkxtNativeCommand -Executable 'docker' -ArgumentList $DockerArgs -TimeoutMilliseconds $TimeoutMilliseconds
  return [ordered]@{
    exitCode = if ($result.invocationFailed) { 1 } else { $result.exitCode }
    # Quiet validation and all failures have no printable native body. Resolved
    # JSON is internal only; stderr warnings must never contaminate it.
    output = if ($result.exitCode -eq 0 -and -not ($DockerArgs -contains '--quiet')) { $result.stdout.Trim() } else { '' }
    timedOut = [bool]$result.timedOut
    commandStarted = [bool]$result.commandStarted
    workMayStillBeRunning = [bool]$result.workMayStillBeRunning
  }
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

function Write-SummaryAndExit {
  param(
    [string]$Status,
    [int]$ExitCode,
    [string]$Detail,
    [System.Collections.ArrayList]$Checks,
    [hashtable]$Extra = @{}
  )

  $summary = [ordered]@{
    status = $Status
    checkedAt = (Get-Date).ToString("yyyy-MM-ddTHH:mm:sszzz")
    composeFile = $ComposeFile
    envFile = $TargetEnv
    dryRun = [bool]$DryRun
    noBuild = [bool]$NoBuild
    confirmPhrase = $ConfirmPhrase
    startRequested = -not [bool]$DryRun
    t3ProductionActionPerformed = if ($Status -eq 'started') { $true } elseif ($script:JkxtPublicStartupAttempted) { $null } else { $false }
    productionStartupAttempted = $script:JkxtPublicStartupAttempted
    productionChangesMayHaveOccurred = $script:JkxtPublicStartupAttempted
    commandStarted = $script:JkxtPublicCommandStarted
    workMayStillBeRunning = $script:JkxtPublicWorkMayStillBeRunning
    longOperationTimeoutMilliseconds = $LongOperationTimeoutMilliseconds
    removesVolumes = $false
    preservesNamedVolumes = $true
    detail = $Detail
    checks = $Checks
  }
  foreach ($key in ($Extra.Keys | Sort-Object)) {
    $summary[$key] = $Extra[$key]
  }
  [pscustomobject]$summary | ConvertTo-Json -Depth 10
  exit $ExitCode
}

$checks = New-Object System.Collections.ArrayList
$createdEnvFromExample = $false

if (-not (Test-Path -LiteralPath $ComposeFile)) {
  Add-Check $checks "compose:file" "fail" "Missing docker-compose.public.yml."
  Write-SummaryAndExit -Status "fail" -ExitCode 1 -Detail "Missing compose file." -Checks $checks
}
Add-Check $checks "compose:file" "ok" "found $ComposeFile"

if (-not (Test-Path -LiteralPath $TargetEnv)) {
  Copy-Item -LiteralPath $ExampleEnv -Destination $TargetEnv
  $createdEnvFromExample = $true
  Add-Check $checks "env:file" "fail" "Created $TargetEnv from .env.public.example; edit domain and secrets before startup."
} else {
  Add-Check $checks "env:file" "ok" "found $TargetEnv"
}

$envText = Get-Content -Raw -LiteralPath $TargetEnv
if ($envText -match "app\.example\.jkxt\.cn") {
  Add-Check $checks "env:domain-placeholder" "fail" "PUBLIC_DOMAIN/APP_BASE_URL still use the example domain."
} else {
  Add-Check $checks "env:domain-placeholder" "ok" "public domain is not the example domain"
}
if ($envText -match "replace_with_secret_manager_value|change_me_private_password") {
  Add-Check $checks "env:secret-placeholder" "fail" "Production secrets still use placeholder values."
} else {
  Add-Check $checks "env:secret-placeholder" "ok" "production secret placeholders were not found"
}
if ($envText -match "JKXT_AUTH_CAPTCHA_DEBUG\s*=\s*true") {
  Add-Check $checks "env:captcha-debug" "fail" "JKXT_AUTH_CAPTCHA_DEBUG must be false for public production startup."
} else {
  Add-Check $checks "env:captcha-debug" "ok" "captcha debug is not true"
}
if ($envText -match "JKXT_AUTH_COOKIE_SECURE\s*=\s*true") {
  Add-Check $checks "env:cookie-secure" "ok" "secure cookies are required"
} else {
  Add-Check $checks "env:cookie-secure" "fail" "JKXT_AUTH_COOKIE_SECURE must be true for public production startup."
}

$guardFailures = @($checks | Where-Object { $_.status -eq "fail" })
if (@($guardFailures).Count -gt 0) {
  Write-SummaryAndExit -Status "fail" -ExitCode 1 -Detail "Public startup guardrails failed; no containers were started." -Checks $checks -Extra @{
    createdEnvFromExample = $createdEnvFromExample
  }
}

$configArgs = @("compose", "-f", $ComposeFile, "--env-file", $TargetEnv, "config", "--quiet")
$statusArgs = @("compose", "-f", $ComposeFile, "--env-file", $TargetEnv, "ps")
$composeArgs = @("compose", "-f", $ComposeFile, "--env-file", $TargetEnv, "up", "-d")
if (-not $NoBuild) {
  $composeArgs += "--build"
}

$config = Invoke-DockerCommand -DockerArgs $configArgs
if ([int]$config.exitCode -ne 0) {
  Add-Check $checks "compose:config" "fail" "docker compose public config validation failed"
  Write-SummaryAndExit -Status "fail" -ExitCode ([int]$config.exitCode) -Detail "docker compose public config validation failed; no containers were started." -Checks $checks -Extra @{
    createdEnvFromExample = $createdEnvFromExample
    composeConfig = $config
  }
}
Add-Check $checks "compose:config" "ok" "docker compose config exited 0"

# Validate resolved values, not just env-file text: shell overrides must not bypass the guard.
. (Join-Path $PSScriptRoot 'production-config.ps1')
$resolved = Invoke-DockerCommand -DockerArgs @('compose', '-f', $ComposeFile, '--env-file', $TargetEnv, 'config', '--format', 'json')
if ($resolved.exitCode -ne 0) {
  Write-SummaryAndExit -Status 'fail' -ExitCode 1 -Detail 'Cannot inspect resolved production configuration; no containers were started.' -Checks $checks
}
try { $resolvedConfig = $resolved.output | ConvertFrom-Json -ErrorAction Stop } catch {
  Write-SummaryAndExit -Status 'fail' -ExitCode 1 -Detail 'Resolved production configuration was not valid JSON; no containers were started.' -Checks $checks
}
if ($null -eq $resolvedConfig) {
  Write-SummaryAndExit -Status 'fail' -ExitCode 1 -Detail 'Resolved production configuration was empty; no containers were started.' -Checks $checks
}
$productionIssues = @(Get-JkxtProductionViolations -Config $resolvedConfig)
foreach ($issue in $productionIssues) { Add-Check $checks 'compose:production-safety' 'fail' $issue }
if ($productionIssues.Count) {
  Write-SummaryAndExit -Status 'fail' -ExitCode 1 -Detail 'Resolved production settings are unsafe; no containers were started.' -Checks $checks
}
Add-Check $checks 'compose:production-safety' 'ok' 'resolved settings passed; secrets are not included in this report'

if ($DryRun) {
  Write-SummaryAndExit -Status "dry-run" -ExitCode 0 -Detail "Public compose config validated. Dry run only; no containers were started." -Checks $checks -Extra @{
    createdEnvFromExample = $createdEnvFromExample
    plannedCommand = "docker " + (ConvertTo-CommandLine -CommandArgs $composeArgs)
    composeConfig = $config
  }
}

if ($ConfirmStartProduction -ne $ConfirmPhrase) {
  Add-Check $checks "confirm:start-production" "fail" "Missing exact production start confirmation phrase."
  Write-SummaryAndExit -Status "refused" -ExitCode 1 -Detail "Refusing public production startup. Re-run with -ConfirmStartProduction '$ConfirmPhrase'." -Checks $checks -Extra @{
    createdEnvFromExample = $createdEnvFromExample
    plannedCommand = "docker " + (ConvertTo-CommandLine -CommandArgs $composeArgs)
    composeConfig = $config
  }
}

Add-Check $checks "confirm:start-production" "ok" "exact production start confirmation phrase supplied"
$script:JkxtPublicStartupAttempted = $true
$up = Invoke-DockerCommand -DockerArgs $composeArgs -TimeoutMilliseconds $LongOperationTimeoutMilliseconds
$script:JkxtPublicCommandStarted = $up.commandStarted
$script:JkxtPublicWorkMayStillBeRunning = $up.workMayStillBeRunning
if ($up.workMayStillBeRunning) {
  Write-SummaryAndExit -Status 'indeterminate' -ExitCode 1 -Detail 'Startup client did not return a definite outcome. Background work may still be running; inspect the exact deployment before retrying. No stop or rollback is assumed.' -Checks $checks -Extra @{composeUp=$up}
}
$status = Invoke-DockerCommand -DockerArgs $statusArgs
$script:JkxtPublicWorkMayStillBeRunning = $status.workMayStillBeRunning
if ([int]$up.exitCode -ne 0) {
  Add-Check $checks "compose:up" "fail" "docker compose public startup failed"
  Write-SummaryAndExit -Status "fail" -ExitCode ([int]$up.exitCode) -Detail "docker compose public startup failed." -Checks $checks -Extra @{
    createdEnvFromExample = $createdEnvFromExample
    startCommand = "docker " + (ConvertTo-CommandLine -CommandArgs $composeArgs)
    composeConfig = $config
    composeUp = $up
    composeStatus = $status
  }
}

Add-Check $checks "compose:up" "ok" "docker compose up exited 0"
if ([int]$status.exitCode -ne 0) {
  Add-Check $checks 'compose:status' 'fail' 'docker compose service status check failed'
  Write-SummaryAndExit -Status 'fail' -ExitCode ([int]$status.exitCode) -Detail 'Startup was requested, but service status could not be verified. Inspect the deployment; success is not assumed.' -Checks $checks -Extra @{
    startupCommandSucceeded = $true
    t3ProductionActionPerformed = $true
    composeStatus = $status
  }
}
Add-Check $checks 'compose:status' 'ok' 'docker compose ps exited 0'
Write-SummaryAndExit -Status "started" -ExitCode 0 -Detail "Public JKXT startup command completed; run verify-connected-remote.ps1 after health is UP." -Checks $checks -Extra @{
  createdEnvFromExample = $createdEnvFromExample
  startCommand = "docker " + (ConvertTo-CommandLine -CommandArgs $composeArgs)
  composeConfig = $config
  composeUp = $up
  composeStatus = $status
}
