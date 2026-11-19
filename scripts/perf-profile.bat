@echo off
setlocal EnableDelayedExpansion

REM =====================================================================
REM  perf-profile.bat - launch the desktop app with the PERFORMANCE OVERLAY on
REM  (frame rate, process CPU/heap, and every instrumented derivation ranked by
REM  milliseconds spent per wall-clock second), against a chosen account.
REM
REM  Two rules this script exists to keep:
REM
REM   1. TIME SIMULATION IS OFF (-Pomniapp.timeSim=false, unlike the plain dev
REM      `run`). The sim clock ticks the now-line ~20x a second, which inflates
REM      every display-path cost by more than an order of magnitude over what a
REM      user ever pays. A profile taken under it measures the simulator.
REM
REM   2. IT NEVER TOUCHES THE RELEASE APP'S STATE DIR. CLAUDE.md: the deployed
REM      Windows app owns %USERPROFILE%\.omniapp-release and a dev build must
REM      never run against it. The default here is the account-2 dir; pass a
REM      different one as the first argument if you want another.
REM
REM  Usage:
REM    scripts\perf-profile.bat                 (account 2's data)
REM    scripts\perf-profile.bat %USERPROFILE%\.omniapp-perf   (a scratch dir)
REM
REM  Profile a REALISTIC account, never an emptied one (CLAUDE.md): an empty
REM  account hides every cost this tool exists to show.
REM
REM  In the app: "more" expands the panel, "reset" zeroes the counters so a
REM  measurement can be scoped to one gesture, "gc" takes a leak sample now,
REM  and "dump" writes the full report into diagnostics.log (which
REM  collect-diagnostics.bat then merges with the calendar-band timeline).
REM
REM  Location: <project-root>\scripts\perf-profile.bat
REM =====================================================================

set "SCRIPT_DIR=%~dp0"
set "STATE_DIR=%~1"
if "%STATE_DIR%"=="" set "STATE_DIR=%USERPROFILE%\.omniapp-acc2"

echo %STATE_DIR% | find /i ".omniapp-release" >nul
if not errorlevel 1 (
  echo [x] Refusing to profile against the RELEASE state dir - that is the live app's DB.
  echo     Pass a scratch dir instead, e.g. %USERPROFILE%\.omniapp-perf
  exit /b 1
)

pushd "%SCRIPT_DIR%.." || (echo [x] Could not enter project root.& exit /b 1)

REM Credentials are optional here: profiling an already-signed-in local state
REM dir needs no login, and a fresh scratch dir can be signed in by hand.
call "%SCRIPT_DIR%internal\load-accounts-env.bat" >nul 2>&1
set "LOGIN_ARGS="
if defined ACC2_USER (
  if /i "%STATE_DIR%"=="%USERPROFILE%\.omniapp-acc2" (
    set "LOGIN_ARGS=-Pomniapp.loginUser=%ACC2_USER% -Pomniapp.loginPass=%ACC2_PASS%"
  )
)

call "%SCRIPT_DIR%internal\kill-app-by-match.bat" "%STATE_DIR%"

echo [1/1] Launching with the perf overlay (state dir %STATE_DIR%, time-sim OFF)...
start "OmniApp perf" cmd /c ".\gradlew.bat :desktopApp:run -Pomniapp.perf=true -Pomniapp.timeSim=false -Pomniapp.stateDir=%STATE_DIR% !LOGIN_ARGS!"

popd
endlocal
