param(
  [Parameter(Mandatory=$true)][string]$Id,
  [Parameter(Mandatory=$true)][string]$Executable,
  [string[]]$Arguments = @(),
  [Parameter(Mandatory=$true)][string]$WorkingDirectory,
  [string]$EvidenceDirectory = (Join-Path $PSScriptRoot '../../docs/release/evidence')
)
$ErrorActionPreference = 'Stop'
Get-Command $Executable -ErrorAction Stop | Out-Null
if ($Id -notmatch '^[a-z0-9-]+$') { throw 'Invalid evidence id' }
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
New-Item -ItemType Directory -Path $EvidenceDirectory -Force | Out-Null
$out = Join-Path $EvidenceDirectory "$Id.txt"
$recordPath = Join-Path $EvidenceDirectory "$Id.command.json"
$snapshotPath = Join-Path $EvidenceDirectory "$Id.source.json"
$afterPath = Join-Path $EvidenceDirectory "$Id.source-after.json"
if (@($out,$recordPath,$snapshotPath,$afterPath) | Where-Object { Test-Path -LiteralPath $_ }) { throw 'Evidence id already exists; use a fresh id' }
$sha = (& git -C $root rev-parse HEAD).Trim()
& node (Join-Path $PSScriptRoot 'capture-source.mjs') $root $snapshotPath
if ($LASTEXITCODE -ne 0) { throw 'Source identity capture failed' }
$snapshot = Get-Content -LiteralPath $snapshotPath -Raw | ConvertFrom-Json
$started = [DateTimeOffset]::UtcNow.ToString('o')
$timer = [Diagnostics.Stopwatch]::StartNew()
$code = -1
Push-Location -LiteralPath $WorkingDirectory
try {
  $PSNativeCommandUseErrorActionPreference = $false
  $global:LASTEXITCODE = $null
  try {
    & $Executable @Arguments > $out 2>&1
    $code = if ($null -eq $LASTEXITCODE) { 127 } else { $LASTEXITCODE }
  } catch {
    $_.ToString() | Add-Content -LiteralPath $out -Encoding utf8
    $code = 127
  }
} finally {
  Pop-Location
  $timer.Stop()
  $nativeExit = $code
  & node (Join-Path $PSScriptRoot 'capture-source.mjs') $root $afterPath
  $after = if ($LASTEXITCODE -eq 0) { Get-Content -LiteralPath $afterPath -Raw | ConvertFrom-Json } else { $null }
  $afterSha = (& git -C $root rev-parse HEAD).Trim()
  $unchanged = $after -and $snapshot.sha256 -eq $after.sha256 -and $sha -eq $afterSha
  if (!$unchanged -and $code -eq 0) { $code = 86 }
  $record = [ordered]@{
    schema='JKXT.CommandEvidence/v1'; id=$Id; gitSha=$sha
    sourceDigest=$snapshot.sha256; sourceSnapshot=(Split-Path -Leaf $snapshotPath)
    executable=$Executable; arguments=$Arguments; cwd=$WorkingDirectory
    startedUtc=$started; endedUtc=[DateTimeOffset]::UtcNow.ToString('o')
    elapsedMs=$timer.ElapsedMilliseconds; exitCode=$code; nativeExitCode=$nativeExit
    sourceUnchanged=[bool]$unchanged; sourceAfter=(Split-Path -Leaf $afterPath)
    output=(Split-Path -Leaf $out); outputSha256=(Get-FileHash -LiteralPath $out -Algorithm SHA256).Hash.ToLower()
  }
  $record | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $recordPath -Encoding utf8
}
Get-Content -LiteralPath $out -Tail 35
Write-Output "COMMAND_EVIDENCE=$recordPath EXIT=$code"
exit $code
