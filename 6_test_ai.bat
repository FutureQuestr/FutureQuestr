@echo off
cd /d "%~dp0"
if not exist logs mkdir logs
powershell -NoProfile -ExecutionPolicy Bypass -File "scripts\test_ai.ps1"
set "RC=%errorlevel%"
echo [%date% %time%] 6_test_ai.bat finished with exit code %RC% >> "logs\ai_test.log"
echo.
pause
