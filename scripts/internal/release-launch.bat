@echo off
REM =====================================================================
REM  release-launch.bat - starts the INSTALLED release app with its own DB.
REM  Deployed by release-deploy.bat to the install root (next to "app\"),
REM  and pointed to by the Windows Startup shortcut. Location-independent:
REM  %~dp0 resolves to wherever this copy lives (the install root).
REM
REM  The release uses a dedicated state dir so it never shares a DB with the
REM  debug (~/.omniapp) or reset (~/.omniapp-reset) runs.
REM =====================================================================
set "OMNIAPP_STATE_DIR=%USERPROFILE%\.omniapp-release"
for %%F in ("%~dp0app\*.exe") do (
  start "" "%%~fF"
  goto :eof
)
echo [x] Release app not found under "%~dp0app". Run release-deploy.bat first.
