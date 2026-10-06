# Build the Liar's Bar APK WITHOUT Gradle.
#
# Gradle's distribution download kept timing out on this machine, so this script
# drives the Android build tools directly:
#   aapt2 compile -> aapt2 link -> javac -> d8 -> jar (add classes.dex) -> zipalign -> apksigner
#
# All tools come from the SDK we fetched into .toolchain/android-sdk.
# ASCII-only on purpose (avoids GBK mojibake when run through Invoke-Expression).
#
# Usage: powershell -ExecutionPolicy Bypass -File tools\build-apk.ps1

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
if (-not $root) { $root = 'C:\Users\x1s2w3\Documents\deepseek-harness\default-workspace\liarbar' }

$sdk      = Join-Path $root '.toolchain\android-sdk'
$bt       = Join-Path $sdk 'build-tools\34.0.0'
# NOTE: d8 from build-tools 34.0.0 (R8 8.2.2-dev) crashes with an internal NPE on any
# class whose MethodParameters attribute has an unnamed entry -- which javac emits for
# enum / anonymous / inner-class constructors (mandated params). Build-tools 36's d8
# handles those fine, so dexing uses 36 while aapt2/zipalign/apksigner stay on 34.
$d8bt     = Join-Path $sdk 'build-tools\36.0.0'
$platform = Join-Path $sdk 'platforms\android-34\android.jar'
$jdk      = 'C:\Program Files\Android\openjdk\jdk-21.0.8'
# Python is only used to pack assets/dex (see the notes in pack_apk.py)
$py       = 'C:\Users\x1s2w3\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe'

$app      = Join-Path $root 'android\app\src\main'
$coreJava = Join-Path $root 'core\src\main\java'
$out      = Join-Path $root 'build\android'
$dist     = Join-Path $root 'build'
$apk      = Join-Path $dist 'liarbar.apk'

function Require($path, $what) {
    if (-not (Test-Path $path)) { throw "$what not found: $path" }
}
function Step($msg) { Write-Host ""; Write-Host ("=== " + $msg) }

Require "$jdk\bin\javac.exe" 'JDK (javac)'
Require $py 'Python (pack_apk.py)'
Require "$bt\aapt2.exe" 'aapt2 (build-tools 34.0.0)'
Require "$d8bt\d8.bat" 'd8 (build-tools 36.0.0)'
Require "$bt\zipalign.exe" 'zipalign'
Require "$bt\apksigner.bat" 'apksigner'
Require $platform 'android.jar (platforms;android-34)'

$env:JAVA_HOME = $jdk

# fresh output dir
if (Test-Path $out) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory -Force -Path $out, "$out\gen", "$out\classes", "$out\dex" | Out-Null

# ---------------------------------------------------------------- 1. resources
Step "aapt2 compile (resources)"
& "$bt\aapt2.exe" compile --dir "$app\res" -o "$out\res.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed ($LASTEXITCODE)" }

Step "aapt2 link (manifest + resources + assets -> base.apk)"
# AGP 8 forbids the 'package' attribute in the source manifest (it uses namespace
# instead), but standalone aapt2 requires it. So patch a copy for this build only.
$patchedManifest = Join-Path $out 'AndroidManifest.xml'
$manifestText = [System.IO.File]::ReadAllText("$app\AndroidManifest.xml", [System.Text.Encoding]::UTF8)
$manifestText = $manifestText -replace '(?s)<manifest\b', '<manifest package="com.liarsbar.android"'
[System.IO.File]::WriteAllText($patchedManifest, $manifestText, (New-Object System.Text.UTF8Encoding($false)))

& "$bt\aapt2.exe" link `
    -o "$out\base.apk" `
    -I $platform `
    --manifest $patchedManifest `
    -R "$out\res.zip" `
    --java "$out\gen" `
    --min-sdk-version 26 `
    --target-sdk-version 34 `
    --version-code 1 `
    --version-name "1.0" `
    --auto-add-overlay
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed ($LASTEXITCODE)" }

# ---------------------------------------------------------------- 2. javac
Step "javac (app + core)"
$sources = @()
$sources += Get-ChildItem "$app\java" -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$sources += Get-ChildItem $coreJava -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$sources += Get-ChildItem "$out\gen" -Recurse -Filter *.java | ForEach-Object { $_.FullName }
Write-Host ("    {0} java files" -f $sources.Count)

& "$jdk\bin\javac.exe" -encoding UTF-8 -nowarn -source 8 -target 8 `
    -classpath $platform -d "$out\classes" $sources
if ($LASTEXITCODE -ne 0) { throw "javac failed ($LASTEXITCODE)" }

# ---------------------------------------------------------------- 3. d8
Step "d8 (class -> dex)"
& "$jdk\bin\jar.exe" --create --file "$out\classes.jar" -C "$out\classes" .
if ($LASTEXITCODE -ne 0) { throw "jar (classes) failed" }

& "$d8bt\d8.bat" --release --lib $platform --min-api 26 --output "$out\dex" "$out\classes.jar"
if ($LASTEXITCODE -ne 0) { throw "d8 failed ($LASTEXITCODE)" }
Require "$out\dex\classes.dex" 'classes.dex'

# ---------------------------------------------------------------- 4. assemble
Step "pack APK (assets + classes.dex, forward-slash asset names)"
# NOTE: aapt2's -A writes asset entry names with WINDOWS BACKSLASHES, which makes
# AssetManager.open("web/index.html") fail on device (404 for every asset). So assets
# are packed here by pack_apk.py with proper forward slashes instead.
& $py "$root\tools\pack_apk.py" "$out\base.apk" "$app\assets" "$out\dex\classes.dex" "$out\unsigned.apk"
if ($LASTEXITCODE -ne 0) { throw "pack_apk failed ($LASTEXITCODE)" }
Require "$out\unsigned.apk" 'unsigned.apk'

Step "zipalign"
& "$bt\zipalign.exe" -f -p 4 "$out\unsigned.apk" "$out\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "zipalign failed ($LASTEXITCODE)" }

# ---------------------------------------------------------------- 5. sign
Step "sign (debug keystore)"
$ks = Join-Path $root '.toolchain\debug.keystore'
if (-not (Test-Path $ks)) {
    & "$jdk\bin\keytool.exe" -genkeypair -keystore $ks -storepass android -keypass android `
        -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 `
        -dname "CN=Android Debug,O=Android,C=US"
    if ($LASTEXITCODE -ne 0) { throw "keytool failed ($LASTEXITCODE)" }
}

& "$bt\apksigner.bat" sign --ks $ks --ks-pass pass:android --key-pass pass:android `
    --ks-key-alias androiddebugkey --out $apk "$out\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "apksigner failed ($LASTEXITCODE)" }

Step "verify"
& "$bt\apksigner.bat" verify --print-certs $apk | Select-Object -First 6

$size = [math]::Round((Get-Item $apk).Length / 1KB, 1)
Write-Host ""
Write-Host ("[apk] OK -> {0} ({1} KB)" -f $apk, $size)
Write-Host ""
Write-Host "Install with:"
Write-Host ("  `"{0}\platform-tools\adb.exe`" install -r `"{1}`"" -f $sdk, $apk)
