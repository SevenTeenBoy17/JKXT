#requires -Version 5.1
param(
  [string]$ReleaseZipPath = "",
  [string]$EnvFile = ".codex-supervisor\public-dryrun.env",
  [string]$OutputDir = ".codex-supervisor\release-selftest",
  [switch]$SkipDocker,
  [switch]$SkipNetwork,
  [switch]$SkipCaddyValidate,
  [switch]$KeepExtracted
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"
$Root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..\..")).Path

function Resolve-ProjectPath {
  param([string]$Path)
  if ([System.IO.Path]::IsPathRooted($Path)) {
    return $Path
  }
  return Join-Path $Root $Path
}

function Get-LatestReleaseZip {
  $releaseDir = Join-Path $Root ".codex-supervisor\releases"
  if (-not (Test-Path -LiteralPath $releaseDir)) {
    return ""
  }
  $latest = Get-ChildItem -LiteralPath $releaseDir -Filter "jkxt-public-*.zip" -File |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
  if ($latest) {
    return $latest.FullName
  }
  return ""
}

function Assert-UnderPath {
  param(
    [string]$Candidate,
    [string]$Parent
  )
  $candidateFull = [System.IO.Path]::GetFullPath($Candidate)
  $parentFull = [System.IO.Path]::GetFullPath($Parent).TrimEnd('\', '/')
  if (-not $candidateFull.StartsWith($parentFull, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing to operate outside expected directory: $candidateFull"
  }
}

function ConvertTo-PowerShellLiteral {
  param([string]$Value)
  return "'" + $Value.Replace("'", "''") + "'"
}

function ConvertTo-CommandLineArgument {
  param([string]$Argument)
  if ($Argument -notmatch '[\s"]') {
    return $Argument
  }
  return '"' + $Argument.Replace('"', '\"') + '"'
}

function Invoke-CapturedProcess {
  param(
    [string]$FilePath,
    [string[]]$Arguments
  )

  $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
  $startInfo.FileName = $FilePath
  $startInfo.UseShellExecute = $false
  $startInfo.RedirectStandardOutput = $true
  $startInfo.RedirectStandardError = $true
  $startInfo.CreateNoWindow = $true

  $argumentListProperty = $startInfo.GetType().GetProperty("ArgumentList")
  if ($argumentListProperty) {
    foreach ($arg in $Arguments) {
      [void]$startInfo.ArgumentList.Add($arg)
    }
  } else {
    $startInfo.Arguments = ($Arguments | ForEach-Object { ConvertTo-CommandLineArgument $_ }) -join " "
  }

  $process = [System.Diagnostics.Process]::Start($startInfo)
  $stdout = $process.StandardOutput.ReadToEnd()
  $stderr = $process.StandardError.ReadToEnd()
  $process.WaitForExit()

  $combined = @()
  if (-not [string]::IsNullOrWhiteSpace($stdout)) {
    $combined += $stdout.Trim()
  }
  if (-not [string]::IsNullOrWhiteSpace($stderr)) {
    $combined += $stderr.Trim()
  }

  return [pscustomobject]@{
    ExitCode = $process.ExitCode
    Output = ($combined -join [Environment]::NewLine)
  }
}

if ([string]::IsNullOrWhiteSpace($ReleaseZipPath)) {
  $zipPath = Get-LatestReleaseZip
} else {
  $zipPath = Resolve-ProjectPath $ReleaseZipPath
}
if ([string]::IsNullOrWhiteSpace($zipPath) -or -not (Test-Path -LiteralPath $zipPath)) {
  throw "Release zip not found. Pass -ReleaseZipPath or build a public release first."
}

$envPath = Resolve-ProjectPath $EnvFile
if (-not (Test-Path -LiteralPath $envPath)) {
  throw "Env file not found for self-test: $envPath"
}

$outRoot = Resolve-ProjectPath $OutputDir
New-Item -ItemType Directory -Force -Path $outRoot | Out-Null
$extractName = [System.IO.Path]::GetFileNameWithoutExtension($zipPath)
if (-not $KeepExtracted) {
  $extractName = "$extractName-$PID-$(([Guid]::NewGuid().ToString('N')).Substring(0, 8))"
}
$extractDir = Join-Path $outRoot $extractName
Assert-UnderPath -Candidate $extractDir -Parent $outRoot
if (Test-Path -LiteralPath $extractDir) {
  Remove-Item -LiteralPath $extractDir -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $extractDir | Out-Null

Add-Type -AssemblyName System.IO.Compression.FileSystem
[System.IO.Compression.ZipFile]::ExtractToDirectory($zipPath, $extractDir)

$manifestPath = Join-Path $extractDir "release-manifest.json"
$preflight = Join-Path $extractDir "scripts\deploy\preflight-public-host.ps1"

if (-not (Test-Path -LiteralPath $manifestPath)) {
  throw "Extracted release is missing release-manifest.json"
}
if (-not (Test-Path -LiteralPath $preflight)) {
  throw "Extracted release is missing scripts/deploy/preflight-public-host.ps1"
}

$psExe = (Get-Process -Id $PID).Path
$childParts = @(
  "`$ProgressPreference = 'SilentlyContinue';",
  "& $(ConvertTo-PowerShellLiteral $preflight)",
  "-EnvFile $(ConvertTo-PowerShellLiteral $envPath)",
  "-ManifestPath $(ConvertTo-PowerShellLiteral $manifestPath)",
  "-RequireManifest",
  "-VerifyManifestHashes"
)
if ($SkipDocker) {
  $childParts += "-SkipDocker"
}
if ($SkipNetwork) {
  $childParts += "-SkipNetwork"
}
if ($SkipCaddyValidate) {
  $childParts += "-SkipCaddyValidate"
}
$childCommand = ($childParts -join " ") + "`nexit `$LASTEXITCODE"
$encodedCommand = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($childCommand))

$processArgs = @("-NoProfile")
if ($PSVersionTable.PSEdition -eq "Desktop" -or $IsWindows) {
  $processArgs += @("-ExecutionPolicy", "Bypass")
}
$processArgs += @("-EncodedCommand", $encodedCommand)

$preflightResult = Invoke-CapturedProcess -FilePath $psExe -Arguments $processArgs
$preflightOutput = $preflightResult.Output
$preflightExit = $preflightResult.ExitCode

$summary = [ordered]@{
  releaseZip = $zipPath
  extractDir = $extractDir
  manifestPath = $manifestPath
  preflightExit = $preflightExit
  checkedAt = (Get-Date).ToString("yyyy-MM-ddTHH:mm:sszzz")
  keptExtracted = [bool]$KeepExtracted
}

if ($preflightExit -ne 0) {
  $summary.preflightOutput = ($preflightOutput | Out-String).Trim()
  [pscustomobject]$summary | ConvertTo-Json -Depth 5
  exit $preflightExit
}

if (-not $KeepExtracted) {
  Remove-Item -LiteralPath $extractDir -Recurse -Force
  $summary.keptExtracted = $false
  $summary.extractDir = $null
}

[pscustomobject]$summary | ConvertTo-Json -Depth 5
