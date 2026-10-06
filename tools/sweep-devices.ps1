# sweep-devices.ps1 -- device adaptation regression sweep (ASCII only! PS 5.1 reads .ps1 as GBK)
#
# Why: real phones in landscape have a CSS viewport of only ~780x360 (1080p at dpr 3),
# while the emulator default is ~1100x617. Layout bugs (buttons pushed off screen) only
# show up at the small viewport, so we replay several device profiles here.
#
# The page reports its own findings to the console (see web/js/uicheck.js):
#   [screen] viewport=786x346 dpr=2.75
#   [layout](load) vp=786x346 checked=9 OK
#   [layout](load) vp=786x346 checked=9 FAIL OUT[lobbyBtn#0 ...]
#   [rect] startBtn#0 "..." 303,602 380x120     <- CSS px, the script taps these
#
# Usage: powershell -ExecutionPolicy Bypass -File tools\sweep-devices.ps1 [-WithGame] [-Only name]

param(
    [switch]$WithGame,
    [string]$Dev = 'emulator-5554',
    [string]$Only = ''
)

$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
$adb = Join-Path $root '.toolchain\android-sdk\platform-tools\adb.exe'
$javaExe = 'C:\Program Files\Android\openjdk\jdk-21.0.8\bin\java.exe'

# profile = resolution + dpi + system font scale
# CSS viewport height = height / (dpi/160) / font_scale
$profiles = @(
    @{ name = 'samsung-1080p-dpr3';   size = '2340x1080'; dpi = 480; fs = '1.0' },
    @{ name = 'huawei-1080p-dpr2.75'; size = '2340x1080'; dpi = 440; fs = '1.0' },
    @{ name = 'huawei-fontbig';       size = '2340x1080'; dpi = 440; fs = '1.45' },
    @{ name = 'xiaomi-1080p-dpr2.5';  size = '2400x1080'; dpi = 400; fs = '1.0' },
    @{ name = 'oldphone-1080p-dpr2';  size = '1920x1080'; dpi = 320; fs = '1.0' },
    @{ name = 'small-720p-dpr2';      size = '1280x720';  dpi = 320; fs = '1.0' },
    @{ name = 'tablet-1600p';         size = '2560x1600'; dpi = 320; fs = '1.0' },
    @{ name = 'extreme-short';        size = '2160x900';  dpi = 440; fs = '1.45' }
)

function Get-LogText {
    param([string]$Serial)
    # Only our own tag: the emulator emits megabytes of system log, which evicts the
    # app's lines from the ring buffer and makes matching unreliable.
    $lines = & $adb -s $Serial logcat -d -s MainActivity:V 2>&1
    $text = ($lines | Out-String)
    if ($text.Length -lt 40) {
        Write-Host ('    [warn] app log almost empty (' + $text.Length + ' chars): ' + $text.Trim())
    }
    return $text
}

function Get-Rect {
    # NOTE: take the FIRST match, not the last. startBtn#0 is the "enter table"
    # button; later ones are "copy address" / "join" / "search nearby".
    param([string]$Text, [string]$Key, [int]$Index = 0)
    $pattern = '\[rect\]\s+' + [regex]::Escape($Key) + '#' + $Index + '\s+"([^"]*)"\s+(\d+),(\d+)\s+(\d+)x(\d+)'
    $m = [regex]::Match($Text, $pattern)
    if (-not $m.Success) { return $null }
    return @{
        x     = [int]$m.Groups[2].Value
        y     = [int]$m.Groups[3].Value
        w     = [int]$m.Groups[4].Value
        h     = [int]$m.Groups[5].Value
        label = $m.Groups[1].Value
    }
}

function Tap-Css {
    param([string]$Serial, [double]$Dpr, $Rect)
    if (-not $Rect) { return $false }
    $dx = [int][math]::Round($Rect.x * $Dpr)
    $dy = [int][math]::Round($Rect.y * $Dpr)
    & $adb -s $Serial shell input tap $dx $dy 2>&1 | Out-Null
    return $true
}

function Get-Verdict {
    # Tag empty = any tag. View: lobby / game. Page: /start.html or /gamepage
    param([string]$Text, [string]$Tag, [string]$View = '', [string]$Page = '')
    $tagPart = if ($Tag) { '\(' + [regex]::Escape($Tag) + '\)' } else { '\([a-z]+\)' }
    $pattern = '\[layout\]' + $tagPart + '[^\r\n]*'
    $best = 'NO-CHECK'
    foreach ($m in [regex]::Matches($Text, $pattern)) {
        $line = $m.Value
        if ($View -and $line -notmatch ('view=' + [regex]::Escape($View) + '(\s|$)')) { continue }
        if ($Page -and $line -notmatch ('page=' + [regex]::Escape($Page) + '(\s|$)')) { continue }
        if ($line -match ' OK$') { $best = 'OK' }
        else {
            $idx = $line.IndexOf('FAIL ')
            if ($idx -ge 0) { $best = $line.Substring($idx + 5) } else { $best = $line }
        }
    }
    return $best
}

