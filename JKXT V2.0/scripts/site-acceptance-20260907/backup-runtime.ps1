$ErrorActionPreference = 'Stop'
$docker = 'C:\Program Files\Docker\Docker\resources\bin\docker.exe'
$project = 'D:\VB\JKXT\JKXT V2.0'
$backup = 'C:\Users\nuoya\AppData\Local\JKXT\backups\site-acceptance-20260907\final-v18'
if (Test-Path -LiteralPath $backup) { throw 'Refusing to overwrite an existing final backup' }
$runtime = (& $docker inspect jkxt-private-app | ConvertFrom-Json)[0]
if ($LASTEXITCODE -ne 0 -or $runtime.Image -ne 'sha256:0fbd42c8e157e7b5970791da060c68944556a500d1d6e8527e74e85b4fbf4b68') { throw 'Runtime changed; review before stopping' }
New-Item -ItemType Directory -Path $backup | Out-Null
$envMap = [ordered]@{}
foreach ($entry in $runtime.Config.Env) { $parts=$entry.Split('=',2); $envMap[$parts[0]]=$parts[1] }
# Preserve the running environment, not potentially newer unrelated LLM edits in .env.private.
$override = @{ services=@{ app=@{ image='jkxt-site-accepted:v30-20260907'; environment=$envMap } } }
$override | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath "$backup\compose-runtime-release.json" -Encoding utf8
foreach ($file in @('.env.private','docker-compose.private.yml')) { Copy-Item -LiteralPath "$project\$file" -Destination $backup }
Copy-Item -LiteralPath "$project\artifacts\site-acceptance-20260907\checkpoint\running-backend-before.jar" -Destination "$backup\old-backend.jar"
$stopAttempted = $false
try {
  $stopAttempted = $true
  & $docker stop --timeout 30 jkxt-private-app
  if ($LASTEXITCODE -ne 0) { throw 'Could not stop application' }
  $count = & $docker exec jkxt-private-postgres sh -lc 'psql -X -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atc "select count(*) from pg_stat_activity where datname=current_database() and pid<>pg_backend_pid();"'
  $connectionCount = 0
  if ($LASTEXITCODE -ne 0 -or -not [int]::TryParse([string]$count, [ref]$connectionCount)) { throw 'Unable to verify database sessions' }
  if ($connectionCount -ne 0) { throw 'Other database sessions exist; upgrade is not permitted under active writes' }
  & $docker exec jkxt-private-postgres sh -lc 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc -f /backups/private-v18-final-20260907.dump && pg_dumpall -U "$POSTGRES_USER" --globals-only -f /backups/private-globals-final-20260907.sql'
  if ($LASTEXITCODE -ne 0) { throw 'Final database backup failed' }
  & $docker cp jkxt-private-postgres:/backups/private-v18-final-20260907.dump "$backup\private-v18-final.dump"
  if ($LASTEXITCODE -ne 0) { throw 'Final database backup copy failed' }
  & $docker cp jkxt-private-postgres:/backups/private-globals-final-20260907.sql "$backup\private-globals-final.sql"
  if ($LASTEXITCODE -ne 0) { throw 'Globals backup copy failed' }
  Copy-Item -LiteralPath "$project\uploads" -Destination "$backup\uploads" -Recurse
  $manifest = @(Get-ChildItem -LiteralPath $backup -Recurse -File | ForEach-Object { @{path=$_.FullName.Substring($backup.Length+1);bytes=$_.Length;sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash} })
  $manifest | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath "$backup\manifest.json" -Encoding utf8
  [ordered]@{backup=$backup;files=$manifest.Count;databaseHash=(Get-FileHash -LiteralPath "$backup\private-v18-final.dump" -Algorithm SHA256).Hash;oldImage=$runtime.Image;state='old app stopped; final restore validation required before upgrade'} | ConvertTo-Json
} catch {
  if ($stopAttempted) {
    & $docker start jkxt-private-app | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Backup failed and the old application could not be restarted; manual recovery required' }
  }
  throw
}
