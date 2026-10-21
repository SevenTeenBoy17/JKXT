#requires -Version 5.1
param(
  [string]$AppUrl = "https://jkxt-responsive-v2.vercel.app",
  [string]$BackendOrigin = "",
  [string]$EnvFile = ".env.public",
  [string]$ReleaseZipPath = "",
  [string]$ReleaseManifestPath = "",
  [string]$Username = "jwc.demo",
  [string]$Password = "Demo@2025",
  [switch]$AllowHttpBackend,
  [switch]$SkipNetwork,
  [switch]$SkipDocker,
  [switch]$SkipCaddyValidate,
  [switch]$SkipLogin,
  [switch]$PostStartVerify,
  [switch]$BrowserSmoke,
  [switch]$BusinessSmoke,
  [switch]$AllowRemoteDataWrites,
  [switch]$RequirePublicEnvForAudit,
  [switch]$StartProduction,
  [switch]$NoBuild,
  [string]$ConfirmStartProduction = "",
  [int]$TimeoutSeconds = 60
)

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..\..")).Path
$ConfirmPhrase = "START JKXT PUBLIC PRODUCTION"

function Resolve-ProjectPath {
  param([string]$Path)
  if ([string]::IsNullOrWhiteSpace($Path)) {
    return ""
  }
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

function ConvertTo-CommandLineArgument {
  param([string]$Argument)
  if ($Argument -notmatch '[\s"]') {
    return $Argument
  }
  return '"' + $Argument.Replace('"', '\"') + '"'
}

function ConvertTo-ArgumentList {
  param([hashtable]$Parameters)
  $args = @()
  foreach ($key in ($Parameters.Keys | Sort-Object)) {
    $value = $Parameters[$key]
    if ($null -eq $value) {
      continue
    }
    if ($value -is [bool]) {
      if ($value) {
        $args += "-$key"
      }
      continue
    }
    if ($value -is [System.Management.Automation.SwitchParameter]) {
      if ($value.IsPresent) {
        $args += "-$key"
      }
      continue
    }
    if ([string]::IsNullOrWhiteSpace([string]$value)) {
      continue
    }
    $args += "-$key"
    $args += [string]$value
  }
  return $args
}

function Invoke-CapturedScript {
  param(
    [string]$Name,
    [string]$ScriptPath,
    [hashtable]$Parameters
  )

  $psExe = (Get-Process -Id $PID).Path
  $arguments = @("-NoProfile")
  if ($PSVersionTable.PSEdition -eq "Desktop" -or $IsWindows) {
    $arguments += @("-ExecutionPolicy", "Bypass")
  }
  $arguments += @("-File", $ScriptPath)
  $arguments += ConvertTo-ArgumentList -Parameters $Parameters

  $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
  $startInfo.FileName = $psExe
  $startInfo.UseShellExecute = $false
  $startInfo.RedirectStandardOutput = $true
  $startInfo.RedirectStandardError = $true
  $startInfo.CreateNoWindow = $true

  $argumentListProperty = $startInfo.GetType().GetProperty("ArgumentList")
  if ($argumentListProperty) {
    foreach ($arg in $arguments) {
      [void]$startInfo.ArgumentList.Add($arg)
    }
  } else {
    $startInfo.Arguments = ($arguments | ForEach-Object { ConvertTo-CommandLineArgument $_ }) -join " "
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
  $output = ($combined -join [Environment]::NewLine).Trim()
  $json = $null
  if ($output.StartsWith("{") -or $output.StartsWith("[")) {
    try {
      $json = $output | ConvertFrom-Json
    } catch {}
  }
  $status = if ($process.ExitCode -eq 0) { "ok" } else { "fail" }
  if ($json -and $json.PSObject.Properties["failed"] -and [int]$json.failed -gt 0) {
    $status = "fail"
  }

  return [ordered]@{
    name = $Name
    status = $status
    exitCode = $process.ExitCode
    script = $ScriptPath
    output = if ($json) { $json } else { $output }
  }
}

function Add-SkippedStep {
  param([string]$Name, [string]$Detail)
  return [ordered]@{
    name = $Name
    status = "skipped"
    exitCode = $null
    script = $null
    output = $Detail
  }
}

if ([string]::IsNullOrWhiteSpace($ReleaseZipPath)) {
  $ReleaseZipPath = Get-LatestReleaseZip
}
$ReleaseZipPath = Resolve-ProjectPath $ReleaseZipPath
if ([string]::IsNullOrWhiteSpace($ReleaseManifestPath) -and -not [string]::IsNullOrWhiteSpace($ReleaseZipPath)) {
  $manifestDir = Join-Path ([System.IO.Path]::GetDirectoryName($ReleaseZipPath)) ([System.IO.Path]::GetFileNameWithoutExtension($ReleaseZipPath))
  $ReleaseManifestPath = Join-Path $manifestDir "release-manifest.json"
} else {
  $ReleaseManifestPath = Resolve-ProjectPath $ReleaseManifestPath
}
$EnvFilePath = Resolve-ProjectPath $EnvFile

$scripts = @{
  discover = Join-Path $PSScriptRoot "discover-production-target.ps1"
  selfTest = Join-Path $PSScriptRoot "test-public-release.ps1"
  preflight = Join-Path $PSScriptRoot "preflight-public-host.ps1"
  init = Join-Path $PSScriptRoot "init-public.ps1"
  backup = Join-Path $PSScriptRoot "backup-public.ps1"
  connected = Join-Path $PSScriptRoot "verify-connected-remote.ps1"
  browser = Join-Path $PSScriptRoot "verify-remote-browser-ui.ps1"
  audit = Join-Path $PSScriptRoot "audit-production-readiness.ps1"
  business = Join-Path $PSScriptRoot "verify-remote-business-smoke.ps1"
}

foreach ($entry in $scripts.GetEnumerator()) {
  if (-not (Test-Path -LiteralPath $entry.Value)) {
    throw "Missing required launch gate script: $($entry.Value)"
  }
}

$steps = New-Object System.Collections.ArrayList

[void]$steps.Add((Invoke-CapturedScript -Name "target:discover" -ScriptPath $scripts.discover -Parameters @{
  AppUrl = $AppUrl
  BackendOrigin = $BackendOrigin
  PublicEnvPath = $EnvFilePath
  ReleaseZipPath = $ReleaseZipPath
  ReleaseManifestPath = $ReleaseManifestPath
  AllowHttpBackend = [bool]$AllowHttpBackend
  SkipNetwork = [bool]$SkipNetwork
  AllowIncomplete = $true
  TimeoutSeconds = $TimeoutSeconds
}))

[void]$steps.Add((Invoke-CapturedScript -Name "release:self-test" -ScriptPath $scripts.selfTest -Parameters @{
  ReleaseZipPath = $ReleaseZipPath
  EnvFile = $EnvFilePath
  SkipDocker = [bool]$SkipDocker
  SkipNetwork = [bool]$SkipNetwork
  SkipCaddyValidate = [bool]$SkipCaddyValidate
}))

[void]$steps.Add((Invoke-CapturedScript -Name "host:preflight" -ScriptPath $scripts.preflight -Parameters @{
  EnvFile = $EnvFilePath
  ManifestPath = $ReleaseManifestPath
  RequireManifest = $true
  VerifyManifestHashes = $true
  SkipDocker = [bool]$SkipDocker
  SkipNetwork = [bool]$SkipNetwork
  SkipCaddyValidate = [bool]$SkipCaddyValidate
}))

[void]$steps.Add((Invoke-CapturedScript -Name "compose:dry-run" -ScriptPath $scripts.init -Parameters @{
  EnvFile = $EnvFile
  DryRun = $true
  NoBuild = [bool]$NoBuild
}))

[void]$steps.Add((Invoke-CapturedScript -Name "backup:dry-run" -ScriptPath $scripts.backup -Parameters @{
  EnvFile = $EnvFile
  DryRun = $true
}))

if ($StartProduction) {
  if ($ConfirmStartProduction -ne $ConfirmPhrase) {
    [void]$steps.Add([ordered]@{
      name = "compose:start"
      status = "fail"
      exitCode = 1
      script = $scripts.init
      output = "Refusing production startup. Re-run with -ConfirmStartProduction '$ConfirmPhrase'."
    })
  } else {
    [void]$steps.Add((Invoke-CapturedScript -Name "compose:start" -ScriptPath $scripts.init -Parameters @{
      EnvFile = $EnvFile
      NoBuild = [bool]$NoBuild
      ConfirmStartProduction = $ConfirmStartProduction
    }))
  }
} else {
  [void]$steps.Add((Add-SkippedStep -Name "compose:start" -Detail "StartProduction was not set; no containers were started."))
}

if ($PostStartVerify) {
  [void]$steps.Add((Invoke-CapturedScript -Name "remote:connected" -ScriptPath $scripts.connected -Parameters @{
    AppUrl = $AppUrl
    Username = $Username
    Password = $Password
    SkipLogin = [bool]$SkipLogin
    TimeoutSeconds = $TimeoutSeconds
  }))

  if ($BrowserSmoke) {
    [void]$steps.Add((Invoke-CapturedScript -Name "remote:browser-ui" -ScriptPath $scripts.browser -Parameters @{
      AppUrl = $AppUrl
      Username = $Username
      Password = $Password
      SkipLogin = [bool]$SkipLogin
      TimeoutSeconds = $TimeoutSeconds
    }))
  } else {
    [void]$steps.Add((Add-SkippedStep -Name "remote:browser-ui" -Detail "BrowserSmoke was not set."))
  }

  [void]$steps.Add((Invoke-CapturedScript -Name "remote:audit" -ScriptPath $scripts.audit -Parameters @{
    AppUrl = $AppUrl
    ReleaseZipPath = $ReleaseZipPath
    ReleaseManifestPath = $ReleaseManifestPath
    Username = $Username
    Password = $Password
    SkipLogin = [bool]$SkipLogin
    BrowserSmoke = [bool]$BrowserSmoke
    BusinessSmoke = [bool]$BusinessSmoke
    AllowRemoteDataWrites = [bool]$AllowRemoteDataWrites
    RequirePublicEnv = [bool]$RequirePublicEnvForAudit
    AllowIncomplete = $true
    TimeoutSeconds = $TimeoutSeconds
  }))

  if ($BusinessSmoke) {
    [void]$steps.Add((Invoke-CapturedScript -Name "remote:business-smoke" -ScriptPath $scripts.business -Parameters @{
      AppUrl = $AppUrl
      Username = $Username
      Password = $Password
      AllowRemoteDataWrites = [bool]$AllowRemoteDataWrites
      TimeoutSeconds = $TimeoutSeconds
    }))
  } else {
    [void]$steps.Add((Add-SkippedStep -Name "remote:business-smoke" -Detail "BusinessSmoke was not set."))
  }
} else {
  [void]$steps.Add((Add-SkippedStep -Name "remote:post-start-verify" -Detail "PostStartVerify was not set."))
}

$failed = @($steps | Where-Object { $_.status -eq "fail" })
$summary = [ordered]@{
  appUrl = $AppUrl
  backendOrigin = $BackendOrigin
  envFile = $EnvFilePath
  releaseZip = $ReleaseZipPath
  releaseManifest = $ReleaseManifestPath
  checkedAt = (Get-Date).ToString("yyyy-MM-ddTHH:mm:sszzz")
  startProductionRequested = [bool]$StartProduction
  postStartVerifyRequested = [bool]$PostStartVerify
  ok = @($steps | Where-Object { $_.status -eq "ok" }).Count
  skipped = @($steps | Where-Object { $_.status -eq "skipped" }).Count
  failed = @($failed).Count
  ready = (@($failed).Count -eq 0)
  steps = $steps
}

[pscustomobject]$summary | ConvertTo-Json -Depth 12

if (@($failed).Count -gt 0) {
  exit 1
}
