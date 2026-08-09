@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\windows\start-ugnay.ps1"
if errorlevel 1 pause
