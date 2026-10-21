#requires -Version 5.1
param(
  [string]$EnvFile = '.env.private',
  [string]$BackupName = '',
  [string]$ScratchDb = 'jkxt_restore_drill',
  [switch]$DropScratchAfter,
  [ValidateRange(1, 86400000)][int]$LongOperationTimeoutMilliseconds = 900000
)

$ErrorActionPreference = 'Stop'
$Root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$ComposeFile = Join-Path $Root 'docker-compose.private.yml'
$TargetEnv = if ([IO.Path]::IsPathRooted($EnvFile)) { $EnvFile } else { Join-Path $Root $EnvFile }
. (Join-Path $PSScriptRoot 'native-command.ps1')
$script:JkxtRestoreIndeterminate = $false
$script:JkxtRestoreCleanupAttempted = $false
$script:createdHere = $false
$script:droppedHere = $false
$script:DbName = $null

function Invoke-Compose {
  param([string]$Stage, [string[]]$ComposeArgs, [int]$TimeoutMilliseconds = 900000)
  $result = Invoke-JkxtNativeCommand -Executable 'docker' -ArgumentList (@('compose', '-f', $ComposeFile, '--env-file', $TargetEnv) + $ComposeArgs) -TimeoutMilliseconds $TimeoutMilliseconds
  if ($result.workMayStillBeRunning) {
    $script:JkxtRestoreIndeterminate = $true
    # Write a nonsecret report even when this function's normal stdout is captured.
    # Do not infer current ownership/termination from an earlier createdb success.
    $uncertain = [pscustomobject]@{
      status='indeterminate'; stage=$Stage; commandStarted=[bool]$result.commandStarted
      workMayStillBeRunning=$true; timedOut=[bool]$result.timedOut
      sourceDb=$script:DbName; scratchDb=$ScratchDb; scratchOwnership='indeterminate'
      createdByThisRun=[bool]$script:createdHere; restored=$false; droppedScratch=[bool]$script:droppedHere
      cleanupAttempted=$script:JkxtRestoreCleanupAttempted; cleanupDeferred=$true
      longOperationTimeoutMilliseconds=$LongOperationTimeoutMilliseconds; releaseApproved=$false
      detail='Client outcome is unknown. Preserve the exact scratch target and inspect running server work before any retry or cleanup; killing a client is not cancellation.'
    }
    [Console]::Out.WriteLine(($uncertain | ConvertTo-Json -Depth 4))
  }
  if ($result.invocationFailed) { throw "Restore drill could not execute $Stage. No success is assumed." }
  if ($result.exitCode -ne 0) { throw "Restore drill failed at $Stage (exit $($result.exitCode)). No success is assumed." }
  return $result.stdout.Trim()
}

function Get-BackupBasename {
  param([string]$Value)
  if ($Value.StartsWith('/backups/', [StringComparison]::Ordinal)) { $Value = $Value.Substring(9) }
  # No traversal, quotes, whitespace, options, subdirectories or shell syntax.
  if ($Value -cnotmatch '\Ajkxt-private-[A-Za-z0-9-]+\.sql\z') { throw 'Backup must be one private backup filename under /backups.' }
  return $Value
}

if (-not (Test-Path -LiteralPath $TargetEnv -PathType Leaf)) { throw 'Environment file not found; no Docker command was run.' }
if (-not (Test-Path -LiteralPath $ComposeFile -PathType Leaf)) { throw 'Compose file not found; no Docker command was run.' }
if ($ScratchDb.Length -gt 63 -or $ScratchDb -cnotmatch '\Ajkxt_restore_[a-z0-9_]+\z') {
  throw 'Scratch database must be a dedicated jkxt_restore_ name using lowercase ASCII letters, digits and underscores, at most 63 characters.'
}
if ($BackupName) { $BackupName = Get-BackupBasename -Value $BackupName }

# Shell variables can override env-file values. Only resolved Compose identifies the source.
$resolvedText = Invoke-Compose -Stage 'resolved configuration' -ComposeArgs @('config', '--format', 'json')
try { $resolved = $resolvedText | ConvertFrom-Json } catch { throw 'Resolved Compose configuration was not valid JSON.' }
$DbUser = [string]$resolved.services.postgres.environment.POSTGRES_USER
$DbName = [string]$resolved.services.postgres.environment.POSTGRES_DB
foreach ($identity in @($DbUser, $DbName)) {
  if ($identity -cnotmatch '\A[A-Za-z_][A-Za-z0-9_]{0,62}\z') { throw 'Resolved database identity is missing or unsupported; no database was modified.' }
}
if ($ScratchDb -ieq $DbName) { throw 'Scratch database must never be the configured source database.' }

