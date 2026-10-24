. "$PSScriptRoot\lib.ps1"
Start-Log 'ai_test'
$c = Get-Config

function Web($method, $url, $headers, $body) {
    Log "HTTP $method $url"
    try {
        $p = @{ Uri = $url; Method = $method; UseBasicParsing = $true; TimeoutSec = 45 }
        if ($headers) { $p['Headers'] = $headers }
        if ($body) { $p['Body'] = $body; $p['ContentType'] = 'application/json' }
        $r = Invoke-WebRequest @p
        return @{ Code = [int]$r.StatusCode; Body = [string]$r.Content }
    } catch {
        $code = 0; $text = $_.Exception.Message
        try {
            if ($_.Exception.Response) {
                $code = [int]$_.Exception.Response.StatusCode
                $sr = New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())
                $text = $sr.ReadToEnd()
            }
        } catch {}
        return @{ Code = $code; Body = $text }
    }
}
function Short($t, $n = 260) { $t = ("$t" -replace '\s+', ' '); if ($t.Length -gt $n) { return $t.Substring(0, $n) + '...' } return $t }
function Chat($url, $key, $model, $extraHeaders, $extraBody) {
    $h = @{ Authorization = "Bearer $key" }
    if ($extraHeaders) { foreach ($k in $extraHeaders.Keys) { $h[$k] = $extraHeaders[$k] } }
    $b = @{ model = $model; max_tokens = 400; messages = @(@{ role = 'user'; content = 'Reply with only this JSON: {"ok":true}' }) }
    if ($extraBody) { foreach ($k in $extraBody.Keys) { $b[$k] = $extraBody[$k] } }
    return (Web 'POST' $url $h ($b | ConvertTo-Json -Depth 6 -Compress))
}
function Verdict($r, $label) {
    if ($r.Code -ge 200 -and $r.Code -lt 300) { Ok "$label answered (code $($r.Code)): $(Short $r.Body 120)" }
    else { Warn "$label FAILED (code $($r.Code)): $(Short $r.Body 300)" }
}

Write-Host ""
Write-Host "=== 1. Your server ===" -ForegroundColor White
$worker = $c['WORKER_URL']
if (-not $worker) { Warn "WORKER_URL is empty. Run 5_deploy_server.bat first." }
else {
    $r = Web 'GET' ($worker.TrimEnd('/') + '/') $null $null
    Info "GET / -> code $($r.Code): $(Short $r.Body)"
    if ($r.Body -match '"version"\s*:\s*(\d+)') { Ok "Server version marker: $($Matches[1])" }
    elseif ($r.Code -eq 200) { Warn "The server has no version marker, so it is an OLD copy. Run 5_deploy_server.bat to update it." }
    $r = Web 'GET' ($worker.TrimEnd('/') + '/time') $null $null
    Info "GET /time -> code $($r.Code)"
}

Write-Host ""
Write-Host "=== 2. Groq (your saved key) ===" -ForegroundColor White
if (-not $c['GROQ_KEY']) { Info "No Groq key saved. Skipping." }
else {
    $r = Web 'GET' 'https://api.groq.com/openai/v1/models' @{ Authorization = "Bearer $($c['GROQ_KEY'])" } $null
    if ($r.Code -ne 200) { Warn "Groq model list FAILED (code $($r.Code)): $(Short $r.Body)" }
    else {
        $ids = @(($r.Body | ConvertFrom-Json).data | ForEach-Object { $_.id } | Where-Object { $_ -notmatch 'whisper|guard|tts|safeguard|embed|compound|orpheus' })
        Ok "Groq lists $($ids.Count) usable models: $(($ids | Select-Object -First 8) -join ', ')"
        $model = $null
        foreach ($p in 'gpt-oss-20b', 'llama-3.3-70b', 'llama-3.1-8b', 'gpt-oss-120b') { $m = $ids | Where-Object { $_ -like "*$p*" } | Select-Object -First 1; if ($m) { $model = $m; break } }
        if (-not $model -and $ids.Count -gt 0) { $model = $ids[0] }
        if ($model) {
            $extra = $null; if ($model -match 'gpt-oss') { $extra = @{ reasoning_effort = 'low'; max_tokens = 1500 } }
            Verdict (Chat 'https://api.groq.com/openai/v1/chat/completions' $c['GROQ_KEY'] $model $null $extra) "Groq chat with $model"
        }
    }
}

