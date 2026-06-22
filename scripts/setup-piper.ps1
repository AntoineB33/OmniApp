<#
.SYNOPSIS
  Install Piper (free, offline neural TTS) for OmniApp's desktop voice cues.

.DESCRIPTION
  Downloads the Piper Windows binary and a voice model into the directory the app
  reads at runtime (~/.omniapp/piper by default; see Voice.jvm.kt / piperDir).
  After this runs, the desktop app speaks with Piper instead of the robotic SAPI
  fallback. Re-running is safe; pass -Force to re-download an existing install.

  Layout produced (matches piperExecutable()/piperModel() in Voice.jvm.kt):
    <Dir>\piper.exe
    <Dir>\espeak-ng-data\...
    <Dir>\en_US-amy-medium.onnx
    <Dir>\en_US-amy-medium.onnx.json

.PARAMETER Dir
  Target install directory. Defaults to $env:USERPROFILE\.omniapp\piper.
  If you override it, launch the app with -Domniapp.piperDir=<Dir>.

.PARAMETER Force
  Re-download even if the binary/model already exist.
#>
[CmdletBinding()]
param(
    [string] $Dir = (Join-Path $env:USERPROFILE ".omniapp\piper"),
    [switch] $Force
)

$ErrorActionPreference = "Stop"

# Pinned versions so installs are reproducible. Bump as new releases land.
$PiperZipUrl   = "https://github.com/rhasspy/piper/releases/download/2023.11.14-2/piper_windows_amd64.zip"
$VoiceBaseUrl  = "https://huggingface.co/rhasspy/piper-voices/resolve/v1.0.0/en/en_US/amy/medium"
$VoiceModel    = "en_US-amy-medium.onnx"

# Download with retries. The HuggingFace CDN occasionally resets mid-transfer,
# which leaves a truncated file; we retry a few times before giving up.
function Get-File {
    param([string] $Url, [string] $OutFile)
    $attempts = 4
    for ($i = 1; $i -le $attempts; $i++) {
        try {
            Invoke-WebRequest -Uri $Url -OutFile $OutFile -UseBasicParsing
            return
        } catch {
            if ($i -eq $attempts) { throw }
            Write-Host "      attempt $i failed ($($_.Exception.Message.Trim())); retrying..."
            Start-Sleep -Seconds 2
        }
    }
}

Write-Host "Installing Piper into: $Dir"
New-Item -ItemType Directory -Force -Path $Dir | Out-Null

# --- Binary -----------------------------------------------------------------
$exe = Join-Path $Dir "piper.exe"
if ((Test-Path $exe) -and -not $Force) {
    Write-Host "[1/2] piper.exe already present (use -Force to re-download)."
} else {
    Write-Host "[1/2] Downloading Piper binary..."
    $tmpZip = Join-Path ([System.IO.Path]::GetTempPath()) "piper_windows_amd64.zip"
    Get-File -Url $PiperZipUrl -OutFile $tmpZip

    # The zip contains a top-level "piper\" folder; extract to the PARENT so its
    # contents land directly in $Dir.
    $parent = Split-Path -Parent $Dir
    Write-Host "      Extracting..."
    Expand-Archive -Path $tmpZip -DestinationPath $parent -Force
    Remove-Item $tmpZip -Force

    if (-not (Test-Path $exe)) {
        throw "Extraction did not produce $exe. Check the archive layout: $PiperZipUrl"
    }
}

# --- Voice model ------------------------------------------------------------
$model = Join-Path $Dir $VoiceModel
if ((Test-Path $model) -and -not $Force) {
    Write-Host "[2/2] Voice model already present (use -Force to re-download)."
} else {
    Write-Host "[2/2] Downloading voice model ($VoiceModel)..."
    Get-File -Url "$VoiceBaseUrl/$VoiceModel"      -OutFile $model
    Get-File -Url "$VoiceBaseUrl/$VoiceModel.json" -OutFile "$model.json"
}

Write-Host ""
Write-Host "Done. OmniApp will now use Piper for voice cues." -ForegroundColor Green
Write-Host "Quick test:"
Write-Host "  'Look away from the screen.' | & '$exe' --model '$model' --output_file `"$env:TEMP\piper-test.wav`"; Start-Process `"$env:TEMP\piper-test.wav`""
