@echo off
setlocal

REM =====================================================================
REM  update-supabase-cli.bat - update the Supabase CLI *that PATH actually
REM  resolves to*, whichever way it was installed:
REM    - npm global (%APPDATA%\npm)      -> npm install -g supabase@latest
REM    - standalone (%LOCALAPPDATA%\supabase\bin) -> latest GitHub release
REM  It then re-resolves PATH and fails loudly if the copy it updated is
REM  NOT the one `supabase` runs (two installs, first one wins) - that
REM  mismatch is what made deploy-supabase.bat keep warning about an old
REM  CLI after a "successful" update.
REM
REM  Updates the CLI TOOL only - it does not touch your database.
REM
REM  Usage:  update-supabase-cli.bat          (skip if already latest)
REM          update-supabase-cli.bat -Force   (reinstall regardless)
REM  Location: <project-root>\scripts\update-supabase-cli.bat
REM =====================================================================

set "SCRIPT_DIR=%~dp0"
where powershell >nul 2>nul || (echo [x] Windows PowerShell not found on PATH.& exit /b 1)
powershell -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT_DIR%internal\update-supabase-cli.ps1" %*
exit /b %errorlevel%
