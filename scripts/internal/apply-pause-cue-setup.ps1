# =====================================================================
#  apply-pause-cue-setup.ps1 - run supabase/pause-cue-setup.sql against the
#  LINKED remote DB via `supabase db query`, substituting the secret
#  __SERVICE_ROLE_KEY__ / __EDGE_BASE_URL__ tokens from the environment
#  (exported by deploy-supabase.bat from scripts/accounts.env).
#
#  The real key is passed to the CLI in-memory (never written to a committed
#  file). Called by scripts/deploy-supabase.bat only when
#  SUPABASE_SERVICE_ROLE_KEY is set.
#  Location: <project-root>\scripts\internal\apply-pause-cue-setup.ps1
# =====================================================================
$ErrorActionPreference = 'Stop'

$scriptDir   = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectRoot = (Resolve-Path (Join-Path $scriptDir '..\..')).Path
$template    = Join-Path $projectRoot 'supabase\pause-cue-setup.sql'

$key = $env:SUPABASE_SERVICE_ROLE_KEY
if ([string]::IsNullOrWhiteSpace($key)) { Write-Error 'SUPABASE_SERVICE_ROLE_KEY is not set.'; exit 1 }

$ref  = if ($env:SUPABASE_PROJECT_REF) { $env:SUPABASE_PROJECT_REF } else { 'itoaqbftjemovkzswiyu' }
$edge = if ($env:EDGE_BASE_URL) { $env:EDGE_BASE_URL } else { "https://$ref.functions.supabase.co" }

$sql = (Get-Content -Raw $template).Replace('__SERVICE_ROLE_KEY__', $key).Replace('__EDGE_BASE_URL__', $edge)

# Pass the SQL inline (single argument) so the resolved secret is never written to disk. The template opens
# with `--` line comments, which the CLI's parser would misread as a flag if the argument started with a dash
# (and it doesn't honor a `--` end-of-options separator) — the leading block comment keeps it a positional arg.
$sql = "/* pause-cue setup */`n" + $sql
& supabase db query --linked --workdir "$projectRoot" $sql
if ($LASTEXITCODE -ne 0) { Write-Error "supabase db query failed ($LASTEXITCODE)."; exit 1 }
Write-Host '[OK] pause-cue project setup applied to the linked DB.'
