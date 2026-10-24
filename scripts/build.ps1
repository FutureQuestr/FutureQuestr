param([string]$Version = '')
. "$PSScriptRoot\lib.ps1"
Start-Log 'build'
$e = Get-Env; Apply-Env $e
$c = Get-Config
if (-not $Version) { $Version = if ($c['CURRENT_VERSION']) { $c['CURRENT_VERSION'] } else { '1.0.0' } }
$code = Version-Code $Version

$ks = Join-Path $Root $c['KEYSTORE_PATH']
if (-not (Test-Path $ks) -or -not $c['KEYSTORE_PASSWORD']) { Fail "No signing keystore yet. Run 2_create_keystore.bat first." }

Write-Host "GitHub sign-in needs a GitHub App Client ID. See README.txt, section 'One-time accounts', step 1." -ForegroundColor Yellow
$clientId = Ask-Config 'GITHUB_APP_CLIENT_ID' 'GitHub App Client ID'
$repo = Ask-Config 'RELEASES_REPO' 'Releases repo as owner/name (for auto-updates), or press Enter to skip' -Optional
$c = Get-Config
$worker = $c['WORKER_URL']
if (-not $worker) { Warn "No server address yet. The app will build, but the AI planner and cloud sync will not work until you run 5_deploy_server.bat and rebuild." }
if (-not $repo) { Warn "No releases repo. Auto-update checks will be off in this build." }

# tell Gradle where the SDK is
$sdkFwd = $e.sdk -replace '\\', '/'
Set-Content -Path (Join-Path $Root 'android\local.properties') -Value "sdk.dir=$sdkFwd" -Encoding ASCII

$env:FQ_KS_PATH = $ks
$env:FQ_KS_PASS = $c['KEYSTORE_PASSWORD']
$env:FQ_KEY_ALIAS = $c['KEY_ALIAS']
$env:FQ_KEY_PASS = if ($c['KEY_PASSWORD']) { $c['KEY_PASSWORD'] } else { $c['KEYSTORE_PASSWORD'] }

$log = Join-Path $LogDir 'build_gradle.log'
Log "Full Gradle output is written to logs\build_gradle.log"
Info "Building FutureQuestr $Version (versionCode $code). The first build downloads a lot and can take 10 to 20 minutes."
$gradleArgs = "-p `"$(Join-Path $Root 'android')`" --no-daemon -Pfq.versionName=$Version -Pfq.versionCode=$code `"-Pfq.clientId=$clientId`" `"-Pfq.workerUrl=$worker`" `"-Pfq.releasesRepo=$repo`" assembleRelease"
$old = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
& cmd.exe /c "`"$($e.gradle)`" $gradleArgs 2>&1" | Tee-Object -FilePath $log
$exit = $LASTEXITCODE
Log "Gradle exit code: $exit"
$ErrorActionPreference = $old
if ($exit -ne 0) {
    Write-Host ""
    Fail "Build failed. The full Gradle output is in logs\build_gradle.log and the steps are in logs\build.log. Run capture_logs.bat and send me the report."
}

$apk = Join-Path $Root 'android\app\build\outputs\apk\release\app-release.apk'
if (-not (Test-Path $apk)) { Fail "Gradle finished but no APK was produced at $apk" }
$dist = Join-Path $Root 'dist'
New-Item -ItemType Directory -Force -Path $dist | Out-Null
$out = Join-Path $dist "FutureQuestr-$Version.apk"
Copy-Item $apk $out -Force

$apksigner = Join-Path $e.sdk 'build-tools\34.0.0\apksigner.bat'
if (Test-Path $apksigner) {
    $r = Run-Cmd "`"$apksigner`" verify --print-certs `"$out`""
    if ($r.Code -eq 0) { Ok "APK signature verified." } else { Warn "Could not verify the signature: $($r.Output)" }
}
Ok "Built $out"
