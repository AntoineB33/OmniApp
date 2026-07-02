@echo off
setlocal

REM =====================================================================
REM  update-supabase-cli.bat - update the standalone Supabase CLI to the
REM  latest GitHub release, in place (%LOCALAPPDATA%\supabase\bin). This
REM  updates the CLI TOOL only - it does not touch your database.
REM
REM  Usage:  update-supabase-cli.bat          (skip if already latest)
REM          update-supabase-cli.bat -Force   (reinstall regardless)
REM  Location: <project-root>\scripts\update-supabase-cli.bat
REM =====================================================================

set "SCRIPT_DIR=%~dp0"
where powershell >nul 2>nul || (echo [x] Windows PowerShell not found on PATH.& exit /b 1)
powershell -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT_DIR%internal\update-supabase-cli.ps1" %*
exit /b %errorlevel%
