#requires -Version 5.1
param(
  [string]$OutputDir = "",
  [string]$ReleaseId = "",
  [switch]$NoZip,
  # Uploads may contain real school files. They are excluded unless explicitly requested.
  [switch]$IncludeUploads,
  # Retained for compatibility; uploads are already excluded by default.
  [switch]$SkipUploads
)

$ErrorActionPreference = "Stop"

$Root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..\..")).Path
if ([string]::IsNullOrWhiteSpace($ReleaseId)) {
  $ReleaseId = Get-Date -Format "yyyyMMdd-HHmmss"
}

if ($IncludeUploads -and $SkipUploads) {
  throw "Use either -IncludeUploads or -SkipUploads, not both."
}
if ([string]::IsNullOrWhiteSpace($OutputDir)) {
  $OutputDir = Join-Path ([System.IO.Path]::GetTempPath()) "jkxt-releases"
}
if ([System.IO.Path]::IsPathRooted($OutputDir)) {
  $OutRoot = [System.IO.Path]::GetFullPath($OutputDir)
} else {
  $OutRoot = [System.IO.Path]::GetFullPath((Join-Path (Get-Location).Path $OutputDir))
}

# Release output must never land inside the source tree (or its Git work tree):
# it would pollute the source digest and could be re-packaged recursively.
$protectedRoots = @($Root)
$gitTop = $null
try { $gitTop = (& git -C $Root rev-parse --show-toplevel 2>$null) } catch { $gitTop = $null }
if ($LASTEXITCODE -eq 0 -and -not [string]::IsNullOrWhiteSpace($gitTop)) {
  $protectedRoots += [System.IO.Path]::GetFullPath(($gitTop | Select-Object -First 1).Trim())
}
foreach ($protected in $protectedRoots) {
  $p = $protected.TrimEnd('\', '/') + [System.IO.Path]::DirectorySeparatorChar
  $o = $OutRoot.TrimEnd('\', '/') + [System.IO.Path]::DirectorySeparatorChar
  if ($o.StartsWith($p, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "OutputDir must be outside the source tree: $OutRoot"
  }
}

$ReleaseName = "jkxt-public-$ReleaseId"
$Stage = [System.IO.Path]::GetFullPath((Join-Path $OutRoot $ReleaseName))
$ManifestPath = Join-Path $Stage "release-manifest.json"
$ZipPath = Join-Path $OutRoot "$ReleaseName.zip"

if (Test-Path -LiteralPath $Stage) {
  throw "Release staging path already exists: $Stage"
}
if ((-not $NoZip) -and (Test-Path -LiteralPath $ZipPath)) {
  throw "Release zip already exists: $ZipPath"
}

New-Item -ItemType Directory -Force -Path $Stage | Out-Null

function Resolve-SourcePath {
  param([string]$RelativePath)
  $source = Join-Path $Root $RelativePath
  if (-not (Test-Path -LiteralPath $source)) {
    throw "Required release path is missing: $RelativePath"
  }
  return $source
}

function Copy-ReleaseFile {
  param([string]$RelativePath)
  $source = Resolve-SourcePath $RelativePath
  $target = Join-Path $Stage $RelativePath
  $targetDir = Split-Path -Parent $target
  New-Item -ItemType Directory -Force -Path $targetDir | Out-Null
  Copy-Item -LiteralPath $source -Destination $target -Force
}

function Copy-ReleaseDirectory {
  param([string]$RelativePath)
  $source = Resolve-SourcePath $RelativePath
  $target = Join-Path $Stage $RelativePath
  New-Item -ItemType Directory -Force -Path (Split-Path -Parent $target) | Out-Null
  Copy-Item -LiteralPath $source -Destination $target -Recurse -Force
}

$files = @(
  ".gitignore",
  ".dockerignore",
  ".env.public.example",
  "Dashboard.html",
  "package.json",
  "package-lock.json",
  "tailwind.config.cjs",
  "scripts\frontend-build.mjs",
  "vercel.json",
  "docker-compose.public.yml",
  "docs\DEPLOYMENT.md",
  "docs\CONNECTED-PRODUCTION-DEPLOYMENT.md",
  "docs\DEV-RUNBOOK.md",
  "docs\UAT-CHECKLIST.md",
  "backend\.dockerignore",
  "backend\Dockerfile",
  "backend\mvnw",
  "backend\mvnw.cmd",
  "backend\pom.xml",
  "backend\README.md"
)

foreach ($file in $files) {
  Copy-ReleaseFile $file
}

$directories = @(
  "src",
  "assets",
  "deploy",
  "scripts\deploy",
  "backend\src"
)

if ($IncludeUploads) {
  $directories += "uploads"
} else {
  New-Item -ItemType Directory -Force -Path (Join-Path $Stage "uploads") | Out-Null
}

foreach ($dir in $directories) {
  Copy-ReleaseDirectory $dir
}

$forbiddenRelative = @(
  ".env",
  ".env.private",
  ".env.public",
  ".env.saas",
  "backend\target"
)

foreach ($forbidden in $forbiddenRelative) {
  $candidate = Join-Path $Stage $forbidden
  if (Test-Path -LiteralPath $candidate) {
    throw "Forbidden release payload path was included: $forbidden"
  }
}

# Recursive guard: secrets, runtime state, build output and evidence must never be shipped.
$forbiddenDirNames = @(".git", "node_modules", "target", "dist", ".m2", "evidence", ".codex-supervisor", ".vercel", "backups")
$forbiddenFilePatterns = @(
  ".env", ".env.*", "*.pem", "*.key", "*.p12", "*.pfx", "*.jks", "*.keystore", "id_rsa*", "id_ed25519*",
  "*.sql", "*.dump", "*.backup", "*.bak", "*.log", "cookies*.txt", "*.har", "*.jar"
)
$allowedFileNames = @(".env.public.example")
# Flyway migrations are source code and must ship; any other SQL (dumps/exports) is refused.
$allowedSqlDir = [System.IO.Path]::Combine("backend", "src", "main", "resources", "db", "migration")
foreach ($dir in Get-ChildItem -LiteralPath $Stage -Recurse -Directory -Force) {
  if ($forbiddenDirNames -contains $dir.Name) {
    throw "Forbidden release payload directory was included: $($dir.FullName.Substring($Stage.Length))"
  }
}
foreach ($file in Get-ChildItem -LiteralPath $Stage -Recurse -File -Force) {
  if ($allowedFileNames -contains $file.Name) { continue }
  $relativeDir = (Split-Path -Parent $file.FullName.Substring($Stage.Length)).TrimStart('\', '/')
  if ($file.Name -like "V*__*.sql" -and $relativeDir -eq $allowedSqlDir) { continue }
  foreach ($pattern in $forbiddenFilePatterns) {
    if ($file.Name -like $pattern) {
      throw "Forbidden release payload file was included: $($file.FullName.Substring($Stage.Length))"
    }
  }
}
if (-not $IncludeUploads) {
  $stagedUploads = @(Get-ChildItem -LiteralPath (Join-Path $Stage "uploads") -Recurse -File -Force)
  if ($stagedUploads.Count -gt 0) { throw "Uploads were staged although -IncludeUploads was not set." }
}

$entries = Get-ChildItem -LiteralPath $Stage -Recurse -File -Force | Sort-Object FullName
$manifestFiles = foreach ($entry in $entries) {
  $relative = $entry.FullName.Substring($Stage.Length).TrimStart('\', '/')
  [ordered]@{
    path = ($relative -replace '\\', '/')
    bytes = $entry.Length
    sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $entry.FullName).Hash.ToLowerInvariant()
  }
}

$manifest = [ordered]@{
  releaseName = $ReleaseName
  # This source bundle targets the public single-host Compose/Caddy route.
  # Vercel project identity and CI environment files must be provisioned separately.
  deploymentRoute = "single-host"
  generatedAt = (Get-Date).ToString("yyyy-MM-ddTHH:mm:sszzz")
  sourceRoot = $Root
  includesUploads = [bool]$IncludeUploads
  fileCount = @($manifestFiles).Count
  totalBytes = (@($manifestFiles) | ForEach-Object { [int64]$_["bytes"] } | Measure-Object -Sum).Sum
  forbiddenChecked = $forbiddenRelative
  forbiddenDirNames = $forbiddenDirNames
  forbiddenFilePatterns = $forbiddenFilePatterns
  files = $manifestFiles
}

$manifest | ConvertTo-Json -Depth 6 | Set-Content -Encoding UTF8 -Path $ManifestPath

if (-not $NoZip) {
  Add-Type -AssemblyName System.IO.Compression
  Add-Type -AssemblyName System.IO.Compression.FileSystem
  $zipStream = [System.IO.File]::Open($ZipPath, [System.IO.FileMode]::CreateNew, [System.IO.FileAccess]::ReadWrite)
  try {
    $archive = [System.IO.Compression.ZipArchive]::new($zipStream, [System.IO.Compression.ZipArchiveMode]::Create)
    try {
      $zipDirectories = Get-ChildItem -LiteralPath $Stage -Recurse -Directory -Force | Sort-Object FullName
      foreach ($directory in $zipDirectories) {
        $relative = $directory.FullName.Substring($Stage.Length).TrimStart('\', '/')
        $zipEntryName = ($relative -replace '\\', '/').TrimEnd('/') + '/'
        [void]$archive.CreateEntry($zipEntryName)
      }
      $zipEntries = Get-ChildItem -LiteralPath $Stage -Recurse -File -Force | Sort-Object FullName
      foreach ($entry in $zipEntries) {
        $relative = $entry.FullName.Substring($Stage.Length).TrimStart('\', '/')
        $zipEntryName = $relative -replace '\\', '/'
        [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
          $archive,
          $entry.FullName,
          $zipEntryName,
          [System.IO.Compression.CompressionLevel]::Optimal
        ) | Out-Null
      }
    } finally {
      $archive.Dispose()
    }
  } finally {
    $zipStream.Dispose()
  }
  if (-not (Test-Path -LiteralPath $ZipPath)) {
    throw "Release zip was not created: $ZipPath"
  }
}

Write-Output "Release staging path: $Stage"
if (-not $NoZip) {
  Write-Output "Release zip: $ZipPath"
}
Write-Output "Manifest: $ManifestPath"
Write-Output "Files: $(@($manifestFiles).Count)"
Write-Output "Bytes: $($manifest.totalBytes)"
