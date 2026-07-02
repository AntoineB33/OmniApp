# =====================================================================
#  update-supabase-cli.ps1 - download the latest standalone Supabase CLI
#  and replace the copy under %LOCALAPPDATA%\supabase\bin (the location
#  scripts/deploy-supabase.bat expects on PATH). No package manager.
#
#  Called by scripts/update-supabase-cli.bat. Pass -Force to reinstall
#  even when already on the latest version.
#  Location: <project-root>\scripts\internal\update-supabase-cli.ps1
# =====================================================================
param([switch]$Force)

$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$dest    = Join-Path $env:LOCALAPPDATA 'supabase\bin'
$destExe = Join-Path $dest 'supabase.exe'

# --- current installed version (empty if not installed yet) ----------
$current = ''
if (Test-Path $destExe) {
    try { $current = (& $destExe --version 2>$null | Select-Object -First 1).Trim() } catch { $current = '' }
}

# --- latest release from GitHub (needs a User-Agent header) ----------
Write-Host '[*] Checking latest Supabase CLI release...'
$headers = @{ 'User-Agent' = 'omniapp-update-script' }
$release = Invoke-RestMethod -Headers $headers -Uri 'https://api.github.com/repos/supabase/cli/releases/latest'
$latest  = $release.tag_name -replace '^v', ''

if ($current -eq $latest -and -not $Force) {
    Write-Host "[OK] Already on the latest version ($current). Use -Force to reinstall."
    exit 0
}
if ($current) { Write-Host "    Installed: $current  ->  Latest: $latest" }
else          { Write-Host "    Installing: $latest" }

# Pick the versioned Windows x64 zip (e.g. supabase_2.109.0_windows_amd64.zip).
$asset = $release.assets | Where-Object { $_.name -match '^supabase_.*_windows_amd64\.zip$' } | Select-Object -First 1
if (-not $asset) { Write-Error 'No windows_amd64.zip asset found in the latest release.'; exit 1 }

# --- download + extract into a temp workspace ------------------------
$work = Join-Path $env:TEMP ("supabase-cli-update-" + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $work | Out-Null
try {
    $zip = Join-Path $work 'supabase.zip'
    Write-Host "[*] Downloading $($asset.name) ..."
    Invoke-WebRequest -Headers $headers -Uri $asset.browser_download_url -OutFile $zip
    Expand-Archive -Path $zip -DestinationPath $work -Force

    $newExe = Join-Path $work 'supabase.exe'
    if (-not (Test-Path $newExe)) { Write-Error 'supabase.exe not found in the downloaded archive.'; exit 1 }

    New-Item -ItemType Directory -Force -Path $dest | Out-Null
    try {
        Copy-Item $newExe $destExe -Force
    } catch {
        Write-Error "Could not replace $destExe - is a 'supabase' process still running? Close it and retry. ($_)"
        exit 1
    }
    Write-Host "[OK] Updated to $((& $destExe --version 2>$null | Select-Object -First 1).Trim()) at $destExe"

    # Remind if this dir isn't on PATH yet (first-ever install via this script).
    $userPath = [Environment]::GetEnvironmentVariable('Path','User')
    if ($userPath -notlike "*$dest*") {
        $newPath = if ([string]::IsNullOrEmpty($userPath)) { $dest } else { $userPath.TrimEnd(';') + ';' + $dest }
        [Environment]::SetEnvironmentVariable('Path', $newPath, 'User')
        Write-Host "[*] Added $dest to your user PATH - open a NEW terminal for 'supabase' to resolve."
    }
} finally {
    Remove-Item -Recurse -Force $work -ErrorAction SilentlyContinue
}
