param([string]$ProjectRoot=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path)
$ErrorActionPreference='Stop'
$docker='C:\Program Files\Docker\Docker\resources\bin\docker.exe'
# Keep expanded environment values private; emit only service names and policies.
$raw=& $docker compose --profile ops --env-file (Join-Path $ProjectRoot '.env.private') -f (Join-Path $ProjectRoot 'docker-compose.private.yml') config --format json 2>$null
if($LASTEXITCODE-ne0){throw 'Private Compose could not be validated; expanded configuration suppressed'}
$config=($raw -join "`n")|ConvertFrom-Json
if($null-eq$config.services){throw 'Private Compose services missing'}
$results=@(foreach($name in @('postgres','redis','app','nginx')){
  $service=$config.services.$name
  [pscustomobject]@{service=$name;restart=$service.restart;pass=($null-ne$service -and $service.restart-ceq'unless-stopped')}
})
$backup=$config.services.backup
$results+=[pscustomobject]@{service='backup';restart=$backup.restart;pass=($null-ne$backup -and ($null-eq$backup.restart -or $backup.restart-ceq'no'))}
$results|ConvertTo-Json -Depth 3
if(@($results|Where-Object {-not $_.pass}).Count){throw 'Private service restart policy contract failed'}
