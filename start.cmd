@echo off
chcp 65001 >nul
setlocal EnableExtensions
cd /d "%~dp0"

where powershell.exe >nul 2>nul
if errorlevel 1 (
  echo 找不到 Windows PowerShell，无法启动 Bill System。
  pause
  exit /b 1
)

powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\start.ps1" %*
set "billExit=%errorlevel%"
if not "%billExit%"=="0" pause
exit /b %billExit%

