# Temporary diagnostics for #72: samples runner health every two minutes into a check run, which survives a lost runner.
param([switch]$Once)
$ErrorActionPreference = 'Continue'
$repo = $env:GITHUB_REPOSITORY
$log = Join-Path $env:RUNNER_TEMP 'metrics.txt'
$body = Join-Path $env:RUNNER_TEMP 'metrics.json'
$id = gh api -X POST "repos/$repo/check-runs" -f name="metrics $env:DIAG_NAME" -f head_sha=$env:GITHUB_SHA -f status=in_progress --jq .id
Set-Content (Join-Path $env:RUNNER_TEMP 'metrics-id.txt') $id
while ($true) {
  try {
    $os = Get-CimInstance Win32_OperatingSystem
    $all = Get-Process
    $chrome = @($all | Where-Object Name -eq 'chrome')
    $java = @($all | Where-Object Name -eq 'java')
    $tmp = Get-ChildItem $env:TEMP -Recurse -Force -File -ErrorAction SilentlyContinue | Measure-Object Length -Sum
    $drives = (Get-PSDrive -PSProvider FileSystem | Where-Object { $_.Used -ne $null } | ForEach-Object { '{0}={1:N1}GB' -f $_.Name, ($_.Free / 1GB) }) -join ' '
    $javaDetail = ($java | Sort-Object WorkingSet64 -Descending | ForEach-Object { '{0}:{1:N0}MB/{2}t' -f $_.Id, ($_.WorkingSet64 / 1MB), $_.Threads.Count }) -join ','
    $line = '{0:HH:mm:ss} procs={1} handles={2} freeRAM={3:N0}MB commit={4:N0}/{5:N0}MB chrome={6}/{7:N0}MB java={8}[{9}] {10} temp={11}files/{12:N0}MB' -f (Get-Date).ToUniversalTime(), $all.Count, ($all | Measure-Object HandleCount -Sum).Sum, ($os.FreePhysicalMemory / 1KB), (($os.TotalVirtualMemorySize - $os.FreeVirtualMemory) / 1KB), ($os.TotalVirtualMemorySize / 1KB), $chrome.Count, (($chrome | Measure-Object WorkingSet64 -Sum).Sum / 1MB), $java.Count, $javaDetail, $drives, $tmp.Count, ($tmp.Sum / 1MB)
  } catch { $line = "{0:HH:mm:ss} sample failed: $_" -f (Get-Date).ToUniversalTime() }
  Add-Content $log $line
  $text = (Get-Content $log | Select-Object -Last 400) -join "`n"
  @{ output = @{ title = "metrics $env:DIAG_NAME"; summary = $text } } | ConvertTo-Json -Depth 3 | Set-Content $body
  gh api -X PATCH "repos/$repo/check-runs/$id" --input $body --silent
  if ($Once) { break }
  Start-Sleep -Seconds 120
}
