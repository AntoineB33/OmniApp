@echo off
setlocal EnableDelayedExpansion

REM =====================================================================
REM  account2-open.bat - open the desktop app already logged in as account 2,
REM  PRESERVING its data (no empty). Account 2 uses its own state dir
REM  (%USERPROFILE%\.omniapp-acc2) so it can run alongside account 1.
REM  Credentials come from scripts/accounts.env (gitignored).
REM
REM  NOTE: the dev `run` launch passes the password as a -P/-D property, so
REM  it is visible in the process command line. Fine for a personal dev tool.
REM  Location: <project-root>\scripts\account2-open.bat
REM =====================================================================

set "SCRIPT_DIR=%~dp0"
set "STATE_DIR=%USERPROFILE%\.omniapp-acc2"

call "%SCRIPT_DIR%internal\load-accounts-env.bat" || exit /b 1
if not defined ACC2_USER (echo [x] ACC2_USER/ACC2_PASS missing from accounts.env.& exit /b 1)

pushd "%SCRIPT_DIR%.." || (echo [x] Could not enter project root.& exit /b 1)

REM ---- [1/2] Stop account 2's running instance (only) -----------------
call "%SCRIPT_DIR%internal\kill-app-by-match.bat" ".omniapp-acc2"

REM ---- [2/2] Launch logged in as account 2 ---------------------------
echo [2/2] Launching the app logged in as "%ACC2_USER%" (state dir %STATE_DIR%)...
start "OmniApp acc2" cmd /c "gradlew.bat :desktopApp:run -Pomniapp.stateDir=%STATE_DIR% -Pomniapp.loginUser=%ACC2_USER% -Pomniapp.loginPass=%ACC2_PASS%"

popd
endlocal
