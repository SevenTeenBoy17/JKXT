param(
  [string]$EnvFile = ".env.private",
  [string]$BackupName = "",
  [string]$ScratchDb = "jkxt_restore_drill",
  [switch]$DropScratchAfter
)

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$ComposeFile = Join-Path $Root "docker-compose.private.yml"
$TargetEnv = Join-Path $Root $EnvFile

function Read-EnvValue {
  param(
    [string]$Key,
    [string]$Fallback
  )
  $line = Get-Content -LiteralPath $TargetEnv |
    Where-Object { $_ -match "^\s*$([regex]::Escape($Key))=" } |
    Select-Object -Last 1
  if (-not $line) {
    return $Fallback
  }
  return ($line -replace "^\s*$([regex]::Escape($Key))=", "").Trim()
}

function Invoke-Compose {
  param([string[]]$ComposeArgs)
  & docker compose -f $ComposeFile --env-file $TargetEnv @ComposeArgs
  if ($LASTEXITCODE -ne 0) {
    throw "docker compose command failed: $($ComposeArgs -join ' ')"
  }
}

if (-not (Test-Path -LiteralPath $TargetEnv)) {
  throw "Env file not found: $TargetEnv"
}

$DbUser = Read-EnvValue -Key "POSTGRES_USER" -Fallback "jkxt"
$DbName = Read-EnvValue -Key "POSTGRES_DB" -Fallback "jkxt_private"

if ([string]::IsNullOrWhiteSpace($BackupName)) {
  $BackupName = (& docker compose -f $ComposeFile --env-file $TargetEnv exec -T postgres sh -lc "ls -1t /backups/jkxt-private-*.sql 2>/dev/null | head -1").Trim()
  if (-not $BackupName) {
    throw "No /backups/jkxt-private-*.sql file found. Run backup-private.ps1 first."
  }
} elseif (-not $BackupName.StartsWith("/backups/")) {
  $BackupName = "/backups/$BackupName"
}

$ChecksumName = "$BackupName.sha256"
Invoke-Compose -ComposeArgs @("exec", "-T", "postgres", "sh", "-lc", "if [ -f '$ChecksumName' ]; then sha256sum -c '$ChecksumName'; else echo 'checksum file missing: $ChecksumName' >&2; exit 1; fi")
Invoke-Compose -ComposeArgs @("exec", "-T", "postgres", "dropdb", "-U", $DbUser, "--if-exists", $ScratchDb)
Invoke-Compose -ComposeArgs @("exec", "-T", "postgres", "createdb", "-U", $DbUser, $ScratchDb)
Invoke-Compose -ComposeArgs @("exec", "-T", "postgres", "psql", "-q", "-U", $DbUser, "-d", $ScratchDb, "-v", "ON_ERROR_STOP=1", "-f", $BackupName)

$counts = @()
foreach ($table in @("organization", "app_user", "exam_task", "schedule_assignment")) {
  $count = (& docker compose -f $ComposeFile --env-file $TargetEnv exec -T postgres psql -U $DbUser -d $ScratchDb -Atc "select count(*) from $table").Trim()
  if ($LASTEXITCODE -ne 0) {
    throw "Failed to count $table in restore drill"
  }
  $counts += "$table=$count"
}

if ($DropScratchAfter) {
  Invoke-Compose -ComposeArgs @("exec", "-T", "postgres", "dropdb", "-U", $DbUser, "--if-exists", $ScratchDb)
}

[pscustomobject]@{
  backup = $BackupName
  checksum = $ChecksumName
  sourceDb = $DbName
  scratchDb = $ScratchDb
  result = $counts
  droppedScratch = [bool]$DropScratchAfter
} | ConvertTo-Json -Depth 4
