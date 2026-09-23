param(
  [ValidatePattern('^frontend-results-[a-zA-Z0-9-]+\.json$')][string]$FrontendReport='frontend-results-2026-09-07T10-31-35-049Z.json',
  [ValidatePattern('^[a-zA-Z0-9-]+\.json$')][string]$OutputName='acceptance-evidence-final.json'
)
$ErrorActionPreference='Stop'
$project='D:\VB\JKXT\JKXT V2.0'
$out=Join-Path $project 'artifacts\site-acceptance-20260907'
$docker='C:\Program Files\Docker\Docker\resources\bin\docker.exe'
$totals=@{suites=0;tests=0;failures=0;errors=0;skipped=0}
foreach($file in Get-ChildItem -LiteralPath "$out\backend-final" -Filter 'TEST-*.xml') {
  [xml]$xml=Get-Content -LiteralPath $file.FullName -Raw
  $totals.suites++
  foreach($key in @('tests','failures','errors','skipped')){$totals[$key]+=[int]$xml.testsuite.$key}
}
if($totals.tests-ne262 -or $totals.failures -or $totals.errors -or $totals.skipped){throw 'Backend test evidence does not meet expected contract'}
$frontendFile=Join-Path $out $FrontendReport
$front=Get-Content -LiteralPath $frontendFile -Raw|ConvertFrom-Json
if($front.passed-ne119 -or $front.total-ne119 -or $front.results.Count-ne119){throw 'Frontend test evidence does not meet expected contract'}
foreach($field in @('pageErrors','writeAttempts','external','missingAssets','unknownGets','changedDuringRun')){if(@($front.$field).Count){throw "Frontend evidence contains $field"}}
if($null -eq $front.sourceHashes -or $front.sourceHashes -isnot [pscustomobject] -or @($front.sourceHashes.PSObject.Properties).Count -eq 0){throw 'Frontend source hash evidence is missing or empty'}
foreach($source in $front.sourceHashes.PSObject.Properties){
  $sourcePath=[IO.Path]::GetFullPath((Join-Path $project $source.Name))
  if(-not $sourcePath.StartsWith($project+'\',[StringComparison]::OrdinalIgnoreCase)){throw 'Source hash points outside project'}
  if((Get-FileHash -LiteralPath $sourcePath -Algorithm SHA256).Hash -ne $source.Value){throw "Frontend source drift: $($source.Name)"}
}
$comparisons=foreach($pair in @(@('migration-before.json','migration-after.json'),@('migration-before.json','rollback-restored.json'),@('live-before-upgrade.json','final-backup-restored.json'),@('live-before-upgrade.json','live-after-upgrade.json'))){
  $a=Get-Content -LiteralPath "$out\$($pair[0])" -Raw|ConvertFrom-Json
  $b=Get-Content -LiteralPath "$out\$($pair[1])" -Raw|ConvertFrom-Json
  $aNames=@($a.tables | ForEach-Object {$_.table_name})
  $bNames=@($b.tables | ForEach-Object {$_.table_name})
  if($aNames.Count-ne31 -or $bNames.Count-ne31 -or
    @($aNames | Where-Object {[string]::IsNullOrWhiteSpace($_)}).Count -or
    @($bNames | Where-Object {[string]::IsNullOrWhiteSpace($_)}).Count -or
    @($aNames | Sort-Object -Unique).Count-ne31 -or @($bNames | Sort-Object -Unique).Count-ne31 -or
    @(Compare-Object -ReferenceObject $aNames -DifferenceObject $bNames).Count){throw 'Database table identity evidence is incomplete or duplicated'}
  $diff=@(foreach($row in $a.tables){$r=$b.tables|Where-Object table_name -eq $row.table_name;if($row.count-ne$r.count -or $row.digest-ne$r.digest -or ($row.columns -join ',') -cne ($r.columns -join ',')){$row.table_name}})
  if($diff.Count){throw 'Database preservation check failed'}
  @{before=$pair[0];after=$pair[1];tables=31;differences=$diff;versions=@($a.migration,$b.migration)}
}
$sourceFiles=@('src/pages/people.jsx','src/pages/classrooms.jsx','src/pages/classes-subjects.jsx','src/styles/frontend-polish.css','scripts/frontend-quality/check-resource-actions.mjs','scripts/frontend-quality/check-operations-v6.mjs','scripts/frontend-quality/check-site-acceptance.cjs','backend/src/test/java/com/jkxt/security/SettingsIsolationAcceptanceTest.java','backend/src/test/java/com/jkxt/examops/JointPlanningMigrationIntegrationTest.java')
$sources=@(foreach($file in $sourceFiles){@{path=$file;sha256=(Get-FileHash -LiteralPath "$project\$file" -Algorithm SHA256).Hash}})
$health=Invoke-RestMethod 'http://127.0.0.1:18180/api/v1/health' -TimeoutSec 15
if($health.status-ne'UP'){throw 'Application health failed'}
$api=Invoke-RestMethod 'http://127.0.0.1:18081/v3/api-docs' -TimeoutSec 15
if(-not $api.openapi -or -not $api.paths.'/api/v1/auth/me/preferences' -or -not $api.paths.'/api/v1/org/settings/appearance'){throw 'Settings route verification failed'}
$image=& $docker inspect jkxt-private-app --format '{{.Image}}'
if($image-ne'sha256:b4727a0ef40953e5834359fad6a2f7414455e4b3153857ec733551775e7c4564'){throw 'Live image differs from rehearsal'}
$backup='C:\Users\nuoya\AppData\Local\JKXT\backups\site-acceptance-20260907\final-v18'
# Update the generated manifest after the reviewed exact-runtime Compose overlay was generated.
$manifest=@(Get-ChildItem -LiteralPath $backup -Recurse -File | Where-Object Name -ne 'manifest.json' | ForEach-Object {@{path=$_.FullName.Substring($backup.Length+1);bytes=$_.Length;sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash}})
$manifest|ConvertTo-Json -Depth 5|Set-Content -LiteralPath "$backup\manifest.json" -Encoding utf8
$record=[ordered]@{
  collectedAt=(Get-Date).ToUniversalTime().ToString('o');collector='root explicit local audit; not a signed Supervisor gate';backend=$totals
  frontendReport=$FrontendReport;frontendReportHash=(Get-FileHash -LiteralPath $frontendFile -Algorithm SHA256).Hash;frontend=@{passed=$front.passed;total=$front.total;sourceFiles=@($front.sourceHashes.PSObject.Properties).Count}
  databaseComparisons=@($comparisons);liveImage=$image;testedJarHash=(Get-FileHash -LiteralPath "$out\jkxt-backend-tested.jar" -Algorithm SHA256).Hash
  health=$health.status;openapiPaths=@($api.paths.PSObject.Properties).Count
  backups=@{path=$backup;files=$manifest.Count;databaseHash=(Get-FileHash -LiteralPath "$backup\private-v18-final.dump" -Algorithm SHA256).Hash;manifestHash=(Get-FileHash -LiteralPath "$backup\manifest.json" -Algorithm SHA256).Hash}
  sourceHashes=$sources;notes=@('SQL summaries normalize only documented migration fields; see report','Real authenticated browser observation is a separate evidence layer','No live business action or real external AI/notification delivery was tested')
}
$record|ConvertTo-Json -Depth 10|Set-Content -LiteralPath "$out\$OutputName" -Encoding utf8
$record|ConvertTo-Json -Depth 10
