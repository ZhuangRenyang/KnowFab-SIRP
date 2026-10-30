@echo off
chcp 65001 >nul
cd /d "%~dp0"
echo.
echo   知造 SIRP · PLC 联网诊断（只读，不改配置）
echo   请先把网线插好，再运行本诊断。
echo.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0plc-diag.ps1" %*
