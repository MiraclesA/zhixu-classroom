$ErrorActionPreference = "Stop"

# This script is shipped with the portable Windows package. It never uses a system JDK or MySQL.
$root = Split-Path -Parent $PSScriptRoot
$javaHome = Join-Path $root "runtime\jdk"
$mysqlHome = Join-Path $root "runtime\mysql"
$jar = Join-Path $root "app\classroom-1.0.0.jar"
$state = Join-Path $root "runtime\state"
$data = Join-Path $state "mysql-data"
$logs = Join-Path $root "logs"
$mysqlPidFile = Join-Path $state "mysql.pid"
$webPidFile = Join-Path $state "web.pid"
$webPort = 8097
$dbPassword = "zhixu_demo_db_2026"

# MySQL on Windows can fail silently when its runtime path contains non-ASCII characters.
# Keep this check before initialization so users get an actionable message instead of a broken service.
$asciiRoot = [Text.Encoding]::ASCII.GetString([Text.Encoding]::ASCII.GetBytes($root))
if ($asciiRoot -ne $root) {
  throw "Extract this package to an ASCII path such as C:\ZhixuClassroom, then run Start-Zhixu.bat again."
}

foreach ($required in @("$javaHome\bin\java.exe", "$mysqlHome\bin\mysqld.exe", "$mysqlHome\bin\mysql.exe", $jar)) {
  if (-not (Test-Path -LiteralPath $required)) {
    throw "Portable package is incomplete: $required"
  }
}
New-Item -ItemType Directory -Force -Path $state, $logs | Out-Null

function Test-TcpPort([int]$Port) {
  return [bool](Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue)
}

# Prefer the original local-demo port, but do not attach to an unrelated MySQL that already owns it.
$mysqlPort = $null
foreach ($candidate in @(3317, 3318, 3319, 3320)) {
  if (-not (Test-TcpPort $candidate)) {
    $mysqlPort = $candidate
    break
  }
  $listener = Get-NetTCPConnection -State Listen -LocalPort $candidate -ErrorAction SilentlyContinue | Select-Object -First 1
  $owner = if ($listener) { Get-CimInstance Win32_Process -Filter "ProcessId=$($listener.OwningProcess)" -ErrorAction SilentlyContinue } else { $null }
  if ($owner -and $owner.ExecutablePath -like "$mysqlHome*") {
    $mysqlPort = $candidate
    break
  }
}
if ($null -eq $mysqlPort) {
  throw "Ports 3317-3320 are already in use. Close the conflicting program, then run Start-Zhixu.bat again."
}

function Wait-ForMysql {
  $admin = Join-Path $mysqlHome "bin\mysqladmin.exe"
  for ($i = 0; $i -lt 60; $i++) {
    & $admin --no-defaults --protocol=TCP --host=127.0.0.1 --port=$mysqlPort --user=root ping --silent 2>$null
    if ($LASTEXITCODE -eq 0) { return }
    Start-Sleep -Seconds 1
  }
  throw "Portable MySQL did not become ready. See logs\mysql-error.log"
}

if (-not (Test-TcpPort $mysqlPort)) {
  if (-not (Test-Path -LiteralPath (Join-Path $data "mysql"))) {
    New-Item -ItemType Directory -Force -Path $data | Out-Null
    & "$mysqlHome\bin\mysqld.exe" --no-defaults "--basedir=$mysqlHome" "--datadir=$data" --initialize-insecure
    if ($LASTEXITCODE -ne 0) { throw "Portable MySQL initialization failed." }
  }
  $mysqlProcess = Start-Process -FilePath "$mysqlHome\bin\mysqld.exe" -ArgumentList @(
    "--no-defaults", "--basedir=$mysqlHome", "--datadir=$data", "--port=$mysqlPort",
    "--bind-address=127.0.0.1", "--mysqlx=0", "--character-set-server=utf8mb4",
    "--collation-server=utf8mb4_0900_ai_ci"
  ) -RedirectStandardOutput "$logs\mysql.log" -RedirectStandardError "$logs\mysql-error.log" -PassThru
  Set-Content -LiteralPath $mysqlPidFile -Value $mysqlProcess.Id -Encoding ascii
}
Wait-ForMysql
Set-Content -LiteralPath (Join-Path $state "mysql-port.txt") -Value $mysqlPort -Encoding ascii

# These are local demonstration credentials only; MySQL is bound to 127.0.0.1.
$sql = "CREATE DATABASE IF NOT EXISTS zhixu CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci; CREATE USER IF NOT EXISTS 'zhixu'@'127.0.0.1' IDENTIFIED BY '$dbPassword'; GRANT ALL PRIVILEGES ON zhixu.* TO 'zhixu'@'127.0.0.1'; FLUSH PRIVILEGES;"
& "$mysqlHome\bin\mysql.exe" --no-defaults --protocol=TCP --host=127.0.0.1 --port=$mysqlPort --user=root -e $sql
if ($LASTEXITCODE -ne 0) { throw "Portable MySQL setup failed." }

if (Test-TcpPort $webPort) {
  Start-Process "http://127.0.0.1:$webPort"
  Write-Host "Zhixu Classroom is already running: http://127.0.0.1:$webPort"
  exit 0
}

$env:JAVA_HOME = $javaHome
$env:ZHIXU_DB_URL = "jdbc:mysql://127.0.0.1:$mysqlPort/zhixu?connectionTimeZone=%2B08:00&forceConnectionTimeZoneToSession=true&characterEncoding=UTF-8"
$env:ZHIXU_DB_USER = "zhixu"
$env:ZHIXU_DB_PASSWORD = $dbPassword
$env:ZHIXU_BIND = "127.0.0.1"
$env:ZHIXU_PORT = "$webPort"
$env:ZHIXU_DEMO = "true"
$webProcess = Start-Process -FilePath "$javaHome\bin\java.exe" -ArgumentList @("-jar", $jar) -RedirectStandardOutput "$logs\web.log" -RedirectStandardError "$logs\web-error.log" -PassThru
Set-Content -LiteralPath $webPidFile -Value $webProcess.Id -Encoding ascii

for ($i = 0; $i -lt 60; $i++) {
  try {
    $response = Invoke-WebRequest -UseBasicParsing "http://127.0.0.1:$webPort/" -TimeoutSec 2
    if ($response.StatusCode -eq 200) {
      Start-Process "http://127.0.0.1:$webPort"
      Write-Host "Started: http://127.0.0.1:$webPort"
      exit 0
    }
  } catch {}
  Start-Sleep -Seconds 1
}
throw "Web service did not become ready. See logs\web-error.log"
