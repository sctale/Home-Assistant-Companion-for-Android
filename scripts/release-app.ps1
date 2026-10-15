# =====================================================
# Home Assistant Companion（无 GMS 版）一键发布脚本
#
# 沿用 TapLedger / TapMate 的 scripts\release-app.ps1 流程：一个 PowerShell 脚本从校验一路做到
# Release 创建，全程不经过 Git Bash（本机的 .bat + bash 链路已被证明不稳：cmd 按 GBK 解析中文、
# `where bash` 会命中 WSL 的 bash.exe）。
#
# 用法（任意目录都能跑，路径由 $PSScriptRoot 解析）：
#   powershell -ExecutionPolicy Bypass -File scripts\release-app.ps1 -Version 0.1.1
#   powershell -ExecutionPolicy Bypass -File scripts\release-app.ps1 -Version 0.1.1 -DryRun
#   powershell -ExecutionPolicy Bypass -File scripts\release-app.ps1 -PublishOnly
#
#   -Version      要发的版本号（X.Y.Z，不带 v）。省略时取最新 tag 的补丁位 +1。
#   -VersionCode  默认由版本号推导 major*10000+minor*100+patch，保证单调递增；需要手工指定时覆盖。
#                 注意首个版本 v0.1.0 用的是 1（写死在当时的构建命令里），所以重放 0.1.0 要显式
#                 传 -VersionCode 1；从 0.1.1 起用推导值（101、102…）都大于 1，不会回退。
#   -DryRun       跑完全部自检 + 真实构建 + 验签，在打 tag 之前停下，不做任何外网操作。
#   -PublishOnly  跳过构建，用仓库里已有的产物和 CHANGELOG 重发 Release（改了说明、或 gh 半途
#                 失败之后用它补），tag 必须已存在。
#
# 前置：
#   - JDK 17+ / Android SDK（aapt2 + apksigner）/ gh CLI 已登录
#   - keystore\signing.env：KEYSTORE_PATH / KEYSTORE_PASSWORD / KEYSTORE_ALIAS（密钥与口令不入库）
#   - CHANGELOG.md 里有本版本小节，标题形如 `## [0.1.1] - 2026-10-15`（Release 正文取自这一节）
#   - 工作区干净：本项目按主题提交，发布脚本不替你 commit 代码，只打 tag、推送、发 Release
# =====================================================

param(
  [string]$Version = '',
  [int]$VersionCode = 0,
  [switch]$DryRun,
  [switch]$PublishOnly
)

$ErrorActionPreference = 'Stop'
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)

function Fail([string]$msg) { Write-Host "[错误] $msg" -ForegroundColor Red; exit 1 }
function Step([string]$msg) { Write-Host ''; Write-Host "==> $msg" -ForegroundColor Cyan }

function Resolve-BuildTool([string]$name) {
  foreach ($sdkRoot in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, "$env:LOCALAPPDATA\Android\Sdk")) {
    if (-not $sdkRoot -or -not (Test-Path "$sdkRoot\build-tools")) { continue }
    $dirs = Get-ChildItem "$sdkRoot\build-tools" -Directory |
            Where-Object { $_.Name -match '^\d+(\.\d+)*$' } |
            Sort-Object { [version]$_.Name } -Descending
    foreach ($dir in $dirs) {
      foreach ($ext in @('.exe', '.bat', '')) {
        $candidate = Join-Path $dir.FullName ($name + $ext)
        if (Test-Path $candidate) { return $candidate }
      }
    }
  }
  return $null
}

