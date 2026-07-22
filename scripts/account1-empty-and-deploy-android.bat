@echo off
setlocal

REM =====================================================================
REM  account1-empty-and-deploy-android.bat - convenience wrapper that
REM  chains, in order:
REM    1. account1-empty.bat            (server logout + empty local&remote,
REM                                       NO desktop app opened)
REM    2. account1-deploy-android.bat   (build/install/launch debug APK on
REM                                       the phone, auto-signed-in as acc 1)
REM
REM  Same as account1-empty-open-and-deploy-android.bat but WITHOUT opening
REM  the desktop app - just empty account 1, then deploy to the phone.
REM
REM  Meant to be double-clicked, not run from an already-open console: if
REM  either step fails, the window WAITS for the user to press Enter before
REM  it closes, so the error is readable instead of vanishing with the window.
REM  Location: <project-root>\scripts\account1-empty-and-deploy-android.bat
REM =====================================================================

set "SCRIPT_DIR=%~dp0"

echo ==== [1/2] account1-empty.bat ====
call "%SCRIPT_DIR%account1-empty.bat"
if errorlevel 1 (
  echo.
  echo [x] account1-empty.bat failed - aborting before the Android deploy.
  echo.
  set /p "_WAIT=Press Enter to close... "
  endlocal
  exit /b 1
)

echo.
echo ==== [2/2] account1-deploy-android.bat ====
call "%SCRIPT_DIR%account1-deploy-android.bat"
if errorlevel 1 (
  echo.
  echo [x] account1-deploy-android.bat failed.
  echo.
  set /p "_WAIT=Press Enter to close... "
  endlocal
  exit /b 1
)

echo.
echo [OK] account 1 emptied (local + remote) and deployed to Android.
endlocal
exit /b 0
