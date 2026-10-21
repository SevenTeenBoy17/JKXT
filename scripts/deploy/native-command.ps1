#requires -Version 5.1
# Native Windows argument encoding must not depend on PowerShell 5.1's legacy
# binder. All arguments are data; no cmd.exe / shell interpolation is used.
function ConvertTo-JkxtNativeArgument {
  param([AllowEmptyString()][string]$Value)
  $escaped = [regex]::Replace($Value, '(\\*)"', '$1$1\"')
  $escaped = [regex]::Replace($escaped, '(\\+)$', '$1$1')
  return '"' + $escaped + '"'
}

function Invoke-JkxtNativeCommand {
  param(
    [Parameter(Mandatory)][string]$Executable,
    [string[]]$ArgumentList = @(),
    [ValidateRange(1, 86400000)][int]$TimeoutMilliseconds = 900000
  )
  $process = $null
  $commandStarted = $false
  $completed = $false
  try {
    $application = Get-Command -Name $Executable -CommandType Application -ErrorAction Stop | Select-Object -First 1
    # A .cmd/.bat file needs a shell and would re-interpret metacharacters. Docker
    # on supported Windows hosts is docker.exe; functions/aliases are not native.
    if ([IO.Path]::GetExtension($application.Source) -in @('.cmd', '.bat')) {
      throw 'A native executable is required.'
    }
    $info = New-Object Diagnostics.ProcessStartInfo
    $info.FileName = $application.Source
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $info.StandardOutputEncoding = New-Object Text.UTF8Encoding($false)
    $info.StandardErrorEncoding = New-Object Text.UTF8Encoding($false)
    $info.WorkingDirectory = (Get-Location).ProviderPath
    $info.Arguments = (@($ArgumentList | ForEach-Object { ConvertTo-JkxtNativeArgument -Value $_ }) -join ' ')
    $process = New-Object Diagnostics.Process
    $process.StartInfo = $info
    if (-not $process.Start()) { throw 'Native process did not start.' }
    $commandStarted = $true
    $clock = [Diagnostics.Stopwatch]::StartNew()
    # Drain both streams concurrently to prevent a full stderr pipe deadlock.
    $stdoutTask = $process.StandardOutput.ReadToEndAsync()
    $stderrTask = $process.StandardError.ReadToEndAsync()
    if (-not $process.WaitForExit($TimeoutMilliseconds)) {
      try { $process.Kill() } catch { }
      # Killing the CLI does not prove Docker/remote work or descendants stopped.
      return [pscustomobject]@{exitCode=-1; stdout=''; stderr=''; invocationFailed=$true; timedOut=$true; timeoutPhase='process'; commandStarted=$true; workMayStillBeRunning=$true}
    }
    # An exited parent can leave inherited pipes open in a child. Stream draining
    # shares the same deadline; GetResult must not wait without a time bound.
    $remaining = [int][Math]::Max(0, $TimeoutMilliseconds - $clock.ElapsedMilliseconds)
    if (-not [Threading.Tasks.Task]::WaitAll([Threading.Tasks.Task[]]@($stdoutTask, $stderrTask), $remaining)) {
      return [pscustomobject]@{exitCode=-1; stdout=''; stderr=''; invocationFailed=$true; timedOut=$true; timeoutPhase='output'; commandStarted=$true; workMayStillBeRunning=$true}
    }
    $completed = $true
    return [pscustomobject]@{
      exitCode = $process.ExitCode
      stdout = $stdoutTask.GetAwaiter().GetResult()
      stderr = $stderrTask.GetAwaiter().GetResult()
      invocationFailed = $false
      timedOut = $false
      commandStarted = $true
      workMayStillBeRunning = $false
    }
  } catch {
    # Callers may handle stage/exit status, never expose raw exception messages:
    # resolved configuration, process arguments and native stderr can be secret.
    return [pscustomobject]@{exitCode=-1; stdout=''; stderr=''; invocationFailed=$true; timedOut=$false; commandStarted=$commandStarted; workMayStillBeRunning=($commandStarted -and -not $completed)}
  } finally {
    if ($null -ne $process) { $process.Dispose() }
  }
}
