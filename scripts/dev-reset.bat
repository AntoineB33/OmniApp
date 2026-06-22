@echo off
setlocal EnableDelayedExpansion

REM =====================================================================
REM  dev-reset.bat  -  kill running app, wait for exit, delete THIS
REM                    script's isolated state dir, then relaunch with an
REM                    empty DB. Development helper only.
REM  This uses a dedicated state dir (.omniapp-reset) that is SEPARATE
REM  from the real DB (~/.omniapp) preserved by dev-restart.bat, so a
REM  reset never destroys the data dev-restart relies on.
REM  Location: <project-root>\scripts\dev-reset.bat
REM  Run from anywhere - paths resolve relative to the project root.
REM =====================================================================

REM ----------------------------- CONFIG --------------------------------
REM A unique string that appears in the RUNNING app's command line.
REM Usually your application's main-class FQN (e.g. com.example.AppKt)
REM or the jar/module name. This is how we find the right process to
REM kill, without touching the Gradle daemon, your IDE, or other JVMs.
set "APP_IDENTIFIER=org.example.project.MainKt"

REM Isolated state folder for reset runs. MUST differ from the real DB
REM (~/.omniapp) used by dev-restart.bat, so wiping it here never touches
REM preserved data. The app is told to use it via -Pomniapp.stateDir.
set "STATE_DIR=%USERPROFILE%\.omniapp-reset"

REM Command used to (re)launch the app, run from the project root.
REM The -P property is forwarded to the app JVM as -Domniapp.stateDir
REM (see desktopApp/build.gradle.kts).
set "LAUNCH_CMD=gradlew.bat :desktopApp:run -Pomniapp.stateDir=%STATE_DIR%"
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

REM ---- [3/4] Delete the state folder ----------------------------------
echo [3/4] Deleting state folder "%STATE_DIR%"...
set /a _dtries=0
:delloop
if not exist "%STATE_DIR%\" goto deldone
rmdir /s /q "%STATE_DIR%" 2>nul
if not exist "%STATE_DIR%\" goto deldone
set /a _dtries+=1
if !_dtries! geq 5 (
  echo       [!] Could not fully delete "%STATE_DIR%" - a file handle may still be open.
  goto deldone
)
timeout /t 1 /nobreak >nul
goto delloop
:deldone
if exist "%STATE_DIR%\" (echo       Partially deleted.) else (echo       Done.)

REM ---- [4/4] Relaunch -------------------------------------------------
echo [4/4] Launching the app: %LAUNCH_CMD%
start "App" cmd /c "%LAUNCH_CMD%"

popd
endlocal