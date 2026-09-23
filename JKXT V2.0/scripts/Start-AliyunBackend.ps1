[CmdletBinding()]
param(
  [string]$ApiKeyAttachment,
  [string]$BaseUrl = 'https://ws-dgs4ei9jjypkwezu.cn-beijing.maas.aliyuncs.com/compatible-mode/v1',
  [switch]$Build
)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path $PSScriptRoot -Parent
$credentialDirectory = Join-Path ([Environment]::GetFolderPath('LocalApplicationData')) 'JKXT\runtime'
$credentialFile = Join-Path $credentialDirectory 'aliyun-key.clixml'
$endpoint = [uri]$BaseUrl
if ($endpoint.Scheme -ne 'https' -or $endpoint.Host -notmatch '\.cn-beijing\.maas\.aliyuncs\.com$' -or $endpoint.UserInfo) {
  throw 'Only the configured Beijing Alibaba workspace HTTPS endpoint is allowed.'
}
function Set-PrivateCredentialAcl([string]$LiteralPath, [bool]$Directory) {
  $item = Get-Item -LiteralPath $LiteralPath -Force
  if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Credential paths must not be links.' }
  $owner = [System.Security.Principal.WindowsIdentity]::GetCurrent().User
  $system = [System.Security.Principal.SecurityIdentifier]::new('S-1-5-18')
  # Modify only the DACL; preserve the existing owner/SACL, which may require privileges we do not hold.
  $acl = Get-Acl -LiteralPath $LiteralPath
  $acl.SetAccessRuleProtection($true, $false)
  foreach ($existingRule in @($acl.GetAccessRules($true, $false, [System.Security.Principal.SecurityIdentifier]))) {
    $acl.RemoveAccessRuleSpecific($existingRule)
  }
  $inheritance = if ($Directory) { [System.Security.AccessControl.InheritanceFlags]'ContainerInherit,ObjectInherit' } else { [System.Security.AccessControl.InheritanceFlags]::None }
  foreach ($principal in @($owner, $system)) {
    $rule = [System.Security.AccessControl.FileSystemAccessRule]::new($principal, [System.Security.AccessControl.FileSystemRights]::FullControl, $inheritance, [System.Security.AccessControl.PropagationFlags]::None, [System.Security.AccessControl.AccessControlType]::Allow)
    $acl.AddAccessRule($rule)
  }
  # Persist only modified access rules through the native .NET ACL API.
  if ($PSVersionTable.PSEdition -eq 'Core') { [IO.FileSystemAclExtensions]::SetAccessControl($item, $acl) }
  else { $item.SetAccessControl($acl) }
}
# Restrict both the directory and any existing file BEFORE saving or loading a key.
New-Item -ItemType Directory -Path $credentialDirectory -Force | Out-Null
Set-PrivateCredentialAcl $credentialDirectory $true
if (Test-Path -LiteralPath $credentialFile -PathType Leaf) { Set-PrivateCredentialAcl $credentialFile $false }
if ($ApiKeyAttachment) {
  $document = Get-Content -LiteralPath $ApiKeyAttachment -Raw
  $keyMatch = [regex]::Match($document, 'sk-[A-Za-z0-9_.\-]+')
  if (-not $keyMatch.Success) { throw 'No API key found in the supplied attachment.' }
  $secureKey = ConvertTo-SecureString $keyMatch.Value -AsPlainText -Force
  # This generated credential is DPAPI-encrypted for this Windows user; never served or committed.
  $secureKey | Export-Clixml -LiteralPath $credentialFile
  Set-PrivateCredentialAcl $credentialFile $false
  $document = $null; $keyMatch = $null
}
if (-not (Test-Path -LiteralPath $credentialFile -PathType Leaf)) {
  throw 'No saved credential. On first run pass -ApiKeyAttachment with the user-supplied file path.'
}
$secureKey = Import-Clixml -LiteralPath $credentialFile
if ($secureKey -isnot [Security.SecureString]) { throw 'Invalid saved credential format.' }
$settings = @{
  JKXT_LLM_ENABLED='true'; JKXT_LLM_ACCEPTANCE_MODE='false'; JKXT_LLM_PROVIDER='aliyun';
  JKXT_LLM_BASE_URL=$BaseUrl.TrimEnd('/'); JKXT_LLM_FALLBACK_BASE_URL='';
  JKXT_LLM_MODEL='qwen-plus'; JKXT_LLM_FALLBACK_MODELS='qwen-flash';
  JKXT_LLM_API_KEY=([Net.NetworkCredential]::new('', $secureKey)).Password
}
$previous = @{}
try {
  foreach ($name in $settings.Keys) {
    $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
    [Environment]::SetEnvironmentVariable($name, $settings[$name], 'Process')
  }
  if ($Build) {
    & docker compose --project-directory $taskRoot -f (Join-Path $taskRoot 'docker-compose.yml') build backend
    if ($LASTEXITCODE -ne 0) { throw 'Backend image build failed; existing service was not recreated.' }
  }
  & docker compose --project-directory $taskRoot -f (Join-Path $taskRoot 'docker-compose.yml') up -d --no-deps backend
  if ($LASTEXITCODE -ne 0) { throw 'Backend restart failed. Inspect container health without printing its environment.' }
  Write-Output 'Backend started with the saved Windows-user encrypted credential. Verify API health separately.'
} finally {
  foreach ($name in $previous.Keys) { [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
  $settings.Clear(); $secureKey=$null
}
