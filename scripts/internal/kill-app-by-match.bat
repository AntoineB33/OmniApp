@echo off
setlocal EnableDelayedExpansion
REM =====================================================================
REM  kill-app-by-match.bat <match> - kill any running desktop app JVM whose
REM  command line contains <match>, then wait for it to fully exit. Used to
REM  stop only ONE account's instance (matched by its isolated state dir,
REM  e.g. ".omniapp-acc1") so the other account's app keeps running.
REM  The Gradle DAEMON is not matched (it carries no -P/-D state-dir on its
REM  own command line), so the build is unaffected.
REM  CALL this with the match token as %1.
REM =====================================================================
set "MATCH=%~1"
if "%MATCH%"=="" (echo [x] kill-app-by-match: no match token given.& endlocal & exit /b 1)

echo [kill] Stopping app instances matching "%MATCH%"...
powershell -NoProfile -Command "@(Get-CimInstance Win32_Process | Where-Object { $_.ProcessId -ne $PID -and $_.Name -like 'java*' -and $_.CommandLine -like '*%MATCH%*' }) | ForEach-Object { Write-Host ('      killing PID ' + $_.ProcessId); Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }"

set /a _tries=0
:waitloop
for /f %%C in ('powershell -NoProfile -Command "@(Get-CimInstance Win32_Process | Where-Object { $_.ProcessId -ne $PID -and $_.Name -like 'java*' -and $_.CommandLine -like '*%MATCH%*' }).Count"') do set "_count=%%C"
if "!_count!"=="" set "_count=0"
if !_count! gtr 0 (
  set /a _tries+=1
  if !_tries! geq 50 (echo       [x] Timed out waiting for the app to close.& endlocal & exit /b 1)
  timeout /t 1 /nobreak >nul
  goto waitloop
)
echo       No matching instance running.
endlocal & exit /b 0