Write-Host ""
Write-Host "=== 3. OpenRouter (your saved key) ===" -ForegroundColor White
if (-not $c['OPENROUTER_KEY']) { Info "No OpenRouter key saved. Skipping." }
else {
    $r = Web 'GET' 'https://openrouter.ai/api/v1/models' @{ Authorization = "Bearer $($c['OPENROUTER_KEY'])" } $null
    if ($r.Code -ne 200) { Warn "OpenRouter model list FAILED (code $($r.Code)): $(Short $r.Body)" }
    else {
        $free = @(($r.Body | ConvertFrom-Json).data | Where-Object { $_.id -match ':free$' -and [double]$_.pricing.prompt -eq 0 } | ForEach-Object { $_.id })
        Ok "OpenRouter lists $($free.Count) free models: $(($free | Select-Object -First 6) -join ', ')"
        $model = $null
        foreach ($p in 'gpt-oss', 'llama-3.3', 'gemma', 'qwen', 'mistral') { $m = $free | Where-Object { $_ -like "*$p*" } | Select-Object -First 1; if ($m) { $model = $m; break } }
        if (-not $model -and $free.Count -gt 0) { $model = $free[0] }
        if ($model) { Verdict (Chat 'https://openrouter.ai/api/v1/chat/completions' $c['OPENROUTER_KEY'] $model $null $null) "OpenRouter chat with $model" }
    }
}

Write-Host ""
Write-Host "=== 4. GitHub AI and the full server path (optional) ===" -ForegroundColor White
Write-Host "To test this part, create a token at https://github.com/settings/personal-access-tokens (fine grained, any repo access," -ForegroundColor Yellow
Write-Host "with the account permission 'Models: Read'), then paste it below. Press Enter to skip." -ForegroundColor Yellow
$tok = Ask "GitHub token (or press Enter to skip)" -Secret
if ($tok) {
    $tok = $tok.Trim()
    $gh = @{ Authorization = "Bearer $tok"; Accept = 'application/vnd.github+json'; 'X-GitHub-Api-Version' = '2022-11-28'; 'User-Agent' = 'FutureQuestr' }
    $r = Web 'GET' 'https://models.github.ai/catalog/models' $gh $null
    if ($r.Code -ne 200) { Warn "GitHub model catalog FAILED (code $($r.Code)): $(Short $r.Body)" }
    else {
        $ids = @(($r.Body | ConvertFrom-Json) | ForEach-Object { $_.id } | Where-Object { $_ -notmatch 'embed|whisper|tts|image' })
        Ok "GitHub lists $($ids.Count) models: $(($ids | Select-Object -First 8) -join ', ')"
        $model = $null
        foreach ($p in 'gpt-4.1-mini', 'gpt-4o-mini', 'gpt-4.1-nano', 'llama-3.3', 'phi-4') { $m = $ids | Where-Object { $_ -like "*$p*" } | Select-Object -First 1; if ($m) { $model = $m; break } }
        if (-not $model -and $ids.Count -gt 0) { $model = $ids[0] }
        if ($model) { Verdict (Chat 'https://models.github.ai/inference/chat/completions' $tok $model @{ 'X-GitHub-Api-Version' = '2022-11-28' } $null) "GitHub chat with $model" }
    }
    if ($worker) {
        $day = Get-Date
        $body = @{ prompt = 'test'; today = $day.ToString('yyyy-MM-dd'); weekday = $day.DayOfWeek.ToString() } | ConvertTo-Json -Compress
        $r = Web 'POST' ($worker.TrimEnd('/') + '/ai/plan') @{ Authorization = "Bearer $tok" } $body
        Info "Server /ai/plan with the prompt 'test' -> code $($r.Code)"
        if ($r.Body -match '"ok"\s*:\s*true') { Ok "The full planner path WORKS. Reply: $(Short $r.Body 200)" }
        else { Warn "The planner path FAILED. The server said: $(Short $r.Body 400)" }
    }
}
Write-Host ""
Ok "Test finished. Everything above is saved in logs\ai_test.log. Send me that file or run capture_logs.bat."
