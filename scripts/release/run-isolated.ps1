param(
  [Parameter(Mandatory=$true)][string]$JavaHome,
  [Parameter(Mandatory=$true)][string]$Maven,
  [ValidatePattern('^[a-z0-9_]+$')][string]$Run = ('release_g1_' + [DateTime]::UtcNow.ToString('yyyyMMddHHmmss')),
  [ValidateRange(1024,65535)][int]$DatabasePort = 25432,
  [ValidateRange(1024,65535)][int]$RedisPort = 26379
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$backend = Join-Path $root 'JKXT V2.0/backend'
$project = 'jkxt-test-' + $Run.Replace('_','-')
if ($DatabasePort -in @(5432,6379) -or $RedisPort -in @(5432,6379) -or $DatabasePort -eq $RedisPort) {
  throw 'Dedicated, distinct, nonstandard loopback ports are required'
}
if (!(Test-Path -LiteralPath (Join-Path $JavaHome 'bin/java.exe')) -or !(Test-Path -LiteralPath $Maven)) {
  throw 'Explicit Java 21 and native Maven paths are required'
}
# Never inherit business connection strings, JVM properties or paid-provider keys.
Get-ChildItem Env: | Where-Object { $_.Name -match '^(JKXT_|SPRING_|SERVER_|MANAGEMENT_|POSTGRES_|REDIS_|DOCKER_|TESTCONTAINERS_|MAVEN_|M2_HOME$|JVM_CONFIG_MAVEN_PROPS$|DATABASE_URL$|JAVA_TOOL_OPTIONS$|JDK_JAVA_OPTIONS$|_JAVA_OPTIONS$)' } | ForEach-Object {
  Remove-Item -LiteralPath ('Env:' + $_.Name)
}
$env:JAVA_HOME = $JavaHome
$env:MAVEN_SKIP_RC = '1'
$env:MAVEN_BASEDIR = $backend
$env:MAVEN_PROJECTBASEDIR = $backend
$env:DOCKER_CONTEXT = 'desktop-linux'
$env:DOCKER_HOST = 'npipe:////./pipe/dockerDesktopLinuxEngine'
foreach ($directory in @($root,$backend)) {
  if (Test-Path -LiteralPath (Join-Path $directory '.mvn')) { throw 'Repository Maven injection configuration requires separate review before this isolated runner can proceed' }
}
$env:JKXT_RELEASE_RUN = $Run
$env:JKXT_RELEASE_DB_PASSWORD = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24)).ToLower()
$env:JKXT_RELEASE_DB_PORT = [string]$DatabasePort
$env:JKXT_RELEASE_REDIS_PORT = [string]$RedisPort
$env:JKXT_TEST_DATASOURCE_URL = "jdbc:postgresql://127.0.0.1:$DatabasePort/jkxt_test_$Run"
$env:JKXT_TEST_DATASOURCE_USERNAME = 'jkxt_test'
$env:JKXT_TEST_DATASOURCE_PASSWORD = $env:JKXT_RELEASE_DB_PASSWORD
$env:JKXT_TEST_REDIS_HOST = '127.0.0.1'
$env:JKXT_TEST_REDIS_PORT = [string]$RedisPort
$env:JKXT_LLM_ENABLED = 'false'
$env:JKXT_EXTERNAL_SMS_ENABLED = 'false'
$evidence = Join-Path $root "docs/release/evidence/$Run"
if (Test-Path -LiteralPath $evidence) { throw 'This run already has evidence. Choose a fresh run id; never overwrite.' }
New-Item -ItemType Directory -Path $evidence | Out-Null
if (!(Get-Command docker -ErrorAction SilentlyContinue)) {
  @{status='BLOCKED';reason='Docker CLI unavailable';run=$Run;businessDatabaseAccessed=$false} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $evidence 'environment.json') -Encoding utf8
  exit 2
}
$dockerEndpoint = & docker context inspect desktop-linux --format '{{.Endpoints.docker.Host}}'
if ($LASTEXITCODE -ne 0 -or $dockerEndpoint.Trim() -ne 'npipe:////./pipe/dockerDesktopLinuxEngine') { throw 'Docker context is not the expected local desktop engine; refusing remote execution' }
& (Join-Path $PSScriptRoot 'record-command.ps1') -Id docker-preflight -Executable docker -Arguments @('version') -WorkingDirectory $root -EvidenceDirectory $evidence
if ($LASTEXITCODE -ne 0) {
  @{status='BLOCKED';reason='Docker engine unavailable';run=$Run;businessDatabaseAccessed=$false} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $evidence 'environment.json') -Encoding utf8
  exit 2
}
foreach ($port in @($DatabasePort,$RedisPort)) {
  if (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue) { throw "Test port $port already in use; refusing reuse" }
}
$existing = & docker ps -aq --filter "label=com.docker.compose.project=$project"
if ($LASTEXITCODE -ne 0 -or $existing) { throw 'Project is not provably fresh; refusing to reuse containers' }
$compose = @('compose','-f',(Join-Path $root 'compose.release-test.yml'),'-p',$project)
& (Join-Path $PSScriptRoot 'record-command.ps1') -Id infrastructure-up -Executable docker -Arguments ($compose + @('up','-d','--wait','--wait-timeout','60')) -WorkingDirectory $root -EvidenceDirectory $evidence
if ($LASTEXITCODE -ne 0) { throw 'Test infrastructure did not become healthy; no tests started' }
$metadata = [ordered]@{
  status='READY';run=$Run;project=$project;database="jkxt_test_$Run"
  databaseEndpoint="127.0.0.1:$DatabasePort";redisEndpoint="127.0.0.1:$RedisPort"
  ephemeralStorage=$true;businessDatabaseAccessed=$false;testcontainersEnabled=$true
  cleanup="Set JKXT_RELEASE_RUN=$Run and JKXT_RELEASE_DB_PASSWORD=cleanup-placeholder, then docker compose -f compose.release-test.yml -p $project down (ephemeral test data only)"
}
$metadata | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $evidence 'environment.json') -Encoding utf8
$sourceFile = Join-Path $evidence 'before-suite-source.json'
& node (Join-Path $PSScriptRoot 'capture-source.mjs') $root $sourceFile
if ($LASTEXITCODE -ne 0) { throw 'Could not capture test identity' }
$source = Get-Content -LiteralPath $sourceFile -Raw | ConvertFrom-Json
$sha = (& git -C $root rev-parse HEAD).Trim()
$reports = (Join-Path $evidence 'junit').Replace('\','/')
$mappings = (Join-Path $evidence 'spring-mappings.json').Replace('\','/')
& (Join-Path $PSScriptRoot 'record-command.ps1') -Id full-maven-suite -Executable $Maven -Arguments @(
  '-B','-o','-s',(Join-Path $PSScriptRoot 'maven-settings.xml'),'-gs',(Join-Path $PSScriptRoot 'maven-settings.xml'),
  "-Djkxt.test.reportsDirectory=$reports",'-Djkxt.testcontainers.enabled=true',
  '-Dserver.address=127.0.0.1',
  "-Djkxt.release.mappingOutput=$mappings","-Djkxt.release.runId=$Run",
  "-Djkxt.release.gitSha=$sha","-Djkxt.release.sourceDigest=$($source.sha256)",'test'
) -WorkingDirectory $backend -EvidenceDirectory $evidence
$testExit = $LASTEXITCODE
# Resources are retained for evidence inspection. Stop only this named ephemeral project afterwards.
Write-Output "TEST_EXIT=$testExit; evidence=$evidence; isolated project=$project"
exit $testExit
