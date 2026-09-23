#requires -Version 5.1
param(
  [string]$AppUrl = "https://jkxt-responsive-v2.vercel.app",
  [string]$BackendOrigin = "",
  [string]$OutputDir = ".codex-supervisor\vercel-connected-plan",
  [string]$ProposedConfigPath = "",
  [switch]$AllowHttpBackend,
  [switch]$AllowPrivateBackend,
  [switch]$SkipNetwork,
  [switch]$AllowIncomplete,
  [int]$TimeoutSeconds = 20
)

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..\..")).Path
$VercelConfig = Join-Path $Root "vercel.json"
$VercelProject = Join-Path $Root ".vercel\project.json"
$WriterScript = Join-Path $PSScriptRoot "write-vercel-connected-config.ps1"
$ContractScript = Join-Path $PSScriptRoot "test-backend-origin-contract.ps1"
$VercelRewriteDocs = "https://vercel.com/docs/routing/rewrites"

function Resolve-ProjectPath {
  param([string]$Path)
  if ([string]::IsNullOrWhiteSpace($Path)) { return "" }
  if ([System.IO.Path]::IsPathRooted($Path)) { return $Path }
  return Join-Path $Root $Path
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

function Test-PrivateHost {
  param([string]$HostName)
  if ([string]::IsNullOrWhiteSpace($HostName)) { return $true }
  $normalized = $HostName.Trim().ToLowerInvariant()
  if (@("localhost", "127.0.0.1", "::1") -contains $normalized) { return $true }
  if ($normalized.EndsWith(".local") -or $normalized.EndsWith(".internal")) { return $true }

  $ip = $null
  if ([System.Net.IPAddress]::TryParse($normalized, [ref]$ip)) {
    $bytes = $ip.GetAddressBytes()
    if ($bytes.Length -eq 4) {
      return (
        $bytes[0] -eq 10 -or
        ($bytes[0] -eq 172 -and $bytes[1] -ge 16 -and $bytes[1] -le 31) -or
        ($bytes[0] -eq 192 -and $bytes[1] -eq 168) -or
        $bytes[0] -eq 127 -or
        ($bytes[0] -eq 169 -and $bytes[1] -eq 254)
      )
    }
  }
  return $false
}

function Invoke-HealthProbe {
  param(
    [System.Collections.ArrayList]$Checks,
    [string]$Name,
    [string]$Uri
  )
  if ($SkipNetwork) {
    Add-Check $Checks $Name "skipped" "SkipNetwork was set"
    return $false
  }
  try {
    $response = Invoke-WebRequest -UseBasicParsing -Uri $Uri -TimeoutSec $TimeoutSeconds
    $json = $response.Content | ConvertFrom-Json
    if ($response.StatusCode -ge 200 -and $response.StatusCode -lt 300 -and $json.status -eq "UP") {
      Add-Check $Checks $Name "ok" "HTTP $($response.StatusCode), status=UP"
      return $true
    }
    Add-Check $Checks $Name "fail" "Expected status=UP, got HTTP $($response.StatusCode), status=$($json.status)"
    return $false
  } catch {
    $status = ""
    if ($_.Exception.Response) {
      try { $status = "HTTP " + [int]$_.Exception.Response.StatusCode + " " } catch {}
    }
    Add-Check $Checks $Name "fail" "$status$($_.Exception.Message)"
    return $false
  }
}

function Invoke-CurrentAppHealthProbe {
  param(
    [System.Collections.ArrayList]$Checks,
    [string]$Uri
  )
  if ($SkipNetwork) {
    Add-Check $Checks "current:api-health" "skipped" "SkipNetwork was set"
    return $false
  }
  try {
    $response = Invoke-WebRequest -UseBasicParsing -Uri $Uri -TimeoutSec $TimeoutSeconds
    $json = $response.Content | ConvertFrom-Json
    if ($response.StatusCode -ge 200 -and $response.StatusCode -lt 300 -and $json.status -eq "UP") {
      Add-Check $Checks "current:api-health" "ok" "HTTP $($response.StatusCode), status=UP"
      return $true
    }
    Add-Check $Checks "current:api-health" "skipped" "Current app is not connected yet; proposed rewrite can fix this after a healthy BackendOrigin is provided"
    return $false
  } catch {
    $status = ""
    if ($_.Exception.Response) {
      try { $status = "HTTP " + [int]$_.Exception.Response.StatusCode + " " } catch {}
    }
    Add-Check $Checks "current:api-health" "skipped" "$status$($_.Exception.Message). Current app connectivity is observational and does not block proposed config generation."
    return $false
  }
}

function Write-MarkdownPlan {
  param(
    [object]$Plan,
    [string]$Path
  )
  $failedChecks = @($Plan.checks | Where-Object { $_.status -eq "fail" })
  $lines = New-Object System.Collections.Generic.List[string]
  $lines.Add("# JKXT Vercel Backend Rewrite Plan")
  $lines.Add("")
  $lines.Add("- Checked: $($Plan.checkedAt)")
  $lines.Add("- App URL: $($Plan.appUrl)")
  $lines.Add("- Backend origin: $($Plan.backendOrigin)")
  $lines.Add("- Current app connected: $($Plan.currentAppConnected)")
  $lines.Add("- Ready for config generation: $($Plan.readyForConfigGeneration)")
  $lines.Add("- Ready for production apply: $($Plan.readyForProductionApply)")
  $lines.Add("- Proposed config: $($Plan.proposedConfigPath)")
  $lines.Add("- Vercel rewrite docs: $($Plan.vercelRewriteDocs)")
  $lines.Add("")
  $lines.Add("## Failed Checks")
  if (@($failedChecks).Count -eq 0) {
    $lines.Add("")
    $lines.Add("- None")
  } else {
    foreach ($check in $failedChecks) {
      $lines.Add("- $($check.name) - $($check.detail)")
    }
  }
  $lines.Add("")
  $lines.Add("## Commands")
  $lines.Add("")
  $tick = [char]0x60
  $lines.Add("- Generate proposed config only: " + $tick + $Plan.generateCommand + $tick)
  $lines.Add("- Apply local vercel.json after approval: " + $tick + $Plan.applyLocalConfigCommand + $tick)
  $lines.Add("- Production deploy remains T3/outward and is intentionally not run by this plan.")
  $lines.Add("")
  $lines.Add("## Next Actions")
  foreach ($action in @($Plan.nextActions)) {
    $lines.Add("- $action")
  }
  Set-Content -LiteralPath $Path -Value $lines -Encoding UTF8
}

$checks = New-Object System.Collections.ArrayList
$outputRoot = Resolve-ProjectPath $OutputDir
New-Item -ItemType Directory -Force -Path $outputRoot | Out-Null
$stamp = (Get-Date).ToString("yyyyMMdd-HHmmss")
if ([string]::IsNullOrWhiteSpace($ProposedConfigPath)) {
  $ProposedConfigPath = Join-Path $outputRoot "vercel.connected.$stamp.json"
} else {
  $ProposedConfigPath = Resolve-ProjectPath $ProposedConfigPath
}
$jsonPlanPath = Join-Path $outputRoot "vercel-connected-plan-$stamp.json"
$markdownPlanPath = Join-Path $outputRoot "vercel-connected-plan-$stamp.md"

$normalizedAppUrl = $AppUrl.Trim().TrimEnd("/")
$normalizedBackendOrigin = $BackendOrigin.Trim().TrimEnd("/")
$appUri = $null
$backendUri = $null
$backendIsPrivate = $true
$backendUsesHttps = $false
$backendHealthUp = $false
$backendCaptchaReady = $false
$backendContractReady = $false
$currentAppConnected = $false
$proposedConfigGenerated = $false

try {
  $appUri = [Uri]$normalizedAppUrl
  if ($appUri.IsAbsoluteUri -and @("http", "https") -contains $appUri.Scheme) {
    Add-Check $checks "target:app-url" "ok" $normalizedAppUrl
  } else {
    Add-Check $checks "target:app-url" "fail" "AppUrl must be absolute http(s)"
  }
} catch {
  Add-Check $checks "target:app-url" "fail" $_.Exception.Message
}

if ($appUri) {
  $currentAppConnected = Invoke-CurrentAppHealthProbe $checks "$normalizedAppUrl/api/v1/health"
}

if (Test-Path -LiteralPath $VercelProject) {
  try {
    $project = Get-Content -Raw -LiteralPath $VercelProject | ConvertFrom-Json
    Add-Check $checks "vercel:project" "ok" "projectId=$($project.projectId), orgId=$($project.orgId), name=$($project.projectName)"
  } catch {
    Add-Check $checks "vercel:project" "fail" $_.Exception.Message
  }
} else {
  Add-Check $checks "vercel:project" "fail" ".vercel/project.json is missing"
}

if (Test-Path -LiteralPath $VercelConfig) {
  try {
    $currentConfig = Get-Content -Raw -LiteralPath $VercelConfig | ConvertFrom-Json
    $rewriteSources = @($currentConfig.rewrites | ForEach-Object { [string]$_.source })
    if ($rewriteSources | Where-Object { $_ -like "/api/v1*" -or $_ -like "/api/*" }) {
      Add-Check $checks "vercel:current-api-rewrite" "ok" "current vercel.json already contains an API rewrite"
    } else {
      Add-Check $checks "vercel:current-api-rewrite" "skipped" "current vercel.json has no API rewrite; proposed config will add one"
    }
  } catch {
    Add-Check $checks "vercel:current-config" "fail" $_.Exception.Message
  }
} else {
  Add-Check $checks "vercel:current-config" "fail" "vercel.json is missing"
}

if ([string]::IsNullOrWhiteSpace($normalizedBackendOrigin)) {
  Add-Check $checks "backend:origin" "fail" "BackendOrigin is required before generating an actionable Vercel rewrite"
} else {
  try {
    $backendUri = [Uri]$normalizedBackendOrigin
    if (-not $backendUri.IsAbsoluteUri -or @("http", "https") -notcontains $backendUri.Scheme) {
      Add-Check $checks "backend:url" "fail" "BackendOrigin must be absolute http(s)"
    } elseif ($backendUri.AbsolutePath.Trim("/")) {
      Add-Check $checks "backend:path" "fail" "BackendOrigin must be an origin root without /api/v1 path"
    } else {
      Add-Check $checks "backend:url" "ok" $normalizedBackendOrigin
      $backendUsesHttps = $backendUri.Scheme -eq "https"
      if ($backendUsesHttps) {
        Add-Check $checks "backend:https" "ok" "BackendOrigin uses https"
      } elseif ($AllowHttpBackend) {
        Add-Check $checks "backend:https" "skipped" "AllowHttpBackend was set for local/staging proof only"
      } else {
        Add-Check $checks "backend:https" "fail" "BackendOrigin must use https for production"
      }

      $backendIsPrivate = Test-PrivateHost -HostName $backendUri.Host
      if (-not $backendIsPrivate) {
        Add-Check $checks "backend:public-origin" "ok" "BackendOrigin host appears public"
      } elseif ($AllowPrivateBackend) {
        Add-Check $checks "backend:public-origin" "skipped" "AllowPrivateBackend was set for local/staging proof only"
      } else {
        Add-Check $checks "backend:public-origin" "fail" "BackendOrigin host is local/private and cannot be reached by Vercel production"
      }

      if (Test-Path -LiteralPath $ContractScript) {
        $contractArgs = @{
          BackendOrigin = $normalizedBackendOrigin
          TimeoutSeconds = $TimeoutSeconds
          AllowIncomplete = $true
        }
        if ($AllowHttpBackend) { $contractArgs.AllowHttp = $true }
        if ($AllowPrivateBackend) { $contractArgs.AllowPrivateBackend = $true }
        $contract = (& $ContractScript @contractArgs | ConvertFrom-Json)
        foreach ($contractCheck in @($contract.checks)) {
          Add-Check $checks "contract:$($contractCheck.name)" ([string]$contractCheck.status) ([string]$contractCheck.detail)
        }
        if ($contract.t3ProductionActionPerformed) {
          Add-Check $checks "contract:no-t3" "fail" "contract verifier reported a T3 production action"
        }
        $backendHealthUp = [bool]$contract.healthUp
        $backendCaptchaReady = [bool]$contract.captchaReady
        $backendContractReady = [bool]$contract.contractReady
      } else {
        Add-Check $checks "contract:script" "fail" "missing $ContractScript"
      }
    }
  } catch {
    Add-Check $checks "backend:url" "fail" $_.Exception.Message
  }
}

$blockingFailuresBeforeGeneration = @($checks | Where-Object { $_.status -eq "fail" })
if (@($blockingFailuresBeforeGeneration).Count -eq 0 -and $backendContractReady) {
  try {
    $writerArgs = @{
      BackendOrigin = $normalizedBackendOrigin
      OutputPath = $ProposedConfigPath
      SkipProbe = $true
    }
    if ($AllowHttpBackend) {
      $writerArgs.AllowHttp = $true
    }
    if ($AllowPrivateBackend) {
      $writerArgs.AllowPrivateBackend = $true
    }
    & $WriterScript @writerArgs | Out-Null
    if ($LASTEXITCODE -eq 0 -or $null -eq $LASTEXITCODE) {
      Add-Check $checks "generator:proposed-config" "ok" "wrote $ProposedConfigPath"
      $proposedConfigGenerated = $true
    } else {
      Add-Check $checks "generator:proposed-config" "fail" "write-vercel-connected-config.ps1 exited $LASTEXITCODE"
    }
  } catch {
    Add-Check $checks "generator:proposed-config" "fail" $_.Exception.Message
  } finally {
    $global:LASTEXITCODE = 0
  }
} else {
  Add-Check $checks "generator:proposed-config" "skipped" "blocked until BackendOrigin contract is valid, /api/v1/health is UP, and /api/v1/auth/captcha returns a real captchaId"
}

$failures = @($checks | Where-Object { $_.status -eq "fail" })
$readyForConfigGeneration = @($failures).Count -eq 0 -and $proposedConfigGenerated
$readyForProductionApply = $readyForConfigGeneration -and $backendUsesHttps -and (-not $backendIsPrivate)
$nextActions = New-Object System.Collections.Generic.List[string]
if ([string]::IsNullOrWhiteSpace($normalizedBackendOrigin)) {
  $nextActions.Add("Provide a public HTTPS BackendOrigin whose /api/v1/health returns status=UP.")
}
if ($backendUri -and -not $backendUsesHttps) {
  $nextActions.Add("Use HTTPS for the BackendOrigin before production apply.")
}
if ($backendUri -and $backendIsPrivate) {
  $nextActions.Add("Move the backend behind a public DNS/TLS origin before Vercel production apply.")
}
if ($backendUri -and -not $backendHealthUp) {
  $nextActions.Add("Fix BackendOrigin /api/v1/health before generating the Vercel rewrite config.")
}
if ($backendUri -and -not $backendCaptchaReady) {
  $nextActions.Add("Fix BackendOrigin /api/v1/auth/captcha so the public frontend can use a real backend captcha before Vercel apply.")
}
if ($readyForProductionApply) {
  $nextActions.Add("After explicit approval, apply the proposed config to vercel.json, deploy to Vercel production, and run connected/browser/business smoke gates.")
} else {
  $nextActions.Add("Do not deploy Vercel production from this plan yet; production apply remains false.")
}
$uniqueNextActions = @($nextActions | Select-Object -Unique)

$generateCommand = "powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy\plan-vercel-backend-rewrite.ps1 -AppUrl $normalizedAppUrl -BackendOrigin <HTTPS_BACKEND_ORIGIN>"
$applyLocalConfigCommand = "powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy\write-vercel-connected-config.ps1 -BackendOrigin <HTTPS_BACKEND_ORIGIN> -Apply"

$plan = [pscustomobject][ordered]@{
  appUrl = $normalizedAppUrl
  backendOrigin = $normalizedBackendOrigin
  checkedAt = (Get-Date).ToString("yyyy-MM-ddTHH:mm:sszzz")
  currentAppConnected = $currentAppConnected
  backendHealthUp = $backendHealthUp
  backendCaptchaReady = $backendCaptchaReady
  backendContractReady = $backendContractReady
  backendUsesHttps = $backendUsesHttps
  backendIsPrivate = $backendIsPrivate
  readyForConfigGeneration = $readyForConfigGeneration
  readyForProductionApply = $readyForProductionApply
  t3ProductionDeployPerformed = $false
  proposedConfigPath = if ($proposedConfigGenerated) { $ProposedConfigPath } else { "" }
  reportJson = $jsonPlanPath
  reportMarkdown = $markdownPlanPath
  vercelRewriteDocs = $VercelRewriteDocs
  generateCommand = $generateCommand
  applyLocalConfigCommand = $applyLocalConfigCommand
  nextActions = $uniqueNextActions
  ok = @($checks | Where-Object { $_.status -eq "ok" }).Count
  skipped = @($checks | Where-Object { $_.status -eq "skipped" }).Count
  failed = @($failures).Count
  checks = $checks
}

$plan | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $jsonPlanPath -Encoding UTF8
Write-MarkdownPlan -Plan $plan -Path $markdownPlanPath
$plan | ConvertTo-Json -Depth 8

if (@($failures).Count -gt 0 -and -not $AllowIncomplete) {
  exit 1
}
