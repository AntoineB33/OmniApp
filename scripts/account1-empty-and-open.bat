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

where python >nul 2>nul || (echo [x] 'python' is not on PATH - cannot reach the remote DB.& popd & exit /b 1)

REM ---- [1/5] Tell the server to log out account 1's apps --------------
REM  Must run FIRST: it bumps the account_logout marker so any OTHER device still signed in as account 1
REM  signs itself out on its next reconcile (pushing nothing), instead of re-seeding the data we clear below.
echo [1/6] Signing out account 1's apps server-side...
python "%SCRIPT_DIR%internal\account_db_admin.py" logout "%ACC1_USER%" "%ACC1_PASS%"
set "LOGOUT_RC=%ERRORLEVEL%"
REM  Exit code 3 = the account_logout table is missing (schema behind). That is the ONE failure a deploy
REM  fixes, so auto-run deploy-supabase.bat and retry the logout once. Any other non-zero (e.g. a wrong
REM  password, code 1) must NOT trigger a deploy - abort instead.
if "%LOGOUT_RC%"=="3" (
  echo     [!] Schema out of date - running deploy-supabase.bat, then retrying the logout...
  call "%SCRIPT_DIR%deploy-supabase.bat" || (echo [x] deploy-supabase.bat failed - aborting before the wipe.& popd & exit /b 1)
  REM  --wait: the migration just created account_logout, but PostgREST returns PGRST205 until it reloads its
  REM  schema cache (a few seconds later). --wait polls through that lag instead of failing on the first try.
  python "%SCRIPT_DIR%internal\account_db_admin.py" logout "%ACC1_USER%" "%ACC1_PASS%" --wait
  if errorlevel 1 (echo [x] Remote logout still failing after deploy - aborting before the wipe.& popd & exit /b 1)
) else if not "%LOGOUT_RC%"=="0" (
  echo [x] Remote logout failed - aborting before the wipe.& popd & exit /b 1
)

REM ---- [2/5] Stop account 1's running instance (only) -----------------
call "%SCRIPT_DIR%internal\kill-app-by-match.bat" ".omniapp-acc1"

REM ---- [3/6] Empty the REMOTE data for account 1 ----------------------
echo [3/6] Emptying account 1's remote data...
python "%SCRIPT_DIR%internal\account_db_admin.py" empty "%ACC1_USER%" "%ACC1_PASS%"
if errorlevel 1 (echo [x] Remote empty failed - aborting before the app re-seeds it.& popd & exit /b 1)

REM ---- [4/6] Wipe the LOCAL DB for account 1 --------------------------
echo [4/6] Deleting local DB "%DB%" ...
del /q "%DB%" "%DB%-wal" "%DB%-shm" 2>nul

REM ---- [5/6] Launch logged in as account 1 ---------------------------
echo [5/6] Launching the app logged in as "%ACC1_USER%" (state dir %STATE_DIR%)...
start "OmniApp acc1" cmd /c "gradlew.bat :desktopApp:run -Pomniapp.stateDir=%STATE_DIR% -Pomniapp.loginUser=%ACC1_USER% -Pomniapp.loginPass=%ACC1_PASS%"

REM ---- [6/6] Build & deploy the debug APK signed in as account 1 -----
echo [6/6] Deploying the Android debug APK signed in as "%ACC1_USER%"...
call "%SCRIPT_DIR%account1-deploy-android.bat"

popd
endlocal
