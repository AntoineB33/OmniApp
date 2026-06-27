@echo off
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
for %%F in ("%~dp0app\*.exe") do (
  start "" "%%~fF"
  goto :eof
)
echo [x] Release app not found under "%~dp0app". Run account3-deploy-windows.bat first.