$root = Split-Path -Parent $PSScriptRoot
Push-Location $root
try {
  # ---------- 0. 环境与固定参数 ----------
  $appName = 'ha-companion-minimal'
  $repo    = 'sctale/Home-Assistant-Companion-for-Android'
  $branch  = 'master'
  # 产物身份带 minimal flavor 的后缀（AndroidFullMinimalFlavorConventionPlugin：
  # applicationIdSuffix ".minimal" / versionNameSuffix "-minimal"），照实校验，不要写成基础包名。
  $appId        = 'dev.sctale.homeassistant.companion.minimal'
  $flavorSuffix = '-minimal'

  $gh = 'C:\Program Files\GitHub CLI\gh.exe'
  if (-not (Test-Path $gh)) {
    $gh = (Get-Command gh -ErrorAction SilentlyContinue | Select-Object -First 1 -ExpandProperty Source)
  }
  if (-not $gh) { Fail "未找到 gh CLI（期望 C:\Program Files\GitHub CLI\gh.exe）。发布需要它：gh auth login --hostname github.com --git-protocol https --web" }

  $signingEnv = Join-Path $root 'keystore\signing.env'
  if (-not (Test-Path $signingEnv)) { Fail "缺少签名材料 $signingEnv（KEYSTORE_PATH / KEYSTORE_PASSWORD / KEYSTORE_ALIAS），禁止发布。" }
  foreach ($line in [System.IO.File]::ReadAllLines($signingEnv, $utf8NoBom)) {
    if ($line -match '^\s*(?:export\s+)?([A-Z][A-Z0-9_]*)\s*=\s*(.*)$') {
      $name = $Matches[1]
      $value = $Matches[2].Trim()
      if ($value -match '^"(.*)"$' -or $value -match "^'(.*)'$") { $value = $Matches[1] }
      Set-Item -Path ('env:' + $name) -Value $value
    }
  }
  if (-not $env:KEYSTORE_PATH) { Fail "$signingEnv 里没有 KEYSTORE_PATH。" }
  if (-not (Test-Path (Join-Path $root $env:KEYSTORE_PATH))) { Fail "keystore 不存在：$env:KEYSTORE_PATH" }

  # ---------- 1. 解析版本号 ----------
  if (-not $Version) {
    $latest = (& git describe --tags --abbrev=0 2>$null)
    if (-not $latest) { Fail '仓库里还没有 tag，无法自动推版本号，请显式传 -Version X.Y.Z' }
    $parts = $latest.TrimStart('v').Split('.')
    $parts[2] = [string]([int]$parts[2] + 1)
    $Version = $parts -join '.'
  }
  if ($Version -notmatch '^\d+\.\d+\.\d+$') { Fail "版本号格式错误：$Version（应为 X.Y.Z，不带 v）" }
  $vp = $Version.Split('.')
  if ([int]$vp[1] -ge 100 -or [int]$vp[2] -ge 100) { Fail 'minor/patch 必须 < 100，否则推导出的 versionCode 会撞车' }
  if (-not $VersionCode) { $VersionCode = [int]$vp[0] * 10000 + [int]$vp[1] * 100 + [int]$vp[2] }
  $tag = 'v' + $Version
  Write-Host "发布 $tag（versionCode $VersionCode）" -ForegroundColor Yellow

  # ---------- 2. CHANGELOG 校验（Release 正文来源） ----------
  Step '校验 CHANGELOG'
  $changelogRaw = [System.IO.File]::ReadAllText((Join-Path $root 'CHANGELOG.md'), $utf8NoBom)
  if ($changelogRaw -notmatch ('## \[' + [regex]::Escape($Version) + '\]')) {
    Fail "CHANGELOG.md 缺少 [$Version] 小节。请补一行 ``## [$Version] - YYYY-MM-DD`` 再发布（Release 正文取自这一节）。"
  }
  Write-Host "  CHANGELOG 有 [$Version] 小节"

  # ---------- 3. 工作区必须干净（本项目按主题提交，脚本不做 git add） ----------
  Step '校验 git 状态'
  $dirty = @(& git status --porcelain)
  if ($dirty.Count -gt 0) {
    foreach ($line in ($dirty | Select-Object -First 10)) { Write-Host "  $line" }
    Fail '工作区不干净。先把改动按主题提交（发布脚本不会替你 commit）。'
  }
  $head = (& git rev-parse HEAD).Trim()
  Write-Host "  HEAD = $($head.Substring(0,7))，工作区干净"

  # ---------- 4. tag 必须落在 HEAD 上 ----------
  # versionName 由 git tag 推导（reckon 插件），所以顺序只能是「提交 → 打 tag → 构建」；
  # tag 不在 HEAD 上就会发出一个自称 vX.Y.Z、实际是别处代码的 APK。
  Step "校验 / 创建 tag $tag"
  # 注意取的是 tag 指向的**提交**：annotated tag（git tag -a）的 refs/tags/vX.Y.Z 是 tag 对象的 sha，
  # 拿它和 HEAD 比会永远不等。
  $existing = (& git rev-parse --verify --quiet "$tag^{commit}" 2>$null)
  if ($existing) {
    $existing = "$existing".Trim()
    if ($existing -ne $head) {
      # 发版之后追加的文档/工具提交会让 tag 落后于 HEAD。只有"不重新构建"的 -PublishOnly 允许这种
      # 状态：它上传的是既有产物，而那份产物本来就出自 tag 所在的那个提交。
      if (-not $PublishOnly) {
        Fail "$tag 已存在但指向 $($existing.Substring(0,7))，HEAD 是 $($head.Substring(0,7))。要按 HEAD 重建，先确认无误再手工重打 tag。"
      }
      Write-Host "  $tag 在 $($existing.Substring(0,7))，HEAD 是 $($head.Substring(0,7))（-PublishOnly：沿用现有产物，不按 HEAD 重建）" -ForegroundColor Yellow
    } else {
      Write-Host "  $tag 已存在且就在 HEAD"
    }
  } elseif ($PublishOnly) {
    Fail "-PublishOnly 要求 $tag 已存在（检查：git tag --points-at HEAD）"
  } elseif ($DryRun) {
    Write-Host '  -DryRun：跳过创建 tag' -ForegroundColor Yellow
  } else {
    & git tag -a $tag -m $tag
    if ($LASTEXITCODE -ne 0) { Fail "git tag -a $tag 失败（exit $LASTEXITCODE）" }
    # 上一版的教训：tag 失败时 stderr 被吞掉，看起来像成功了。创建后必须回读确认。
    $created = (& git rev-parse --verify "$tag^{commit}" 2>$null)
    $created = "$created".Trim()
    if ($created -ne $head) { Fail "打了 tag 但回读不是 HEAD（得到 $created）" }
    Write-Host "  已创建 $tag -> $($head.Substring(0,7))"
  }

  # ---------- 5. 本地构建 minimalRelease ----------
  $apkOut  = Join-Path $root 'app\build\outputs\apk\minimal\release\app-minimal-release.apk'
  $apkCopy = Join-Path $root ('release\' + $appName + '-' + $tag + '.apk')
  if (-not $PublishOnly) {
    Step 'gradlew :app:assembleMinimalRelease'
    $env:VERSION_CODE = [string]$VersionCode
    & .\gradlew.bat --console=plain :app:assembleMinimalRelease
    if ($LASTEXITCODE -ne 0) { Fail "构建失败（exit $LASTEXITCODE）。" }
  }
  if (-not (Test-Path $apkOut)) { Fail "未找到构建产物：$apkOut" }

  # ---------- 6. aapt2 校验包名与版本号 ----------
  $aapt2 = Resolve-BuildTool 'aapt2'
  $apksigner = Resolve-BuildTool 'apksigner'
  if (-not $aapt2) { Fail "未找到 aapt2（ANDROID_HOME=$env:ANDROID_HOME），无法校验产物。" }
  if (-not $apksigner) { Fail "未找到 apksigner（ANDROID_HOME=$env:ANDROID_HOME），无法校验签名。" }

  Step '校验产物身份'
  $badging = ((& $aapt2 dump badging $apkOut 2>$null) -join "`n")
  $pkgLine = ($badging -split "`n" | Where-Object { $_ -match '^package:' } | Select-Object -First 1)
  if (-not $pkgLine) { Fail 'aapt2 没有输出 package: 行。' }
  Write-Host "  $pkgLine" -ForegroundColor Gray
  if ($pkgLine -notmatch [regex]::Escape("name='$appId'")) { Fail "包名不是 $appId —— 产物可能来自别的分支或别的 flavor。" }
  if ($pkgLine -notmatch [regex]::Escape("versionName='$Version$flavorSuffix'")) { Fail "versionName 校验失败（期望 $Version$flavorSuffix）。" }
  if ($pkgLine -notmatch [regex]::Escape("versionCode='$VersionCode'")) { Fail "versionCode 校验失败（期望 $VersionCode）。" }

  # ---------- 7. 签名校验：release 绝不允许 debug key ----------
  Step '校验签名'
  $certs = (& $apksigner verify --print-certs $apkOut 2>&1) | Out-String
  $dnLine = ($certs -split "`n" | Where-Object { $_ -match 'certificate DN' } | Select-Object -First 1)
  if ($certs -match 'CN=Android Debug') { Fail '产物是 debug key 签名的，禁止发布。' }
  if (-not $dnLine) { Fail "apksigner 没有给出证书 DN：`n$certs" }
  Write-Host ('  ' + $dnLine.Trim()) -ForegroundColor Gray

  # ---------- 8. 产物落到 release/ 并记下校验和 ----------
  Step '复制产物'
  New-Item -ItemType Directory -Force -Path (Split-Path $apkCopy) | Out-Null
  Copy-Item $apkOut $apkCopy -Force
  $hash = (Get-FileHash $apkCopy -Algorithm SHA256).Hash.ToLower()
  $mb = [math]::Round((Get-Item $apkCopy).Length / 1MB, 1)
  Write-Host "  $appName-$tag.apk（$mb MB）sha256 $($hash.Substring(0,12))…"

  # ---------- 9. Release 正文：CHANGELOG 本版本小节 ----------
  # 用 --notes-file 而不是 --notes：含 [x] 的正文经 --notes 传参会被通配符/引号干扰（TapLedger 已踩过）。
  $pattern = '(?s)## \[' + [regex]::Escape($Version) + '\][^\r\n]*\r?\n(.*?)(?=\r?\n## \[|\Z)'
  $m = [regex]::Match($changelogRaw, $pattern)
  if (-not $m.Success) { Fail "CHANGELOG 的 [$Version] 小节取不到正文。" }
  $notes = "fork 自上游 home-assistant/android 与国内定制版 nesror/Home-Assistant-Companion-for-Android；详见 README「源头与致谢」。`n`n" + $m.Groups[1].Value.Trim()
  $notesFile = Join-Path ([System.IO.Path]::GetTempPath()) ('ha-companion-release-notes-' + $Version + '.md')
  [System.IO.File]::WriteAllText($notesFile, $notes, $utf8NoBom)

  if ($DryRun) {
    Step '-DryRun：自检与构建全部通过，未创建 tag、未推送、未创建 Release'
    Write-Host "  将要推送：$branch + $tag" -ForegroundColor Yellow
    Write-Host "  将要上传：$apkCopy" -ForegroundColor Yellow
    Write-Host "  正文临时文件：$notesFile" -ForegroundColor Yellow
    exit 0
  }

  # ---------- 10. 推送分支与 tag ----------
  Step "git push origin $branch $tag"
  & git push origin $branch $tag
  if ($LASTEXITCODE -ne 0) { Fail 'git push 失败。远端可能已收到部分内容，重跑本脚本即可（各步都做了存在性检查）。' }

  # ---------- 11. 创建 Release 或补传产物 ----------
  Step "gh release（$tag）"
  & $gh release view $tag --repo $repo | Out-Null
  if ($LASTEXITCODE -eq 0) {
    Write-Host "  Release $tag 已存在，同步标题与正文、再覆盖产物"
    # 改了 CHANGELOG 小节之后跑 -PublishOnly，正文要真的同步过去，否则远端还是旧说明。
    & $gh release edit $tag --repo $repo --title "$tag 无GMS版" --notes-file $notesFile
    if ($LASTEXITCODE -ne 0) { Fail "更新 Release 正文失败（exit $LASTEXITCODE）。" }
    & $gh release upload $tag $apkCopy --clobber --repo $repo
    if ($LASTEXITCODE -ne 0) { Fail '产物上传失败。' }
  } else {
    & $gh release create $tag $apkCopy --repo $repo --title "$tag 无GMS版" --target $branch --notes-file $notesFile
    # gh 偶发 HTTP 500：资产其实已经传完、Release 也已经公开，只是收尾编辑失败。
    # 所以失败后先回读，绝不盲目重建（重建会得到一个没有正文/资产的残缺 Release）。
    if ($LASTEXITCODE -ne 0) {
      Write-Host "  gh release create 返回 $LASTEXITCODE，回读远端确认实际状态…" -ForegroundColor Yellow
      & $gh release view $tag --repo $repo | Out-Null
      if ($LASTEXITCODE -ne 0) { Fail 'Release 未创建成功，可修复后重跑本脚本（-PublishOnly）。' }
      Write-Host '  Release 其实已创建，补齐产物'
      & $gh release upload $tag $apkCopy --clobber --repo $repo
    }
  }

  # ---------- 12. 回读远端 ----------
  Step '远端状态'
  (& git ls-remote origin "refs/heads/$branch" "refs/tags/$tag") | ForEach-Object { Write-Host "  $_" }
  & $gh release view $tag --repo $repo --json tagName,isDraft,assets `
    --jq '"  tag: " + .tagName + "  draft: " + (.isDraft | tostring) + "  assets: " + ([.assets[].name] | join(", "))'
  Remove-Item $notesFile -Force -ErrorAction SilentlyContinue

  Write-Host ''
  Write-Host "发布完成：$tag" -ForegroundColor Green
  Write-Host "  https://github.com/$repo/releases/tag/$tag" -ForegroundColor Cyan
}
finally {
  Pop-Location
}
