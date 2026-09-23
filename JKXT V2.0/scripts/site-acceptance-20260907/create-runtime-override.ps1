$ErrorActionPreference='Stop'
$docker='C:\Program Files\Docker\Docker\resources\bin\docker.exe'
$destination='C:\Users\nuoya\AppData\Local\JKXT\backups\site-acceptance-20260907\final-v18\compose-runtime-release.json'
$runtime=(& $docker inspect jkxt-private-app | ConvertFrom-Json)[0]
if($LASTEXITCODE -ne 0 -or $runtime.Image -ne 'sha256:0fbd42c8e157e7b5970791da060c68944556a500d1d6e8527e74e85b4fbf4b68'){throw 'Unexpected previous image'}
$base=(& $docker compose --env-file .env.private -f docker-compose.private.yml config --format json | ConvertFrom-Json).services.app.environment
if($LASTEXITCODE -ne 0 -or $null-eq$base){throw 'Unable to read base Compose environment'}
$envMap=[ordered]@{}
foreach($entry in $runtime.Config.Env){$p=$entry.Split('=',2);$envMap[$p[0]]=$p[1]}
$runtimeKeys=@($envMap.Keys)
# A null Compose value removes inherited variables absent from the actual old runtime.
foreach($p in $base.PSObject.Properties){if(-not $envMap.Contains($p.Name)){$envMap[$p.Name]=$null}}
@{services=@{app=@{image='jkxt-site-accepted:v30-20260907';environment=$envMap}}}|ConvertTo-Json -Depth 8|Set-Content -LiteralPath $destination -Encoding utf8
$effective=(& $docker compose --env-file .env.private -f docker-compose.private.yml -f $destination config --format json | ConvertFrom-Json).services.app.environment
if($LASTEXITCODE -ne 0 -or $null-eq$effective){throw 'Unable to read effective Compose environment'}
$effectiveMap=@{}
foreach($p in $effective.PSObject.Properties){if($null-ne$p.Value){$effectiveMap[$p.Name]=[string]$p.Value}}
# Compare both directions: an omitted old variable is a change, including an empty value.
$allKeys=@(@($runtimeKeys)+@($effectiveMap.Keys)|Sort-Object -Unique)
$differences=@(foreach($key in $allKeys){
  if($key -notin $runtimeKeys -or -not $effectiveMap.ContainsKey($key) -or [string]$envMap[$key] -cne $effectiveMap[$key]){$key}
})
if($differences.Count){throw ('Unexpected environment changes: '+($differences -join ', '))}
@{environmentChanges=0;overrideHash=(Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash}|ConvertTo-Json
