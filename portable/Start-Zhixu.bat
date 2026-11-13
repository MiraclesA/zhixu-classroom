@echo off
setlocal
set "PS=%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe"
if not exist "%PS%" (
  echo [ERROR] Windows PowerShell was not found.
  echo Install or enable Windows PowerShell 5.1, then run this file again.
  pause
  exit /b 1
)
"%PS%" -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\Start-Zhixu.ps1"
if errorlevel 1 pause
endlocal
