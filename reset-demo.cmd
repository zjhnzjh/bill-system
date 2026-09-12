@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\reset-demo.ps1"
if errorlevel 1 (
  echo 重置失败，请确认 Bill System 正在运行。
  pause
  exit /b 1
)
pause
