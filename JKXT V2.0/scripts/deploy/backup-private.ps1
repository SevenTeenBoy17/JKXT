param(
  [string]$EnvFile = ".env.private",
  [string]$Stamp = (Get-Date -Format "yyyyMMdd-HHmmss")
)

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$ComposeFile = Join-Path $Root "docker-compose.private.yml"
$TargetEnv = Join-Path $Root $EnvFile
$BackupName = "jkxt-private-$Stamp.sql"

if (-not (Test-Path -LiteralPath $TargetEnv)) {
  throw "Env file not found: $TargetEnv"
}

$command = "set -eu; pg_dump -U ""`$POSTGRES_USER"" -d ""`$POSTGRES_DB"" -f /backups/$BackupName; sha256sum /backups/$BackupName > /backups/$BackupName.sha256; cat /backups/$BackupName.sha256"
& docker compose -f $ComposeFile --env-file $TargetEnv exec -T postgres sh -lc $command
if ($LASTEXITCODE -ne 0) {
  throw "Private backup failed"
}

[pscustomobject]@{
  backup = "/backups/$BackupName"
  checksum = "/backups/$BackupName.sha256"
  compose = $ComposeFile
} | ConvertTo-Json -Depth 3
