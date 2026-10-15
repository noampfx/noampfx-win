@echo off
cd /d "%~dp0"
where pythonw >nul 2>nul
if %errorlevel%==0 (
  start "" pythonw keygen_gui.pyw
) else (
  python keygen_gui.pyw
  pause
)
