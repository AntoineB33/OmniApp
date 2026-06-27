@echo off
setlocal EnableDelayedExpansion

REM =====================================================================
REM  account2-empty.bat - empty account 2's synced data (local + remote),
REM  leaving it empty. Does NOT relaunch (mirrors dev-empty.bat) - run
REM  account2-open.bat next to open it again.
REM
REM  Deletes the account's remote Supabase snapshot + presence rows AND the
REM  local DB under %USERPROFILE%\.omniapp-acc2, so the next launch starts
REM  empty rather than re-pulling the old cloud data.
REM  Credentials come from scripts/accounts.env (gitignored).
REM  Location: <project-root>\scripts\account2-empty.bat
REM =====================================================================

set "SCRIPT_DIR=%~dp0"
set "STATE_DIR=%USERPROFILE%\.omniapp-acc2"
set "DB=%STATE_DIR%\scheduler-state.db"

call "%SCRIPT_DIR%internal\load-accounts-env.bat" || exit /b 1
if not defined ACC2_USER (echo [x] ACC2_USER/ACC2_PASS missing from accounts.env.& exit /b 1)

REM ---- [1/3] Stop account 2's running instance (only) -----------------
call "%SCRIPT_DIR%internal\kill-app-by-match.bat" ".omniapp-acc2"

REM ---- [2/3] Empty the REMOTE data for account 2 ----------------------
echo [2/3] Emptying account 2's remote data...
where python >nul 2>nul || (echo [x] 'python' is not on PATH - cannot empty the remote DB.& exit /b 1)
python "%SCRIPT_DIR%internal\account_db_admin.py" empty "%ACC2_USER%" "%ACC2_PASS%"
if errorlevel 1 (echo [x] Remote empty failed.& exit /b 1)

REM ---- [3/3] Wipe the LOCAL DB for account 2 --------------------------
echo [3/3] Deleting local DB "%DB%" ...
del /q "%DB%" "%DB%-wal" "%DB%-shm" 2>nul
echo       Done - run account2-open.bat to reopen account 2 (empty).

endlocal
