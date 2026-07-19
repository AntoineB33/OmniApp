@echo off
setlocal EnableDelayedExpansion

REM =====================================================================
REM  Kill any previous instance of THIS script that is still running
REM  (e.g. stuck at a prompt or waiting on a hung child process).
REM
REM  We do NOT use `taskkill /FI "WINDOWTITLE eq ..."`: the window-title
REM  filter is unreliable - under Windows Terminal it can't read the tab
REM  title, and any child process that retitled the console breaks the
REM  match - so the old stuck cmd.exe used to survive. Instead each run
REM  records its own cmd.exe PID in a lock file and the next run kills that
REM  PID (and its child tree) directly. The `IMAGENAME eq cmd.exe` guard
REM  keeps a recycled PID from taking out an unrelated process.
REM =====================================================================
set "LOCK=%TEMP%\omniapp-account1-empty-and-open.pid"
if exist "%LOCK%" (
  set "OLDPID="
  set /p OLDPID=<"%LOCK%"
  if defined OLDPID taskkill /F /T /FI "PID eq !OLDPID!" /FI "IMAGENAME eq cmd.exe" >nul 2>nul
)
REM Record THIS run's own cmd.exe PID so the next run can kill it. We launch PowerShell
REM DIRECTLY (not via `for /f`, which runs it under a throwaway `cmd.exe /c` whose PID dies
REM instantly - the reason the first attempt never killed anything) so PowerShell's parent
REM IS this script's cmd.exe; PowerShell writes that PID to the lock file itself.
REM Get-CimInstance, not WMIC (removed on Win11 24H2+).
powershell -NoProfile -Command "$pp=(Get-CimInstance Win32_Process -Filter ('ProcessId='+$PID)).ParentProcessId; Set-Content -LiteralPath '%LOCK%' -Value $pp -Encoding ASCII" >nul 2>nul
title OmniApp_Account1_Empty_And_Open

REM =====================================================================
REM  account1-empty-and-open.bat - three things, in order:
REM    1. log out every app signed in as account 1 (server-side marker),
REM    2. empty account 1's data, local AND remote,
REM    3. open the desktop app logged in as account 1.
REM  Nothing else - the Android debug deploy is a separate script
REM  (account1-deploy-android.bat), run it yourself when a phone is needed.
REM
REM  "Empty" = local + remote (the user's choice): the account's remote
REM  Supabase snapshot (and session rows) are deleted AND the local DB for
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

REM Diagnostics timeline (scripts/collect-diagnostics.bat): stamp the script's steps into the same
REM per-account log the desktop app appends to, so "script ran -> app opened -> calendar showed X"
REM reads as one chronology. The empty below deletes only scheduler-state.db*, never this log.
call :diag "account1-empty-and-open: START"

REM ---- [1/5] Tell the server to log out account 1's apps --------------
REM  Must run FIRST: it bumps the account_logout marker so any OTHER device still signed in as account 1
REM  signs itself out on its next reconcile (pushing nothing), instead of re-seeding the data we clear below.
echo [1/5] Signing out account 1's apps server-side...
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

REM Kill any previous spawned console window that might be stuck
taskkill /F /FI "WINDOWTITLE eq OmniApp acc1*" /T >nul 2>nul

REM ---- [3/5] Empty the REMOTE data for account 1 ----------------------
echo [3/5] Emptying account 1's remote data...
python "%SCRIPT_DIR%internal\account_db_admin.py" empty "%ACC1_USER%" "%ACC1_PASS%"
if errorlevel 1 (echo [x] Remote empty failed - aborting before the app re-seeds it.& popd & exit /b 1)
call :diag "remote data emptied (snapshot/presence/sleep-gaps/active-sessions)"

REM ---- [4/5] Wipe the LOCAL DB for account 1 --------------------------
echo [4/5] Deleting local DB "%DB%" ...
del /q "%DB%" "%DB%-wal" "%DB%-shm" 2>nul

REM ---- [5/5] Launch the desktop app logged in as account 1 -----------
echo [5/5] Launching the desktop app logged in as "%ACC1_USER%" (state dir %STATE_DIR%)...
call :diag "local DB wiped; launching desktop app"
start "OmniApp acc1" cmd /c "gradlew.bat :desktopApp:run -Pomniapp.stateDir=%STATE_DIR% -Pomniapp.loginUser=%ACC1_USER% -Pomniapp.loginPass=%ACC1_PASS%"
call :diag "account1-empty-and-open: DONE (desktop launch dispatched)"

REM Clean completion: drop our PID lock so the next run doesn't target a dead/recycled PID.
del /q "%LOCK%" 2>nul

popd
endlocal
goto :eof

REM Append a wall-clock-stamped [script] marker to the acc1 diagnostics timeline. Timestamp format must
REM stay "yyyy-MM-dd HH:mm:ss.fff" - scripts/internal/merge_diagnostics.py sorts the merged timeline on it.
:diag
if not exist "%STATE_DIR%" mkdir "%STATE_DIR%" >nul 2>nul
for /f "usebackq delims=" %%t in (`powershell -NoProfile -Command "Get-Date -Format 'yyyy-MM-dd HH:mm:ss.fff'"`) do set "DIAG_TS=%%t"
>> "%STATE_DIR%\diagnostics.log" echo %DIAG_TS% [script] %~1
exit /b 0