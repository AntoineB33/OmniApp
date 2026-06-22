@echo off
setlocal EnableDelayedExpansion

REM =====================================================================
REM  dev-restart.bat  -  kill running app, wait for exit, then relaunch,
REM                      PRESERVING the existing DB/state. Dev helper only.
REM  (For an empty DB, use dev-reset.bat instead.)
REM  Location: <project-root>\scripts\dev-restart.bat
REM  Run from anywhere - paths resolve relative to the project root.
REM =====================================================================

REM ----------------------------- CONFIG --------------------------------
REM A unique string that appears in the RUNNING app's command line.
REM Usually your application's main-class FQN (e.g. com.example.AppKt)
REM or the jar/module name. This is how we find the right process to
REM kill, without touching the Gradle daemon, your IDE, or other JVMs.
set "APP_IDENTIFIER=org.example.project.MainKt"

REM Command used to (re)launch the app, run from the project root.
set "LAUNCH_CMD=gradlew.bat :desktopApp:run"
REM ---------------------------------------------------------------------

REM Resolve project root as the parent of this script's folder.
set "SCRIPT_DIR=%~dp0"
pushd "%SCRIPT_DIR%.." || (echo [x] Could not enter project root.& exit /b 1)
set "PROJECT_ROOT=%CD%"
echo Project root: %PROJECT_ROOT%
echo.

REM ---- [1/3] Kill any running instance --------------------------------
echo [1/3] Killing running app matching "%APP_IDENTIFIER%"...
powershell -NoProfile -Command "@(Get-CimInstance Win32_Process | Where-Object { $_.ProcessId -ne $PID -and $_.Name -like 'java*' -and $_.CommandLine -like '*%APP_IDENTIFIER%*' }) | ForEach-Object { Write-Host ('      killing PID ' + $_.ProcessId); Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }"

REM ---- [2/3] Wait for it to fully exit --------------------------------
echo [2/3] Waiting for the app to fully close...
set /a _tries=0
:waitloop
for /f %%C in ('powershell -NoProfile -Command "@(Get-CimInstance Win32_Process | Where-Object { $_.ProcessId -ne $PID -and $_.Name -like 'java*' -and $_.CommandLine -like '*%APP_IDENTIFIER%*' }).Count"') do set "_count=%%C"
if "%_count%"=="" set "_count=0"
if %_count% gtr 0 (
  set /a _tries+=1
  if !_tries! geq 50 (
    echo       [x] Timed out waiting for the app to close.
    popd & exit /b 1
  )
  timeout /t 1 /nobreak >nul
  goto waitloop
)
echo       App is not running.

REM ---- [3/3] Relaunch -------------------------------------------------
echo [3/3] Launching the app: %LAUNCH_CMD%
start "App" cmd /c "%LAUNCH_CMD%"

popd
endlocal