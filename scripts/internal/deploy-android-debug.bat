@echo off
REM DisableDelayedExpansion (not Enable): credentials from accounts.env may contain '!', which
REM EnableDelayedExpansion silently eats - both when the deploy vars are set and when
REM %OMNIAPP_DEPLOY_PASS% is expanded on the `am start` line (a wrong password then makes Supabase
REM return "400 invalid login credentials"). This script uses no !var! expansion.
setlocal DisableDelayedExpansion

REM =====================================================================
REM  internal\deploy-android-debug.bat - build a DEBUG APK, install it on the
REM  connected device over adb, WIPE its local data, and launch it
REM  auto-signed-in as the account given in the environment. Shared by
REM  account1-deploy-android.bat and account2-deploy-android.bat.
REM
REM  Debug (not release) build: no signing keystore needed and it carries the
REM  in-app debug tooling seams, which is what these test-account deploys want.
REM  A debug APK is signed with the local debug key, so it does NOT share a
REM  signature with the account3 RELEASE build - if a release build is already
REM  installed, `install -r` clashes and we uninstall + clean-install instead
REM  (removing that release install and its on-device data).
REM
REM  Accounts 1 and 2 share the one app install on a phone (single package), so
REM  each deploy `pm clear`s local data first: auto-login only runs when signed
REM  out, so without the wipe a relaunch would stay on whichever account was
REM  already cached. This is the Android analog of the desktop scripts' separate
REM  per-account state dirs. Remote Supabase data is untouched.
REM
REM  Contract (set by the caller before `call`-ing this):
REM    OMNIAPP_DEPLOY_USER / OMNIAPP_DEPLOY_PASS - sign-in credentials
REM    %1 - a human label for the echoes (e.g. "account 1")
REM  The caller must also `pushd` the project root (this builds from CWD).
REM
REM  NOTE: the credentials are passed on the `adb shell am start` command line
REM  (visible to `ps`/logcat on the device). Fine for a personal device.
REM  Location: <project-root>\scripts\internal\deploy-android-debug.bat
REM =====================================================================

set "APP_ID=org.example.project"
set "LAUNCH_ACTIVITY=%APP_ID%/.MainActivity"
set "APK=androidApp\build\outputs\apk\debug\androidApp-debug.apk"

set "LABEL=%~1"
if "%LABEL%"=="" set "LABEL=the account"

if not defined OMNIAPP_DEPLOY_USER (echo [x] OMNIAPP_DEPLOY_USER/OMNIAPP_DEPLOY_PASS not set by caller.& exit /b 1)

REM ---- Resolve adb (Android SDK platform-tools) ----------------------
set "ADB=adb"
where adb >nul 2>&1 || (
  if exist "%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
)
if defined ANDROID_HOME if exist "%ANDROID_HOME%\platform-tools\adb.exe" set "ADB=%ANDROID_HOME%\platform-tools\adb.exe"

echo [1/6] Building the debug APK (can take a few minutes)...
call gradlew.bat :androidApp:assembleDebug
if errorlevel 1 (echo [x] Build failed.& exit /b 1)
if not exist "%APK%" (echo [x] APK not found at %APK%.& exit /b 1)

echo [2/6] Checking for a connected device...
"%ADB%" start-server >nul 2>&1
"%ADB%" devices | findstr /r /c:"	device$" >nul
if errorlevel 1 (
  echo [x] No authorized device found. Plug in via USB with USB debugging on,
  echo     or connect wifi-adb ^("%ADB% connect ^<phone-ip^>:5555"^), then re-run.
  "%ADB%" devices
  exit /b 1
)

echo [3/6] Installing the debug app...
"%ADB%" install -r "%APK%"
if errorlevel 1 (
  echo       install -r failed ^(usually a signature clash with an installed release build^);
  echo       uninstalling and doing a clean install...
  "%ADB%" uninstall "%APP_ID%" >nul 2>&1
  "%ADB%" install "%APK%"
  if errorlevel 1 (echo [x] Install failed.& exit /b 1)
)

REM ---- [4/6] Wipe local data so the next launch signs in fresh as this
REM ---- account (auto-login is skipped once a session is cached on-device).
echo [4/6] Clearing local app data ^(fresh sign-in as %LABEL%^)...
"%ADB%" shell pm clear "%APP_ID%" >nul

REM ---- [5/6] Force-stop so the next launch is a fresh process. The shared
REM ---- scheduler VM is a process singleton; it must be (re)built with the
REM ---- credentials present, so kill any running instance before relaunching.
echo [5/6] Launching auto-signed-in as %OMNIAPP_DEPLOY_USER%...
"%ADB%" shell am force-stop "%APP_ID%"
"%ADB%" shell am start -n "%LAUNCH_ACTIVITY%" --es omniapp_login_user "%OMNIAPP_DEPLOY_USER%" --es omniapp_login_pass "%OMNIAPP_DEPLOY_PASS%" >nul
if errorlevel 1 (echo [x] Launch with login extras failed.& exit /b 1)

echo [6/6] Done.
echo.
echo   Installed APK: %APK%
echo   Login:         signing in as %OMNIAPP_DEPLOY_USER% ^(local data was wiped first^)
echo.
echo   NOTE: accounts 1 and 2 share one app install on the phone; each deploy
echo         wipes local data and re-signs in. Remote Supabase data is untouched.
echo.
echo   TIP: For reliable background reminders, exempt OmniApp from battery
echo        optimization ^(Settings ^> Apps ^> OmniApp ^> Battery ^> Unrestricted^).
endlocal
exit /b 0
