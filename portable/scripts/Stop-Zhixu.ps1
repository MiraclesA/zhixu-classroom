$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$state = Join-Path $root "runtime\state"

foreach ($name in @("web.pid", "mysql.pid")) {
  $pidFile = Join-Path $state $name
  if (Test-Path -LiteralPath $pidFile) {
    $processId = (Get-Content -LiteralPath $pidFile -Raw).Trim()
    if ($processId -match "^\d+$") {
      # mysqld can create a child server process on Windows; taskkill /T closes that whole tree.
      & "$env:SystemRoot\System32\taskkill.exe" /F /T /PID $processId 2>$null | Out-Null
    }
    Remove-Item -LiteralPath $pidFile -Force
  }
}
Write-Host "Zhixu Classroom stopped. Data remains in runtime\state."
