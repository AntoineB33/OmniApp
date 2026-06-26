@echo off
setlocal EnableDelayedExpansion

REM =====================================================================
REM  dev-restart.bat  -  kill running app, wait for exit, then relaunch,
REM                      PRESERVING the existing DB/state. Dev helper only.
REM  (To wipe the real DB, use dev-empty.bat; for an isolated empty run,
REM   use dev-reset.bat.)
REM  Before relaunching, if an adapted "duplicate" DB exists alongside the
REM  live one (scheduler-state.duplicate.db), this warns when the live DB
REM  has history units newer than the duplicate. See CLAUDE.md "Dev DB
REM  scripts" for the duplicate workflow.
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

REM The live DB and the optional adapted duplicate it is checked against.
set "STATE_DIR=%USERPROFILE%\.omniapp"
set "DB=%STATE_DIR%\scheduler-state.db"
set "DUP=%STATE_DIR%\scheduler-state.duplicate.db"
REM ---------------------------------------------------------------------

REM Resolve project root as the parent of this script's folder.
set "SCRIPT_DIR=%~dp0"
pushd "%SCRIPT_DIR%.." || (echo [x] Could not enter project root.& exit /b 1)
set "PROJECT_ROOT=%CD%"
echo Project root: %PROJECT_ROOT%
echo.

REM ---- [1/4] Kill any running instance --------------------------------
echo [1/4] Killing running app matching "%APP_IDENTIFIER%"...
powershell -NoProfile -Command "@(Get-CimInstance Win32_Process | Where-Object { $_.ProcessId -ne $PID -and $_.Name -like 'java*' -and $_.CommandLine -like '*%APP_IDENTIFIER%*' }) | ForEach-Object { Write-Host ('      killing PID ' + $_.ProcessId); Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }"

REM ---- [2/4] Wait for it to fully exit --------------------------------
echo [2/4] Waiting for the app to fully close...
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

REM ---- [3/4] Adapted-duplicate guard ---------------------------------
REM If a scheduler-state.duplicate.db exists (the DB changed to adapt to
REM development changes), warn when the LIVE DB has newer history units so
REM the user can incorporate them into the duplicate before continuing.
if not exist "%DUP%" (
  echo [3/4] No adapted duplicate present - skipping the history check.
  goto :launch
)
where python >nul 2>nul || (
  echo [3/4] Adapted duplicate found, but 'python' is not on PATH - skipping the history check.
  goto :launch
)
echo [3/4] Comparing the live DB history against the adapted duplicate...
python "%SCRIPT_DIR%internal\db_duplicate_check.py" "%DB%" "%DUP%"
if not errorlevel 2 goto :launch
echo.
echo       [!] The live DB has history units NEWER than the adapted duplicate.
echo           You may want to re-apply your DB adaptation so these new history
echo           units are incorporated into the duplicate before continuing.
set /p "_ans=      Continue launching the live DB anyway? [y/N] "
if /i not "%_ans%"=="y" (
  echo       Aborted - no app launched.
  popd & exit /b 1
)

:launch
REM ---- [4/4] Relaunch -------------------------------------------------
echo [4/4] Launching the app: %LAUNCH_CMD%
start "App" cmd /c "%LAUNCH_CMD%"

popd
endlocal