@echo off
chcp 65001 >nul
cd /d "%~dp0"
echo 正在停止知造 SIRP ...
rem 先通知守护循环：这是主动停止，别自动拉起（start.ps1 看到这个标记会退出守护）
if not exist "runtime" mkdir "runtime"
echo stop > "runtime\stop.flag"
if exist "runtime\app.pid" (
  for /f %%i in (runtime\app.pid) do taskkill /F /PID %%i >nul 2>&1
  del /q "runtime\app.pid" >nul 2>&1
)
rem 数据库：按端口找进程（便携版固定 127.0.0.1:3307）
rem 注意：findstr 是子串匹配，":3307" 会连 ":33070" 一起命中，把客户机上无关的服务强杀掉。
rem 这里用精确匹配：端口后面必须跟空格（netstat 输出是 "127.0.0.1:3307    0.0.0.0:0    LISTENING"）。
for /f "tokens=5" %%p in ('netstat -ano ^| findstr /R /C:":3307 " ^| findstr LISTENING') do taskkill /F /PID %%p >nul 2>&1
echo 已停止（数据保留在 runtime\data）
timeout /t 2 >nul
