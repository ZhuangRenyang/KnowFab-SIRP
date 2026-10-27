@echo off
chcp 65001 >nul
title 设备联网平台

set JAVA_OPTS=-Xms256m -Xmx512m -Dfile.encoding=UTF-8
set APP=target\device-hub.jar

if not exist "%APP%" (
    echo [错误] 找不到 %APP%
    echo 请先执行: mvn clean package
    pause
    exit /b 1
)

echo 正在启动设备联网平台...
echo 启动后浏览器打开: http://localhost:8080
echo 按 Ctrl+C 停止
echo.

java %JAVA_OPTS% -jar "%APP%"

echo.
echo 服务已停止
pause
