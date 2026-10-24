. "$PSScriptRoot\lib.ps1"
Start-Log 'keystore'
$e = Get-Env; Apply-Env $e
$c = Get-Config
$ksRel = if ($c['KEYSTORE_PATH']) { $c['KEYSTORE_PATH'] } else { 'keystore\futurequestr.jks' }
$ks = Join-Path $Root $ksRel
$alias = if ($c['KEY_ALIAS']) { $c['KEY_ALIAS'] } else { 'futurequestr' }
Set-Config 'KEYSTORE_PATH' $ksRel; Set-Config 'KEY_ALIAS' $alias

if ((Test-Path $ks) -and $c['KEYSTORE_PASSWORD']) {
    Found "Signing keystore at $ks"
} else {
    if (Test-Path $ks) { Fail "A keystore exists at $ks but config.txt has no KEYSTORE_PASSWORD. Add it, or delete the keystore only if you have never shipped the app." }
    Missing "Signing keystore. Creating one now."
    Write-Host "Choose a password: letters and numbers only, at least 8 characters (no spaces or symbols)." -ForegroundColor Yellow
    $pw = Ask "Keystore password" -Secret
    if ($pw -notmatch '^[A-Za-z0-9]{8,}$') { Fail "Password must be letters and numbers only, 8 or more characters." }
    New-Item -ItemType Directory -Force -Path (Split-Path $ks) | Out-Null
    $keytool = Join-Path $e.java_home 'bin\keytool.exe'
    $r = Run-Cmd "`"$keytool`" -genkeypair -v -keystore `"$ks`" -alias $alias -keyalg RSA -keysize 2048 -validity 10000 -storepass $pw -keypass $pw -dname `"CN=FutureQuestr, O=FutureQuestr, C=GB`""
    if ($r.Code -ne 0 -or -not (Test-Path $ks)) { Write-Host $r.Output; Fail "keytool could not create the keystore." }
    Set-Config 'KEYSTORE_PASSWORD' $pw; Set-Config 'KEY_PASSWORD' $pw
    Ok "Keystore created."
}

# backup copy
$bk = Join-Path $Root 'backup'
New-Item -ItemType Directory -Force -Path $bk | Out-Null
Copy-Item $ks (Join-Path $bk (Split-Path $ks -Leaf)) -Force
Copy-Item $ConfigPath (Join-Path $bk 'config.txt') -Force
Write-Host ""
Write-Host "IMPORTANT: copy the 'backup' folder somewhere safe (USB stick or private cloud storage)." -ForegroundColor Yellow
Write-Host "If you lose this keystore, nobody can update the app. They would have to uninstall and reinstall." -ForegroundColor Yellow
