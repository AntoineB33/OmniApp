@echo off
setlocal EnableDelayedExpansion

REM =====================================================================
REM  account2-open-fast-break.bat - open the desktop app logged in as
REM  account 2 (the scratch / "guest" account, data PRESERVED) at REAL
REM  time, with the 5-min screen break shrunk so the pause-cue voice
REM  message can be tested on a phone in seconds.
REM
REM  Usage:  account2-open-fast-break.bat [DURATION_S] [INTERVAL_S]
REM    DURATION_S  length of the 5-min break              (default 5 seconds)
REM    INTERVAL_S  gap after the previous >=5-min pause    (default 5 seconds)
REM
REM  With both at 5s the now-line reaches a 5-min break almost immediately:
REM  put the computer to sleep and ~DURATION_S later the phone speaks the
REM  break-end cue. The tick_pause_cues() cron fires it from the client's
REM  device_break row, which since migration 20260728000000 holds only WHEN
REM  each break comes due - the LENGTH is the server's, from break_config.
REM  So this script also upserts break_config.length_ms for 5min_break to
REM  DURATION_S (step 1 below); still NO Supabase redeploy needed, it is a
REM  plain authenticated HTTP upsert.
REM
REM  Runs at REAL time (omniapp.timeSim=false) so the seconds are real, matching
REM  the wall-clock listener/Edge Function (a sim-accelerated clock does NOT
REM  speed up the server). Only the 5-min pose is retimed; the 15-min pose and
REM  the 20-20-20 look-away keep production timings. For a clean pose anchor
REM  (first break ~INTERVAL_S after launch), run account2-empty.bat first.
REM  Credentials come from scripts/accounts.env (gitignored).
REM  Location: <project-root>\scripts\account2-open-fast-break.bat
REM =====================================================================

set "SCRIPT_DIR=%~dp0"
set "STATE_DIR=%USERPROFILE%\.omniapp-acc2"

set "DURATION_S=%~1"
if not defined DURATION_S set "DURATION_S=5"
set "INTERVAL_S=%~2"
if not defined INTERVAL_S set "INTERVAL_S=5"

set /a "DURATION_MS=%DURATION_S%*1000" 2>nul
set /a "INTERVAL_MS=%INTERVAL_S%*1000" 2>nul
if not defined DURATION_MS (echo [x] DURATION_S must be a whole number of seconds.& exit /b 1)
if not defined INTERVAL_MS (echo [x] INTERVAL_S must be a whole number of seconds.& exit /b 1)

call "%SCRIPT_DIR%internal\load-accounts-env.bat" || exit /b 1
if not defined ACC2_USER (echo [x] ACC2_USER/ACC2_PASS missing from accounts.env.& exit /b 1)

pushd "%SCRIPT_DIR%.." || (echo [x] Could not enter project root.& exit /b 1)

REM ---- [1/3] Shrink the SERVER's idea of the 5-min break's length ------
REM  The client publishes only WHEN the break is due; break_config.length_ms is
REM  what the Edge Function adds to the walk-away instant to time the cue.
echo [1/3] Setting the server-side 5min_break length to %DURATION_S%s...
call "%SCRIPT_DIR%internal\set-break-length.bat" "%ACC2_USER%" "%ACC2_PASS%" "5min_break" "%DURATION_MS%"

REM ---- [2/3] Stop account 2's running instance (only) -----------------
call "%SCRIPT_DIR%internal\kill-app-by-match.bat" ".omniapp-acc2"

REM ---- [3/3] Launch logged in as account 2 with the fast 5-min break --
echo [3/3] Launching as "%ACC2_USER%" (state %STATE_DIR%) - 5-min break: %DURATION_S%s long, due %INTERVAL_S%s after a pause...
start "OmniApp acc2 fast-break" cmd /c "gradlew.bat :desktopApp:run -Pomniapp.stateDir=%STATE_DIR% -Pomniapp.loginUser=%ACC2_USER% -Pomniapp.loginPass=%ACC2_PASS% -Pomniapp.timeSim=false -Pomniapp.breakDurationMs=%DURATION_MS% -Pomniapp.breakIntervalMs=%INTERVAL_MS%"

popd
endlocal
