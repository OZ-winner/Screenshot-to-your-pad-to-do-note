@echo off
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ".\scripts\dev-windows.ps1"
if errorlevel 1 (
  echo.
  echo Startup failed. Send the error above to Codex.
  pause
)
