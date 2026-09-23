$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$sourceFile = Join-Path $repoRoot 'scripts\Start-AliyunBackend.ps1'
$parseTokens = $null; $parseErrors = $null
$ast = [System.Management.Automation.Language.Parser]::ParseFile($sourceFile, [ref]$parseTokens, [ref]$parseErrors)
if ($parseErrors.Count) { throw 'Credential startup script has syntax errors.' }
$definition = $ast.Find({ param($node) $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Set-PrivateCredentialAcl' }, $true)
if (-not $definition) { throw 'Credential ACL function missing.' }
# Load only the exact function declaration; never run startup, import credentials, or call Docker.
. ([scriptblock]::Create($definition.Extent.Text))
$testDirectory = Join-Path ([IO.Path]::GetTempPath()) ('jkxt-acl-test-' + [guid]::NewGuid().ToString('N'))
$testFile = Join-Path $testDirectory 'empty-test-file'
New-Item -ItemType Directory -Path $testDirectory | Out-Null
New-Item -ItemType File -Path $testFile | Out-Null
try {
  $everyone = [System.Security.Principal.SecurityIdentifier]::new('S-1-1-0')
  $broadRule = [System.Security.AccessControl.FileSystemAccessRule]::new($everyone, 'FullControl', 'Allow')
  foreach ($target in @($testDirectory, $testFile)) {
    $before = Get-Acl -LiteralPath $target
    $before.AddAccessRule($broadRule)
    Set-Acl -LiteralPath $target -AclObject $before
  }
  Set-PrivateCredentialAcl $testDirectory $true
  Set-PrivateCredentialAcl $testFile $false
  $allowed = @([System.Security.Principal.WindowsIdentity]::GetCurrent().User.Value, 'S-1-5-18')
  foreach ($target in @($testDirectory, $testFile)) {
    $actual = Get-Acl -LiteralPath $target
    if (-not $actual.AreAccessRulesProtected) { throw 'ACL inheritance remains enabled.' }
    $rules = @($actual.GetAccessRules($true, $true, [System.Security.Principal.SecurityIdentifier]))
    if ($rules.Count -ne 2) { throw 'Unexpected credential ACL entry count.' }
    foreach ($rule in $rules) {
      if ($rule.IdentityReference.Value -notin $allowed -or $rule.IsInherited -or $rule.AccessControlType -ne 'Allow' -or $rule.FileSystemRights -ne 'FullControl') { throw 'Credential ACL contains an unexpected permission.' }
    }
  }
  Write-Output 'PASS: script syntax, restricted directory and file ACLs, existing broad explicit grants removed; no credentials or services accessed.'
} finally {
  # Only these two unique test paths are removed; no recursion or parent deletion.
  Remove-Item -LiteralPath $testFile -Force
  Remove-Item -LiteralPath $testDirectory -Force
}
