# ============================================================================
#  sync-web.ps1 -- copy core web assets into the Android APK assets folder
#
#  Source : <repo>\liarbar\core\src\main\resources\web\**
#  Target : <repo>\liarbar\android\app\src\main\assets\web\
#
#  Usage:
#      powershell -NoProfile -ExecutionPolicy Bypass -File tools\sync-web.ps1
#      powershell -NoProfile -ExecutionPolicy Bypass -File tools\sync-web.ps1 -DryRun
#
#  Notes:
#    * All paths are derived from $PSScriptRoot; no hard-coded absolute paths.
#    * The target folder is wiped first, so files deleted in core disappear
#      from the APK as well.
#    * !! THIS FILE IS DELIBERATELY PURE ASCII !!
#      Windows PowerShell 5.1 decodes BOM-less UTF-8 files using the system
#      ANSI code page (GBK on a Chinese Windows). Non-ASCII characters in a
#      .ps1 therefore cause "The string is missing the terminator" parser
#      errors. Keep every byte in this file < 0x80.
# ============================================================================

[CmdletBinding()]
param(
    # Optional: point at a different web source folder
    [string] $SourceDir,

    # Preview only, do not touch the filesystem
    [switch] $DryRun
)

$ErrorActionPreference = 'Stop'

function Write-Step  ([string] $m) { Write-Host "==> $m" -ForegroundColor Cyan }
function Write-Ok    ([string] $m) { Write-Host "    $m" -ForegroundColor Green }
function Write-Note  ([string] $m) { Write-Host "    $m" -ForegroundColor Yellow }

# --- 1. Resolve paths -------------------------------------------------------

# $PSScriptRoot = <repo>\liarbar\tools
$toolsDir   = $PSScriptRoot
$liarbarDir = Split-Path -Parent $toolsDir            # <repo>\liarbar
$androidDir = Join-Path $liarbarDir 'android'         # <repo>\liarbar\android

# <repo>\liarbar\android\app\src\main\assets\web
$destDir = Join-Path $androidDir 'app\src\main\assets\web'

if ([string]::IsNullOrWhiteSpace($SourceDir)) {
    $SourceDir = Join-Path $liarbarDir 'core\src\main\resources\web'
}

$SourceDir = [System.IO.Path]::GetFullPath($SourceDir)
$destDir   = [System.IO.Path]::GetFullPath($destDir)

Write-Step "source : $SourceDir"
Write-Step "target : $destDir"

if (-not (Test-Path -LiteralPath $SourceDir -PathType Container)) {
    Write-Host ''
    Write-Host 'Web asset source folder not found:' -ForegroundColor Red
    Write-Host "  $SourceDir" -ForegroundColor Red
    Write-Host ''
    Write-Note 'The core module (and its web/ resources) may not exist yet.'
    Write-Note 'Ask the core author to put the front-end files under'
    Write-Note 'core\src\main\resources\web\ and run this script again.'
    exit 1
}

# --- 2. Safety check: target must really be ...\assets\web -------------------

$destLeaf = Split-Path -Leaf $destDir
if ($destLeaf -ne 'web' -or $destDir -notmatch '[\\/]assets[\\/]web$') {
    throw "Refusing to delete an unexpected target path: $destDir"
}

# --- 3. Wipe the target folder ---------------------------------------------

if (Test-Path -LiteralPath $destDir) {
    if ($DryRun) {
        Write-Step "[DryRun] would wipe $destDir"
    }
    else {
        Write-Step 'wiping old target folder'
        try {
            Remove-Item -LiteralPath $destDir -Recurse -Force
        }
        catch {
            throw "Failed to wipe target folder: $destDir`n$($_.Exception.Message)"
        }
    }
}

if (-not $DryRun) {
    New-Item -ItemType Directory -Path $destDir -Force | Out-Null
}

# --- 4. Copy ----------------------------------------------------------------

Write-Step 'copying web assets'

$srcFull = (Get-Item -LiteralPath $SourceDir).FullName
$skipNames = @('.gitkeep', '.DS_Store', 'Thumbs.db', 'desktop.ini')

$files = @(
    Get-ChildItem -LiteralPath $srcFull -Recurse -File -Force |
        Where-Object { $skipNames -notcontains $_.Name }
)

if ($files.Count -eq 0) {
    Write-Note "source folder contains no files ($srcFull)"
}

$copied = 0
foreach ($f in $files) {
    $rel     = $f.FullName.Substring($srcFull.Length).TrimStart('\', '/')
    $target  = Join-Path $destDir $rel
    $tParent = Split-Path -Parent $target

    if ($DryRun) {
        Write-Host "    [DryRun] $rel"
        $copied++
        continue
    }

    if (-not (Test-Path -LiteralPath $tParent)) {
        New-Item -ItemType Directory -Path $tParent -Force | Out-Null
    }
    Copy-Item -LiteralPath $f.FullName -Destination $target -Force
    $copied++
}

# --- 5. Keep a placeholder so the folder survives in git --------------------

if (-not $DryRun) {
    $gitkeep = Join-Path $destDir '.gitkeep'
    $note = "# Filled by tools/sync-web.ps1 from core/src/main/resources/web/.`n" +
            "# Do not hand-write web content in this folder.`n"
    [System.IO.File]::WriteAllText($gitkeep, $note, (New-Object System.Text.UTF8Encoding($false)))
}

# --- 6. Report --------------------------------------------------------------

Write-Host ''
Write-Ok "copied $copied file(s) -> $destDir"

if ($files.Count -gt 0) {
    Write-Host ''
    Write-Host 'top level:' -ForegroundColor DarkGray
    Get-ChildItem -LiteralPath $destDir -Force |
        Sort-Object { -not $_.PSIsContainer }, Name |
        ForEach-Object {
            $kind = if ($_.PSIsContainer) { 'dir ' } else { 'file' }
            Write-Host ("    [{0}] {1}" -f $kind, $_.Name) -ForegroundColor DarkGray
        }

    $hasIndex = @($files | Where-Object { $_.Name -ieq 'index.html' }).Count -gt 0
    $hasIndexInRoot = $false
    if ($hasIndex) {
        $hasIndexInRoot = @(
            $files | Where-Object {
                $_.Name -ieq 'index.html' -and
                (Split-Path -Parent $_.FullName) -eq $srcFull
            }
        ).Count -gt 0
    }

    if (-not $hasIndex) {
        Write-Host ''
        Write-Note 'WARNING: no index.html found -- the server will 404 on "/"'
    }
    elseif (-not $hasIndexInRoot) {
        Write-Host ''
        Write-Note 'WARNING: index.html is not at the web/ root -- the server will 404 on "/"'
    }
}

exit 0
