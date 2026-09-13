@echo off
setlocal
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\v0.3.1-integration-test.ps1"
if errorlevel 1 (
  echo.
  echo v0.3.1 Kafka verification failed. Inspect the first failed gate above.
  pause
  exit /b 1
)
echo.
echo v0.3.1 Kafka verification succeeded.
pause
