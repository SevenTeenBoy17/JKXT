#requires -Version 5.1
param(
  [string]$OutputDir = ".codex-supervisor\releases",
  [string]$ReleaseId = "",
  [switch]$NoZip,
  [switch]$SkipUploads
)

$ErrorActionPreference = "Stop"

$Root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..\..")).Path
if ([string]::IsNullOrWhiteSpace($ReleaseId)) {
  $ReleaseId = Get-Date -Format "yyyyMMdd-HHmmss"
}

if ([System.IO.Path]::IsPathRooted($OutputDir)) {
  $OutRoot = [System.IO.Path]::GetFullPath($OutputDir)
} else {
  $OutRoot = [System.IO.Path]::GetFullPath((Join-Path $Root $OutputDir))
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
  ".env.public.example",
  ".vercelignore",
  ".vercel\project.json",
  ".circleci\config.yml",
  ".circleci\public-preflight.env",
  "Dashboard.html",
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
  "backend\src",
  "test-data\import-backend-columns"
)

if (-not $SkipUploads) {
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
  generatedAt = (Get-Date).ToString("yyyy-MM-ddTHH:mm:sszzz")
  sourceRoot = $Root
  includesUploads = (-not $SkipUploads)
  fileCount = @($manifestFiles).Count
  totalBytes = (@($manifestFiles) | ForEach-Object { [int64]$_["bytes"] } | Measure-Object -Sum).Sum
  forbiddenChecked = $forbiddenRelative
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
