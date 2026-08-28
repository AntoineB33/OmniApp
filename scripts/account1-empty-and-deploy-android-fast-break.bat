@echo off
setlocal

REM =====================================================================
REM  account1-empty-and-deploy-android-fast-break.bat - the fast-break
REM  sibling of account1-empty-and-deploy-android.bat. It chains the SAME
REM  two account 1 scripts, in order:
REM    1. account1-empty.bat           (server logout + empty local&remote,
REM                                      NO desktop app opened)
REM    2. account1-deploy-android.bat  (build/install/launch debug APK on
REM                                      the phone, auto-signed-in as acc 1)
REM
REM  Same as account1-empty-open-and-deploy-android-fast-break.bat but
REM  WITHOUT opening the desktop app - just empty account 1, then deploy to
REM  the phone with the fast-break timing.
REM
REM  The fast-break override shrinks the 5-min screen break so the pause-cue
REM  can be tested in seconds. The 5-min break now LASTS 5 seconds and comes
REM  due 5 seconds after the previous qualifying pause, where "qualifying"
REM  means a >=2h no-screen period (the pose anchors only after a >=2h real
REM  pause, decoupled from its drawn length). The phone honors the matching
REM  omniapp_break_* launch extras (internal\deploy-android-debug.bat). The
REM  15-min pose and the 20-20-20 look-away keep production timings.
REM
REM  The break LENGTH must also be shrunk SERVER-side (step 2 below): since
REM  migration 20260728000000 the app publishes only WHEN each screen break
REM  comes due, so break_config.length_ms is what the Edge Function adds to
REM  the walk-away instant to time the cue. Without it the phone would stay
REM  silent for the default 5 minutes after the walk-away.
REM
REM  Meant to be double-clicked, not run from an already-open console: if
REM  either step fails, the window WAITS for the user to press Enter before
REM  it closes, so the error is readable instead of vanishing with the window.
REM  Location: <project-root>\scripts\account1-empty-and-deploy-android-fast-break.bat
REM =====================================================================

REM The 5-min-break timing overrides: the break LASTS 5 s, comes due 5 s after
REM the previous qualifying pause, and only a >=2h no-screen period qualifies as
REM that pause. account1-deploy-android.bat reads these env vars and forwards
REM them to the phone as am-start extras.
set "OMNIAPP_BREAK_DURATION_MS=20000"
set "OMNIAPP_BREAK_INTERVAL_MS=5000"

set "SCRIPT_DIR=%~dp0"

echo ==== [1/3] account1-empty.bat ====
call "%SCRIPT_DIR%account1-empty.bat"
if errorlevel 1 (
  echo.
  echo [x] account1-empty.bat failed - aborting before the Android deploy.
  echo.
  set /p "_WAIT=Press Enter to close... "
  endlocal
  exit /b 1
)

REM  account1-empty.bat runs inside its own setlocal, so its ACC1_* vars do not
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
echo ==== [2/3] server-side 5min_break length -^> %OMNIAPP_BREAK_DURATION_MS%ms ====
call "%SCRIPT_DIR%internal\set-break-length.bat" "%ACC1_USER%" "%ACC1_PASS%" "5min_break" "%OMNIAPP_BREAK_DURATION_MS%"

echo.
echo ==== [3/3] account1-deploy-android.bat (fast-break: 5-min break lasts 5s, due 5s after a ^>=2h pause) ====
call "%SCRIPT_DIR%account1-deploy-android.bat"
if errorlevel 1 (
  echo.
  echo [x] account1-deploy-android.bat failed.
  echo.
  set /p "_WAIT=Press Enter to close... "
  endlocal
  exit /b 1
)

echo.
echo [OK] account 1 emptied (local + remote) and deployed to Android (fast-break).
endlocal
exit /b 0
