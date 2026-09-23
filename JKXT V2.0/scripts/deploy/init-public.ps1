#requires -Version 5.1
param(
  [string]$EnvFile = ".env.public",
  [switch]$NoBuild,
  [switch]$DryRun,
  [string]$ConfirmStartProduction = ""
)

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..\..")).Path
$ComposeFile = Join-Path $Root "docker-compose.public.yml"
$ExampleEnv = Join-Path $Root ".env.public.example"
$TargetEnv = if ([System.IO.Path]::IsPathRooted($EnvFile)) { $EnvFile } else { Join-Path $Root $EnvFile }
$ConfirmPhrase = "START JKXT PUBLIC PRODUCTION"

function ConvertTo-CommandLine {
  param([string[]]$CommandArgs)
  return ($CommandArgs | ForEach-Object {
    if ($_ -match '[\s"]') {
      '"' + $_.Replace('"', '\"') + '"'
    } else {
      $_
    }
  }) -join " "
}

function Invoke-DockerCommand {
  param([string[]]$DockerArgs)
  try {
    $output = & docker @DockerArgs 2>&1
    $exitCode = if ($null -eq $LASTEXITCODE) { 0 } else { $LASTEXITCODE }
    return [ordered]@{
      exitCode = $exitCode
      output = (($output | Out-String).Trim())
    }
  } catch {
    return [ordered]@{
      exitCode = 1
      output = $_.Exception.Message
    }
  } finally {
    $global:LASTEXITCODE = 0
  }
}

function Add-Check {
  param(
    [System.Collections.ArrayList]$Checks,
    [string]$Name,
    [string]$Status,
    [string]$Detail
  )
  [void]$Checks.Add([ordered]@{
    name = $Name
    status = $Status
    detail = $Detail
  })
}

function Write-SummaryAndExit {
  param(
    [string]$Status,
    [int]$ExitCode,
    [string]$Detail,
    [System.Collections.ArrayList]$Checks,
    [hashtable]$Extra = @{}
  )

  $summary = [ordered]@{
    status = $Status
    checkedAt = (Get-Date).ToString("yyyy-MM-ddTHH:mm:sszzz")
    composeFile = $ComposeFile
    envFile = $TargetEnv
    dryRun = [bool]$DryRun
    noBuild = [bool]$NoBuild
    confirmPhrase = $ConfirmPhrase
    startRequested = -not [bool]$DryRun
    t3ProductionActionPerformed = ($Status -eq "started")
    removesVolumes = $false
    preservesNamedVolumes = $true
    detail = $Detail
    checks = $Checks
  }
  foreach ($key in ($Extra.Keys | Sort-Object)) {
    $summary[$key] = $Extra[$key]
  }
  [pscustomobject]$summary | ConvertTo-Json -Depth 10
  exit $ExitCode
}

$checks = New-Object System.Collections.ArrayList
$createdEnvFromExample = $false

if (-not (Test-Path -LiteralPath $ComposeFile)) {
  Add-Check $checks "compose:file" "fail" "Missing docker-compose.public.yml."
  Write-SummaryAndExit -Status "fail" -ExitCode 1 -Detail "Missing compose file." -Checks $checks
}
Add-Check $checks "compose:file" "ok" "found $ComposeFile"

if (-not (Test-Path -LiteralPath $TargetEnv)) {
  Copy-Item -LiteralPath $ExampleEnv -Destination $TargetEnv
  $createdEnvFromExample = $true
  Add-Check $checks "env:file" "fail" "Created $TargetEnv from .env.public.example; edit domain and secrets before startup."
} else {
  Add-Check $checks "env:file" "ok" "found $TargetEnv"
}

