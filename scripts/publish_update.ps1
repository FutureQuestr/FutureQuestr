. "$PSScriptRoot\lib.ps1"
Start-Log 'publish'
$e = Get-Env; Apply-Env $e
$c = Get-Config
$cur = if ($c['CURRENT_VERSION']) { $c['CURRENT_VERSION'] } else { '1.0.0' }
Write-Host "Current version: $cur" -ForegroundColor Cyan
$new = ''
while ($true) {
    $new = (Ask "New version number (any number is accepted, for example 1.0.0 or 1.0.4)").Trim()
    if ($new -notmatch '^\d{1,2}\.\d{1,2}\.\d{1,2}$') { Warn "Use three numbers with dots, each 0 to 99, like 1.0.4"; continue }
    if ($new -eq $cur) {
        Info "That is the same as the current version ($cur). That is fine."
    } elseif (-not (Version-Newer $new $cur)) {
        Warn "$new is LOWER than $cur. It will be uploaded, but phones that already have a higher version will not be offered it, and Android refuses to install a lower version over a higher one."
        $go = Ask "Continue with $new anyway? (Y/n)"
        if ($go -match '^[Nn]') { continue }
    }
    break
}
$note = Ask "What's new? (one line, or press Enter to skip)"
if (-not $note) { $note = "FutureQuestr $new" }
$repo = Ask-Config 'RELEASES_REPO' 'Releases repo as owner/name (it will be created if missing)'

$apk = Join-Path $Root "dist\FutureQuestr-$new.apk"
$skipBuild = $false
if (Test-Path $apk) {
    $built = (Get-Item $apk).LastWriteTime.ToString('yyyy-MM-dd HH:mm')
    Info "FutureQuestr-$new.apk already exists in dist (built $built)."
    $use = Ask "Publish that exact file without rebuilding? It may be older than your latest code. (Y/n)"
    if ($use -notmatch '^[Nn]') { $skipBuild = $true }
}
if (-not $skipBuild) {
    & "$PSScriptRoot\build.ps1" -Version $new
    if ($LASTEXITCODE -ne 0) { Fail "Build failed, so nothing was published." }
}
if (-not (Test-Path $apk)) { Fail "Expected APK not found: $apk" }

# ----- GitHub CLI -----
$gh = ''
$g = Get-Command gh -ErrorAction SilentlyContinue
if ($g) { $gh = $g.Source; Found "GitHub CLI at $gh" }
else {
    $local = Get-ChildItem (Join-Path $ToolsDir 'gh') -Recurse -Filter 'gh.exe' -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($local) { $gh = $local.FullName; Found "GitHub CLI at $gh" }
    else {
        Missing "GitHub CLI. Downloading a portable copy..."
        $rel = Invoke-RestMethod -Uri 'https://api.github.com/repos/cli/cli/releases/latest' -Headers @{ 'User-Agent' = 'FutureQuestr' }
        $asset = $rel.assets | Where-Object { $_.name -match 'windows_amd64\.zip$' } | Select-Object -First 1
        if (-not $asset) { Fail "Could not find the GitHub CLI download." }
        $zip = Join-Path $ToolsDir 'gh.zip'
        Download $asset.browser_download_url $zip
        Expand-Archive -Path $zip -DestinationPath (Join-Path $ToolsDir 'gh') -Force
        Remove-Item $zip -Force
        $gh = (Get-ChildItem (Join-Path $ToolsDir 'gh') -Recurse -Filter 'gh.exe' | Select-Object -First 1).FullName
    }
}
$r = Run-Cmd "`"$gh`" auth status"
if ($r.Code -ne 0) {
    Missing "GitHub login for publishing. Follow the prompts."
    Run-Interactive "`"$gh`" auth login --web -h github.com" | Out-Null
    $r = Run-Cmd "`"$gh`" auth status"
    if ($r.Code -ne 0) { Fail "GitHub login did not complete." }
}
$r = Run-Cmd "`"$gh`" repo view $repo"
if ($r.Code -ne 0) {
    Info "Creating public repo $repo ..."
    $r = Run-Cmd "`"$gh`" repo create $repo --public --add-readme"
    if ($r.Code -ne 0) { Write-Host $r.Output; Fail "Could not create the releases repo." }
}
$notesFile = Join-Path $LogDir 'notes.txt'
Set-Content -Path $notesFile -Value $note -Encoding ASCII
$exists = Run-Cmd "`"$gh`" release view v$new --repo $repo"
if ($exists.Code -eq 0) {
    Warn "A release named v$new already exists in $repo."
    $rp = Ask "Replace its APK file with this build? (y/N)"
    if ($rp -notmatch '^[Yy]') { Fail "Cancelled. Nothing was changed on GitHub." }
    $r = Run-Cmd "`"$gh`" release upload v$new `"$apk`" --repo $repo --clobber"
    Write-Host $r.Output
    if ($r.Code -eq 0) {
        $ed = Run-Cmd "`"$gh`" release edit v$new --repo $repo --notes-file `"$notesFile`""
        Write-Host $ed.Output
    }
} else {
    $r = Run-Cmd "`"$gh`" release create v$new `"$apk`" --repo $repo --title `"FutureQuestr v$new`" --notes-file `"$notesFile`""
    Write-Host $r.Output
}
$v = Run-Cmd "`"$gh`" release view v$new --repo $repo"
if ($r.Code -ne 0 -or $v.Code -ne 0) { Fail "The upload did not complete, so v$new was NOT published." }
Set-Config 'CURRENT_VERSION' $new
Write-Host ""
Ok "Published v$new. Phones with a LOWER version installed will offer it within about 30 minutes. Phones with the same or a higher version will not."
