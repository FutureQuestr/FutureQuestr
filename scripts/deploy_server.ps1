. "$PSScriptRoot\lib.ps1"
Start-Log 'deploy'
$e = Get-Env; Apply-Env $e
$server = Join-Path $Root 'server'
Push-Location $server
function Wr($a) { return (Run-Cmd "npx --yes wrangler@4 $a") }

function Test-Key($name, $key) {
    try {
        if ($name -eq 'GROQ_KEY') { Invoke-RestMethod -Uri 'https://api.groq.com/openai/v1/models' -Headers @{ Authorization = "Bearer $key" } | Out-Null }
        else { Invoke-RestMethod -Uri 'https://openrouter.ai/api/v1/auth/key' -Headers @{ Authorization = "Bearer $key" } | Out-Null }
        return $true
    } catch { return $false }
}

Info "Checking your Cloudflare login..."
$w = Wr 'whoami'
if ($w.Output -match 'not authenticated|You are not authenticated|Not logged in') {
    Missing "Cloudflare login. A browser window will open. Sign up (free) or log in, then click Allow."
    Run-Interactive "npx --yes wrangler@4 login" | Out-Null
    $w = Wr 'whoami'
    if ($w.Output -match 'not authenticated') { Pop-Location; Fail "Cloudflare login did not complete." }
}
Ok "Logged in to Cloudflare."

# ----- database -----
$dbId = ''
$l = Wr 'd1 list --json'
$txt = $l.Output
$a = $txt.IndexOf('['); $b = $txt.LastIndexOf(']')
if ($a -ge 0 -and $b -gt $a) {
    try {
        $arr = $txt.Substring($a, $b - $a + 1) | ConvertFrom-Json
        foreach ($d in $arr) { if ($d.name -eq 'futurequestr-db') { $dbId = $d.uuid } }
    } catch {}
}
if ($dbId) { Found "Database futurequestr-db ($dbId)" }
else {
    Missing "Database. Creating futurequestr-db..."
    $r = Wr 'd1 create futurequestr-db'
    if ($r.Output -match '([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})') { $dbId = $Matches[1] }
    else { Write-Host $r.Output; Pop-Location; Fail "Could not read the new database id." }
    Ok "Database created."
}
$toml = Get-Content 'wrangler.toml' -Raw
$toml = [regex]::Replace($toml, 'database_id\s*=\s*"[^"]*"', "database_id = `"$dbId`"")
Set-Content -Path 'wrangler.toml' -Value $toml -Encoding ASCII

Info "Creating tables..."
$r = Wr 'd1 execute futurequestr-db --remote --file=schema.sql'
if ($r.Code -ne 0) { Write-Host $r.Output; Pop-Location; Fail "Could not create the database tables." }
Ok "Tables ready."

# ----- deploy (interactive first, so any one-time questions can be answered) -----
Info "Deploying the server. If Cloudflare asks you to choose a workers.dev name, type one and press Enter."
Run-Interactive "npx --yes wrangler@4 deploy" | Out-Null
$r = Wr 'deploy'
$url = ''
if ($r.Output -match '(https://[a-zA-Z0-9\-]+\.[a-zA-Z0-9\-]+\.workers\.dev)') { $url = $Matches[1] }
if (-not $url) {
    Write-Host $r.Output
    $url = Ask "Paste the https://...workers.dev address shown above"
}
$url = $url.Trim().TrimEnd('/')
if ($url -notmatch '^https://') { Pop-Location; Fail "That does not look like a server address." }

# ----- AI keys (optional, tested before saving) -----
Write-Host ""
Write-Host "AI keys: Groq and OpenRouter keys can only be created while you are logged in to those sites." -ForegroundColor Yellow
Write-Host "Free sign-up, no card needed. Press Enter to skip either one (GitHub AI still works for every user)." -ForegroundColor Yellow
$sites = @{ 'GROQ_KEY' = 'https://console.groq.com/keys'; 'OPENROUTER_KEY' = 'https://openrouter.ai/keys' }
foreach ($name in 'GROQ_KEY', 'OPENROUTER_KEY') {
    $cur = (Get-Config)[$name]
    if (-not $cur) {
        $open = Ask "Open the page to create your $name now? (Y/n)"
        if ($open -notmatch '^[Nn]') { Start-Process $sites[$name] }
        $cur = Ask "Paste your $name (or press Enter to skip)" -Secret
        $cur = if ($cur) { $cur.Trim() } else { '' }
    }
    if ($cur) {
        if (Test-Key $name $cur) {
            Ok "$name works."
            Set-Config $name $cur
            $cur | & cmd.exe /c "npx --yes wrangler@4 secret put $name 2>&1" | Out-Null
            Log "Stored secret $name on the server (value not logged)."
            Ok "$name stored securely on the server (it is never put in the app)."
        } else { Warn "$name was rejected by the provider. Skipping it. Fix it in config.txt and run this again." }
    }
}

# ----- test -----
try {
    $t = Invoke-RestMethod -Uri "$url/time"
    if ($t.now) { Ok "Server answered at $url" } else { throw "no time" }
} catch { Pop-Location; Fail "The server was deployed but did not answer at $url/time. Wait a minute and try again." }
Set-Config 'WORKER_URL' $url
Pop-Location
Ok "Server deployed. WORKER_URL saved to config.txt. Rebuild the app (3_build_apk.bat) so it knows the address."
