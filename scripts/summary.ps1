. "$PSScriptRoot\lib.ps1"
$c = Get-Config
$n = 1
$names = @('GitHub Models (each user''s own allowance)')
if ($c['GROQ_KEY']) { $n++; $names += 'Groq' }
if ($c['OPENROUTER_KEY']) { $n++; $names += 'OpenRouter' }
Write-Host ""
Write-Host "FINISHED: FutureQuestr is built, signed and implemented on your phone." -ForegroundColor Green
Write-Host "AI providers active: $n ($($names -join ', '))" -ForegroundColor Green
if (-not $c['WORKER_URL']) { Write-Host "Note: no server address yet, so the AI planner and cloud sync are off in this build." -ForegroundColor Yellow }
