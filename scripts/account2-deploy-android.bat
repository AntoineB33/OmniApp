@echo off
REM DisableDelayedExpansion (not Enable): credentials from accounts.env may contain '!', which
REM EnableDelayedExpansion silently eats. This script uses no !var! expansion.
setlocal DisableDelayedExpansion

REM =====================================================================
REM  account2-deploy-android.bat - build a DEBUG APK, install it on the
REM  connected phone over adb, wipe its local data, and launch it
REM  auto-signed-in as ACCOUNT 2.
REM
REM  For on-device testing (e.g. exercising the pause-cue Edge Function
REM  against a real phone). Accounts 1 and 2 share the one app install, so
REM  each deploy wipes local data and re-signs in as its account - the
REM  Android analog of the desktop scripts' separate per-account state dirs.
REM  Remote Supabase data is preserved. Credentials come from
REM  scripts/accounts.env (gitignored; see accounts.env.example).
REM
REM  Debug build => no signing keystore needed, but a different signature
REM  than the account3 release build (see internal\deploy-android-debug.bat).
REM  Location: <project-root>\scripts\account2-deploy-android.bat
REM =====================================================================

set "SCRIPT_DIR=%~dp0"
call "%SCRIPT_DIR%internal\load-accounts-env.bat" || exit /b 1
if not defined ACC2_USER (echo [x] ACC2_USER/ACC2_PASS missing from accounts.env.& exit /b 1)

pushd "%SCRIPT_DIR%.." || (echo [x] Could not enter project root.& exit /b 1)

set "OMNIAPP_DEPLOY_USER=%ACC2_USER%"
set "OMNIAPP_DEPLOY_PASS=%ACC2_PASS%"
call "%SCRIPT_DIR%internal\deploy-android-debug.bat" "account 2"
set "_RC=%ERRORLEVEL%"

popd
endlocal & exit /b %_RC%
