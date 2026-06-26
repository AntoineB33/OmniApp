@echo off
setlocal EnableDelayedExpansion

REM =====================================================================
REM  dev-empty.bat  -  kill the running app, wait for exit, then DELETE
REM                    the LIVE development DB used by dev-restart.bat
REM                    (~/.omniapp/scheduler-state.db), leaving an empty
REM                    state. Does NOT relaunch - run dev-restart.bat next.
REM                    Development helper only.
REM
REM  Difference from dev-reset.bat: dev-reset.bat wipes a SEPARATE isolated
REM  dir (~/.omniapp-reset) it launches with, so it never touches the real
REM  data. dev-empty.bat empties the REAL DB that dev-restart.bat preserves.
REM  The piper TTS models and any scheduler-state.duplicate.db are left
REM  untouched (only the scheduler-state.db files are removed).
REM  Location: <project-root>\scripts\dev-empty.bat
REM =====================================================================

REM ----------------------------- CONFIG --------------------------------
set "APP_IDENTIFIER=org.example.project.MainKt"
set "STATE_DIR=%USERPROFILE%\.omniapp"
set "DB=%STATE_DIR%\scheduler-state.db"
REM ---------------------------------------------------------------------

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
    exit /b 1
  )
  timeout /t 1 /nobreak >nul
  goto waitloop
)
echo       App is not running.

REM ---- [3/3] Delete the DB (and its WAL/SHM sidecars) -----------------
echo [3/3] Deleting "%DB%" ...
del /q "%DB%" "%DB%-wal" "%DB%-shm" 2>nul
if exist "%DB%" (
  echo       [!] Could not delete the DB - a file handle may still be open.
  exit /b 1
)
echo       Done - the next dev-restart.bat will start from an empty DB.

endlocal
