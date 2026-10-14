@echo off
chcp 65001 >nul
rem 发布 v0.1.0 到 GitHub：推送 master 与 tag，再创建 Release 并上传已签名 APK。
rem 这一步需要仓库所有者本人执行：自动化环境被本机策略禁止对外网写入。
setlocal
cd /d "%~dp0"
where bash >nul
if errorlevel 1 (
    echo 未找到 bash。请安装 Git for Windows，或直接执行：
    echo     bash scripts/publish-release.sh
    exit /b 1
)
bash scripts/publish-release.sh %*
set RC=%ERRORLEVEL%
echo.
if "%1"=="--yes" goto :eof
pause
:eof
exit /b %RC%
