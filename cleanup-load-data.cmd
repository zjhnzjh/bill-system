@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"
type "%~dp0scripts\cleanup-load-data.sql" | docker exec -i bill-system-mysql-1 mysql -ubill -pbill_dev_only
if errorlevel 1 (
  echo 压测数据清理失败，请确认 Bill System 正在运行。
  pause
  exit /b 1
)
echo 已只清理 LIFE-LOAD-001 压测订单，并恢复其专用库存。
pause
