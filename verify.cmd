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
echo v0.1 reliability verification succeeded.
echo.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\v0.2-integration-test.ps1"
if errorlevel 1 (
  echo.
  echo v0.2 integration verification failed. Keep this window open and inspect the first failed gate.
  pause
  exit /b 1
)
echo.
echo v0.1 and v0.2 verification succeeded.
echo.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\v0.3.1-integration-test.ps1"
if errorlevel 1 (
  echo.
  echo v0.3.1 Kafka verification failed. Keep this window open and inspect the first failed gate.
  pause
  exit /b 1
)
echo.
echo All v0.1, v0.2, and v0.3.1 verification succeeded.
pause
