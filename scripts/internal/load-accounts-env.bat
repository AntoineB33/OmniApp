@echo off
REM =====================================================================
REM  load-accounts-env.bat - parse ..\accounts.env (KEY=VALUE) into the
REM  CALLER's environment. CALL this (do not start it) so the variables
REM  persist in the caller. '#'-prefixed and blank lines are skipped.
REM  Exits 1 (to the caller) if the credentials file is missing.
REM  Location: <project-root>\scripts\internal\load-accounts-env.bat
REM =====================================================================
set "_ENVFILE=%~dp0..\accounts.env"
if not exist "%_ENVFILE%" (
  echo [x] Missing credentials file: %_ENVFILE%
  echo     Copy scripts\accounts.env.example to scripts\accounts.env and fill it in.
  exit /b 1
)
for /f "usebackq eol=# tokens=1,* delims==" %%K in ("%_ENVFILE%") do (
  if not "%%K"=="" set "%%K=%%L"
)
exit /b 0
