. "$PSScriptRoot\lib.ps1"
Start-Log 'capture'
$stamp = Get-Date -Format 'yyyyMMdd_HHmmss'
$report = Join-Path $LogDir "report_$stamp.txt"
$sb = New-Object System.Text.StringBuilder
function Add($t) { [void]$sb.AppendLine($t) }
Info "Collecting your report. This should take under a minute."
Add "FutureQuestr report $stamp"
Add "Windows: $([Environment]::OSVersion.VersionString)  PowerShell: $($PSVersionTable.PSVersion)"
Add ""
Add "---- config (secrets hidden) ----"
foreach ($kv in (Get-Config).GetEnumerator()) {
    $v = $kv.Value
    if ($kv.Key -match 'PASSWORD|KEY$') { $v = if ($v) { '(set)' } else { '(empty)' } }
    Add "$($kv.Key)=$v"
}
Add ""
if (Test-Path $EnvPath) {
    Add "---- tools ----"; Add (Get-Content $EnvPath -Raw)
    $e = Get-Env
    $adb = Join-Path $e.sdk 'platform-tools\adb.exe'
    if (Test-Path $adb) {
        Info "Checking for a connected phone (15 second limit)..."
        $d = Run-CmdTimeout "`"$adb`" devices -l" 15
        Add "---- adb devices ----"; Add $d.Output
        if ($d.TimedOut) { Add "(adb did not answer in time)" }
        $serial = ''
        foreach ($l in ($d.Output -split "`r?`n")) { if ($l -match '^(.+?)\s+device(\s|$)') { $serial = $Matches[1]; break } }
        if ($serial) {
            Info "Reading the app log from the phone..."
            $lg = Run-CmdTimeout "`"$adb`" -s `"$serial`" logcat -d -t 300 AndroidRuntime:E ActivityManager:W *:S" 20
            Add "---- app log (last 300 lines) ----"; Add $lg.Output
        } else {
            Add "---- app log ---- (skipped: no phone connected)"
        }
    }
}
$logFiles = Get-ChildItem -LiteralPath $LogDir -Filter '*.log' -ErrorAction SilentlyContinue | Where-Object { $_.Name -ne 'capture.log' } | Sort-Object Name
foreach ($lf in $logFiles) {
    $n = 150
    if ($lf.Name -eq 'build_gradle.log') { $n = 300 }
    Add "---- $($lf.Name) (last $n lines) ----"
    Add ((Get-Content -LiteralPath $lf.FullName -Tail $n) -join "`r`n")
    Add ""
}
$crash = Join-Path $LogDir 'crash.txt'
if (Test-Path -LiteralPath $crash) { Add "---- crash.txt ----"; Add ((Get-Content -LiteralPath $crash -Tail 150) -join "`r`n") }
Set-Content -Path $report -Value $sb.ToString() -Encoding UTF8
Ok "Saved $report"
Write-Host "Send me that one file. Passwords and keys are hidden in it."
Start-Process explorer.exe $LogDir
