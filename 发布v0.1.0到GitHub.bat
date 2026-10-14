@echo off
chcp 65001 >/dev/null
rem ASCII only on purpose: cmd parses .bat in the OEM codepage, and non-ASCII bytes here break
rem line parsing. All human-readable output lives in scripts/publish-release.sh (UTF-8, run by bash).
setlocal
cd /d "%~dp0"

rem Probe known Git-for-Windows locations first. `where bash` alone is not enough: on this machine it
rem also resolves C:\Windows\System32\bash.exe, which is the WSL launcher, and with no distro
rem installed it dies with "execvpe(/bin/bash) failed" before running a single line of the script.
set "BASH="
if exist "C:\Program Files\Git\bin\bash.exe" set "BASH=C:\Program Files\Git\bin\bash.exe"
if not defined BASH if exist "%ProgramFiles%\Git\bin\bash.exe" set "BASH=%ProgramFiles%\Git\bin\bash.exe"
if not defined BASH if exist "%LocalAppData%\Programs\Git\bin\bash.exe" set "BASH=%LocalAppData%\Programs\Git\bin\bash.exe"
if not defined BASH (
    echo Could not find Git for Windows bash.exe.
    echo Install Git for Windows, or run this from a Git Bash prompt:
    echo     bash scripts/publish-release.sh
    exit /b 1
)

"%BASH%" scripts/publish-release.sh %*
set "RC=%ERRORLEVEL%"
if "%~1"=="--yes" exit /b %RC%
echo.
pause
exit /b %RC%
