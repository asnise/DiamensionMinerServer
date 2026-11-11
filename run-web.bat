@echo off
chcp 65001 >nul
setlocal
title Dimensional Miner - Web Console

rem Usage:
rem   run-web.bat          dev server  (hot reload)        http://localhost:3000
rem   run-web.bat prod     build, then production server   http://localhost:3000
rem
rem Port 3000 is fixed: ProfileCommand.java links players to http://localhost:3000/profile

cd /d "%~dp0web-console"

where npm >nul 2>nul
if errorlevel 1 (
    echo [ERROR] npm not found. Install Node.js 18+ LTS from https://nodejs.org
    goto fail
)

if not exist ".env.local" (
    echo [ERROR] web-console\.env.local is missing.
    echo         It needs DB_PROFILE, LOCAL_PG* / CLOUD_PG*, ADMIN_PASSWORD and SESSION_SECRET.
    goto fail
)

findstr /b /c:"SESSION_SECRET=" ".env.local" >nul
if errorlevel 1 (
    echo [ERROR] SESSION_SECRET is not set in web-console\.env.local - nobody could log in.
    goto fail
)

netstat -ano | findstr /r /c:":3000 .*LISTENING" >nul
if not errorlevel 1 (
    echo [ERROR] Port 3000 is already in use. Is the web console already running in another window?
    goto fail
)

if not exist "node_modules" (
    echo [INFO] node_modules not found - running npm install ...
    call npm install
    if errorlevel 1 (
        echo [ERROR] npm install failed.
        goto fail
    )
)

if /i "%~1"=="prod" goto prod

echo [INFO] Starting dev server on http://localhost:3000  (Ctrl+C to stop)
call npm run dev -- -p 3000
goto end

:prod
echo [INFO] Building production bundle ...
call npm run build
if errorlevel 1 (
    echo [ERROR] Build failed.
    goto fail
)
echo [INFO] Starting production server on http://localhost:3000  (Ctrl+C to stop)
call npm run start -- -p 3000
goto end

:fail
echo.
pause
endlocal
exit /b 1

:end
endlocal
exit /b 0
