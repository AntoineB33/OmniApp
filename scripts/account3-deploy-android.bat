@echo off
setlocal EnableDelayedExpansion

REM =====================================================================
REM  account3-deploy-android.bat - build a SIGNED release APK, install it on
REM  the connected device over adb, and launch it auto-signed-in as ACCOUNT 3.
REM  The MainActivity starts the foreground SchedulerService and a
REM  BOOT_COMPLETED receiver restarts that service on every reboot, so the
REM  scheduler keeps firing reminders in the background - the Android analog
REM  of account3-deploy-windows.bat's Windows-startup registration.
REM
REM  Auto-login: account-3 credentials are passed as launch-Intent extras
REM  (omniapp_login_user / omniapp_login_pass); MainActivity stages them and
REM  the shared VM signs in on first build. The session is then cached in the
REM  on-device DB, so the BOOT_COMPLETED restart (and ordinary taps) keep the
REM  app logged in without the extras afterwards. App data / DB is preserved
REM  across updates (adb install -r keeps app data; only binaries change).
REM  Credentials come from scripts/accounts.env (gitignored).
REM
REM  First run generates a local signing keystore (kept OUTSIDE the repo,
REM  under %USERPROFILE%\.omniapp) and records its coordinates in
REM  local.properties (gitignored) so the Gradle build can sign with it.
REM
REM  NOTE: the credentials are passed on the `adb shell am start` command
REM  line (visible to `ps`/logcat on the device). Fine for a personal device.
REM =====================================================================

REM ----------------------------- CONFIG --------------------------------
set "APP_ID=org.example.project"
set "LAUNCH_ACTIVITY=%APP_ID%/.MainActivity"
set "KEYSTORE=%USERPROFILE%\.omniapp\omniapp-release.jks"
set "KEY_ALIAS=omniapp"
set "STORE_PASS=omniapp"
set "KEY_PASS=omniapp"
REM ---------------------------------------------------------------------

set "SCRIPT_DIR=%~dp0"
call "%SCRIPT_DIR%internal\load-accounts-env.bat" || exit /b 1
if not defined ACC3_USER (echo [x] ACC3_USER/ACC3_PASS missing from accounts.env.& exit /b 1)

pushd "%SCRIPT_DIR%.." || (echo [x] Could not enter project root.& exit /b 1)
set "LOCALPROPS=%CD%\local.properties"
set "APK=androidApp\build\outputs\apk\release\androidApp-release.apk"

REM ---- Resolve keytool (JDK) and adb (Android SDK platform-tools) -------
set "KEYTOOL=keytool"
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\keytool.exe" set "KEYTOOL=%JAVA_HOME%\bin\keytool.exe"

set "ADB=adb"
where adb >nul 2>&1 || (
  if exist "%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
)
if defined ANDROID_HOME if exist "%ANDROID_HOME%\platform-tools\adb.exe" set "ADB=%ANDROID_HOME%\platform-tools\adb.exe"

echo [1/6] Ensuring release signing keystore...
if not exist "%USERPROFILE%\.omniapp\" mkdir "%USERPROFILE%\.omniapp"
if not exist "%KEYSTORE%" (
  echo       Generating "%KEYSTORE%" ...
  "%KEYTOOL%" -genkeypair -v -keystore "%KEYSTORE%" -alias %KEY_ALIAS% -keyalg RSA -keysize 2048 ^
    -validity 10000 -storepass %STORE_PASS% -keypass %KEY_PASS% ^
    -dname "CN=OmniApp, OU=Dev, O=OmniApp, C=US"
  if errorlevel 1 (echo [x] keytool failed. Is a JDK on PATH or JAVA_HOME set?& popd & exit /b 1)
) else (
  echo       Reusing existing keystore.
)

REM ---- Record signing coordinates in local.properties (forward slashes
REM ---- avoid Java-Properties backslash escaping). Idempotent. -----------
set "KS_FWD=%KEYSTORE:\=/%"
findstr /b /c:"omniapp.releaseKeystore=" "%LOCALPROPS%" >nul 2>&1
if errorlevel 1 (
  echo       Recording signing config in local.properties ...
  >>"%LOCALPROPS%" echo.
  >>"%LOCALPROPS%" echo omniapp.releaseKeystore=%KS_FWD%
  >>"%LOCALPROPS%" echo omniapp.releaseStorePassword=%STORE_PASS%
  >>"%LOCALPROPS%" echo omniapp.releaseKeyAlias=%KEY_ALIAS%
  >>"%LOCALPROPS%" echo omniapp.releaseKeyPassword=%KEY_PASS%
)

echo [2/6] Building the signed release APK (can take a few minutes)...
call gradlew.bat :androidApp:assembleRelease
if errorlevel 1 (echo [x] Build failed.& popd & exit /b 1)
if not exist "%APK%" (echo [x] APK not found at %APK%.& popd & exit /b 1)

echo [3/6] Checking for a connected device...
"%ADB%" start-server >nul 2>&1
"%ADB%" devices | findstr /r /c:"	device$" >nul
if errorlevel 1 (
  echo [x] No authorized device found. Plug in via USB with USB debugging on,
  echo     or connect wifi-adb ^("%ADB% connect ^<phone-ip^>:5555"^), then re-run.
  "%ADB%" devices
  popd & exit /b 1
)

echo [4/6] Installing/updating the app ^(app data + DB preserved^)...
"%ADB%" install -r "%APK%"
if errorlevel 1 (echo [x] Install failed.& popd & exit /b 1)

REM ---- [5/6] Force-stop so the next launch is a fresh process. The shared
REM ---- scheduler VM is a process singleton; it must be (re)built with the
REM ---- credentials present, so kill any running instance before relaunching.
echo [5/6] Launching auto-signed-in as %ACC3_USER%...
"%ADB%" shell am force-stop "%APP_ID%"
"%ADB%" shell am start -n "%LAUNCH_ACTIVITY%" --es omniapp_login_user "%ACC3_USER%" --es omniapp_login_pass "%ACC3_PASS%" >nul
if errorlevel 1 (echo [x] Launch with login extras failed.& popd & exit /b 1)

echo [6/6] Done.
popd
echo.
echo   Installed APK: %APK%
echo   Keystore:      %KEYSTORE%
echo   Login:         signing in as %ACC3_USER% ^(session then cached on-device^)
echo   Boot-start:    BootReceiver restarts SchedulerService after every reboot.
echo.
echo   TIP: For reliable background reminders, exempt OmniApp from battery
echo        optimization ^(Settings ^> Apps ^> OmniApp ^> Battery ^> Unrestricted^),
echo        and on some OEMs enable "Autostart" for it.
endlocal
