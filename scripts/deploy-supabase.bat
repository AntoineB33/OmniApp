@echo off
setlocal EnableDelayedExpansion

REM =====================================================================
REM  deploy-supabase.bat - apply the DB schema and deploy the Edge
REM  Function to Supabase with the CLI, so you never copy-paste SQL into
REM  the Dashboard SQL Editor again.
REM
REM    [1] supabase db push            - applies supabase/migrations/*.sql
REM    [2] supabase functions deploy   - deploys supabase/functions/pause-cue
REM    [3] supabase db query           - applies supabase/pause-cue-setup.sql
REM                                      (pg_cron/pg_net + Vault secrets + cron job), with the secret
REM                                      service-role key injected from accounts.env. Skipped if unset.
REM
REM  Every statement is idempotent, so re-running is safe.
REM
REM  ONE-TIME setup (interactive, do these yourself first):
REM    supabase login                          (opens a browser to authorize)
REM    supabase link --project-ref <ref>       (<ref> = subdomain of your
REM                                             project URL; prompts for the DB
REM                                             password once and caches it)
REM  After that this script is non-interactive. Optionally put
REM  SUPABASE_DB_PASSWORD / SUPABASE_PROJECT_REF in scripts/accounts.env to
REM  let it link/push without prompting.
REM
REM  Step [3] needs SUPABASE_SERVICE_ROLE_KEY in accounts.env (secret; never
REM  committed). Only the Edge Function's FCM_/APNS_ secrets (supabase secrets
REM  set ...) and native push-token registration remain manual follow-ups.
REM  Location: <project-root>\scripts\deploy-supabase.bat
REM =====================================================================

set "SCRIPT_DIR=%~dp0"
set "PROJECT_ROOT=%SCRIPT_DIR%.."

REM ---- accounts.env is optional here (only for the two vars below) ----
if exist "%SCRIPT_DIR%accounts.env" call "%SCRIPT_DIR%internal\load-accounts-env.bat"

REM `call supabase ...` everywhere below: if PATH resolves supabase to a .cmd wrapper (e.g. an npm global
REM install), invoking it without `call` transfers control and the rest of this script silently never runs.
where supabase >nul 2>nul || (
  echo [x] The Supabase CLI is not on PATH.
  echo     Install it with update-supabase-cli.bat ^(downloads the standalone binary
  echo     to %%LOCALAPPDATA%%\supabase\bin and adds it to PATH^), then open a NEW
  echo     terminal and run 'supabase login' + 'supabase link' once.
  exit /b 1
)

set "REF="
if defined SUPABASE_PROJECT_REF set "REF=%SUPABASE_PROJECT_REF%"

REM ---- Link once if not already linked (harmless to re-run) -----------
if not exist "%PROJECT_ROOT%\supabase\.temp\project-ref" (
  echo [*] Not linked yet - linking...
  if not defined REF (
    echo [x] No link found and SUPABASE_PROJECT_REF is unset.
    echo     Run once:  supabase link --project-ref ^<ref^>
    exit /b 1
  )
  if defined SUPABASE_DB_PASSWORD (
    call supabase link --project-ref "%REF%" --password "%SUPABASE_DB_PASSWORD%" || (echo [x] link failed.& exit /b 1)
  ) else (
    call supabase link --project-ref "%REF%" || (echo [x] link failed.& exit /b 1)
  )
)

REM ---- [1/3] Apply the schema (migrations) ---------------------------
echo [1/3] Applying DB migrations ^(supabase db push^)...
if defined SUPABASE_DB_PASSWORD (
  call supabase db push --workdir "%PROJECT_ROOT%" --password "%SUPABASE_DB_PASSWORD%"
) else (
  call supabase db push --workdir "%PROJECT_ROOT%"
)
if errorlevel 1 (echo [x] db push failed.& exit /b 1)

REM ---- [2/3] Deploy the pause-cue Edge Function ----------------------
echo [2/3] Deploying Edge Function 'pause-cue'...
call supabase functions deploy pause-cue --workdir "%PROJECT_ROOT%"
if errorlevel 1 (echo [x] functions deploy failed.& exit /b 1)

REM ---- [3/3] Pause-cue project setup (extensions/GUCs/cron) ----------
REM Optional: needs the SECRET service-role key, kept only in accounts.env (never committed). Runs the
REM non-migration, project-level SQL (pg_cron/pg_net + the omni_* Vault secrets + the cron job) via `supabase db query`,
REM injecting the key from the environment. Skipped when the key is absent (the rest of sync works without it).
if defined SUPABASE_SERVICE_ROLE_KEY (
  echo [3/3] Applying pause-cue project setup ^(supabase/pause-cue-setup.sql^)...
  powershell -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT_DIR%internal\apply-pause-cue-setup.ps1"
  if errorlevel 1 (echo [x] pause-cue project setup failed.& exit /b 1)
) else (
  echo [3/3] Skipping pause-cue project setup - SUPABASE_SERVICE_ROLE_KEY not set in accounts.env.
  echo       ^(Only needed for the phone pause-end voice cue push; core sync works without it.^)
)

echo.
echo [OK] Supabase schema + Edge Function deployed.
echo      Still native-only follow-ups: the Edge Function's FCM_/APNS_ secrets
echo      ^(supabase secrets set ...^) and each phone registering its push token.

endlocal
