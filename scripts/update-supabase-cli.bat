@echo off
setlocal EnableDelayedExpansion

REM =====================================================================
REM  update-supabase-cli.bat - update the Supabase CLI *that PATH actually
REM  resolves to*, whichever way it was installed:
REM    - npm global (%APPDATA%\npm)      -> npm install -g supabase@latest
REM    - standalone (%LOCALAPPDATA%\supabase\bin) -> latest GitHub release
REM  It then re-resolves PATH and fails loudly if the copy it updated is
REM  NOT the one `supabase` runs (two installs, first one wins) - that
REM  mismatch is what made deploy-supabase.bat keep warning about an old
REM  CLI after a "successful" update.
REM
REM  Updates the CLI TOOL only - it does not touch your database.
REM
REM  Usage:  update-supabase-cli.bat          (skip if already latest)
REM          update-supabase-cli.bat -Force   (reinstall regardless)
REM  Location: <project-root>\scripts\update-supabase-cli.bat
REM =====================================================================

set "SCRIPT_DIR=%~dp0"

REM ---- Keep the window open on failure when double-clicked -----------
REM  Only pause when the console would otherwise vanish, i.e. when this .bat
REM  is its own cmd /c command line (Explorer double-click). From an existing
REM  terminal / another script / CI the output stays visible anyway.
REM  Set OMNIAPP_NO_PAUSE=1 to force the non-pausing behavior.
REM  (substring test, NOT `echo !cmdcmdline! | find ...`: the left side of a pipe
REM   runs in a child cmd that has delayed expansion OFF, so !cmdcmdline! would
REM   stay literal there and the detection would never match.)
set "PAUSE_ON_FAIL="
set "OMNI_CMDLINE=!cmdcmdline!"
if defined OMNI_CMDLINE if not "!OMNI_CMDLINE:%~nx0=!"=="!OMNI_CMDLINE!" set "PAUSE_ON_FAIL=1"
if defined OMNIAPP_NO_PAUSE set "PAUSE_ON_FAIL="

where powershell >nul 2>nul || (echo [x] Windows PowerShell not found on PATH.& goto :fail)
powershell -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT_DIR%internal\update-supabase-cli.ps1" %*
set "RC=%errorlevel%"
if not "%RC%"=="0" goto :fail

endlocal
exit /b 0

REM ---- Failure handler: keep the error readable -----------------------
:fail
if not defined RC set "RC=1"
echo.
echo ======================================================================
echo  [x] update-supabase-cli.bat FAILED ^(exit code !RC!^) - see above.
echo ======================================================================
if defined PAUSE_ON_FAIL (
  echo.
  echo  To copy the error: drag-select the text with the mouse, then press
  echo  Enter ^(console QuickEdit^) or Ctrl+Shift+C ^(Windows Terminal^).
  echo.
  pause
)
endlocal & exit /b %RC%
