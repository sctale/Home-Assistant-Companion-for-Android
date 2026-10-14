#!/usr/bin/env bash
#
# Publishes v0.1.0 to GitHub: pushes master + the tag, then opens the Release with the signed APK.
#
# This is deliberately a *human-invoked* script: the agent that built everything else is blocked by
# this machine's permission policy from performing network egress, so the last step is run by the
# repository owner. Nothing here is destructive - the push is a fast-forward and the release is a
# new tag, so re-running after a partial failure is safe.
set -euo pipefail

# --dry-run 只跑自检与差异统计，不做任何外网操作（供 CI 之外的本地复核）。
cd "$(dirname "$0")/.."

TAG=v0.1.0
APK="release/ha-companion-minimal-v0.1.0.apk"
NOTES="D:/V-Coding/ha-companion-v0.1.0-release-notes.md"
REPO=sctale/Home-Assistant-Companion-for-Android

echo "==> Pre-flight"
[[ -f "$APK" ]] || { echo "缺少产物 $APK，先跑 scripts/build-release.sh 1" >&2; exit 1; }
[[ -f "$NOTES" ]] || { echo "缺少发布说明 $NOTES" >&2; exit 1; }
git rev-parse "$TAG" >/dev/null 2>&1 || { echo "本地没有 $TAG 这个 tag" >&2; exit 1; }

# The tag must be on the commit we are about to publish, otherwise the Release would point at code
# that is not the code the APK was built from.
if [[ "$(git rev-parse "$TAG^{commit}")" != "$(git rev-parse HEAD)" ]]; then
  echo "$TAG 不在当前 HEAD 上（tag=$(git rev-parse --short "$TAG^{commit}") head=$(git rev-parse --short HEAD)）" >&2
  echo "若这是有意的（例如 HEAD 之后只有文档提交），确认后手动重打 tag 再跑。" >&2
  exit 1
fi

# The APK is the artifact named in the release notes; refuse to publish a mismatched pair.
ACTUAL_SHA=$(sha256sum "$APK" | cut -d' ' -f1)
grep -q "$ACTUAL_SHA" "$NOTES" || { echo "发布说明里的 APK 校验和与实际产物不一致，先更新说明" >&2; exit 1; }

echo "  branch  master -> origin（$(git rev-list --count "origin/master..master") 个提交待推）"
echo "  tag     $TAG = $(git rev-parse --short HEAD)"
echo "  artifact $APK  $(du -h "$APK" | cut -f1)  sha256 ${ACTUAL_SHA:0:12}…"

echo
if [[ "${1:-}" == "--dry-run" ]]; then
  echo
  echo "==> --dry-run：自检通过，未执行任何推送。"
  exit 0
fi

echo "==> 推送 master 与 $TAG"
git push origin master "$TAG"

echo
echo "==> 创建 Release"
if gh release view "$TAG" --repo "$REPO" >/dev/null 2>&1; then
  echo "Release $TAG 已存在，改为上传产物"
  gh release upload "$TAG" "$APK" --clobber --repo "$REPO"
else
  gh release create "$TAG" "$APK" \
    --repo "$REPO" \
    --title "v0.1.0 无GMS版" \
    --notes-file "$NOTES"
fi

echo
echo "==> 校验远端状态"
git ls-remote origin "refs/heads/master" "refs/tags/$TAG" | sed 's/^/  /'
gh release view "$TAG" --repo "$REPO" --json tagName,isDraft,assets \
  --jq '"  tag: " + .tagName + "\n  draft: " + (.isDraft|tostring) + "\n  assets: " + ([.assets[].name]|join(", "))'

echo
echo "完成。下载地址见上面 Release 页面。"
