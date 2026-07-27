# =====================================================================
#  update-supabase-cli.ps1 - update the Supabase CLI *that PATH actually
#  resolves to*, whichever way it was installed:
#
#    - npm global  (%APPDATA%\npm\supabase.cmd)  -> npm install -g supabase@latest
#    - standalone  (%LOCALAPPDATA%\supabase\bin) -> download the latest
#                                                   GitHub release zip
#
#  Why the detection matters: `where supabase` can list BOTH, and the
#  first one wins. Updating the shadowed one leaves deploy-supabase.bat
#  running an old CLI while this script reports success - the exact
#  failure this script exists to prevent. So it updates the winner, then
#  re-resolves PATH and prints which install `supabase` really is.
#
#  Updates the CLI TOOL only - it never touches your database.
#  Called by scripts/update-supabase-cli.bat. -Force reinstalls even when
#  already on the latest version.
#  Location: <project-root>\scripts\internal\update-supabase-cli.ps1
# =====================================================================
param([switch]$Force)

$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$standaloneDir = Join-Path $env:LOCALAPPDATA 'supabase\bin'
$standaloneExe = Join-Path $standaloneDir 'supabase.exe'

# --- what does PATH actually resolve `supabase` to? -------------------
# Use where.exe (cmd.exe's own search order, which is what the .bat files
# get), keeping only entries cmd.exe would actually execute - npm also
# drops an extension-less shell script that Windows ignores.
function Get-SupabaseCandidates {
    $found = @()
    try { $found = @(& where.exe supabase) } catch { $found = @() }
    return @($found | Where-Object { $_ -match '\.(exe|cmd|bat)$' })
}

function Get-SupabaseVersion([string]$exePath) {
    if (-not $exePath) { return '' }
    try { return (& $exePath --version | Select-Object -First 1).Trim() } catch { return '' }
}

$candidates = Get-SupabaseCandidates
$winner     = if ($candidates.Count -gt 0) { $candidates[0] } else { '' }

# --- npm global prefix, to classify the winner -----------------------
$npmAvailable = [bool](Get-Command npm -ErrorAction SilentlyContinue)
$npmPrefix    = ''
if ($npmAvailable) {
    try { $npmPrefix = (& npm prefix -g | Select-Object -First 1).Trim() } catch { $npmPrefix = '' }
}
function Test-IsNpmManaged([string]$p) {
    if (-not $p) { return $false }
    if ($npmPrefix -and $p.StartsWith($npmPrefix, [StringComparison]::OrdinalIgnoreCase)) { return $true }
    return $p.StartsWith((Join-Path $env:APPDATA 'npm'), [StringComparison]::OrdinalIgnoreCase)
}
function Test-IsStandalone([string]$p) {
    if (-not $p) { return $false }
    return $p.StartsWith($standaloneDir, [StringComparison]::OrdinalIgnoreCase)
}

# --- pick the mode ---------------------------------------------------
$mode = ''
if (Test-IsNpmManaged $winner) {
    $mode = 'npm'
    Write-Host "[*] supabase resolves to the npm global install: $winner"
} elseif (Test-IsStandalone $winner) {
    $mode = 'standalone'
    Write-Host "[*] supabase resolves to the standalone install: $winner"
} elseif ($winner) {
    Write-Host "[x] supabase resolves to $winner"
    Write-Host '    That is neither the npm global nor the standalone install this script'
    Write-Host '    manages (scoop / chocolatey / a manual copy?). Update it with whatever'
    Write-Host '    installed it, or remove it so this script can take over.'
    exit 1
} elseif ($npmAvailable) {
    $mode = 'npm'
    Write-Host '[*] No supabase on PATH - installing the npm global (npm is available).'
} else {
    $mode = 'standalone'
    Write-Host '[*] No supabase on PATH and no npm - installing the standalone binary.'
}

if ($candidates.Count -gt 1) {
    Write-Host "[!] $($candidates.Count) supabase executables are on PATH; only the first one runs:"
    foreach ($c in $candidates) {
        $mark = if ($c -eq $winner) { '  <- wins' } else { '  (shadowed)' }
        Write-Host "      $c$mark"
    }
}

# --- drop user-PATH entries pointing at a supabase dir that is gone ---
# (e.g. after deleting a standalone install by hand - a dead entry is
# harmless but makes `where supabase` output confusing.) Runs on every
# invocation, including the "already latest" early exit below.
function Remove-DeadSupabasePathEntries {
    $userPath = [Environment]::GetEnvironmentVariable('Path','User')
    if (-not $userPath) { return }
    $entries = @($userPath -split ';' | Where-Object { $_ })
    $dead    = @($entries | Where-Object { ($_ -like '*supabase*') -and -not (Test-Path $_) })
    if ($dead.Count -eq 0) { return }
    foreach ($d in $dead) { Write-Host "[*] Removing dead user-PATH entry: $d" }
    $kept = @($entries | Where-Object { $dead -notcontains $_ })
    [Environment]::SetEnvironmentVariable('Path', ($kept -join ';'), 'User')
}
Remove-DeadSupabasePathEntries

