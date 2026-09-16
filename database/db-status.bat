@echo off
chcp 65001 >nul
setlocal
title Database Server - Status
set "SVC=postgresql-x64-16"

sc query "%SVC%" | findstr /i "RUNNING" >nul
if %errorlevel%==0 (
    echo [OK] Database is RUNNING.
) else (
    echo [INFO] Database is STOPPED.
)

echo.
echo Usage: db-start.bat = start   db-stop.bat = stop   db-status.bat = check status
pause