#requires -Version 5.1
param(
  [string]$EnvFile = '.env.private',
  [switch]$NoBuild,
  [switch]$DryRun,
  [string]$ConfirmStartProduction = '',
  [ValidateRange(1, 86400000)][int]$LongOperationTimeoutMilliseconds = 900000
)
$ErrorActionPreference = 'Stop'
$Root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$ComposeFile = Join-Path $Root 'docker-compose.private.yml'
$TargetEnv = if ([IO.Path]::IsPathRooted($EnvFile)) { $EnvFile } else { Join-Path $Root $EnvFile }
. (Join-Path $PSScriptRoot 'production-config.ps1')
. (Join-Path $PSScriptRoot 'native-command.ps1')
if (-not (Test-Path -LiteralPath $TargetEnv)) {
  Copy-Item -LiteralPath (Join-Path $Root '.env.private.example') -Destination $TargetEnv
  throw 'Created the private configuration template. Set real HTTPS origins and unique secrets before retrying. No containers were started.'
}
$resolved = Invoke-JkxtNativeCommand -Executable 'docker' -ArgumentList @('compose', '-f', $ComposeFile, '--env-file', $TargetEnv, 'config', '--format', 'json')
if ($resolved.invocationFailed -or $resolved.exitCode -ne 0) { throw 'Compose configuration is invalid; no containers were started.' }
try { $config = $resolved.stdout | ConvertFrom-Json -ErrorAction Stop } catch { throw 'Resolved production configuration was not valid JSON; no containers were started.' }
if ($null -eq $config) { throw 'Resolved production configuration was empty; no containers were started.' }
$issues = @(Get-JkxtProductionViolations -Config $config)
if ($issues.Count) { throw ('Production preflight refused startup: ' + ($issues -join '; ')) }
if ($DryRun) {
  [pscustomobject]@{status='dry-run'; containersStarted=$false; secretsPrinted=$false; checks='resolved configuration passed'} | ConvertTo-Json
  exit 0
}
if ($ConfirmStartProduction -cne 'START JKXT PRIVATE PRODUCTION') {
  throw "Validated, but not started. Provide -ConfirmStartProduction 'START JKXT PRIVATE PRODUCTION' only after deployment authorization."
}
$arguments = @('compose', '-f', $ComposeFile, '--env-file', $TargetEnv, 'up', '-d')
if (-not $NoBuild) { $arguments += '--build' }
$start = Invoke-JkxtNativeCommand -Executable 'docker' -ArgumentList $arguments -TimeoutMilliseconds $LongOperationTimeoutMilliseconds
if ($start.invocationFailed -or $start.exitCode -ne 0) {
  [pscustomobject]@{
    status = if ($start.workMayStillBeRunning) { 'indeterminate' } else { 'fail' }
    commandStarted = [bool]$start.commandStarted
    workMayStillBeRunning = [bool]$start.workMayStillBeRunning
    timedOut = [bool]$start.timedOut
    productionStartupAttempted = $true
    productionChangesMayHaveOccurred = [bool]$start.commandStarted
    longOperationTimeoutMilliseconds = $LongOperationTimeoutMilliseconds
    detail = 'Startup has no confirmed successful outcome. Inspect the exact deployment before retrying; a terminated client does not prove background work stopped.'
  } | ConvertTo-Json
  throw 'Private startup failed; no success or background cancellation is assumed'
}
$status = Invoke-JkxtNativeCommand -Executable 'docker' -ArgumentList @('compose', '-f', $ComposeFile, '--env-file', $TargetEnv, 'ps')
if ($status.invocationFailed -or $status.exitCode -ne 0) { throw 'Private service status check failed' }
Write-Host 'Startup requested. Verify HTTPS, readiness, operator provisioning and business acceptance separately; this is not release approval.'
