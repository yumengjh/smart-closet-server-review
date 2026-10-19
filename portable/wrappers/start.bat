@echo off
rem Yuda portable (Windows): double-click to open the menu; "start.bat help" lists commands. ASCII only: cmd reads this file before chcp.
chcp 65001 >nul
setlocal
set "DIR=%~dp0"
"%DIR%runtime\node\node.exe" "%DIR%app\launcher\yuda.mjs" %*
if errorlevel 1 if "%~1"=="" pause
endlocal
