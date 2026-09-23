$ErrorActionPreference = 'Stop'
$script = Join-Path $PSScriptRoot 'candidate-manifest.ps1'
if (-not (Test-Path -LiteralPath $script)) { throw 'RED: candidate-manifest implementation missing' }
$fixture = Join-Path ([IO.Path]::GetTempPath()) ('jkxt-manifest-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path "$fixture/source/backend/src", "$fixture/output" | Out-Null
Set-Content "$fixture/source/Dashboard.html" 'initial'
Set-Content "$fixture/source/backend/src/new.java" 'new source'
git -C "$fixture/source" init -q
git -C "$fixture/source" add Dashboard.html
git -C "$fixture/source" -c user.name=Fixture -c user.email=fixture@example.invalid commit -qm initial
Set-Content "$fixture/source/Dashboard.html" 'dirty current source'
function Reject($name, [scriptblock]$action) {
  $rejected = $false
  try { & $action | Out-Null } catch { $rejected = $true }
  if (-not $rejected) { throw "Expected rejection: $name" }
  Write-Output "PASS rejection: $name"
}
Reject 'missing directory' { & $script -Source "$fixture/missing" -Destination "$fixture/a" -OutputDirectory "$fixture/output" -RunId fixture }
Reject 'same root' { & $script -Source "$fixture/source" -Destination "$fixture/source" -OutputDirectory "$fixture/output" -RunId fixture }
Reject 'traversal' { & $script -Source "$fixture/source" -Destination "$fixture/b" -OutputDirectory "$fixture/output" -RunId fixture -SelectedPaths @('../outside') }
Reject 'omitted dirty tracked input' { & $script -Source "$fixture/source" -Destination "$fixture/c" -OutputDirectory "$fixture/output" -RunId fixture -SelectedPaths @('backend/src/new.java') }
Reject 'secret path' { & $script -Source "$fixture/source" -Destination "$fixture/d" -OutputDirectory "$fixture/output" -RunId fixture -SelectedPaths @('.env.private') }
New-Item -ItemType Directory -Path "$fixture/source/backend/config", "$fixture/source/backend/target" | Out-Null
Set-Content "$fixture/source/backend/config/.env.private" 'fixture-only-private'
Set-Content "$fixture/source/backend/target/capture.json" 'fixture-only-runtime'
Reject 'mixed separator nested secret' { & $script -Source "$fixture/source" -Destination "$fixture/mixedsecret" -OutputDirectory "$fixture/output" -RunId fixture -SelectedPaths @('Dashboard.html', 'backend/src/new.java', 'backend/config\.env.private') }
Reject 'mixed separator excluded directory' { & $script -Source "$fixture/source" -Destination "$fixture/mixedruntime" -OutputDirectory "$fixture/output" -RunId fixture -SelectedPaths @('Dashboard.html', 'backend/src/new.java', 'backend/target\capture.json') }
Reject 'duplicate separator aliases' { & $script -Source "$fixture/source" -Destination "$fixture/duplicate" -OutputDirectory "$fixture/output" -RunId fixture -SelectedPaths @('Dashboard.html', 'backend/src/new.java', 'backend\src\new.java') }
New-Item -ItemType Directory -Path "$fixture/source/test-data/results/iter6", "$fixture/source/backend/runtime", "$fixture/source/backend/captures" | Out-Null
Set-Content "$fixture/source/test-data/results/iter6/L2_session_security_capture.json" '{"JKXT_SESSION":"fixture-only-not-a-real-session"}'
Set-Content "$fixture/source/backend/runtime/state.json" '{}'
Set-Content "$fixture/source/backend/captures/session.json" '{}'
Reject 'runtime results explicitly requested' { & $script -Source "$fixture/source" -Destination "$fixture/results" -OutputDirectory "$fixture/output" -RunId fixture -SelectedPaths @('Dashboard.html', 'backend/src/new.java', 'test-data/results/iter6/L2_session_security_capture.json') }
& $script -Source "$fixture/source" -Destination "$fixture/candidate" -OutputDirectory "$fixture/output" -RunId fixture
$manifest = Get-Content "$fixture/output/candidate-manifest.json" -Raw | ConvertFrom-Json
if ($manifest.files.Count -ne 2) { throw 'Expected both dirty tracked and untracked source files' }
if ($manifest.files.path -match '\\') { throw 'Manifest paths must use canonical slash separators' }
if (Test-Path "$fixture/candidate/test-data/results") { throw 'Runtime results copied' }
if (Test-Path "$fixture/candidate/backend/runtime") { throw 'Runtime directory copied' }
if (Test-Path "$fixture/candidate/backend/captures") { throw 'Capture directory copied' }
foreach ($file in $manifest.files) {
  if ((Get-FileHash -LiteralPath (Join-Path "$fixture/candidate" $file.path)).Hash.ToLowerInvariant() -ne $file.sha256) { throw 'Copied hash mismatch' }
}
& $script -Source "$fixture/source" -Destination "$fixture/canonical" -OutputDirectory "$fixture/canonical-output" -RunId fixture -SelectedPaths @('Dashboard.html', 'backend\src\new.java')
$canonicalManifest = Get-Content "$fixture/canonical-output/candidate-manifest.json" -Raw | ConvertFrom-Json
if ('backend/src/new.java' -notin $canonicalManifest.files.path -or $canonicalManifest.files.path -match '\\') { throw 'Mixed input separators were not canonicalized' }
Write-Output "PASS current dirty/untracked sources and hash verification; retained fixture: $fixture"
