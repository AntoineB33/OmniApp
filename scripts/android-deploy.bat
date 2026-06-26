@echo off
setlocal EnableDelayedExpansion

REM =====================================================================
REM  android-deploy.bat - the Android analog of release-deploy.bat.
REM  Builds a SIGNED release APK and installs/updates it onto a connected
REM  device over adb (USB or wifi-adb), then launches it. The app's
REM  MainActivity starts the foreground SchedulerService, and a
REM  BOOT_COMPLETED receiver restarts that service on every reboot - so the
REM  scheduler keeps firing reminders at startup and in the background,
REM  mirroring how release-deploy.bat registers a Windows Startup entry.
REM
REM  The on-device app data / SQLite DB is preserved across updates
REM  (adb install -r keeps app data; only the binaries are replaced).
REM
REM  First run generates a local signing keystore (kept OUTSIDE the repo,
REM  under %USERPROFILE%\.omniapp) and records its coordinates in
REM  local.properties (gitignored) so the Gradle build can sign with it.
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

echo [5/6] Launching ^(starts the foreground SchedulerService^)...
"%ADB%" shell am start -n "%LAUNCH_ACTIVITY%" >nul

echo [6/6] Done.
popd
echo.
echo   Installed APK: %APK%
echo   Keystore:      %KEYSTORE%
echo   Boot-start:    BootReceiver restarts SchedulerService after every reboot.
echo.
echo   TIP: For reliable background reminders, exempt OmniApp from battery
echo        optimization ^(Settings ^> Apps ^> OmniApp ^> Battery ^> Unrestricted^),
echo        and on some OEMs enable "Autostart" for it.
endlocal
