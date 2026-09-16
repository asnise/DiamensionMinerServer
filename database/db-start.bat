@echo off
chcp 65001 >nul
setlocal
title Database Server - Start
set "SVC=postgresql-x64-16"

net session >nul 2>&1
if %errorlevel% neq 0 (
    echo Requesting administrator privileges...
    powershell -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
    exit /b
)

sc query "%SVC%" | findstr /i "RUNNING" >nul
if %errorlevel%==0 (
    echo [INFO] Database is ALREADY RUNNING. Nothing to do.
    goto :status
)

echo Starting database service...
net start "%SVC%"

:status
sc query "%SVC%" | findstr /i "RUNNING" >nul
if %errorlevel%==0 (
    echo [OK] Database is RUNNING.
) else (
    echo [FAIL] Database is STOPPED.
    echo        Note: run as Administrator if the service refuses to start.
)
pause