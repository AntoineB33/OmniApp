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
REM  each deploy uninstalls + reinstalls to wipe local data first: auto-login only
REM  runs when signed out, so without the wipe a relaunch would stay on whichever
REM  account was already cached. (We uninstall rather than `pm clear` because MIUI
REM  and some other OEM ROMs deny `adb shell pm clear` for third-party packages.)
REM  This is the Android analog of the desktop scripts' separate per-account state
REM  dirs. Remote Supabase data is untouched.
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

REM ---- Deploy/launch loop. A locked phone makes install / pm clear / am start
REM ---- fail; rather than abort we prompt to unlock and retry (infinite).
REM ---- The build above is done once; only these on-device steps retry.
:deploy_retry

echo [2/6] Checking for a connected device...
"%ADB%" start-server >nul 2>&1
"%ADB%" devices | findstr /r /c:"	device$" >nul
if not errorlevel 1 goto device_ok

REM ---- Not in the ready "device" state. Distinguish WHY so the retry prompt is
REM ---- accurate: "unlock the phone" is wrong for an offline/unauthorized device
REM ---- (unlocking never fixes those - you re-accept the RSA prompt or reconnect adb).
"%ADB%" devices | findstr /r /c:"	unauthorized$" >nul && (
  echo [x] Device is UNAUTHORIZED. On the PHONE, accept the "Allow USB debugging?"
  echo     prompt ^(tick "Always allow from this computer" =^> Allow^), then retry.
  goto deploy_failed
)
"%ADB%" devices | findstr /r /c:"	offline$" >nul && (
  echo [x] Device is OFFLINE ^(stale connection - not a lock^). Restarting adb server...
  "%ADB%" kill-server >nul 2>&1
  "%ADB%" start-server >nul 2>&1
  echo     If it stays offline: replug USB or re-toggle USB debugging, then retry.
  goto deploy_failed
)
echo [x] No device found. Plug in via USB with USB debugging on,
echo     or connect wifi-adb ^("%ADB% connect ^<phone-ip^>:5555"^).
"%ADB%" devices
goto deploy_failed

:device_ok

REM ---- [3/6] Uninstall first to WIPE local data, so the next launch signs in
REM ---- fresh as this account (auto-login is skipped once a session is cached).
REM ---- We uninstall rather than `pm clear` because MIUI/HyperOS (and some other
REM ---- OEM ROMs) deny `adb shell pm clear` for third-party packages with a
REM ---- "does not have permission CLEAR_APP_USER_DATA" SecurityException;
REM ---- uninstall wipes the data too and needs no special permission. It also
REM ---- makes the old release-signature clash a non-issue (the app is gone).
echo [3/6] Removing any existing install ^(wipes local data for a fresh sign-in as %LABEL%^)...
"%ADB%" uninstall "%APP_ID%" >nul 2>&1
REM  (a "not installed" failure here is fine - we install fresh next.)

echo [4/6] Installing the debug app fresh...
"%ADB%" install "%APK%"
if errorlevel 1 (echo [x] Install failed.& goto deploy_failed)

REM ---- Re-grant keep-alive permissions over adb so the one-time on-device prompt
REM ---- (MainActivity.maybePromptKeepAliveOnce) needs no manual tapping/scrolling.
REM ---- The uninstall above wiped both the "already prompted" flag AND the actual
REM ---- grants, so they must be re-applied every deploy. Both are best-effort
REM ---- (|| true style: a failure/unknown-op on a given ROM changes nothing).
REM ----  1) Doze / battery-optimization exemption - works on ALL ROMs.
REM ----  2) MIUI/HyperOS AUTO_START appops - Xiaomi-specific; on some builds the
REM ----     op string is unknown and this is a harmless no-op (then the OEM
REM ----     autostart list must still be picked by hand once per reinstall).
REM ----  3) POST_NOTIFICATIONS (API 33+) - MainActivity asks for this at launch, but
REM ----     the dialog is only ANSWERED seconds later, and a fresh reinstall starts
REM ----     ungranted by definition. Under the fast-break scripts the first break cue
REM ----     fires ~14 s after install, i.e. inside that window, and
REM ----     NotificationManagerCompat.notify then drops it SILENTLY while the History
REM ----     window still lists it (notificationLog is written before the OS call) -
REM ----     which reads as "the phone stayed silent through a break". Granting it here
REM ----     closes the window. No-ops on pre-33 devices (unknown permission).
"%ADB%" shell dumpsys deviceidle whitelist +%APP_ID% >nul 2>&1
"%ADB%" shell cmd appops set %APP_ID% AUTO_START allow >nul 2>&1
"%ADB%" shell pm grant %APP_ID% android.permission.POST_NOTIFICATIONS >nul 2>&1

REM ---- [5/6] Force-stop so the next launch is a fresh process. The shared
REM ---- scheduler VM is a process singleton; it must be (re)built with the
REM ---- credentials present, so kill any running instance before relaunching.
REM  Optional debug fast-break overrides: set OMNIAPP_BREAK_DURATION_MS and/or OMNIAPP_BREAK_INTERVAL_MS
REM  (e.g. 5000) before running to shrink the 5-min screen break (its length / how long after the previous
REM  pause it comes due), so the pause-cue voice message can be tested on the phone in seconds.
REM  The app REMEMBERS whatever this launch passes (AndroidDebugFlagStore, debug builds only), so a phone
REM  reboot - which restarts the engine via BootReceiver with no Activity and no extras - keeps the same
REM  5-min-break rules as the server-side break_config.length_ms this deploy upserted. A later deploy that
REM  passes none of these CLEARS them, so a plain account{1,2}-deploy-android.bat is back to production timings.
set "BREAK_EXTRA="
if defined OMNIAPP_BREAK_DURATION_MS set "BREAK_EXTRA=%BREAK_EXTRA% --es omniapp_break_duration_ms %OMNIAPP_BREAK_DURATION_MS%"
if defined OMNIAPP_BREAK_INTERVAL_MS set "BREAK_EXTRA=%BREAK_EXTRA% --es omniapp_break_interval_ms %OMNIAPP_BREAK_INTERVAL_MS%"

echo [5/6] Launching auto-signed-in as %OMNIAPP_DEPLOY_USER%...
"%ADB%" shell am force-stop "%APP_ID%"
REM  omniapp_skip_keepalive_prompt: the adb grants above already re-applied the keep-alive permissions,
REM  so tell MainActivity NOT to pop the battery-opt dialog + MIUI autostart app-list screen on launch
REM  (which the uninstall/reinstall would otherwise re-trigger every deploy - no more scrolling to find OmniApp).
"%ADB%" shell am start -n "%LAUNCH_ACTIVITY%" --es omniapp_login_user "%OMNIAPP_DEPLOY_USER%" --es omniapp_login_pass "%OMNIAPP_DEPLOY_PASS%" --es omniapp_skip_keepalive_prompt true %BREAK_EXTRA% >nul
if errorlevel 1 (echo [x] Launch with login extras failed.& goto deploy_failed)

goto deploy_done

:deploy_failed
echo.
echo [!] The on-device step failed - see the reason above (a locked phone can also
echo     cause install/launch to fail; if so, unlock it now).
set /p "_RETRY=    Fix the above, then press Enter to retry (or Ctrl+C to abort)... "
echo.
goto deploy_retry

:deploy_done
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
