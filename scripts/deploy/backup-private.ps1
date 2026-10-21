param(
  [string]$EnvFile = ".env.private",
  [ValidatePattern('^[0-9]{8}-[0-9]{6}$')][string]$Stamp = (Get-Date -Format "yyyyMMdd-HHmmss")
)

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$ComposeFile = Join-Path $Root "docker-compose.private.yml"
$TargetEnv = if ([IO.Path]::IsPathRooted($EnvFile)) { $EnvFile } else { Join-Path $Root $EnvFile }

if (-not (Test-Path -LiteralPath $TargetEnv)) {
  throw "Env file not found: $TargetEnv"
}

$result = & docker compose -f $ComposeFile --env-file $TargetEnv --profile ops run --rm --no-deps backup jkxt-private $Stamp
if ($LASTEXITCODE -ne 0) {
  throw "Private backup failed"
}
$artifact = [regex]::Match(($result -join "`n"), 'BACKUP=(jkxt-private-[A-Za-z0-9-]+\.sql) SHA256=([a-f0-9]{64})')
if (-not $artifact.Success) { throw 'Backup did not return a verified completed artifact' }
$BackupName = $artifact.Groups[1].Value

[pscustomobject]@{
  backup = "/backups/$BackupName"
  checksum = "/backups/$BackupName.sha256"
  sha256 = $artifact.Groups[2].Value
  compose = $ComposeFile
} | ConvertTo-Json -Depth 3
