@echo off
setlocal
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\smoke-test.ps1"
if errorlevel 1 (
  echo.
  echo Verification failed. Keep this window open and inspect the first failed assertion.
  pause
  exit /b 1
)
echo.
echo Verification succeeded.
pause
