#requires -Version 5.1
param(
  [string]$EnvFile = ".env.public",
  [switch]$DryRun,
  [string]$ConfirmStopProduction = ""
)

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..\..")).Path
$ComposeFile = Join-Path $Root "docker-compose.public.yml"
$TargetEnv = if ([System.IO.Path]::IsPathRooted($EnvFile)) { $EnvFile } else { Join-Path $Root $EnvFile }
$ConfirmPhrase = "STOP JKXT PUBLIC PRODUCTION"

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
  param([string[]]$DockerArgs)
  try {
    $output = & docker @DockerArgs 2>&1
    $exitCode = if ($null -eq $LASTEXITCODE) { 0 } else { $LASTEXITCODE }
    return [ordered]@{
      exitCode = $exitCode
      output = (($output | Out-String).Trim())
    }
  } catch {
    return [ordered]@{
      exitCode = 1
      output = $_.Exception.Message
    }
  } finally {
    $global:LASTEXITCODE = 0
  }
}

function Write-SummaryAndExit {
  param(
    [string]$Status,
    [int]$ExitCode,
    [string]$Detail,
    [hashtable]$Extra = @{}
  )

  $summary = [ordered]@{
    status = $Status
    checkedAt = (Get-Date).ToString("yyyy-MM-ddTHH:mm:sszzz")
    composeFile = $ComposeFile
    envFile = $TargetEnv
    confirmPhrase = $ConfirmPhrase
    removesVolumes = $false
    preservesNamedVolumes = $true
    detail = $Detail
  }
  foreach ($key in ($Extra.Keys | Sort-Object)) {
    $summary[$key] = $Extra[$key]
  }
  [pscustomobject]$summary | ConvertTo-Json -Depth 8
  exit $ExitCode
}

if (-not (Test-Path -LiteralPath $ComposeFile)) {
  Write-SummaryAndExit -Status "fail" -ExitCode 1 -Detail "Missing docker-compose.public.yml."
}

if (-not (Test-Path -LiteralPath $TargetEnv)) {
  Write-SummaryAndExit -Status "fail" -ExitCode 1 -Detail "Missing env file: $TargetEnv."
}

$configArgs = @("compose", "-f", $ComposeFile, "--env-file", $TargetEnv, "config", "--quiet")
$statusArgs = @("compose", "-f", $ComposeFile, "--env-file", $TargetEnv, "ps")
$stopArgs = @("compose", "-f", $ComposeFile, "--env-file", $TargetEnv, "stop")
$stopCommand = "docker " + (ConvertTo-CommandLine -CommandArgs $stopArgs)

if ($DryRun) {
  $configResult = Invoke-DockerCommand -DockerArgs $configArgs
  $statusResult = Invoke-DockerCommand -DockerArgs $statusArgs
  $dryRunStatus = if ([int]$configResult.exitCode -eq 0) { "dry-run" } else { "fail" }
  $dryRunExit = if ([int]$configResult.exitCode -eq 0) { 0 } else { [int]$configResult.exitCode }
  Write-SummaryAndExit -Status $dryRunStatus -ExitCode $dryRunExit -Detail "Dry run only; no containers were stopped." -Extra @{
    plannedCommand = $stopCommand
    composeConfig = $configResult
    composeStatus = $statusResult
  }
}

if ($ConfirmStopProduction -ne $ConfirmPhrase) {
  Write-SummaryAndExit -Status "refused" -ExitCode 1 -Detail "Refusing public production stop. Re-run with -ConfirmStopProduction '$ConfirmPhrase'."
}

$config = Invoke-DockerCommand -DockerArgs $configArgs
if ([int]$config.exitCode -ne 0) {
  Write-SummaryAndExit -Status "fail" -ExitCode ([int]$config.exitCode) -Detail "docker compose config failed; no stop command was run." -Extra @{
    composeConfig = $config
  }
}

$stop = Invoke-DockerCommand -DockerArgs $stopArgs
$status = Invoke-DockerCommand -DockerArgs $statusArgs
$finalStatus = if ([int]$stop.exitCode -eq 0) { "stopped" } else { "fail" }
Write-SummaryAndExit -Status $finalStatus -ExitCode ([int]$stop.exitCode) -Detail "docker compose stop completed without deleting named volumes." -Extra @{
  stopCommand = $stopCommand
  composeStop = $stop
  composeStatus = $status
}
