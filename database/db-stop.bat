@echo off
chcp 65001 >nul
setlocal
title Database Server - Stop
set "SVC=postgresql-x64-16"

net session >nul 2>&1
if %errorlevel% neq 0 (
    echo Requesting administrator privileges...
    powershell -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
    exit /b
)

sc query "%SVC%" | findstr /i "RUNNING" >nul
if not %errorlevel%==0 (
    echo [INFO] Database is ALREADY STOPPED. Nothing to do.
    goto :status
)

echo Stopping database service...
net stop "%SVC%"

:status
sc query "%SVC%" | findstr /i "RUNNING" >nul
if %errorlevel%==0 (
    echo [FAIL] Database is still RUNNING.
) else (
    echo [OK] Database is STOPPED.
)
pause