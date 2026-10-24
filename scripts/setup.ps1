. "$PSScriptRoot\lib.ps1"
Start-Log 'setup'
Write-Host ""
Write-Host "=== FutureQuestr setup: checking what you already have ===" -ForegroundColor White
$result = @{ java_home = ''; sdk = ''; gradle = ''; node_dir = '' }

# ---------- Java 17+ ----------
function Java-Major($javaExe) {
    $old = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    try {
        $o = & cmd.exe /c "`"$javaExe`" -version 2>&1" | Out-String
        if ($o -match 'version "(\d+)(\.(\d+))?') {
            $m = [int]$Matches[1]
            if ($m -eq 1 -and $Matches[3]) { $m = [int]$Matches[3] }
            return $m
        }
    } catch {} finally { $ErrorActionPreference = $old }
    return 0
}
function Jdk-Ok($home_) {
    if (-not $home_) { return $false }
    foreach ($f in 'java.exe', 'javac.exe', 'keytool.exe') { if (-not (Test-Path "$home_\bin\$f")) { return $false } }
    return ((Java-Major "$home_\bin\java.exe") -ge 17)
}

$jdk = ''
$cands = @()
if ($env:JAVA_HOME) { $cands += $env:JAVA_HOME }
$j = Get-Command java -ErrorAction SilentlyContinue
if ($j) { $cands += (Split-Path -Parent (Split-Path -Parent $j.Source)) }
foreach ($d in Get-ChildItem "$ToolsDir" -Directory -Filter 'jdk*' -ErrorAction SilentlyContinue) { $cands += $d.FullName }
foreach ($c in $cands) { if (Jdk-Ok $c) { $jdk = $c; break } }
if ($jdk) {
    Found "Java 17+ at $jdk"
} else {
    Missing "Java 17+ (JDK). Downloading a portable copy into tools\ (no admin needed)..."
    $zip = Join-Path $ToolsDir 'jdk.zip'
    Download 'https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse' $zip
    $tmp = Join-Path $ToolsDir 'jdk_tmp'
    if (Test-Path $tmp) { Remove-Item $tmp -Recurse -Force }
    Expand-Archive -Path $zip -DestinationPath $tmp -Force
    $inner = Get-ChildItem $tmp -Directory | Select-Object -First 1
    $dest = Join-Path $ToolsDir 'jdk17'
    if (Test-Path $dest) { Remove-Item $dest -Recurse -Force }
    Move-Item $inner.FullName $dest
    Remove-Item $tmp -Recurse -Force; Remove-Item $zip -Force
    if (-not (Jdk-Ok $dest)) { Fail "JDK download did not produce a working Java." }
    $jdk = $dest
    Ok "Installed Java at $jdk"
}
$result.java_home = $jdk
$env:JAVA_HOME = $jdk

# ---------- Android SDK ----------
$need = @('platform-tools\adb.exe', 'platforms\android-34', 'build-tools\34.0.0')
function Sdk-Complete($p) {
    if (-not $p) { return $false }
    foreach ($n in $need) { if (-not (Test-Path (Join-Path $p $n))) { return $false } }
    return $true
}
function Find-Sdkmanager($sdk) {
    foreach ($p in "$sdk\cmdline-tools\latest\bin\sdkmanager.bat", "$sdk\cmdline-tools\bin\sdkmanager.bat", "$sdk\tools\bin\sdkmanager.bat") {
        if (Test-Path $p) { return $p }
    }
    return ''
}
function Ensure-Sdk($sdk) {
    New-Item -ItemType Directory -Force -Path $sdk | Out-Null
    $mgr = Find-Sdkmanager $sdk
    if (-not $mgr) {
        Missing "Android command-line tools in $sdk. Downloading..."
        $zip = Join-Path $ToolsDir 'cmdline.zip'
        Download 'https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip' $zip
        $tmp = Join-Path $ToolsDir 'cmdline_tmp'
        if (Test-Path $tmp) { Remove-Item $tmp -Recurse -Force }
        Expand-Archive -Path $zip -DestinationPath $tmp -Force
        $target = Join-Path $sdk 'cmdline-tools\latest'
        New-Item -ItemType Directory -Force -Path (Split-Path $target) | Out-Null
        if (Test-Path $target) { Remove-Item $target -Recurse -Force }
        Move-Item (Join-Path $tmp 'cmdline-tools') $target
        Remove-Item $tmp -Recurse -Force; Remove-Item $zip -Force
        $mgr = Find-Sdkmanager $sdk
    }
    if (-not $mgr) { return $false }
    Info "Accepting Android SDK licenses..."
    $yes = (1..40 | ForEach-Object { 'y' }) -join "`r`n"
    $old = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    $yes | & cmd.exe /c "`"$mgr`" --sdk_root=`"$sdk`" --licenses" 2>&1 | ForEach-Object { Log "$_" }
    Info "Installing missing SDK parts (platform 34, build-tools 34.0.0, platform-tools). This can take a while..."
    Run-Live "`"$mgr`" --sdk_root=`"$sdk`" `"platform-tools`" `"platforms;android-34`" `"build-tools;34.0.0`"" | Out-Null
    $ErrorActionPreference = $old
    return (Sdk-Complete $sdk)
}

$sdk = ''
$sdkCands = @()
if ($env:ANDROID_HOME) { $sdkCands += $env:ANDROID_HOME }
if ($env:ANDROID_SDK_ROOT) { $sdkCands += $env:ANDROID_SDK_ROOT }
$sdkCands += "$env:LOCALAPPDATA\Android\Sdk"
$sdkCands += (Join-Path $ToolsDir 'android-sdk')
foreach ($c in $sdkCands) { if ($c -and (Sdk-Complete $c)) { $sdk = $c; break } }
if ($sdk) {
    Found "Android SDK (platform 34, build-tools, adb) at $sdk"
} else {
    $existing = $sdkCands | Where-Object { $_ -and (Test-Path $_) -and ($_ -ne (Join-Path $ToolsDir 'android-sdk')) } | Select-Object -First 1
    if ($existing) {
        Missing "Some Android SDK parts in $existing. Adding only what is missing..."
        if (Ensure-Sdk $existing) { $sdk = $existing }
    }
    if (-not $sdk) {
        Missing "Usable Android SDK. Installing a private copy in tools\android-sdk ..."
        $local = Join-Path $ToolsDir 'android-sdk'
        if (Ensure-Sdk $local) { $sdk = $local } else { Fail "Android SDK install failed. Run capture_logs.bat and send me the report." }
    }
    Ok "Android SDK ready at $sdk"
}
$result.sdk = $sdk

# ---------- adb ----------
$adb = Join-Path $sdk 'platform-tools\adb.exe'
if (Test-Path $adb) { Found "adb at $adb" } else { Fail "adb is missing from the SDK." }

# ---------- Gradle ----------
function Gradle-Ver($exe) {
    $r = Run-Cmd "`"$exe`" --version"
    if ($r.Output -match 'Gradle (\d+)\.(\d+)') { return @([int]$Matches[1], [int]$Matches[2]) }
    return @(0, 0)
}
$gradle = ''
$g = Get-Command gradle -ErrorAction SilentlyContinue
if ($g) {
    $env:JAVA_HOME = $jdk
    $v = Gradle-Ver $g.Source
    if (($v[0] -gt 8) -or ($v[0] -eq 8 -and $v[1] -ge 7)) { $gradle = $g.Source; Found "Gradle $($v[0]).$($v[1]) on PATH" }
    else { Warn "Gradle on PATH is too old for this project ($($v[0]).$($v[1])). Using a private copy." }
}
if (-not $gradle) {
    $local = Join-Path $ToolsDir 'gradle-8.9\bin\gradle.bat'
    if (Test-Path $local) { $gradle = $local; Found "Gradle 8.9 at $local" }
    else {
        Missing "Gradle 8.7+. Downloading Gradle 8.9 into tools\ ..."
        $zip = Join-Path $ToolsDir 'gradle.zip'
        Download 'https://services.gradle.org/distributions/gradle-8.9-bin.zip' $zip
        Expand-Archive -Path $zip -DestinationPath $ToolsDir -Force
        Remove-Item $zip -Force
        if (-not (Test-Path $local)) { Fail "Gradle download did not unpack correctly." }
        $gradle = $local; Ok "Installed Gradle 8.9"
    }
}
$result.gradle = $gradle

# ---------- Node.js (only needed for the server step) ----------
$node = Get-Command node -ErrorAction SilentlyContinue
if ($node) {
    Found "Node.js at $($node.Source)"
    $result.node_dir = ''
} else {
    $nd = Get-ChildItem $ToolsDir -Directory -Filter 'node-v*' -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($nd) { Found "Node.js (portable) at $($nd.FullName)"; $result.node_dir = $nd.FullName }
    else {
        Missing "Node.js (needed to deploy the server). Downloading a portable copy..."
        $zip = Join-Path $ToolsDir 'node.zip'
        Download 'https://nodejs.org/dist/v20.17.0/node-v20.17.0-win-x64.zip' $zip
        Expand-Archive -Path $zip -DestinationPath $ToolsDir -Force
        Remove-Item $zip -Force
        $nd = Get-ChildItem $ToolsDir -Directory -Filter 'node-v*' | Select-Object -First 1
        $result.node_dir = $nd.FullName
        Ok "Installed Node.js"
    }
}

($result | ConvertTo-Json) | Set-Content -Path $EnvPath -Encoding ASCII
Log ("Saved tool locations: " + ($result | ConvertTo-Json -Compress))
Write-Host ""
Ok "Setup complete. Tool locations saved to tools\env.json"
