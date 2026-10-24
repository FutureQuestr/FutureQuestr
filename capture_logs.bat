@echo off
cd /d "%~dp0"
if not exist logs mkdir logs
powershell -NoProfile -ExecutionPolicy Bypass -File "scripts\capture_logs.ps1"
set "RC=%errorlevel%"
echo [%date% %time%] capture_logs.bat finished with exit code %RC% >> "logs\capture.log"
if not "%RC%"=="0" (
  echo.
  echo Something went wrong. The log is logs\capture.log
  echo Run capture_logs.bat and send me the report in the logs folder.
)
echo.
pause
