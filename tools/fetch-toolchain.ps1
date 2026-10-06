# 拉取 Android 构建工具链到 workspace 内的 .toolchain 目录
# （本机没有 Android SDK / Gradle，且只有 Python 的 TLS 栈可用）
$ErrorActionPreference = 'Continue'
# 注意：本机 PowerShell 执行策略禁止运行 .ps1，本脚本用 Invoke-Expression 注入执行，
# 因此 $PSScriptRoot 为空，这里写死根目录。
$root = 'C:\Users\x1s2w3\Documents\deepseek-harness\default-workspace\liarbar'
$tc   = Join-Path $root '.toolchain'
$py   = 'C:\Users\x1s2w3\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe'
$jdk  = 'C:\Program Files\Android\openjdk\jdk-21.0.8'
New-Item -ItemType Directory -Force -Path $tc | Out-Null

function Log($m) { Write-Output ("[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss'), $m) }

# ---------- 1. 下载 ----------
$cmdlineZip = Join-Path $tc 'commandlinetools.zip'
$gradleZip  = Join-Path $tc 'gradle-8.7-bin.zip'
Log '开始下载 Android cmdline-tools 与 Gradle 8.7 ...'
& $py (Join-Path $root 'tools\dl.py') `
    'https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip' $cmdlineZip `
    'https://services.gradle.org/distributions/gradle-8.7-bin.zip' $gradleZip
Log ("下载完成: cmdline={0} gradle={1}" -f (Test-Path $cmdlineZip), (Test-Path $gradleZip))

# ---------- 2. 解压 ----------
$sdk = Join-Path $tc 'android-sdk'
if (-not (Test-Path (Join-Path $sdk 'cmdline-tools'))) {
    Log '解压 cmdline-tools ...'
    $tmp = Join-Path $tc 'cltmp'
    if (Test-Path $tmp) { Remove-Item -Recurse -Force $tmp }
    Expand-Archive -LiteralPath $cmdlineZip -DestinationPath $tmp -Force
    New-Item -ItemType Directory -Force -Path (Join-Path $sdk 'cmdline-tools') | Out-Null
    Move-Item (Join-Path $tmp 'cmdline-tools') (Join-Path $sdk 'cmdline-tools\latest') -Force
    Remove-Item -Recurse -Force $tmp
}
$gradleHome = Join-Path $tc 'gradle-8.7'
if (-not (Test-Path $gradleHome)) {
    Log '解压 Gradle ...'
    Expand-Archive -LiteralPath $gradleZip -DestinationPath $tc -Force
}
Log '解压完成'

# ---------- 3. 安装 SDK 组件 ----------
$env:JAVA_HOME = $jdk
$env:ANDROID_HOME = $sdk
$env:ANDROID_SDK_ROOT = $sdk
$sdkmanager = Join-Path $sdk 'cmdline-tools\latest\bin\sdkmanager.bat'
if (Test-Path $sdkmanager) {
    Log '接受 SDK 许可 ...'
    $yes = (1..60 | ForEach-Object { 'y' }) -join "`n"
    $yes | & $sdkmanager --sdk_root="$sdk" --licenses 2>&1 | Select-Object -Last 3 | ForEach-Object { Log $_ }
    Log '安装 platform-tools / platforms;android-34 / build-tools;34.0.0 ...'
    $yes | & $sdkmanager --sdk_root="$sdk" 'platform-tools' 'platforms;android-34' 'build-tools;34.0.0' 2>&1 |
        Select-Object -Last 8 | ForEach-Object { Log $_ }
    Log 'SDK 组件安装结束'
} else {
    Log "找不到 sdkmanager: $sdkmanager"
}

# ---------- 4. 校验 ----------
Log '---- 校验 ----'
Log ("gradle: " + (Test-Path (Join-Path $gradleHome 'bin\gradle.bat')))
Log ("adb:    " + (Test-Path (Join-Path $sdk 'platform-tools\adb.exe')))
Log ("aapt2:  " + (Test-Path (Join-Path $sdk 'build-tools\34.0.0\aapt2.exe')))
Log ("android.jar: " + (Test-Path (Join-Path $sdk 'platforms\android-34\android.jar')))
& (Join-Path $gradleHome 'bin\gradle.bat') --version 2>&1 | Select-Object -First 8 | ForEach-Object { Log $_ }
Log 'ALL DONE'
