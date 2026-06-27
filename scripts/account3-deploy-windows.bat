@echo off
setlocal EnableDelayedExpansion

REM =====================================================================
REM  account3-deploy-windows.bat - build a self-contained release app image,
REM  install it OUTSIDE the project tree, register it to start at Windows
REM  login, and have it auto-sign-in as ACCOUNT 3. This is the account-3
REM  variant of release-deploy.bat (which deploys the same release with no
REM  login). Re-run it whenever you want the startup release to pick up the
REM  latest code.
REM
REM  Auto-login: the deployed launcher (release-launch-acc3.bat) reads
REM  account-3 credentials from acc3.cred (written here next to it, NOT
REM  committed) and exports them as env vars the packaged exe inherits. After
REM  the first login the session is cached in the release DB, so later
REM  auto-starts keep working even if acc3.cred is later removed.
REM
REM  The release DB lives in %USERPROFILE%\.omniapp-release and is left
REM  untouched by updates (only the app binaries + launcher are replaced).
REM  Credentials come from scripts/accounts.env (gitignored).
REM =====================================================================

REM ----------------------------- CONFIG --------------------------------
set "PACKAGE_NAME=org.example.project"
set "INSTALL_ROOT=%LOCALAPPDATA%\OmniApp"
set "APP_DEST=%INSTALL_ROOT%\app"
set "EXE_NAME=%PACKAGE_NAME%.exe"
REM ---------------------------------------------------------------------

set "SCRIPT_DIR=%~dp0"
call "%SCRIPT_DIR%internal\load-accounts-env.bat" || exit /b 1
if not defined ACC3_USER (echo [x] ACC3_USER/ACC3_PASS missing from accounts.env.& exit /b 1)

pushd "%SCRIPT_DIR%.." || (echo [x] Could not enter project root.& exit /b 1)
set "APP_IMAGE=desktopApp\build\compose\binaries\main\app\%PACKAGE_NAME%"

echo [1/6] Building the release app image (bundled JRE; can take a few minutes)...
call gradlew.bat :desktopApp:createDistributable
if errorlevel 1 (echo [x] Build failed.& popd & exit /b 1)
if not exist "%APP_IMAGE%\%EXE_NAME%" (echo [x] App image / exe not found under %APP_IMAGE%.& popd & exit /b 1)

echo [2/6] Stopping any running release instance...
powershell -NoProfile -Command "Get-CimInstance Win32_Process | Where-Object { $_.Name -eq '%EXE_NAME%' } | ForEach-Object { Write-Host ('      killing PID ' + $_.ProcessId); Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }"

echo [3/6] Letting file handles release...
timeout /t 3 /nobreak >nul

REM ---- Register the launcher + credentials + Startup entry FIRST, so a ---
REM ---- locked-file hiccup in the binary copy below can never skip it. ----
echo [4/6] Installing account-3 launcher + credentials + Windows startup entry...
if not exist "%INSTALL_ROOT%\" mkdir "%INSTALL_ROOT%"
copy /y "%SCRIPT_DIR%internal\release-launch-acc3.bat" "%INSTALL_ROOT%\release-launch-acc3.bat" >nul
REM acc3.cred: read by release-launch-acc3.bat and exported to the app. Plaintext,
REM personal-machine only. Overwritten on each deploy.
> "%INSTALL_ROOT%\acc3.cred" echo OMNIAPP_LOGIN_USER=%ACC3_USER%
>>"%INSTALL_ROOT%\acc3.cred" echo OMNIAPP_LOGIN_PASS=%ACC3_PASS%
powershell -NoProfile -Command "$wsh = New-Object -ComObject WScript.Shell; $lnk = $wsh.CreateShortcut((Join-Path $env:APPDATA 'Microsoft\Windows\Start Menu\Programs\Startup\OmniApp.lnk')); $lnk.TargetPath = '%INSTALL_ROOT%\release-launch-acc3.bat'; $lnk.WorkingDirectory = '%INSTALL_ROOT%'; $lnk.WindowStyle = 7; $lnk.Save()"

echo [5/6] Deploying binaries to %APP_DEST% (DB at %USERPROFILE%\.omniapp-release untouched)...
robocopy "%APP_IMAGE%" "%APP_DEST%" /MIR /R:5 /W:2 /NJH /NJS /NFL /NDL >nul
if errorlevel 8 (echo       [!] Some files were locked and may not have updated. Close the release app and re-run.)

echo [6/6] Launching the updated release (auto-signs in as %ACC3_USER%)...
call "%INSTALL_ROOT%\release-launch-acc3.bat"

popd
echo.
echo Done.
echo   Installed:  %INSTALL_ROOT%
echo   Release DB: %USERPROFILE%\.omniapp-release
echo   Startup:    Start Menu\Programs\Startup\OmniApp.lnk  ^(auto-login as %ACC3_USER%^)
endlocal
