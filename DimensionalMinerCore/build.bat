@echo off
setlocal
cd /d "%~dp0"

set "GRADLE_CMD="
set "GRADLE_HINT="
set "GRADLE_MISSING=1"

rem 1) Prefer a generated wrapper script, if present.
if exist "gradlew.bat" (
    set "GRADLE_CMD=gradlew.bat"
    set "GRADLE_HINT=wrapper"
    set "GRADLE_MISSING="
)
if not defined GRADLE_MISSING goto build

rem 2) Wrapper jar without the scripts - invoke it directly.
if exist "gradle\wrapper\gradle-wrapper.jar" (
    set "GRADLE_CMD=java -classpath gradle\wrapper\gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain"
    set "GRADLE_HINT=wrapper jar"
    set "GRADLE_MISSING="
)
if not defined GRADLE_MISSING goto build

rem 3) System-wide Gradle. Match real executables only - "where gradle" would
rem    also match the local "gradle\" folder.
where gradle.exe >nul 2>nul
if errorlevel 1 goto noGradleService
set "GRADLE_CMD=gradle.exe"
set "GRADLE_HINT=system Gradle"
goto build

:noGradleService
where gradle.bat >nul 2>nul
if errorlevel 1 goto noGradleAtAll
set "GRADLE_CMD=gradle.bat"
set "GRADLE_HINT=system Gradle"
goto build

:noGradleAtAll
echo [ERROR] No Gradle found.
echo.
echo A gradlew.bat wrapper is missing. Generate it on a machine that has Gradle:
echo     gradle wrapper --gradle-version 9.7.0
echo Then re-run this script.
echo.
echo Java 25 is required for this plugin. Currently on PATH:
where java >nul 2>nul
if errorlevel 1 (
    echo     java: NOT FOUND
    exit /b 1
)
java -version 2>&1 | findstr /i "version"
exit /b 1

:build
echo [INFO] Building DimensionalMinerCore using %GRADLE_HINT% ...
call %GRADLE_CMD% jar
if errorlevel 1 (
    echo.
    echo [ERROR] Build FAILED.
    exit /b 1
)

echo.
if exist "..\server\plugins\DimensionalMinerCore.jar" (
    echo [OK] Output: server\plugins\DimensionalMinerCore.jar
    echo      Note: stop the Minecraft server if the file is locked.
) else (
    echo [WARN] Build finished, but server\plugins\DimensionalMinerCore.jar was not found.
)

endlocal
exit /b 0