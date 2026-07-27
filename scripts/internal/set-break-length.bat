@echo off
setlocal

REM =====================================================================
REM  set-break-length.bat <user> <pass> <break_kind> <ms|default>
REM
REM  Sets the SERVER-side length of one screen break - the delay between
REM  the walk-away instant and the pushed "your pause is over" cue - by
REM  upserting break_config.length_ms for the account.
REM
REM  WHY THE FAST-BREAK SCRIPTS NEED THIS. Since migration
REM  20260728000000 the client's device_break row carries only WHEN each
REM  screen break comes due (the account plus the two due instants), never
REM  how long it lasts: the length is a property of the break KIND, which
REM  the server owns. So OMNIAPP_BREAK_DURATION_MS / -Pomniapp.breakDurationMs
REM  now only shrink the break the APP draws and speaks locally; without
REM  this call the server would still wait the kind's default 5 minutes
REM  before pushing the cue, and a "5-second break" test would look broken.
REM
REM  Best-effort by design: a failure here is printed and ignored, because
REM  it only affects a debug convenience (the deploy itself must not fail
REM  because the operator is offline or the schema is behind).
REM  Location: <project-root>\scripts\internal\set-break-length.bat
REM =====================================================================

if "%~4"=="" (echo [x] usage: set-break-length.bat ^<user^> ^<pass^> ^<break_kind^> ^<ms^|default^>& exit /b 1)

where python >nul 2>nul || (
  echo     [!] 'python' is not on PATH - skipping the server-side break length.
  exit /b 0
)

python "%~dp0account_db_admin.py" break-length "%~1" "%~2" "%~3" "%~4"
if errorlevel 1 echo     [!] Could not set the server-side break length - the pushed cue will use the default.

endlocal
exit /b 0
