@echo off
setlocal EnableDelayedExpansion
REM =====================================================================
REM  release-launch-acc3.bat - starts the INSTALLED release app for account 3
REM  with its own DB and auto-login credentials. Deployed by
REM  account3-deploy-windows.bat to the install root (next to "app\") and
REM  pointed to by the Windows Startup shortcut. Location-independent:
REM  %~dp0 resolves to wherever this copy lives (the install root).
REM
REM  The state dir is the dedicated release DB. The login credentials are
REM  read from acc3.cred (written next to this file by the deploy script and
REM  NOT committed) and exported as env vars the app reads on launch; the
REM  packaged exe inherits this environment. After the first successful login
REM  the session is cached in the DB, so later auto-starts work even if
REM  acc3.cred is removed.
REM =====================================================================
set "OMNIAPP_STATE_DIR=%USERPROFILE%\.omniapp-release"
if exist "%~dp0acc3.cred" (
  for /f "usebackq eol=# tokens=1,* delims==" %%K in ("%~dp0acc3.cred") do (
    if not "%%K"=="" set "%%K=%%L"
  )
)
set "APP_EXE="
for %%F in ("%~dp0app\*.exe") do (
  if not defined APP_EXE (
    set "APP_EXE=%%~fF"
    set "APP_EXE_NAME=%%~nxF"
  )
)
if not defined APP_EXE (
  echo [x] Release app not found under "%~dp0app". Run account3-deploy-windows.bat first.
  goto :eof
)

REM Prevent duplicate release instances from sharing the same state DB and tripping SQLite locks.
REM taskkill only ASKS Windows to end the process: it returns immediately, while the dying instance still
REM holds its SQLite file lock for a moment. Starting the replacement inside that window is a real race -
REM the new instance runs its schema create/migrate (a WRITE transaction) against a still-locked file and
REM dies at startup with "[SQLITE_BUSY] The database file is locked", which the packaged launcher shows
REM as a fatal "Error" box. So WAIT for the image to actually leave the process list before starting.
taskkill /F /IM "!APP_EXE_NAME!" /T >nul 2>&1
for /l %%N in (1,1,30) do (
  tasklist /FI "IMAGENAME eq !APP_EXE_NAME!" 2>nul | find /i "!APP_EXE_NAME!" >nul || goto :launch
  ping -n 2 127.0.0.1 >nul
)
echo [warn] "!APP_EXE_NAME!" is still running after 30s - starting anyway.

:launch
start "" "!APP_EXE!"
