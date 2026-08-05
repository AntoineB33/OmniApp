@echo off
setlocal

REM =====================================================================
REM  account1-empty-open-fast-break.bat - account1-empty-and-open.bat with
REM  the SCREEN BREAKS retimed, and NOTHING else: no Android deploy. It is
REM  account1-empty-open-and-deploy-android-fast-break.bat minus its phone
REM  step, for when only the desktop app is being looked at.
REM
REM  ALL THREE SCREEN BREAKS ARE TWEAKABLE HERE - the 20-20-20 look-away, the
REM  5-min pose and the 15-min pose - each through the same three rules:
REM
REM    DURATION_MS         how long the break LASTS (its drawn length)
REM    INTERVAL_MS         how long after the previous qualifying pause it
REM                        comes DUE
REM    PAUSE_THRESHOLD_MS  how long a real pause must be to ANCHOR it. 0 means
REM                        "as long as the break itself" (the production rule);
REM                        setting it DECOUPLES the trigger from the drawn
REM                        length, e.g. a 5-second break placed only after a
REM                        >=2h pause.
REM
REM  Edit the nine `set` lines below; each is passed straight through to the
REM  desktop app (account1-empty-and-open.bat turns them into
REM  -Pomniapp.break.<lookAway|pose5|pose15>.<durationMs|intervalMs|pauseThresholdMs>).
REM  The defaults below are the PRODUCTION timings except for the 5-min pose,
REM  which arrives fast-break-shaped (5 s long, due 5 s after a >=2h pause) as
REM  in the Android wrappers. Comment a line out to leave that rule alone.
REM
REM  A note on what a short INTERVAL does, because the two shapes behave very
REM  differently: with PAUSE_THRESHOLD_MS at 0 the break is *coupled* (a 5 s
REM  break is itself a qualifying 5 s pause), so it grid-recurs and the dense
REM  cap holds it to one at a time; with a threshold far above the duration it
REM  is *decoupled* and CANNOT self-recur - it appears exactly INTERVAL_MS
REM  after each qualifying pause, which on a freshly-emptied account means one
REM  break at the now-line plus one per night's wake, not a day full of them.
REM
REM  The two POSES' lengths are also shrunk SERVER-side (step 2 below): since
REM  migration 20260728000000 the apps publish only WHEN each screen break
REM  comes due, so break_config.length_ms is what the Edge Function adds to
REM  the walk-away instant to time the pushed cue. Harmless with no phone
REM  attached, and it keeps this script's account state consistent with the
REM  Android flavor - a later account1-deploy-android.bat then needs no fixup.
REM  (The look-away has no server row: it is served locally and never pushed.)
REM
REM  NOTE: the desktop launch keeps gradle `run`'s default omniapp.timeSim=true,
REM  so the now-line is sim-accelerated (unlike account2-open-fast-break.bat,
REM  which runs at real time because it targets a real phone's wall clock).
REM
REM  Meant to be double-clicked, not run from an already-open console: if a
REM  step fails, the window WAITS for the user to press Enter before it
REM  closes, so the error is readable instead of vanishing with the window.
REM  Location: <project-root>\scripts\account1-empty-open-fast-break.bat
REM =====================================================================

REM ---- THE BREAK RULES - tweak these nine lines ------------------------
REM  Handy reference values, in ms: 1s=1000  20s=20000  1min=60000
REM  5min=300000  15min=900000  20min=1200000  1h=3600000  2h=7200000

REM  The 20-20-20 look-away (production: 20 s long, due 20 min after a rest that
REM  served it). Three things serve it: a look-away break that happened, a 5-min
REM  pose break that happened, or a real no-screen pause of at least the
REM  threshold - 15 min, the 15-min pose's own length, so that pose serves it
REM  through the plain pause rule. A brief step away no longer restarts it.
set "OMNIAPP_LOOKAWAY_DURATION_MS=20000"
set "OMNIAPP_LOOKAWAY_INTERVAL_MS=5000"
set "OMNIAPP_LOOKAWAY_PAUSE_THRESHOLD_MS=5000"

REM  The 5-min pose (production: 5 min long, due 1 h after a >=5-min pause).
set "OMNIAPP_POSE5_DURATION_MS=5000"
set "OMNIAPP_POSE5_INTERVAL_MS=3600000"
set "OMNIAPP_POSE5_PAUSE_THRESHOLD_MS=0"

REM  The 15-min pose (production: 15 min long, due 2 h after a >=15-min pause).
set "OMNIAPP_POSE15_DURATION_MS=900000"
set "OMNIAPP_POSE15_INTERVAL_MS=7200000"
set "OMNIAPP_POSE15_PAUSE_THRESHOLD_MS=0"

set "SCRIPT_DIR=%~dp0"

echo ==== [1/2] account1-empty-and-open.bat ====
echo      look-away: %OMNIAPP_LOOKAWAY_DURATION_MS%ms long, due %OMNIAPP_LOOKAWAY_INTERVAL_MS%ms after a pause (threshold %OMNIAPP_LOOKAWAY_PAUSE_THRESHOLD_MS%ms)
echo      5-min pose: %OMNIAPP_POSE5_DURATION_MS%ms long, due %OMNIAPP_POSE5_INTERVAL_MS%ms after a pause (threshold %OMNIAPP_POSE5_PAUSE_THRESHOLD_MS%ms)
echo      15-min pose: %OMNIAPP_POSE15_DURATION_MS%ms long, due %OMNIAPP_POSE15_INTERVAL_MS%ms after a pause (threshold %OMNIAPP_POSE15_PAUSE_THRESHOLD_MS%ms)
call "%SCRIPT_DIR%account1-empty-and-open.bat"
if errorlevel 1 (
  echo.
  echo [x] account1-empty-and-open.bat failed.
  echo.
  set /p "_WAIT=Press Enter to close... "
  endlocal
  exit /b 1
)

REM  The called script runs inside its own setlocal, so its ACC1_* vars do not
REM  reach us - load accounts.env here too (load-accounts-env.bat has no setlocal).
call "%SCRIPT_DIR%internal\load-accounts-env.bat" || (
  echo.
  echo [x] Could not load scripts\accounts.env.
  echo.
  set /p "_WAIT=Press Enter to close... "
  endlocal
  exit /b 1
)

echo.
echo ==== [2/2] server-side pose lengths -^> 5min_break %OMNIAPP_POSE5_DURATION_MS%ms, 15min_break %OMNIAPP_POSE15_DURATION_MS%ms ====
call "%SCRIPT_DIR%internal\set-break-length.bat" "%ACC1_USER%" "%ACC1_PASS%" "5min_break" "%OMNIAPP_POSE5_DURATION_MS%"
call "%SCRIPT_DIR%internal\set-break-length.bat" "%ACC1_USER%" "%ACC1_PASS%" "15min_break" "%OMNIAPP_POSE15_DURATION_MS%"

echo.
echo [OK] account 1 emptied ^& opened on desktop with the retimed screen breaks.
endlocal
exit /b 0
