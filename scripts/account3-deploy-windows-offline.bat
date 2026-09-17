@echo off
REM =====================================================================
REM  account3-deploy-windows-offline.bat - account3-deploy-windows.bat, but the
REM  installed app WORKS COMPLETELY OFFLINE BY DEFAULT: every launch (including
REM  the Windows auto-start) sends no request and opens no socket
REM  (docs/invariants/sync-and-accounts.md, Working offline). Edits are kept and
REM  pushed when you press the in-app "work online" button, for that session.
REM
REM  Same build, install dir, release DB and credentials as the online deploy.
REM  Run account3-deploy-windows.bat to install it online again.
REM =====================================================================
call "%~dp0account3-deploy-windows.bat" offline
exit /b %errorlevel%
