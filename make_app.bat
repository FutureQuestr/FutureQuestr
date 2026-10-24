@echo off
setlocal
cd /d "%~dp0"
if not exist logs mkdir logs
title FutureQuestr - build and install
>> "logs\make_app.log" echo ==== make_app started %date% %time% ====
echo ============================================================
echo   FutureQuestr: full build (setup, signing, server, APK, install)
echo ============================================================

call :run setup.ps1 "Step 1 of 5: checking and installing tools" || goto :fail
call :run keystore.ps1 "Step 2 of 5: signing keystore" || goto :fail

echo.
set /p DEPLOY=Step 3 of 5: deploy the AI + sync server now? (Y/n): 
>> "logs\make_app.log" echo [%date% %time%] Deploy server answer: %DEPLOY%
if /i "%DEPLOY%"=="n" (
  echo Skipping the server. The AI planner and cloud sync stay off until you run 5_deploy_server.bat and rebuild.
) else (
  call :run deploy_server.ps1 "Step 3 of 5: deploying the server" || goto :fail
)

call :run build.ps1 "Step 4 of 5: building and signing the APK" || goto :fail
call :run install.ps1 "Step 5 of 5: installing on your phone" || goto :fail

powershell -NoProfile -ExecutionPolicy Bypass -File "scripts\summary.ps1"
>> "logs\make_app.log" echo [%date% %time%] FINISHED: all steps succeeded
echo.
pause
exit /b 0

:run
echo.
echo ---- %~2 ----
>> "%~dp0logs\make_app.log" echo [%date% %time%] START %~2
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\%~1"
set "RC=%errorlevel%"
>> "%~dp0logs\make_app.log" echo [%date% %time%] END %~2 - exit code %RC%
exit /b %RC%

:fail
>> "logs\make_app.log" echo [%date% %time%] STOPPED: a step failed
echo.
echo ============================================================
echo   Something failed, so this is NOT finished.
echo   Logs are in the logs folder. Run capture_logs.bat and send me the report.
echo ============================================================
pause
exit /b 1
