param([string]$Tests='DemoWorkspaceIntegrationTest,DemoResetConcurrencyTest,DemoExpiryTest')
$ErrorActionPreference='Stop'
$candidate=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
if($candidate -ne 'D:\VB\JKXT-candidates\login-entry-20260920-v2'){throw 'Unexpected candidate root'}
$namedContainer='jkxt-demo-task4-entry20260920v2-jkxt-test-demo-db-1'
# Deliberately select only the dedicated test container; never print the credential-bearing values.
$demoValues=docker inspect --format '{{json .Config.Env}}' $namedContainer | ConvertFrom-Json
$env:JKXT_DEMO_TEST_ADMIN_PASSWORD=($demoValues | Where-Object {$_.StartsWith('POSTGRES_PASSWORD=')}).Substring(18)
$env:JKXT_DEMO_TEST_PASSWORD=($demoValues | Where-Object {$_.StartsWith('JKXT_DEMO_TEST_PASSWORD=')}).Substring(24)
$env:JKXT_CANDIDATE_ROOT=$candidate
$reports=Join-Path $candidate 'backend/target/surefire-reports'
if(Test-Path -LiteralPath $reports){
  $archive=Join-Path 'D:/VB/JKXT/.superpowers/sdd/2026-09-20-login-entry-isolation-implementation/task4-test-checkpoint' ([Guid]::NewGuid().ToString('N'))
  $null=New-Item -ItemType Directory -Path $archive -Force
  Copy-Item -LiteralPath $reports -Destination $archive -Recurse
  Write-Output "Prior test reports preserved: $archive"
}
Push-Location -LiteralPath $candidate
try{
  docker compose -f scripts/login-isolation/compose.demo-task4.yml run --rm demo-runner mvn -B "-Dtest=$Tests" clean test
  exit $LASTEXITCODE
} finally {Pop-Location}