if (-not $BackupName) {
  $selected = Invoke-Compose -Stage 'backup lookup' -ComposeArgs @('exec', '-T', 'postgres', 'sh', '-c', 'cd /backups && ls -1t -- jkxt-private-*.sql | head -n 1')
  $BackupName = Get-BackupBasename -Value $selected
}
$BackupPath = '/backups/' + $BackupName

# Names are positional data, not shell source. Compare only the requested file to its sidecar;
# a tampered sidecar cannot make sha256sum read an unrelated path. Reject symlink artifacts.
$checksumScript = 'set -eu; cd /backups; test -f "$1"; test ! -L "$1"; test -f "$1.sha256"; test ! -L "$1.sha256"; expected=$(sha256sum -- "$1"); actual=$(cat -- "$1.sha256"); test "$expected" = "$actual"'
$null = Invoke-Compose -Stage 'backup checksum' -ComposeArgs @('exec', '-T', 'postgres', 'sh', '-c', $checksumScript, 'jkxt-checksum', $BackupName)
$existsSql = "select exists(select 1 from pg_database where datname = '$ScratchDb')"
$exists = Invoke-Compose -Stage 'scratch existence check' -ComposeArgs @('exec', '-T', 'postgres', 'psql', '-X', '-w', '-U', $DbUser, '-d', $DbName, '-v', 'ON_ERROR_STOP=1', '-Atc', $existsSql)
if ($exists -ne 'f') { throw 'Scratch database exists or absence could not be proven. Choose a new name; existing databases are never removed.' }

$createdHere = $false
$droppedHere = $false
$failure = $null
$counts = @()
try {
  # createdb itself closes the existence-check race: failure never grants cleanup ownership.
  $null = Invoke-Compose -Stage 'scratch creation' -ComposeArgs @('exec', '-T', 'postgres', 'createdb', '-w', '-U', $DbUser, "--maintenance-db=$DbName", '--', $ScratchDb)
  $createdHere = $true
  $null = Invoke-Compose -Stage 'SQL restoration' -ComposeArgs @('exec', '-T', 'postgres', 'psql', '-X', '-w', '-q', '-U', $DbUser, '-d', $ScratchDb, '-v', 'ON_ERROR_STOP=1', '-f', $BackupPath) -TimeoutMilliseconds $LongOperationTimeoutMilliseconds
  foreach ($table in @('organization', 'app_user', 'exam_task', 'schedule_assignment')) {
    $count = Invoke-Compose -Stage "content query $table" -ComposeArgs @('exec', '-T', 'postgres', 'psql', '-X', '-w', '-U', $DbUser, '-d', $ScratchDb, '-v', 'ON_ERROR_STOP=1', '-Atc', "select count(*) from $table")
    if ($count -cnotmatch '\A[0-9]+\z') { throw "Invalid content count for $table." }
    $counts += "$table=$count"
  }
} catch {
  $failure = $_.Exception.Message
} finally {
  if ($DropScratchAfter -and $createdHere -and -not $script:JkxtRestoreIndeterminate) {
    try {
      $script:JkxtRestoreCleanupAttempted = $true
      $null = Invoke-Compose -Stage 'owned scratch cleanup' -ComposeArgs @('exec', '-T', 'postgres', 'dropdb', '-w', '-U', $DbUser, "--maintenance-db=$DbName", '--', $ScratchDb)
      $droppedHere = $true
    } catch {
      $failure = if ($failure) { "$failure Cleanup also failed; inspect the owned scratch database." } else { $_.Exception.Message }
    }
  }
}
if ($failure) { throw $failure }

[pscustomobject]@{
  backup = $BackupPath
  checksum = "$BackupPath.sha256"
  sourceDb = $DbName
  scratchDb = $ScratchDb
  restored = $true
  result = $counts
  droppedScratch = $droppedHere
  verificationScope = 'SQL restore and four table counts only; not complete content, ownership, application or RTO acceptance.'
  releaseApproved = $false
} | ConvertTo-Json -Depth 4
