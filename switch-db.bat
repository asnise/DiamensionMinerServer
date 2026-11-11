@echo off
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0switch-db.ps1" %*
endlocal
