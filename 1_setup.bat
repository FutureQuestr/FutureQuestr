@echo off
cd /d "%~dp0"
if not exist logs mkdir logs
powershell -NoProfile -ExecutionPolicy Bypass -File "scripts\setup.ps1"
set "RC=%errorlevel%"
echo [%date% %time%] 1_setup.bat finished with exit code %RC% >> "logs\setup.log"
if not "%RC%"=="0" (
  echo.
  echo Something went wrong. The log is logs\setup.log
  echo Run capture_logs.bat and send me the report in the logs folder.
)
echo.
pause
