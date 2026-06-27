@echo off
setlocal EnableDelayedExpansion

REM =====================================================================
REM  account1-empty-and-open.bat - empty account 1's synced data, then open
REM  the desktop app already logged in as account 1.
REM
REM  "Empty" = local + remote (the user's choice): the account's remote
REM  Supabase snapshot (and presence rows) are deleted AND the local DB for
REM  account 1's isolated state dir is wiped, so the app opens truly empty
REM  instead of re-pulling the old cloud data on first sync.
REM
REM  Account 1 uses its own state dir (%USERPROFILE%\.omniapp-acc1) so it
REM  can run alongside account 2 without sharing a DB. Credentials come from
REM  scripts/accounts.env (gitignored; see accounts.env.example).
REM
REM  NOTE: the dev `run` launch passes the password as a -P/-D property, so
REM  it is visible in the process command line. Fine for a personal dev tool.
REM  Location: <project-root>\scripts\account1-empty-and-open.bat
REM =====================================================================

set "SCRIPT_DIR=%~dp0"
set "STATE_DIR=%USERPROFILE%\.omniapp-acc1"
set "DB=%STATE_DIR%\scheduler-state.db"

call "%SCRIPT_DIR%internal\load-accounts-env.bat" || exit /b 1
if not defined ACC1_USER (echo [x] ACC1_USER/ACC1_PASS missing from accounts.env.& exit /b 1)

pushd "%SCRIPT_DIR%.." || (echo [x] Could not enter project root.& exit /b 1)

REM ---- [1/4] Stop account 1's running instance (only) -----------------
call "%SCRIPT_DIR%internal\kill-app-by-match.bat" ".omniapp-acc1"

REM ---- [2/4] Empty the REMOTE data for account 1 ----------------------
echo [2/4] Emptying account 1's remote data...
where python >nul 2>nul || (echo [x] 'python' is not on PATH - cannot empty the remote DB.& popd & exit /b 1)
python "%SCRIPT_DIR%internal\account_db_admin.py" empty "%ACC1_USER%" "%ACC1_PASS%"
if errorlevel 1 (echo [x] Remote empty failed - aborting before the app re-seeds it.& popd & exit /b 1)

REM ---- [3/4] Wipe the LOCAL DB for account 1 --------------------------
echo [3/4] Deleting local DB "%DB%" ...
del /q "%DB%" "%DB%-wal" "%DB%-shm" 2>nul

REM ---- [4/4] Launch logged in as account 1 ---------------------------
echo [4/4] Launching the app logged in as "%ACC1_USER%" (state dir %STATE_DIR%)...
start "OmniApp acc1" cmd /c "gradlew.bat :desktopApp:run -Pomniapp.stateDir=%STATE_DIR% -Pomniapp.loginUser=%ACC1_USER% -Pomniapp.loginPass=%ACC1_PASS%"

popd
endlocal
