# Build Liar's Bar desktop server jar (ASCII-only on purpose: avoids GBK mojibake).
# Usage: powershell -ExecutionPolicy Bypass -File tools\build.ps1
$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
if (-not $root) { $root = 'C:\Users\x1s2w3\Documents\deepseek-harness\default-workspace\liarbar' }

$jdk = 'C:\Program Files\Android\openjdk\jdk-21.0.8'
if (-not (Test-Path "$jdk\bin\javac.exe")) { $jdk = $env:JAVA_HOME }
if (-not $jdk -or -not (Test-Path "$jdk\bin\javac.exe")) {
    throw "JDK not found. Set JAVA_HOME or edit the jdk path in this script."
}

$classes = Join-Path $root 'build\classes'
$dist    = Join-Path $root 'build'
$jar     = Join-Path $dist 'liarbar-server.jar'

if (Test-Path $classes) { Remove-Item -Recurse -Force $classes }
New-Item -ItemType Directory -Force -Path $classes | Out-Null

$sources = @()
$sources += Get-ChildItem (Join-Path $root 'core\src\main\java')     -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$sources += Get-ChildItem (Join-Path $root 'desktop\src\main\java')  -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$launcherDir = Join-Path $root 'launcher\src\main\java'
if (Test-Path $launcherDir) {
    $sources += Get-ChildItem $launcherDir -Recurse -Filter *.java | ForEach-Object { $_.FullName }
}

Write-Host ("[build] compiling {0} java files ..." -f $sources.Count)
# --release 11 keeps the code compilable by the Android toolchain too
& "$jdk\bin\javac.exe" -encoding UTF-8 --release 11 -d $classes $sources
if ($LASTEXITCODE -ne 0) { throw "javac failed with exit code $LASTEXITCODE" }

$res = Join-Path $root 'core\src\main\resources'
if (Test-Path $res) {
    Copy-Item (Join-Path $res '*') $classes -Recurse -Force
    Write-Host "[build] web resources copied from core\src\main\resources"
} else {
    Write-Host "[build] WARNING: no core\src\main\resources (frontend missing?)"
}

if (Test-Path $jar) { Remove-Item -Force $jar }
& "$jdk\bin\jar.exe" --create --file $jar --main-class com.liarsbar.desktop.Main -C $classes .
if ($LASTEXITCODE -ne 0) { throw "jar failed with exit code $LASTEXITCODE" }

$size = [math]::Round((Get-Item $jar).Length / 1KB, 1)
Write-Host ("[build] OK -> {0} ({1} KB)" -f $jar, $size)

# ---- graphics launcher jar (same classes, different main class) ----
if (Test-Path $launcherDir) {
    $guiJar = Join-Path $dist 'liarbar-launcher.jar'
    if (Test-Path $guiJar) { Remove-Item -Force $guiJar }
    & "$jdk\bin\jar.exe" --create --file $guiJar --main-class com.liarsbar.launcher.Launcher -C $classes .
    if ($LASTEXITCODE -ne 0) { throw "jar (launcher) failed with exit code $LASTEXITCODE" }
    $gsize = [math]::Round((Get-Item $guiJar).Length / 1KB, 1)
    Write-Host ("[build] OK -> {0} ({1} KB)" -f $guiJar, $gsize)
}

Write-Host ""
Write-Host "Run it with:"
Write-Host ("  `"{0}\bin\java.exe`" -jar `"{1}`"" -f $jdk, $jar)
Write-Host ("  `"{0}\bin\javaw.exe`" -jar `"{1}`"   (graphics launcher)" -f $jdk, (Join-Path $dist 'liarbar-launcher.jar'))
