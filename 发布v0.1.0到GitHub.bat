@echo off
chcp 65001 >nul
rem ASCII only on purpose: cmd parses .bat in the OEM codepage, and non-ASCII bytes here break
rem line parsing. Human-readable output lives in scripts/publish-release.sh (UTF-8, run by bash).
setlocal
cd /d "%~dp0"

set "BASH="
if exist "C:\Program Files\Gitinash.exe" set "BASH=C:\Program Files\Gitinash.exe"
if not defined BASH for /f "delims=" %%i in ('where bash 2^>nul') do if not defined BASH set "BASH=%%i"
if not defined BASH (
    echo bash.exe not found. Install Git for Windows, or run:
    echo     bash scripts/publish-release.sh
    exit /b 1
)

"%BASH%" scripts/publish-release.sh %*
set "RC=%ERRORLEVEL%"
if "%~1"=="--yes" exit /b %RC%
echo.
pause
exit /b %RC%