$envText = Get-Content -Raw -LiteralPath $TargetEnv
if ($envText -match "app\.example\.jkxt\.cn") {
  Add-Check $checks "env:domain-placeholder" "fail" "PUBLIC_DOMAIN/APP_BASE_URL still use the example domain."
} else {
  Add-Check $checks "env:domain-placeholder" "ok" "public domain is not the example domain"
}
if ($envText -match "replace_with_secret_manager_value|change_me_private_password") {
  Add-Check $checks "env:secret-placeholder" "fail" "Production secrets still use placeholder values."
} else {
  Add-Check $checks "env:secret-placeholder" "ok" "production secret placeholders were not found"
}
if ($envText -match "JKXT_AUTH_CAPTCHA_DEBUG\s*=\s*true") {
  Add-Check $checks "env:captcha-debug" "fail" "JKXT_AUTH_CAPTCHA_DEBUG must be false for public production startup."
} else {
  Add-Check $checks "env:captcha-debug" "ok" "captcha debug is not true"
}
if ($envText -match "JKXT_AUTH_COOKIE_SECURE\s*=\s*true") {
  Add-Check $checks "env:cookie-secure" "ok" "secure cookies are required"
} else {
  Add-Check $checks "env:cookie-secure" "fail" "JKXT_AUTH_COOKIE_SECURE must be true for public production startup."
}

$guardFailures = @($checks | Where-Object { $_.status -eq "fail" })
if (@($guardFailures).Count -gt 0) {
  Write-SummaryAndExit -Status "fail" -ExitCode 1 -Detail "Public startup guardrails failed; no containers were started." -Checks $checks -Extra @{
    createdEnvFromExample = $createdEnvFromExample
  }
}

$configArgs = @("compose", "-f", $ComposeFile, "--env-file", $TargetEnv, "config", "--quiet")
$statusArgs = @("compose", "-f", $ComposeFile, "--env-file", $TargetEnv, "ps")
$composeArgs = @("compose", "-f", $ComposeFile, "--env-file", $TargetEnv, "up", "-d")
if (-not $NoBuild) {
  $composeArgs += "--build"
}

$config = Invoke-DockerCommand -DockerArgs $configArgs
if ([int]$config.exitCode -ne 0) {
  Add-Check $checks "compose:config" "fail" "docker compose public config validation failed"
  Write-SummaryAndExit -Status "fail" -ExitCode ([int]$config.exitCode) -Detail "docker compose public config validation failed; no containers were started." -Checks $checks -Extra @{
    createdEnvFromExample = $createdEnvFromExample
    composeConfig = $config
  }
}
Add-Check $checks "compose:config" "ok" "docker compose config exited 0"

if ($DryRun) {
  Write-SummaryAndExit -Status "dry-run" -ExitCode 0 -Detail "Public compose config validated. Dry run only; no containers were started." -Checks $checks -Extra @{
    createdEnvFromExample = $createdEnvFromExample
    plannedCommand = "docker " + (ConvertTo-CommandLine -CommandArgs $composeArgs)
    composeConfig = $config
  }
}

if ($ConfirmStartProduction -ne $ConfirmPhrase) {
  Add-Check $checks "confirm:start-production" "fail" "Missing exact production start confirmation phrase."
  Write-SummaryAndExit -Status "refused" -ExitCode 1 -Detail "Refusing public production startup. Re-run with -ConfirmStartProduction '$ConfirmPhrase'." -Checks $checks -Extra @{
    createdEnvFromExample = $createdEnvFromExample
    plannedCommand = "docker " + (ConvertTo-CommandLine -CommandArgs $composeArgs)
    composeConfig = $config
  }
}

Add-Check $checks "confirm:start-production" "ok" "exact production start confirmation phrase supplied"
$up = Invoke-DockerCommand -DockerArgs $composeArgs
$status = Invoke-DockerCommand -DockerArgs $statusArgs
if ([int]$up.exitCode -ne 0) {
  Add-Check $checks "compose:up" "fail" "docker compose public startup failed"
  Write-SummaryAndExit -Status "fail" -ExitCode ([int]$up.exitCode) -Detail "docker compose public startup failed." -Checks $checks -Extra @{
    createdEnvFromExample = $createdEnvFromExample
    startCommand = "docker " + (ConvertTo-CommandLine -CommandArgs $composeArgs)
    composeConfig = $config
    composeUp = $up
    composeStatus = $status
  }
}

Add-Check $checks "compose:up" "ok" "docker compose up exited 0"
Write-SummaryAndExit -Status "started" -ExitCode 0 -Detail "Public JKXT startup command completed; run verify-connected-remote.ps1 after health is UP." -Checks $checks -Extra @{
  createdEnvFromExample = $createdEnvFromExample
  startCommand = "docker " + (ConvertTo-CommandLine -CommandArgs $composeArgs)
  composeConfig = $config
  composeUp = $up
  composeStatus = $status
}
