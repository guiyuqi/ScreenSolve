#!/usr/bin/env bash
# 用 GitHub REST API 创建 Release 并上传两个 APK。
#
# 用法：
#   GITHUB_TOKEN=xxx ./scripts/publish-release.sh
#
# token 只需要对 guiyuqi/ScreenSolve 有 Contents: Read and write（细粒度）
# 或 classic 的 repo 权限。用完可以在 GitHub 设置里立刻删除。
#
# 说明：WSL 直连 api.github.com 可用（实测 http=200），若直连不稳可设
#   export HTTPS_PROXY=http://192.168.192.1:10090
set -euo pipefail

REPO="${REPO:-guiyuqi/ScreenSolve}"
TAG="${TAG:-v1.0.0}"
NAME="${NAME:-搜题助手 v1.0.0}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NOTES="$ROOT/RELEASE_NOTES_v1.0.0.md"
# 本地产物路径 → 上传后的资产名（用户下载时看到的名字，要和发布说明里一致）
ASSET_LOCAL=(
    "$ROOT/app/build/outputs/apk/lite/release/app-lite-release.apk"
    "$ROOT/app/build/outputs/apk/full/release/app-full-release.apk"
)
ASSET_NAME=(
    "ScreenSolve-lite-release.apk"
    "ScreenSolve-full-release.apk"
)

: "${GITHUB_TOKEN:?请先 export GITHUB_TOKEN=你的 token}"

for f in "${ASSET_LOCAL[@]}"; do
    [ -f "$f" ] || { echo "缺少产物: $f"; exit 1; }
done

api() {
    curl -sS -m 300 -H "Authorization: Bearer $GITHUB_TOKEN" \
        -H "Accept: application/vnd.github+json" \
        -H "X-GitHub-Api-Version: 2022-11-28" "$@"
}

echo "== 检查是否已有该 tag 的 release"
EXISTING=$(api "https://api.github.com/repos/$REPO/releases/tags/$TAG" 2>/dev/null || true)
if echo "$EXISTING" | grep -q '"id"'; then
    UPLOAD_URL=$(echo "$EXISTING" | python3 -c 'import sys,json;print(json.load(sys.stdin)["upload_url"])')
    echo "已有 release，直接追加/覆盖资产"
else
    echo "== 创建 release"
    BODY=$(python3 -c 'import json,sys;print(json.dumps({"tag_name":sys.argv[1],"name":sys.argv[2],"body":open(sys.argv[3],encoding="utf-8").read(),"draft":False,"prerelease":False}))' "$TAG" "$NAME" "$NOTES")
    CREATED=$(api -X POST "https://api.github.com/repos/$REPO/releases" -d "$BODY")
    # 403/404 时 CREATED 里没有 upload_url，直接取会抛 KeyError 看不懂，这里先给可读错误
    if ! printf '%s' "$CREATED" | grep -q '"upload_url"'; then
        echo "创建 release 失败：$(printf '%s' "$CREATED" | head -c 200)" >&2
        echo "提示：fine-grained token 需要 代码内容 / Contents: Read and write" >&2
        exit 1
    fi
    UPLOAD_URL=$(echo "$CREATED" | python3 -c 'import sys,json;print(json.load(sys.stdin)["upload_url"])')
    echo "$CREATED" | python3 -c 'import sys,json;d=json.load(sys.stdin);print("release:", d.get("html_url"))'
fi

UPLOAD_URL="${UPLOAD_URL%%\{*}"   # 去掉 {?name,label}

for i in "${!ASSET_LOCAL[@]}"; do
    f="${ASSET_LOCAL[$i]}"
    name="${ASSET_NAME[$i]}"
    echo "== 上传 $name ($(du -h "$f" | cut -f1))"
    api -X POST -H "Content-Type: application/vnd.android.package-archive" \
        --data-binary @"$f" "$UPLOAD_URL?name=$name" \
        | python3 -c 'import sys,json;d=json.load(sys.stdin);print("   ->", d.get("browser_download_url") or d)'
done

echo "== 完成"
