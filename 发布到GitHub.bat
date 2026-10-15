@echo off
chcp 65001 >nul
rem ASCII only on purpose: cmd parses .bat in the OEM codepage (GBK here), and non-ASCII bytes in
rem this file break line parsing. All human-readable output comes from scripts\release-app.ps1,
rem which is UTF-8 with BOM and prints Chinese fine.
rem
rem Double-click = publish the next patch version (auto -Version, latest tag +1).
rem The PowerShell script refuses to run unless the working tree is clean and CHANGELOG.md already
rem has a "## [X.Y.Z] - date" section, so an accidental double click cannot publish anything.
rem
rem Other modes (run from a terminal instead):
rem   powershell -ExecutionPolicy Bypass -File scripts\release-app.ps1 -Version 0.1.1 -DryRun
rem   powershell -ExecutionPolicy Bypass -File scripts\release-app.ps1 -PublishOnly
setlocal
cd /d "%~dp0"

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\release-app.ps1"
set "RC=%ERRORLEVEL%"
echo.
pause
exit /b %RC%
