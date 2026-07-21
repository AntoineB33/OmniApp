@echo off
setlocal

REM =====================================================================
REM  account1-empty-and-open-and-deploy-android-fast-break.bat - the
REM  fast-break sibling of account1-empty-open-and-deploy-android.bat.
REM  It chains the SAME two account 1 scripts, in order:
REM    1. account1-empty-and-open.bat  (server logout + empty local&remote + open desktop app)
REM    2. account1-deploy-android.bat  (build/install/launch debug APK on phone as account 1)
REM
REM  The ONLY difference from account1-empty-open-and-deploy-android.bat is the
REM  5-min screen break's TIMING: this wrapper shrinks it so the pause-cue can be
REM  tested in seconds. The 5-min break now LASTS 5 seconds and comes due 5
REM  seconds after the previous qualifying pause, where "qualifying" means a
REM  >=2h no-screen period (the pose anchors only after a >=2h real pause,
REM  decoupled from its drawn length). All three knobs are forwarded to BOTH
REM  surfaces so they agree - the desktop gradle launch honors
REM  -Pomniapp.breakDurationMs / -Pomniapp.breakIntervalMs /
REM  -Pomniapp.breakPauseThresholdMs (account1-empty-and-open.bat forwards
REM  OMNIAPP_BREAK_DURATION_MS / OMNIAPP_BREAK_INTERVAL_MS /
REM  OMNIAPP_BREAK_PAUSE_THRESHOLD_MS when set), and the phone honors the
REM  matching omniapp_break_* launch extras (internal\deploy-android-debug.bat).
REM  The 15-min pose and the 20-20-20 look-away keep production timings. (For the
REM  real-time account-2 flavor, use account2-open-fast-break.)
REM
REM  Meant to be double-clicked, not run from an already-open console: if
REM  either step fails, the window WAITS for the user to press Enter before
REM  it closes, so the error is readable instead of vanishing with the window.
REM  Location: <project-root>\scripts\account1-empty-and-open-and-deploy-android-fast-break.bat
REM =====================================================================

REM The 5-min-break timing overrides: the break LASTS 5 s, comes due 5 s after
REM the previous qualifying pause, and only a >=2h no-screen period qualifies as
REM that pause. Both called scripts read these env vars (desktop -> gradle props,
REM phone -> am-start extras), so the two surfaces agree.
set "OMNIAPP_BREAK_DURATION_MS=5000"
set "OMNIAPP_BREAK_INTERVAL_MS=5000"
set "OMNIAPP_BREAK_PAUSE_THRESHOLD_MS=7200000"

set "SCRIPT_DIR=%~dp0"

echo ==== [1/2] account1-empty-and-open.bat (fast-break: 5-min break lasts 5s, due 5s after a ^>=2h pause) ====
call "%SCRIPT_DIR%account1-empty-and-open.bat"
if errorlevel 1 (
  echo.
  echo [x] account1-empty-and-open.bat failed - aborting before the Android deploy.
  echo.
  set /p "_WAIT=Press Enter to close... "
  endlocal
  exit /b 1
)

echo.
echo ==== [2/2] account1-deploy-android.bat (fast-break: 5-min break lasts 5s, due 5s after a ^>=2h pause) ====
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
echo [OK] account 1 emptied ^& opened on desktop (fast-break), and deployed to Android (fast-break).
endlocal
exit /b 0
