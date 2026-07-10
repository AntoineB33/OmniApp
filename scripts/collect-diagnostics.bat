@echo off
setlocal

REM =====================================================================
REM  collect-diagnostics.bat - print the merged cross-device diagnostics
REM  timeline: the [script] markers + desktop app lines (both appended to
REM  <stateDir>\diagnostics.log) and the Android app's lines (pulled over
REM  adb from the debug app's private files dir).
REM
REM  Usage: collect-diagnostics.bat [stateDir]
REM    stateDir defaults to %USERPROFILE%\.omniapp-acc1 (the account-1 dir
REM    the empty-and-open script uses). Pass another state dir to inspect
REM    account 2 / the default install.
REM
REM  Android retrieval needs a connected device with the DEBUG build
REM  installed (`run-as` only works on debuggable apps); if adb or the
REM  device is unavailable the desktop-side timeline still prints.
REM  Location: <project-root>\scripts\collect-diagnostics.bat
REM =====================================================================

set "SCRIPT_DIR=%~dp0"
set "STATE_DIR=%~1"
if "%STATE_DIR%"=="" set "STATE_DIR=%USERPROFILE%\.omniapp-acc1"
set "DESKTOP_LOG=%STATE_DIR%\diagnostics.log"
set "ANDROID_LOG=%TEMP%\omniapp-android-diagnostics.log"

where python >nul 2>nul || (echo [x] 'python' is not on PATH.& exit /b 1)

REM NOTE: no parenthesized if/else blocks here - an echo containing "(...)" inside one ends the block
REM early and cmd dies with "- was unexpected at this time".
REM Resolve adb the same way internal\deploy-android-debug.bat does: PATH, then the default SDK dir,
REM then ANDROID_HOME.
set "ADB=adb"
where adb >nul 2>nul
if errorlevel 1 if exist "%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
if defined ANDROID_HOME if exist "%ANDROID_HOME%\platform-tools\adb.exe" set "ADB=%ANDROID_HOME%\platform-tools\adb.exe"

type nul > "%ANDROID_LOG%"
"%ADB%" shell run-as org.example.project cat files/diagnostics.log > "%ANDROID_LOG%" 2>nul
if errorlevel 1 echo [!] Could not read the Android log - adb missing, device offline, or release build? Desktop only.

:merge
python "%SCRIPT_DIR%internal\merge_diagnostics.py" "%DESKTOP_LOG%" "%ANDROID_LOG%"
endlocal
