@echo off
setlocal EnableDelayedExpansion

REM =====================================================================
REM  release-deploy.bat - build a self-contained release app image and
REM  install/update it OUTSIDE the project tree, then register it to start
REM  at Windows login. Run this whenever you want the release to pick up
REM  the latest code. Development helper only.
REM
REM  Why this isolates the release from active work: createDistributable
REM  bundles a private JRE into a standalone app image that is COPIED to
REM  %LOCALAPPDATA%\OmniApp. The running release uses that copy, so it
REM  never recompiles and is unaffected by edits/rebuilds in the project.
REM
REM  The release DB lives in %USERPROFILE%\.omniapp-release and is left
REM  untouched by updates (only the app binaries are replaced).
REM
REM  Three databases total:
REM    release       -> %USERPROFILE%\.omniapp-release   (this script)
REM    debug         -> %USERPROFILE%\.omniapp           (dev-restart.bat)
REM    debug emptied -> %USERPROFILE%\.omniapp-reset      (dev-reset.bat)
REM =====================================================================

REM ----------------------------- CONFIG --------------------------------
set "PACKAGE_NAME=org.example.project"
set "INSTALL_ROOT=%LOCALAPPDATA%\OmniApp"
set "APP_DEST=%INSTALL_ROOT%\app"
set "EXE_NAME=%PACKAGE_NAME%.exe"
REM ---------------------------------------------------------------------

set "SCRIPT_DIR=%~dp0"
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

REM ---- Register the launcher + Startup entry FIRST, so a locked-file -----
REM ---- hiccup in the binary copy below can never skip startup setup. -----
echo [4/6] Installing launcher + registering Windows startup entry...
if not exist "%INSTALL_ROOT%\" mkdir "%INSTALL_ROOT%"
copy /y "%SCRIPT_DIR%internal\release-launch.bat" "%INSTALL_ROOT%\release-launch.bat" >nul
powershell -NoProfile -Command "$wsh = New-Object -ComObject WScript.Shell; $lnk = $wsh.CreateShortcut((Join-Path $env:APPDATA 'Microsoft\Windows\Start Menu\Programs\Startup\OmniApp.lnk')); $lnk.TargetPath = '%INSTALL_ROOT%\release-launch.bat'; $lnk.WorkingDirectory = '%INSTALL_ROOT%'; $lnk.WindowStyle = 7; $lnk.Save()"

echo [5/6] Deploying binaries to %APP_DEST% (DB at %USERPROFILE%\.omniapp-release untouched)...
robocopy "%APP_IMAGE%" "%APP_DEST%" /MIR /R:5 /W:2 /NJH /NJS /NFL /NDL >nul
if errorlevel 8 (echo       [!] Some files were locked and may not have updated. Close the release app and re-run.)

echo [6/6] Launching the updated release...
call "%INSTALL_ROOT%\release-launch.bat"

popd
echo.
echo Done.
echo   Installed:  %INSTALL_ROOT%
echo   Release DB: %USERPROFILE%\.omniapp-release
echo   Startup:    Start Menu\Programs\Startup\OmniApp.lnk
endlocal
