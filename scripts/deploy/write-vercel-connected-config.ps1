param(
  [Parameter(Mandatory = $true)]
  [string]$BackendOrigin,

  [string]$OutputPath = "",

  [switch]$Apply,

  [switch]$AllowHttp,

  [switch]$AllowPrivateBackend,

  [switch]$SkipProbe
)

$ErrorActionPreference = "Stop"

$ProjectRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..\..")).Path
$ContractScript = Join-Path $PSScriptRoot "test-backend-origin-contract.ps1"
if ([string]::IsNullOrWhiteSpace($OutputPath)) {
  $OutputPath = if ($Apply) {
    Join-Path $ProjectRoot "vercel.json"
  } else {
    Join-Path $ProjectRoot "vercel.connected.json"
  }
}

$origin = $BackendOrigin.Trim().TrimEnd("/")
$uri = [Uri]$origin
if (-not $uri.IsAbsoluteUri) {
  throw "BackendOrigin must be an absolute URL, for example https://api.example.com"
}
if ($uri.Scheme -ne "https" -and -not $AllowHttp) {
  throw "BackendOrigin must use https for production. Pass -AllowHttp only for local/staging probes."
}
if (-not [string]::IsNullOrWhiteSpace($uri.AbsolutePath.Trim("/"))) {
  throw "BackendOrigin must be an origin root without a path. Use https://api.example.com, not https://api.example.com/api/v1."
}

if (-not $SkipProbe) {
  if (-not (Test-Path -LiteralPath $ContractScript)) {
    throw "Backend origin contract verifier is missing: $ContractScript"
  }
  $contractArgs = @{
    BackendOrigin = $origin
    TimeoutSeconds = 20
    AllowIncomplete = $true
  }
  if ($AllowHttp) { $contractArgs.AllowHttp = $true }
  if ($AllowPrivateBackend) { $contractArgs.AllowPrivateBackend = $true }
  $contract = (& $ContractScript @contractArgs | ConvertFrom-Json)
  if ($contract.t3ProductionActionPerformed) {
    throw "Backend origin contract verifier unexpectedly performed a T3 production action."
  }
  if (-not $contract.contractReady) {
    throw "Backend origin contract is not ready: healthUp=$($contract.healthUp), captchaReady=$($contract.captchaReady), failed=$($contract.failed). Deploy/fix the backend first or pass -SkipProbe only when generating a draft config."
  }
}

$config = [ordered]@{
  '$schema' = "https://openapi.vercel.sh/vercel.json"
  version = 2
  rewrites = @(
    [ordered]@{
      source = "/api/v1/:path*"
      destination = "$origin/api/v1/:path*"
    },
    [ordered]@{
      source = "/"
      destination = "/Dashboard.html"
    }
  )
  headers = @(
    [ordered]@{
      source = "/api/v1/:path*"
      headers = @(
        [ordered]@{
          key = "x-vercel-enable-rewrite-caching"
          value = "0"
        },
        [ordered]@{
          key = "Cache-Control"
          value = "no-store"
        }
      )
    },
    [ordered]@{
      source = "/(.*)"
      headers = @(
        [ordered]@{
          key = "X-Content-Type-Options"
          value = "nosniff"
        }
      )
    }
  )
}

$json = $config | ConvertTo-Json -Depth 8
$resolvedOutput = if ([System.IO.Path]::IsPathRooted($OutputPath)) {
  $OutputPath
} else {
  Join-Path $ProjectRoot $OutputPath
}
$parent = Split-Path -Parent $resolvedOutput
if ($parent -and -not (Test-Path -LiteralPath $parent)) {
  New-Item -ItemType Directory -Force -Path $parent | Out-Null
}
$json | Set-Content -LiteralPath $resolvedOutput -Encoding UTF8

[pscustomobject]@{
  outputPath = $resolvedOutput
  appliedToVercelJson = [bool]$Apply
  backendOrigin = $origin
  apiRewrite = "$origin/api/v1/:path*"
  healthProbed = -not [bool]$SkipProbe
  contractProbed = -not [bool]$SkipProbe
} | ConvertTo-Json -Depth 4
