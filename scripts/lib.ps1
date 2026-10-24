# Shared helpers for all FutureQuestr scripts. ASCII only on purpose.
$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
$ConfigPath = Join-Path $Root 'config.txt'
$ToolsDir = Join-Path $Root 'tools'
$LogDir = Join-Path $Root 'logs'
$EnvPath = Join-Path $ToolsDir 'env.json'
New-Item -ItemType Directory -Force -Path $ToolsDir, $LogDir | Out-Null

# ---------------- logging ----------------
$script:LogFile = $null
$script:Secrets = @()

function Add-Secret($v) {
    if ($v -and $v.Length -ge 4 -and ($script:Secrets -notcontains $v)) { $script:Secrets += $v }
}
function Load-Secrets {
    if (Test-Path $ConfigPath) {
        foreach ($line in Get-Content $ConfigPath) {
            if ($line -match '^\s*(KEYSTORE_PASSWORD|KEY_PASSWORD|GROQ_KEY|OPENROUTER_KEY)=(.*)$') { Add-Secret $Matches[2].Trim() }
        }
    }
}
function Mask($t) {
    foreach ($s in $script:Secrets) { $t = $t.Replace($s, '********') }
    return $t
}
function Log($m) {
    if ($script:LogFile) {
        try {
            $text = Mask ("$m")
            Add-Content -LiteralPath $script:LogFile -Value ("{0}  {1}" -f (Get-Date -Format 'HH:mm:ss'), $text) -Encoding UTF8
        } catch {}
    }
}
function Start-Log($name) {
    $script:LogFile = Join-Path $LogDir "$name.log"
    $prev = Join-Path $LogDir "$name.previous.log"
    if (Test-Path -LiteralPath $script:LogFile) { try { Move-Item -LiteralPath $script:LogFile -Destination $prev -Force } catch {} }
    Load-Secrets
    Set-Content -LiteralPath $script:LogFile -Value ("FutureQuestr '" + $name + "' log. Started " + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss')) -Encoding UTF8
    Log ("Windows: " + [Environment]::OSVersion.VersionString + "   PowerShell: " + $PSVersionTable.PSVersion)
    Log ("Project folder: " + $Root)
}

# Everything printed with Write-Host is also written to the log.
function Write-Host {
    [CmdletBinding()]
    param(
        [Parameter(Position = 0, ValueFromRemainingArguments = $true)] [object[]]$Object,
        [object]$ForegroundColor,
        [object]$BackgroundColor,
        [switch]$NoNewline,
        [object]$Separator = ' '
    )
    $text = ''
    if ($Object) { $text = ($Object | ForEach-Object { "$_" }) -join $Separator }
    Log $text
    $splat = @{}
    if ($PSBoundParameters.ContainsKey('ForegroundColor')) { $splat['ForegroundColor'] = $ForegroundColor }
    if ($PSBoundParameters.ContainsKey('BackgroundColor')) { $splat['BackgroundColor'] = $BackgroundColor }
    if ($NoNewline) { $splat['NoNewline'] = $true }
    Microsoft.PowerShell.Utility\Write-Host $text @splat
}

# Any unexpected PowerShell error is written to the log before the script stops.
trap {
    Write-Host ("[FAILED]  Unexpected error: " + $_.Exception.Message) -ForegroundColor Red
    Log ("UNHANDLED ERROR: " + $_.Exception.Message)
    Log ("At: " + $_.InvocationInfo.PositionMessage)
    Log ("Stack: " + $_.ScriptStackTrace)
    exit 1
}

# ---------------- messages ----------------
function Info($m)    { Write-Host "[INFO]    $m" -ForegroundColor Cyan }
function Ok($m)      { Write-Host "[OK]      $m" -ForegroundColor Green }
function Found($m)   { Write-Host "[FOUND]   $m" -ForegroundColor Green }
function Missing($m) { Write-Host "[MISSING] $m" -ForegroundColor Yellow }
function Warn($m)    { Write-Host "[WARNING] $m" -ForegroundColor Yellow }
function Fail($m)    { Write-Host "[FAILED]  $m" -ForegroundColor Red; Log "Script ended with a failure."; exit 1 }

# Asks a question. Typed answers are logged, except secrets (and anything passed -Secret).
function Ask($prompt, [switch]$Secret) {
    $v = Read-Host $prompt
    if ($Secret -and $v) { Add-Secret $v }
    $shown = $v
    if ($Secret) { $shown = '(hidden)' }
    Log ("PROMPT: " + $prompt + " => " + $shown)
    return $v
}

# ---------------- config ----------------
function Get-Config {
    $c = @{}
    if (Test-Path $ConfigPath) {
        foreach ($line in Get-Content $ConfigPath) {
            if ($line -match '^\s*([A-Z_]+)=(.*)$') { $c[$Matches[1]] = $Matches[2].Trim() }
        }
    }
    return $c
}

function Set-Config($key, $value) {
    if ($key -match 'PASSWORD|KEY$') { Add-Secret $value }
    $lines = @(); $done = $false
    if (Test-Path $ConfigPath) {
        foreach ($l in Get-Content $ConfigPath) {
            if ($l -match "^\s*$key=") { $lines += "$key=$value"; $done = $true } else { $lines += $l }
        }
    }
    if (-not $done) { $lines += "$key=$value" }
    Set-Content -Path $ConfigPath -Value $lines -Encoding ASCII
    Log "config.txt updated: $key"
}

function Ask-Config($key, $prompt, [switch]$Optional) {
    $c = Get-Config
    if ($c[$key]) { return $c[$key] }
    $v = Ask $prompt
    if ($v) { $v = $v.Trim() }
    if (-not $v -and -not $Optional) { Fail "$key is required. Add it to config.txt and run again." }
    if ($v) { Set-Config $key $v }
    return $v
}

# ---------------- downloads and commands ----------------
function Download($url, $out) {
    Info "Downloading $url"
    Log "Download target: $out"
    try { [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12 } catch {}
    $old = $ProgressPreference; $ProgressPreference = 'SilentlyContinue'
    try { Invoke-WebRequest -Uri $url -OutFile $out -UseBasicParsing } finally { $ProgressPreference = $old }
    Log "Download finished."
}

function Get-Env {
    if (Test-Path $EnvPath) { return (Get-Content $EnvPath -Raw | ConvertFrom-Json) }
    Fail "Tools are not set up yet. Run 1_setup.bat first."
}

# Runs a command line through cmd so stderr never aborts the script. Returns @{Code; Output}
function Run-Cmd($cmdline) {
    Log "RUN: $cmdline"
    $old = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    $out = & cmd.exe /c "$cmdline 2>&1" | Out-String
    $code = $LASTEXITCODE
    $ErrorActionPreference = $old
    Log "EXIT $code`r`n$out"
    return @{ Code = $code; Output = $out }
}

# Like Run-Cmd but gives up after N seconds so a waiting adb can never freeze a script.
function Run-CmdTimeout($cmdline, $seconds = 20) {
    Log "RUN (limit $seconds s): $cmdline"
    # temp file lives in our own logs folder (the Windows TEMP path can contain 8.3 short names that break Remove-Item)
    $tmp = Join-Path $LogDir ("tmp_" + [guid]::NewGuid().ToString('N') + ".txt")
    $full = '/c "' + $cmdline + ' > "' + $tmp + '" 2>&1"'
    $p = Start-Process -FilePath cmd.exe -ArgumentList $full -PassThru -WindowStyle Hidden
    $timedOut = $false
    if (-not $p.WaitForExit($seconds * 1000)) {
        $timedOut = $true
        try { & taskkill.exe /F /T /PID $p.Id 2>&1 | Out-Null } catch {}
        Start-Sleep -Milliseconds 500
    }
    $out = ''
    try { if ([System.IO.File]::Exists($tmp)) { $out = [System.IO.File]::ReadAllText($tmp) } } catch {}
    try { [System.IO.File]::Delete($tmp) } catch {}
    $code = -1
    if (-not $timedOut) { $code = $p.ExitCode }
    if ($timedOut) { Log "TIMED OUT after $seconds s`r`n$out" } else { Log "EXIT $code`r`n$out" }
    return @{ Code = $code; Output = "$out"; TimedOut = $timedOut }
}

# Runs a command and shows its output live while also logging it. Returns the exit code.
function Run-Live($cmdline) {
    Log "RUN (live): $cmdline"
    $old = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    & cmd.exe /c "$cmdline 2>&1" | ForEach-Object { Write-Host "$_" }
    $code = $LASTEXITCODE
    $ErrorActionPreference = $old
    Log "EXIT $code"
    return $code
}

# For commands that ask you questions or open a browser. Their output cannot be captured.
function Run-Interactive($cmdline) {
    Log "RUN (interactive, output not captured): $cmdline"
    $old = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    & cmd.exe /c $cmdline
    $code = $LASTEXITCODE
    $ErrorActionPreference = $old
    Log "EXIT $code"
    return $code
}

function Apply-Env($e) {
    $env:JAVA_HOME = $e.java_home
    $env:ANDROID_HOME = $e.sdk
    $env:ANDROID_SDK_ROOT = $e.sdk
    $paths = @("$($e.java_home)\bin", "$($e.sdk)\platform-tools")
    if ($e.node_dir) { $paths += $e.node_dir }
    $env:Path = ($paths -join ';') + ';' + $env:Path
    Log "JAVA_HOME=$($e.java_home)  ANDROID_HOME=$($e.sdk)  gradle=$($e.gradle)  node_dir=$($e.node_dir)"
}

function Version-Code($v) {
    $p = $v.Split('.')
    if ($p.Count -ne 3) { Fail "Version must look like 1.0.4" }
    foreach ($x in $p) { if ($x -notmatch '^\d{1,2}$') { Fail "Each part of the version must be 0 to 99 (got $v)" } }
    return ([int]$p[0] * 10000 + [int]$p[1] * 100 + [int]$p[2])
}

function Version-Newer($a, $b) {
    return ((Version-Code $a) -gt (Version-Code $b))
}
