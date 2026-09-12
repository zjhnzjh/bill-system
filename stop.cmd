@echo off
chcp 65001 >nul
setlocal EnableExtensions
cd /d "%~dp0"
docker compose down
if errorlevel 1 pause

