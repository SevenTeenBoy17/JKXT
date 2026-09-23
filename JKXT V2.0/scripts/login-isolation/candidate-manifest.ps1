[CmdletBinding()]
param(
  [Parameter(Mandatory)][string]$Source,
  [Parameter(Mandatory)][string]$Destination,
  [Parameter(Mandatory)][string]$OutputDirectory,
  [Parameter(Mandatory)][ValidatePattern('^[a-z0-9_]+$')][string]$RunId,
  [string[]]$SelectedPaths,
  [string[]]$ContractPaths = @()
)
$ErrorActionPreference = 'Stop'
$sourceRoot = [IO.Path]::GetFullPath($Source).TrimEnd('\','/')
$candidateRoot = [IO.Path]::GetFullPath($Destination).TrimEnd('\','/')
if (-not (Test-Path -LiteralPath $sourceRoot -PathType Container)) { throw 'Source directory missing' }
if ($sourceRoot -eq $candidateRoot -or $candidateRoot.StartsWith($sourceRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Candidate must differ from source and remain outside it' }
if (Test-Path -LiteralPath $candidateRoot) { throw 'Candidate already exists; never overwrite a checkpoint' }
$outputRoot = [IO.Path]::GetFullPath($OutputDirectory)
if (Test-Path -LiteralPath (Join-Path $outputRoot 'candidate-manifest.json')) { throw 'Manifest already exists; retain previous evidence' }
function Is-SourceInput([string]$path) {
  $path = $path.Replace('\', '/')
  if ($path -match '(^|/)(\.git|\.worktrees|artifacts|target|node_modules|uploads|screenshots|backups?|results|runtime|captures?|JKXT-V2\.0-源码|\.agent-supervisor|\.codex-supervisor)(/|$)' -or $path -match '(^|/)(\.env[^/]*|[^/]*\.(pem|key|p12|pfx|zip|log|db|sqlite|jar|class))$' -or $path -match '(?i)[_-](capture|results?)\.(json|txt|html)$') { return $false }
  # Only these three input spreadsheets are required by existing smoke/release
  # scripts. No broad test-data tree: it also contains cookies and runtime reports.
  if ($path.StartsWith('test-data/', [StringComparison]::OrdinalIgnoreCase)) {
    return $path -cin @(
      'test-data/import-backend-columns/01-教师安排-后端列-天津市实验中学.xlsx',
      'test-data/import-backend-columns/02-考场信息-后端列-天津市实验中学.xlsx',
      'test-data/import-backend-columns/03-考试安排-后端列-天津市实验中学.xlsx'
    )
  }
  if ($path -notmatch '^(backend/|src/|assets/|scripts/|deploy/|docs/|Dashboard\.html$|docker-compose[^/]*\.ya?ml$|vercel\.json$|\.gitignore$|[^/]+\.md$)') { return $false }
  $extension = [IO.Path]::GetExtension($path).ToLowerInvariant()
  if ($extension -in @('.java','.xml','.sql','.yml','.yaml','.properties','.json','.js','.jsx','.mjs','.cjs','.ts','.tsx','.css','.html','.md','.ps1','.cmd','.sh','.py','.png','.jpg','.jpeg','.webp','.gif','.svg','.woff','.woff2','.ttf','.mp4','.docx','.pdf','.conf','.csv','.xlsx')) { return $true }
  return $path -cin @('.gitignore', 'backend/.dockerignore', 'backend/Dockerfile', 'backend/mvnw', 'deploy/caddy/Caddyfile')
}
$head = & git -C $sourceRoot rev-parse HEAD
if ($LASTEXITCODE -ne 0) { throw 'Source must have a Git HEAD' }
$all = @(& git -C $sourceRoot -c core.quotepath=false ls-files --cached --others --exclude-standard -- . | ForEach-Object { $_.Replace('\', '/') } | Sort-Object -Unique)
if ($LASTEXITCODE -ne 0) { throw 'Cannot enumerate current Git source inputs' }
$eligible = @($all | Where-Object { (Is-SourceInput $_) -and (Test-Path -LiteralPath (Join-Path $sourceRoot $_) -PathType Leaf) })
if (-not $PSBoundParameters.ContainsKey('SelectedPaths')) { $SelectedPaths = $eligible }
if ($SelectedPaths.Count -eq 0) { throw 'Empty source selection' }
$canonical = @($SelectedPaths | ForEach-Object { $_.Replace('\', '/') })
if (@($canonical | Sort-Object -Unique).Count -ne $canonical.Count) { throw 'Duplicate path aliases are forbidden' }
$selected = @($canonical | Sort-Object)
foreach ($relativePath in $selected) {
  if ([IO.Path]::IsPathRooted($relativePath) -or $relativePath -match '(^|/)\.\.?(/|$)' -or $relativePath -match '//|:|[. ](/|$)') { throw 'Non-canonical or outside source manifest path' }
  $absolutePath = [IO.Path]::GetFullPath((Join-Path $sourceRoot $relativePath))
  if (-not $absolutePath.StartsWith($sourceRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Path outside source manifest' }
  if (-not (Is-SourceInput $relativePath)) { throw 'Excluded or private source path' }
  if (-not (Test-Path -LiteralPath $absolutePath -PathType Leaf)) { throw 'Selected source file missing' }
  $probe = Get-Item -LiteralPath $absolutePath
  while ($probe.FullName -ne $sourceRoot) {
    if ($probe.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Symlink/reparse source inputs are forbidden' }
    $probe = $probe.Parent ?? $probe.Directory
  }
}
# All eligible current files are required, including modified tracked and new files.
$omitted = @($eligible | Where-Object { $_ -notin $selected })
if ($omitted.Count -gt 0) { throw "Omitted current source inputs: $($omitted -join ', ')" }
$contracts = @($ContractPaths | ForEach-Object {
  if (-not (Test-Path -LiteralPath $_ -PathType Leaf)) { throw 'Contract file missing' }
  @{path=[IO.Path]::GetFullPath($_);sha256=(Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash.ToLowerInvariant()}
})
$files = @($selected | ForEach-Object { @{path=$_;sha256=(Get-FileHash -LiteralPath (Join-Path $sourceRoot $_) -Algorithm SHA256).Hash.ToLowerInvariant()} })
New-Item -ItemType Directory -Path $candidateRoot -ErrorAction Stop | Out-Null
foreach ($file in $files) {
  $target = Join-Path $candidateRoot $file.path
  New-Item -ItemType Directory -Path (Split-Path $target) -Force | Out-Null
  Copy-Item -LiteralPath (Join-Path $sourceRoot $file.path) -Destination $target
  if ((Get-FileHash -LiteralPath $target).Hash.ToLowerInvariant() -ne $file.sha256 -or (Get-FileHash -LiteralPath (Join-Path $sourceRoot $file.path)).Hash.ToLowerInvariant() -ne $file.sha256) { throw 'Source changed during snapshot or copied hash mismatch; candidate retained for inspection' }
}
$manifest = [ordered]@{sourceRoot=$sourceRoot;candidateRoot=$candidateRoot;head="$head";files=$files;excluded=@($all | Where-Object { $_ -notin $selected });runId=$RunId;createdAt=[DateTime]::UtcNow.ToString('o');contracts=$contracts;deletedTracked=@(& git -C $sourceRoot -c core.quotepath=false ls-files --deleted -- .)}
New-Item -ItemType Directory -Path $outputRoot -Force | Out-Null
$manifest | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $outputRoot 'candidate-manifest.json') -Encoding utf8
Write-Output "Verified candidate: $candidateRoot ($($files.Count) files); manifest: $outputRoot"
