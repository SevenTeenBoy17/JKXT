#requires -Version 5.1
param(
  [Parameter(Mandatory = $true)]
  [string]$EvidenceJson,

  [switch]$AllowIncomplete
)

$ErrorActionPreference = "Stop"

function Resolve-InputPath {
  param([string]$Path)
  if ([System.IO.Path]::IsPathRooted($Path)) { return $Path }
  $root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..\..")).Path
  return (Join-Path $root $Path)
}

function Add-Check {
  param(
    [System.Collections.ArrayList]$Checks,
    [string]$Name,
    [ValidateSet("ok", "fail")]
    [string]$Status,
    [string]$Detail
  )
  [void]$Checks.Add([ordered]@{
    name = $Name
    status = $Status
    detail = $Detail
  })
}

function Find-Requirement {
  param(
    [object]$Envelope,
    [string]$Id
  )
  if (-not $Envelope -or -not $Envelope.PSObject.Properties["requirements"]) { return $null }
  return @($Envelope.requirements | Where-Object { $_.id -eq $Id } | Select-Object -First 1)[0]
}

function Test-RequirementStatus {
  param(
    [object]$Envelope,
    [string]$Id,
    [string[]]$AllowedStatuses,
    [System.Collections.ArrayList]$Checks
  )
  $req = Find-Requirement -Envelope $Envelope -Id $Id
  if (-not $req) {
    Add-Check $Checks "requirement:$Id" "fail" "missing"
    return
  }
  $status = [string]$req.status
  if ($AllowedStatuses -contains $status) {
    Add-Check $Checks "requirement:$Id" "ok" "status=$status"
  } else {
    Add-Check $Checks "requirement:$Id" "fail" "status=$status evidence=$($req.evidence)"
  }
}

$evidencePath = Resolve-InputPath $EvidenceJson
if (-not (Test-Path -LiteralPath $evidencePath -PathType Leaf)) {
  throw "Final delivery evidence JSON not found: $evidencePath"
}

$envelope = Get-Content -Raw -LiteralPath $evidencePath -Encoding UTF8 | ConvertFrom-Json
$checks = New-Object System.Collections.ArrayList

if ($envelope.PSObject.Properties["productionComplete"] -and [bool]$envelope.productionComplete) {
  Add-Check $checks "productionComplete" "ok" "true"
} else {
  Add-Check $checks "productionComplete" "fail" "productionComplete is not true"
}

if ($envelope.PSObject.Properties["t3ProductionActionPerformed"] -and [bool]$envelope.t3ProductionActionPerformed) {
  Add-Check $checks "no-unapproved-t3-action" "fail" "t3ProductionActionPerformed=true"
} else {
  Add-Check $checks "no-unapproved-t3-action" "ok" "t3ProductionActionPerformed=false"
}

foreach ($id in @(
  "public-api-health",
  "frontend-backend-connected",
  "browser-ui-smoke",
  "business-smoke",
  "release-integrity",
  "live-release-match",
  "vercel-project-link",
  "production-env",
  "approval-pack",
  "platform-capabilities",
  "no-unapproved-t3"
)) {
  Test-RequirementStatus -Envelope $envelope -Id $id -AllowedStatuses @("proven") -Checks $checks
}

Test-RequirementStatus -Envelope $envelope -Id "public-backup-readiness" -AllowedStatuses @("proven", "skipped") -Checks $checks
Test-RequirementStatus -Envelope $envelope -Id "vercel-readiness" -AllowedStatuses @("proven", "skipped") -Checks $checks

$staticPreview = Find-Requirement -Envelope $envelope -Id "static-preview-diagnostic"
if ($staticPreview) {
  $staticStatus = [string]$staticPreview.status
  if ($staticStatus -eq "skipped") {
    Add-Check $checks "requirement:static-preview-diagnostic" "ok" "status=skipped"
  } else {
    Add-Check $checks "requirement:static-preview-diagnostic" "fail" "production handoff must not rely on static-preview diagnostic status=$staticStatus"
  }
} else {
  Add-Check $checks "requirement:static-preview-diagnostic" "ok" "not present"
}

$failed = @($checks | Where-Object { $_.status -eq "fail" })
$ready = @($failed).Count -eq 0
$result = [pscustomobject][ordered]@{
  readyForFinalDelivery = $ready
  evidenceJson = $evidencePath
  appUrl = if ($envelope.PSObject.Properties["appUrl"]) { $envelope.appUrl } else { "" }
  route = if ($envelope.PSObject.Properties["route"]) { $envelope.route } else { "" }
  productionComplete = if ($envelope.PSObject.Properties["productionComplete"]) { [bool]$envelope.productionComplete } else { $false }
  t3ProductionActionPerformed = if ($envelope.PSObject.Properties["t3ProductionActionPerformed"]) { [bool]$envelope.t3ProductionActionPerformed } else { $false }
  ok = @($checks | Where-Object { $_.status -eq "ok" }).Count
  failed = @($failed).Count
  checks = $checks
}

$result | ConvertTo-Json -Depth 8
if (-not $ready -and -not $AllowIncomplete) {
  exit 1
}