& $adb start-server 2>&1 | Out-Null
# Bigger log buffers so the app's lines survive the emulator's log flood
& $adb -s $Dev logcat -G 16M 2>&1 | Out-Null
$results = @()

foreach ($p in $profiles) {
    if ($Only -and $p.name -notlike ('*' + $Only + '*')) { continue }

    Write-Host ('=== ' + $p.name + '  ' + $p.size + ' @' + $p.dpi + 'dpi  font=' + $p.fs + ' ===')

    & $adb -s $Dev shell settings put system font_scale $p.fs 2>&1 | Out-Null
    & $adb -s $Dev shell wm size $p.size 2>&1 | Out-Null
    & $adb -s $Dev shell wm density $p.dpi 2>&1 | Out-Null
    Start-Sleep -Seconds 3

    & $adb -s $Dev shell am force-stop com.liarsbar.android 2>&1 | Out-Null
    & $adb -s $Dev logcat -c 2>&1 | Out-Null
    # auto_enter: after ~8s the start page walks itself into the table, so the sweep
    # never depends on tapping (emulator input coordinates turned out unreliable).
    & $adb -s $Dev shell am start -n com.liarsbar.android/.MainActivity --ez auto_enter true 2>&1 | Out-Null
    Start-Sleep -Seconds 4

    # For the in-game check the PC helper must be in the room BEFORE the phone enters,
    # otherwise the phone becomes host and nothing can start the game.
    $host = $null
    if ($WithGame) {
        & $adb -s $Dev forward tcp:19080 tcp:8080 2>&1 | Out-Null
        $cp = (Join-Path $root 'build\classes') + ';' + (Join-Path $root 'build\test-classes')
        $host = Start-Process -FilePath $javaExe -ArgumentList @(
            '-Dfile.encoding=UTF-8', '-cp', $cp,
            'HostBots', '19080', '2', '120', '4', '700', '1'
        ) -NoNewWindow -PassThru `
            -RedirectStandardOutput (Join-Path $root 'build\sweep-host.log') `
            -RedirectStandardError (Join-Path $root 'build\sweep-host.err')
    }

    Start-Sleep -Seconds 10
    $log = Get-LogText $Dev
    Write-Host ('    log chars=' + $log.Length + '  screen-lines=' +
        ([regex]::Matches($log, '\[screen\]')).Count +
        '  layout-lines=' + ([regex]::Matches($log, '\[layout\]')).Count)
    $dpr = 1.0
    $sm = [regex]::Match($log, 'dpr=([0-9.]+)')
    if ($sm.Success) { $dpr = [double]$sm.Groups[1].Value }
    $vm = [regex]::Match($log, '\[screen\][^\r\n]*viewport=(\d+)x(\d+)')
    if ($vm.Success) { $vp = $vm.Groups[1].Value + 'x' + $vm.Groups[2].Value } else { $vp = '?' }

    $startVerdict = Get-Verdict $log 'load' '' '/start.html'

    $lobbyVerdict = 'SKIP'
    $tableVerdict = 'SKIP'
    if ($WithGame -or $true) {
        Start-Sleep -Seconds 16
        $log2 = Get-LogText $Dev
        $lobbyVerdict = Get-Verdict $log2 '' 'lobby'
        $tableVerdict = Get-Verdict $log2 '' 'game'
        if ($host -and -not $host.HasExited) { $host | Stop-Process -Force -ErrorAction SilentlyContinue }
        Start-Sleep -Seconds 1
    }

    Write-Host ('    vp=' + $vp + ' dpr=' + $dpr)
    Write-Host ('    start : ' + $startVerdict)
    Write-Host ('    lobby : ' + $lobbyVerdict)
    if ($WithGame) { Write-Host ('    table : ' + $tableVerdict) }

    $results += [pscustomobject]@{
        profile  = $p.name
        viewport = $vp
        dpr      = $dpr
        start    = $startVerdict
        lobby    = $lobbyVerdict
        table    = $tableVerdict
    }
}

Write-Host ''
Write-Host '================ SUMMARY ================'
$results | Format-Table -AutoSize -Wrap | Out-String | Write-Host

& $adb -s $Dev shell wm size reset 2>&1 | Out-Null
& $adb -s $Dev shell wm density reset 2>&1 | Out-Null
& $adb -s $Dev shell settings put system font_scale 1.0 2>&1 | Out-Null
Write-Host 'screen settings restored'