# --- latest released version -----------------------------------------
$current = Get-SupabaseVersion $winner
Write-Host '[*] Checking latest Supabase CLI release...'
$headers = @{ 'User-Agent' = 'omniapp-update-script' }
$release = Invoke-RestMethod -Headers $headers -Uri 'https://api.github.com/repos/supabase/cli/releases/latest'
$latest  = $release.tag_name -replace '^v', ''

if ($current -eq $latest -and -not $Force) {
    Write-Host "[OK] Already on the latest version ($current) at $winner. Use -Force to reinstall."
    exit 0
}
if ($current) { Write-Host "    Installed: $current  ->  Latest: $latest" }
else          { Write-Host "    Installing: $latest" }

# --- do the update ---------------------------------------------------
$updatedPath = ''
if ($mode -eq 'npm') {
    if (-not $npmAvailable) { Write-Error 'npm is not on PATH.'; exit 1 }
    Write-Host '[*] Running: npm install -g supabase@latest'
    & npm install -g supabase@latest
    if ($LASTEXITCODE -ne 0) { Write-Error "npm install -g supabase@latest failed (exit $LASTEXITCODE)."; exit 1 }
    $updatedPath = @(Get-SupabaseCandidates | Where-Object { Test-IsNpmManaged $_ } | Select-Object -First 1)[0]
} else {
    # Versioned Windows x64 zip, e.g. supabase_2.109.0_windows_amd64.zip.
    $asset = $release.assets | Where-Object { $_.name -match '^supabase_.*_windows_amd64\.zip$' } | Select-Object -First 1
    if (-not $asset) { Write-Error 'No windows_amd64.zip asset found in the latest release.'; exit 1 }

    $work = Join-Path $env:TEMP ("supabase-cli-update-" + [Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Force -Path $work | Out-Null
    try {
        $zip = Join-Path $work 'supabase.zip'
        Write-Host "[*] Downloading $($asset.name) ..."
        Invoke-WebRequest -Headers $headers -Uri $asset.browser_download_url -OutFile $zip
        Expand-Archive -Path $zip -DestinationPath $work -Force

        $newExe = Join-Path $work 'supabase.exe'
        if (-not (Test-Path $newExe)) { Write-Error 'supabase.exe not found in the downloaded archive.'; exit 1 }

        New-Item -ItemType Directory -Force -Path $standaloneDir | Out-Null
        try {
            Copy-Item $newExe $standaloneExe -Force
        } catch {
            Write-Error "Could not replace $standaloneExe - is a 'supabase' process still running? Close it and retry. ($_)"
            exit 1
        }
        $updatedPath = $standaloneExe

        # First-ever install via this script: make sure the dir is on PATH.
        $userPath = [Environment]::GetEnvironmentVariable('Path','User')
        if ($userPath -notlike "*$standaloneDir*") {
            $newPath = if ([string]::IsNullOrEmpty($userPath)) { $standaloneDir } else { $userPath.TrimEnd(';') + ';' + $standaloneDir }
            [Environment]::SetEnvironmentVariable('Path', $newPath, 'User')
            $env:Path = $env:Path.TrimEnd(';') + ';' + $standaloneDir
            Write-Host "[*] Added $standaloneDir to your user PATH - open a NEW terminal for 'supabase' to resolve."
        }
    } finally {
        Remove-Item -Recurse -Force $work -ErrorAction SilentlyContinue
    }
}

Remove-DeadSupabasePathEntries

# --- verify: is the install we just updated the one PATH will use? ----
$candidates = Get-SupabaseCandidates
$winner     = if ($candidates.Count -gt 0) { $candidates[0] } else { '' }
$version    = Get-SupabaseVersion $winner

if (-not $winner) {
    Write-Host "[OK] Updated to $latest, but 'supabase' is not on PATH in THIS shell yet - open a NEW terminal."
    exit 0
}
Write-Host "[OK] supabase is now $version at $winner"
if ($updatedPath -and $winner -ne $updatedPath) {
    Write-Host "[!] WARNING: this script updated $updatedPath, but PATH resolves supabase to"
    Write-Host "    $winner - so deploy-supabase.bat will run THAT one, not the updated copy."
    Write-Host '    Remove one of the two installs (npm uninstall -g supabase, or delete'
    Write-Host "    $standaloneDir) so a single CLI is in charge."
    exit 1
}
exit 0
