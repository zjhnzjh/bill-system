@echo off
setlocal
cd /d "%~dp0"

echo Bill System v0.3.1 full acceptance
echo.
echo Suite 1/3: core transaction reliability
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\smoke-test.ps1"
if errorlevel 1 (
  echo.
  echo Core reliability verification failed. Inspect the first failed assertion above.
  pause
  exit /b 1
)

echo.
echo Suite 2/3: three-console integration
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\v0.2-integration-test.ps1"
if errorlevel 1 (
  echo.
  echo Three-console integration verification failed. Inspect the first failed gate above.
  pause
  exit /b 1
)

echo.
echo Suite 3/3: Kafka and asynchronous reliability
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\v0.3.1-integration-test.ps1"
if errorlevel 1 (
  echo.
  echo v0.3.1 Kafka verification failed. Inspect the first failed gate above.
  pause
  exit /b 1
)
echo.
echo Bill System v0.3.1 full verification succeeded.
pause
