@echo off
chcp 65001 >nul
cd /d "%~dp0"
echo 正在停止知造 SIRP ...
if exist "runtime\app.pid" (
  for /f %%i in (runtime\app.pid) do taskkill /F /PID %%i >nul 2>&1
  del /q "runtime\app.pid" >nul 2>&1
)
rem 数据库：按端口找进程（便携版固定 127.0.0.1:3307）
for /f "tokens=5" %%p in ('netstat -ano ^| findstr ":3307" ^| findstr LISTENING') do taskkill /F /PID %%p >nul 2>&1
echo 已停止（数据保留在 runtime\data）
timeout /t 2 >nul
