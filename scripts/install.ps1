. "$PSScriptRoot\lib.ps1"
Start-Log 'install'
$e = Get-Env; Apply-Env $e
$adb = Join-Path $e.sdk 'platform-tools\adb.exe'
$pkg = 'com.futurequestr.app'

$apkFile = Get-ChildItem (Join-Path $Root 'dist') -Filter '*.apk' -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $apkFile) { Fail "No APK found in dist\. Run 3_build_apk.bat first." }
Info "APK: $($apkFile.Name)"

function Get-Devices {
    $r = Run-CmdTimeout "`"$adb`" devices" 15
    $list = @()
    foreach ($l in ($r.Output -split "`r?`n")) { if ($l -match '^(.+?)\s+device\s*$') { $list += $Matches[1] } }
    return $list
}

Run-CmdTimeout "`"$adb`" start-server" 20 | Out-Null
$devs = @(Get-Devices)
if ($devs.Count -eq 0) {
    Missing "No phone connected."
    Write-Host "  1) On the phone: Settings > About phone > tap 'Build number' 7 times to unlock Developer options."
    Write-Host "  2) Settings > Developer options > turn on USB debugging."
    Write-Host "  3) Plug in the USB cable and tap 'Allow' on the phone's popup."
    Write-Host "  Or use Wireless debugging (Developer options) if you prefer no cable."
    $ans = Ask "Press Enter to try again, or type W for wireless setup"
    if ($ans -match '^[Ww]') {
        $pairAddr = Ask "Pairing address shown on the phone (for example 192.168.1.20:41234)"
        $pairCode = Ask "Pairing code (6 digits)" -Secret
        $r = Run-CmdTimeout "`"$adb`" pair $pairAddr $pairCode" 30; Write-Host $r.Output
        $conn = Ask "Connect address shown on the main Wireless debugging screen (for example 192.168.1.20:37099)"
        $r = Run-CmdTimeout "`"$adb`" connect $conn" 20; Write-Host $r.Output
    }
    Start-Sleep -Seconds 2
    $devs = @(Get-Devices)
    if ($devs.Count -eq 0) { Fail "Still no phone found. Check the cable, USB debugging, and the 'Allow' popup, then run 4_install.bat again." }
}

# prefer a USB entry over a Wi-Fi one (the same device can be listed several ways)
$usb = @($devs | Where-Object { $_ -notmatch ':' -and $_ -notmatch '_adb-tls' })
$ip  = @($devs | Where-Object { $_ -match '^\d{1,3}(\.\d{1,3}){3}:\d+$' })
$dev = [string]$devs[0]
if ($usb.Count -gt 0) { $dev = [string]$usb[0] } elseif ($ip.Count -gt 0) { $dev = [string]$ip[0] }
Log ("Devices found: " + ($devs -join ' | ') + "   Chosen: " + $dev)
$model = (Run-CmdTimeout "`"$adb`" -s `"$dev`" shell getprop ro.product.model" 10).Output.Trim()
Found "Device: $model ($dev)"
if ($devs.Count -gt 1) { Warn "adb lists $($devs.Count) entries. The same device can appear over USB and Wi-Fi. Using $dev." }

$state = (Run-CmdTimeout "`"$adb`" -s `"$dev`" get-state" 10).Output.Trim()
if ($state -ne 'device') {
    Fail "The device is listed but not ready (state: '$state'). Unlock the screen, accept any 'Allow debugging' prompt, then run 4_install.bat again."
}

function Do-Install { return (Run-Cmd "`"$adb`" -s `"$dev`" install -r `"$($apkFile.FullName)`"") }

Info "Installing $($apkFile.Name). Over Wi-Fi this can take a minute or two..."
$r = Do-Install
Write-Host $r.Output
if ($r.Output -notmatch 'Success' -and $r.Output -match 'failed to read copy response|protocol fault|connection reset|device offline|closed|no devices|error: device') {
    Warn "The connection dropped. Reconnecting and trying once more..."
    Run-CmdTimeout "`"$adb`" reconnect" 15 | Out-Null
    Start-Sleep -Seconds 4
    $r = Do-Install
    Write-Host $r.Output
}

if ($r.Output -notmatch 'Success') {
    $o = $r.Output
    if ($o -match 'UPDATE_INCOMPATIBLE|signatures do not match') {
        Warn "A copy signed with a different key is already on the device."
        $a = Ask "Uninstall it (this deletes its local data) and install this one? (y/N)"
        if ($a -match '^[Yy]') {
            Run-CmdTimeout "`"$adb`" -s `"$dev`" uninstall $pkg" 30 | Out-Null
            $r = Do-Install; Write-Host $r.Output
        }
    } elseif ($o -match 'VERSION_DOWNGRADE') {
        Warn "The device has a NEWER version than this APK. Uninstall FutureQuestr on the device first (Settings > Apps > FutureQuestr > Uninstall), then run again."
    } elseif ($o -match 'USER_RESTRICTED|Install canceled by user|INSTALL_FAILED_ABORTED|User rejected') {
        Warn "The device blocked the install. Look at the device screen for an 'Install' or 'Allow' prompt and tap it."
        Warn "Samsung: also turn OFF Settings > Security and privacy > Auto Blocker. Xiaomi/Redmi/POCO: turn on 'Install via USB' in Developer options."
    } elseif ($o -match 'INSUFFICIENT_STORAGE') {
        Warn "The device is out of storage space. Free some space and run again."
    } elseif ($o -match 'unauthorized') {
        Warn "The device has not approved this computer. Unlock it and tap 'Allow' on the debugging prompt."
    }
    if ($r.Output -notmatch 'Success') { Fail "Install failed. The full output is saved in logs\install.log. Run capture_logs.bat and send me the report." }
}
Ok "Installed."

Info "Launching the app..."
Run-CmdTimeout "`"$adb`" -s `"$dev`" shell monkey -p $pkg -c android.intent.category.LAUNCHER 1" 20 | Out-Null
Start-Sleep -Seconds 5
$pid_ = (Run-CmdTimeout "`"$adb`" -s `"$dev`" shell pidof $pkg" 10).Output.Trim()
if (-not $pid_) {
    $crash = Run-CmdTimeout "`"$adb`" -s `"$dev`" logcat -d -t 400 AndroidRuntime:E ActivityManager:W *:S" 20
    Set-Content -Path (Join-Path $LogDir 'crash.txt') -Value $crash.Output
    Write-Host $crash.Output
    Fail "The app did not stay open. The crash log is saved to logs\crash.txt. Send it to me."
}
Ok "FutureQuestr is running on your device."
