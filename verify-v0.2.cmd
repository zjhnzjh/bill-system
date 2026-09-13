@echo off
setlocal
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\v0.2-integration-test.ps1"
if errorlevel 1 (
  echo.
  echo v0.2 integration verification failed. Inspect the first failed gate above.
  pause
  exit /b 1
)
echo.
echo v0.2 integration verification succeeded.
pause
