#requires -Version 5.1
param(
  [string]$EnvFile = ".env.public",
  [ValidatePattern('^[0-9]{8}-[0-9]{6}$')][string]$Stamp = (Get-Date -Format "yyyyMMdd-HHmmss"),
  [switch]$DryRun,
  [string]$ConfirmBackupProduction = ""
)

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..\..")).Path
$ComposeFile = Join-Path $Root "docker-compose.public.yml"
$TargetEnv = if ([System.IO.Path]::IsPathRooted($EnvFile)) { $EnvFile } else { Join-Path $Root $EnvFile }
$ConfirmPhrase = "BACKUP JKXT PUBLIC PRODUCTION"
$BackupName = $null
$script:JkxtPublicBackupAttempted = $false
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
  param([string[]]$DockerArgs)
  $result = Invoke-JkxtNativeCommand -Executable 'docker' -ArgumentList $DockerArgs
  # Native warnings are not failures or artifact receipts. Only the exact
  # requested artifact line may enter a report; other output can contain secrets.
  $receipt = ''
  if (-not $result.invocationFailed -and $result.exitCode -eq 0 -and ($DockerArgs -contains 'run')) {
    $pattern = '(?m)^BACKUP=jkxt-public-' + [regex]::Escape($Stamp) + '-[A-Za-z0-9]{6}\.sql SHA256=[a-f0-9]{64}\r?$'
    $matches = [regex]::Matches($result.stdout, $pattern)
    if ($matches.Count -eq 1) { $receipt = $matches[0].Value.Trim() }
  }
  return [ordered]@{
    exitCode = if ($result.invocationFailed) { 1 } else { $result.exitCode }
    output = $receipt
    timedOut = [bool]$result.timedOut
    externalWorkMayStillBeRunning = [bool]$result.workMayStillBeRunning
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
    confirmPhrase = $ConfirmPhrase
    backupRequested = -not [bool]$DryRun
    t3ProductionActionPerformed = if ($Status -eq 'backed-up') { $true } elseif ($script:JkxtPublicBackupAttempted) { $null } else { $false }
    backupAttempted = $script:JkxtPublicBackupAttempted
    productionChangesMayHaveOccurred = $script:JkxtPublicBackupAttempted
    backup = if ($BackupName) { "/backups/$BackupName" } else { $null }
    checksum = if ($BackupName) { "/backups/$BackupName.sha256" } else { $null }
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
if (-not (Test-Path -LiteralPath $ComposeFile)) {
  Add-Check $checks "compose:file" "fail" "Missing docker-compose.public.yml."
  Write-SummaryAndExit -Status "fail" -ExitCode 1 -Detail "Missing compose file." -Checks $checks
}
Add-Check $checks "compose:file" "ok" "found $ComposeFile"

if (-not (Test-Path -LiteralPath $TargetEnv)) {
  Add-Check $checks "env:file" "fail" "Missing env file: $TargetEnv."
  Write-SummaryAndExit -Status "fail" -ExitCode 1 -Detail "Missing env file; no backup command was run." -Checks $checks
}
Add-Check $checks "env:file" "ok" "found $TargetEnv"

$configArgs = @("compose", "-f", $ComposeFile, "--env-file", $TargetEnv, "config", "--quiet")
$backupArgs = @('compose', '-f', $ComposeFile, '--env-file', $TargetEnv, '--profile', 'ops', 'run', '--rm', '--no-deps', 'backup', 'jkxt-public', $Stamp)

$config = Invoke-DockerCommand -DockerArgs $configArgs
if ([int]$config.exitCode -ne 0) {
  Add-Check $checks "compose:config" "fail" "docker compose public config validation failed"
  Write-SummaryAndExit -Status "fail" -ExitCode ([int]$config.exitCode) -Detail "docker compose public config validation failed; no backup command was run." -Checks $checks -Extra @{
    composeConfig = $config
  }
}
Add-Check $checks "compose:config" "ok" "docker compose config exited 0"

if ($DryRun) {
  Write-SummaryAndExit -Status "dry-run" -ExitCode 0 -Detail "Public backup command validated. Dry run only; no backup file was written." -Checks $checks -Extra @{
    plannedCommand = "docker " + (ConvertTo-CommandLine -CommandArgs $backupArgs)
    composeConfig = $config
  }
}

if ($ConfirmBackupProduction -ne $ConfirmPhrase) {
  Add-Check $checks "confirm:backup-production" "fail" "Missing exact production backup confirmation phrase."
  Write-SummaryAndExit -Status "refused" -ExitCode 1 -Detail "Refusing public production backup. Re-run with -ConfirmBackupProduction '$ConfirmPhrase'." -Checks $checks -Extra @{
    plannedCommand = "docker " + (ConvertTo-CommandLine -CommandArgs $backupArgs)
    composeConfig = $config
  }
}

Add-Check $checks "confirm:backup-production" "ok" "exact production backup confirmation phrase supplied"
$script:JkxtPublicBackupAttempted = $true
$backup = Invoke-DockerCommand -DockerArgs $backupArgs
if ([int]$backup.exitCode -ne 0) {
  Add-Check $checks "backup:pg-dump" "fail" "public database backup failed"
  Write-SummaryAndExit -Status "fail" -ExitCode ([int]$backup.exitCode) -Detail "Public backup command failed." -Checks $checks -Extra @{
    backupCommand = "docker " + (ConvertTo-CommandLine -CommandArgs $backupArgs)
    composeConfig = $config
    composeBackup = $backup
  }
}

Add-Check $checks "backup:pg-dump" "ok" "public database backup command exited 0"
$artifact = [regex]::Match($backup.output, 'BACKUP=(jkxt-public-[A-Za-z0-9-]+\.sql) SHA256=([a-f0-9]{64})')
if (-not $artifact.Success) {
  Write-SummaryAndExit -Status 'fail' -ExitCode 1 -Detail 'No completed backup artifact was confirmed.' -Checks $checks
}
$BackupName = $artifact.Groups[1].Value
Write-SummaryAndExit -Status "backed-up" -ExitCode 0 -Detail "Public backup completed; verify the checksum before restore." -Checks $checks -Extra @{
  backupCommand = "docker " + (ConvertTo-CommandLine -CommandArgs $backupArgs)
  composeConfig = $config
  composeBackup = $backup
  sha256 = $artifact.Groups[2].Value
}
