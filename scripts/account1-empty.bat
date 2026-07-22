@echo off
setlocal EnableDelayedExpansion

REM =====================================================================
REM  account1-empty.bat - empty account 1's synced data (local + remote),
REM  leaving it empty. Does NOT relaunch anything - mirrors
REM  account2-empty.bat, but keeps account 1's server-logout-FIRST step.
REM
REM  Order matters:
REM    1. log out every app signed in as account 1 (bumps the server-side
REM       account_logout marker) so any OTHER still-running app on the
REM       account signs itself out on its next reconcile and can't re-seed
REM       the data we clear below,
REM    2. empty account 1's REMOTE Supabase snapshot + session/presence rows,
REM    3. wipe the LOCAL DB under %USERPROFILE%\.omniapp-acc1 so the next
REM       launch starts empty instead of re-pulling the old cloud data.
REM
REM  Run account1-deploy-android.bat (or a desktop open script) next.
REM  Credentials come from scripts/accounts.env (gitignored).
REM  Location: <project-root>\scripts\account1-empty.bat
REM =====================================================================

set "SCRIPT_DIR=%~dp0"
set "STATE_DIR=%USERPROFILE%\.omniapp-acc1"
set "DB=%STATE_DIR%\scheduler-state.db"

call "%SCRIPT_DIR%internal\load-accounts-env.bat" || exit /b 1
if not defined ACC1_USER (echo [x] ACC1_USER/ACC1_PASS missing from accounts.env.& exit /b 1)

where python >nul 2>nul || (echo [x] 'python' is not on PATH - cannot reach the remote DB.& exit /b 1)

REM Diagnostics timeline (scripts/collect-diagnostics.bat): stamp the script's steps into the same
REM per-account log the desktop/Android app appends to. The empty below deletes only
REM scheduler-state.db*, never this log.
call :diag "account1-empty: START"

REM ---- [1/4] Tell the server to log out account 1's apps --------------
REM  Must run FIRST: it bumps the account_logout marker so any OTHER device still signed in as account 1
REM  signs itself out on its next reconcile (pushing nothing), instead of re-seeding the data we clear below.
echo [1/4] Signing out account 1's apps server-side...
python "%SCRIPT_DIR%internal\account_db_admin.py" logout "%ACC1_USER%" "%ACC1_PASS%"
set "LOGOUT_RC=%ERRORLEVEL%"
REM  Exit code 3 = the account_logout table is missing (schema behind). That is the ONE failure a deploy
REM  fixes, so auto-run deploy-supabase.bat and retry the logout once. Any other non-zero (e.g. a wrong
REM  password, code 1) must NOT trigger a deploy - abort instead.
if "%LOGOUT_RC%"=="3" (
  echo     [!] Schema out of date - running deploy-supabase.bat, then retrying the logout...
  call "%SCRIPT_DIR%deploy-supabase.bat" || (echo [x] deploy-supabase.bat failed - aborting before the wipe.& exit /b 1)
  REM  --wait: the migration just created account_logout, but PostgREST returns PGRST205 until it reloads its
  REM  schema cache (a few seconds later). --wait polls through that lag instead of failing on the first try.
  python "%SCRIPT_DIR%internal\account_db_admin.py" logout "%ACC1_USER%" "%ACC1_PASS%" --wait
  if errorlevel 1 (echo [x] Remote logout still failing after deploy - aborting before the wipe.& exit /b 1)
) else if not "%LOGOUT_RC%"=="0" (
  echo [x] Remote logout failed - aborting before the wipe.& exit /b 1
)

REM ---- [2/4] Stop account 1's running instance (only) -----------------
call "%SCRIPT_DIR%internal\kill-app-by-match.bat" ".omniapp-acc1"

REM Kill any previous spawned console window that might be stuck
taskkill /F /FI "WINDOWTITLE eq OmniApp acc1*" /T >nul 2>nul

REM ---- [3/4] Empty the REMOTE data for account 1 ----------------------
echo [3/4] Emptying account 1's remote data...
python "%SCRIPT_DIR%internal\account_db_admin.py" empty "%ACC1_USER%" "%ACC1_PASS%"
if errorlevel 1 (echo [x] Remote empty failed - aborting before the app re-seeds it.& exit /b 1)
call :diag "remote data emptied (snapshot/presence/sleep-gaps/active-sessions)"

REM ---- [4/4] Wipe the LOCAL DB for account 1 --------------------------
echo [4/4] Deleting local DB "%DB%" ...
del /q "%DB%" "%DB%-wal" "%DB%-shm" 2>nul
call :diag "account1-empty: DONE (local DB wiped)"
echo       Done - account 1 is empty (local + remote).

endlocal
goto :eof

REM Append a wall-clock-stamped [script] marker to the acc1 diagnostics timeline. Timestamp format must
REM stay "yyyy-MM-dd HH:mm:ss.fff" - scripts/internal/merge_diagnostics.py sorts the merged timeline on it.
:diag
if not exist "%STATE_DIR%" mkdir "%STATE_DIR%" >nul 2>nul
for /f "usebackq delims=" %%t in (`powershell -NoProfile -Command "Get-Date -Format 'yyyy-MM-dd HH:mm:ss.fff'"`) do set "DIAG_TS=%%t"
>> "%STATE_DIR%\diagnostics.log" echo %DIAG_TS% [script] %~1
exit /b 0
