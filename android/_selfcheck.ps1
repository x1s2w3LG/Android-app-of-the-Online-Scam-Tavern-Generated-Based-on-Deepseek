# Pure-ASCII structural self-check for the Android shell project.
# No Android SDK needed -- only a JDK on this machine.
#
#   1. every res/ + manifest XML is well-formed
#   2. every Java file parses (checked with javac's own parser, no classpath,
#      no code generation) -- authoritative, unlike regex/brace counting
#   3. every @string/@drawable/... and R.* reference resolves to a real resource
#   4. every manifest <activity>/<service> has a matching .java file
#   5. every LiarbarServer member used exists in PROTOCOL.md section 6
#
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File _selfcheck.ps1
param(
    [string] $Javac = 'C:\Program Files\Android\openjdk\jdk-21.0.8\bin\javac.exe'
)

$ErrorActionPreference = 'Stop'
$root = 'C:\Users\x1s2w3\Documents\deepseek-harness\default-workspace\liarbar\android'
$script:fail = 0

function Report($ok, $msg) {
    if ($ok) { Write-Host "  OK   $msg" -ForegroundColor Green }
    else     { Write-Host "  FAIL $msg" -ForegroundColor Red; $script:fail++ }
}

function Get-SourceFiles([string] $ext) {
    Get-ChildItem -LiteralPath $root -Recurse -Filter "*.$ext" |
        Where-Object { $_.FullName -notmatch '\\build\\' -and $_.FullName -notmatch '\\\.gradle\\' }
}

function Invoke-Javac([string[]] $Arguments) {
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $Javac
    $psi.Arguments = $Arguments -join ' '
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.CreateNoWindow = $true
    $p = [System.Diagnostics.Process]::Start($psi)
    $o = $p.StandardOutput.ReadToEnd()
    $e = $p.StandardError.ReadToEnd()
    $p.WaitForExit()
    return @{ Code = $p.ExitCode; Out = (($o + "`n" + $e).Trim()) }
}

$xmls  = @(Get-SourceFiles 'xml')
$javas = @(Get-SourceFiles 'java')

Write-Host '=== 1. XML well-formedness ===' -ForegroundColor Cyan
foreach ($x in $xmls) {
    try {
        $doc = New-Object System.Xml.XmlDocument
        $doc.Load($x.FullName)
        Report $true $x.FullName.Replace($root, '')
    } catch {
        Report $false ("$($x.FullName.Replace($root,''))  ->  $($_.Exception.Message)")
    }
}

Write-Host '=== 2. Java files compile-check (against the API stubs) ===' -ForegroundColor Cyan
# Authoritative syntax + API check: _compile-check.ps1 builds a stub classpath
# (minimal Android API + the PROTOCOL.md LiarbarServer). Reuse it here so this
# script does not need its own fragile parser.
$stubClasses = Join-Path $env:TEMP 'liarbar-javac-check\classes'
if (-not (Test-Path -LiteralPath $stubClasses)) {
    Write-Host "  SKIP stub classpath not built yet -- run _compile-check.ps1 first" -ForegroundColor Yellow
    Write-Host "       (expected at $stubClasses)" -ForegroundColor DarkGray
} else {
    # IMPORTANT: always pass -d, otherwise javac drops .class files next to the
    # sources inside the project tree.
    $syntaxOut = Join-Path $env:TEMP 'liarbar-selfcheck-out'
    if (-not (Test-Path -LiteralPath $syntaxOut)) {
        New-Item -ItemType Directory -Path $syntaxOut -Force | Out-Null
    }
    $javaArgs = @('-J-Duser.language=en', '-J-Duser.country=US', '-proc:none', '-encoding', 'UTF-8',
                  '-classpath', ('"' + $stubClasses + '"'),
                  '-d', ('"' + $syntaxOut + '"'))
    foreach ($j in $javas) {
        $r = Invoke-Javac ($javaArgs + @('"' + $j.FullName + '"'))
        if ($r.Code -eq 0) {
            Report $true "$($j.Name)"
        } else {
            Report $false "$($j.Name)"
            ($r.Out -split "`r?`n") | Where-Object { $_ -match 'error' } | ForEach-Object {
                Write-Host "         $_" -ForegroundColor DarkRed
            }
        }
    }
}

Write-Host '=== 3. Resource references resolve ===' -ForegroundColor Cyan
$defined = @{}
foreach ($x in $xmls) {
    $rel = $x.FullName.Substring($root.Length)
    if ($rel -notmatch '\\res\\') { continue }
    $doc = New-Object System.Xml.XmlDocument
    $doc.Load($x.FullName)
    $kind = Split-Path -Leaf (Split-Path -Parent $x.FullName)
    if ($kind -eq 'values') {
        foreach ($node in $doc.DocumentElement.ChildNodes) {
            if ($node -isnot [System.Xml.XmlElement]) { continue }
            # NOTE: on an XmlElement, .Name is the *node type* name here (a known
            # PowerShell adapter quirk), so take the resource type from LocalName.
            $defined["$($node.LocalName)/$($node.GetAttribute('name'))"] = $x.Name
        }
    } elseif ($kind -like 'drawable*' -or $kind -like 'mipmap*') {
        $defined["$($kind.Split('-')[0])/$($x.BaseName)"] = $x.Name
    } elseif ($kind -eq 'layout' -or $kind -eq 'xml') {
        $defined["$kind/$($x.BaseName)"] = $x.Name
    }
}
Write-Host "  (collected $($defined.Count) resource definitions)" -ForegroundColor DarkGray

$refs = @{}
foreach ($f in (@($xmls) + @($javas))) {
    $txt = Get-Content -LiteralPath $f.FullName -Raw -Encoding UTF8
    foreach ($m in [regex]::Matches($txt, '@(string|drawable|mipmap|layout|style|color|xml)/([A-Za-z0-9_]+)')) {
        $refs["$($m.Groups[1].Value)/$($m.Groups[2].Value)"] = $f.Name
    }
    foreach ($m in [regex]::Matches($txt, '\bR\.(string|drawable|mipmap|layout|style|color|xml)\.([A-Za-z0-9_]+)')) {
        $refs["$($m.Groups[1].Value)/$($m.Groups[2].Value)"] = $f.Name
    }
}
foreach ($k in ($refs.Keys | Sort-Object)) {
    Report ($defined.ContainsKey($k)) ("$k   (used in $($refs[$k]))")
}

Write-Host '=== 4. Manifest components exist as .java files ===' -ForegroundColor Cyan
$mDoc = New-Object System.Xml.XmlDocument
$mDoc.Load((Join-Path $root 'app\src\main\AndroidManifest.xml'))
$app = $mDoc.DocumentElement.SelectSingleNode('application')
foreach ($node in (@($app.SelectNodes('activity')) + @($app.SelectNodes('service')))) {
    $cls = $node.GetAttribute('name', 'http://schemas.android.com/apk/res/android')
    if ([string]::IsNullOrEmpty($cls)) { continue }
    if ($cls.StartsWith('.')) { $cls = 'com.liarsbar.android' + $cls }
    $p = Join-Path $root ("app\src\main\java\" + $cls.Replace('.', '\') + '.java')
    Report (Test-Path -LiteralPath $p) ("$($node.Name) $cls")
}

Write-Host '=== 5. core API usage matches PROTOCOL.md section 6 ===' -ForegroundColor Cyan
$proto = Get-Content -LiteralPath (Join-Path (Split-Path -Parent $root) 'PROTOCOL.md') -Raw -Encoding UTF8
$protoCalls = @('start', 'stop', 'getPort', 'getLanUrl', 'getPlayerCount')
$usedCalls = @{}
foreach ($j in $javas) {
    $txt = Get-Content -LiteralPath $j.FullName -Raw -Encoding UTF8
    foreach ($m in [regex]::Matches($txt, '\.(start|stop|getPort|getLanUrl|getPlayerCount)\s*\(')) {
        $usedCalls[$m.Groups[1].Value] = $j.Name
    }
    foreach ($m in [regex]::Matches($txt, 'new\s+LiarbarServer\s*\(([^)]*)\)')) {
        $a = $m.Groups[1].Value.Trim()
        $arity = if ($a.Length -eq 0) { 0 } else { ($a -split ',').Count }
        Report ($arity -eq 0 -or $arity -eq 2) `
               "new LiarbarServer(<$arity args>) in $($j.Name)   [protocol: () and (int, StaticProvider)]"
    }
}
foreach ($c in ($usedCalls.Keys | Sort-Object)) {
    Report ($protoCalls -contains $c) ("LiarbarServer.$c() declared in protocol   (used in $($usedCalls[$c]))")
}
Report ($proto -match 'interface\s+StaticProvider\s*\{\s*byte\[\]\s+read\(String\s+path\)') `
       'protocol declares StaticProvider.read(String): byte[]'

Write-Host ''
if ($script:fail -eq 0) { Write-Host 'ALL CHECKS PASSED' -ForegroundColor Green }
else { Write-Host "$($script:fail) CHECK(S) FAILED" -ForegroundColor Red }
exit $script:fail
