#!/usr/bin/env bash
# 把 GitHub 发布用的 token 存到本机（WSL）一个仅本人可读的文件里，之后 publish-release.sh 会自动读取。
#
# 用法：./scripts/set-token.sh
#   - 交互式输入（不回显、不进 shell 历史）
#   - 写入 ~/.config/screensolve/token（文件 600、目录 700）
#
# 为什么不写进仓库/环境变量文件：仓库会进 git（等于泄密），shell rc 里的 export 会被子进程、
# 日志、`env` 一览无余。放在用户私有目录 + 600 权限是最小暴露面。
set -euo pipefail

DIR="${XDG_CONFIG_HOME:-$HOME/.config}/screensolve"
FILE="$DIR/token"

mkdir -p "$DIR"
chmod 700 "$DIR"

printf "粘贴 GitHub token（输入不回显）: "
read -rs TOKEN
echo

TOKEN="${TOKEN//[$'\r\n\t ']/}"
if [ -z "$TOKEN" ]; then
    echo "没有输入，已取消" >&2
    exit 1
fi
case "$TOKEN" in
    github_pat_*|ghp_*|gho_*|gHS_*) ;;
    *) echo "看起来不是 GitHub token（应以 github_pat_ / ghp_ 开头）" >&2; exit 1 ;;
esac

umask 077
printf '%s' "$TOKEN" > "$FILE"
chmod 600 "$FILE"
echo "已保存到 $FILE（权限 $(stat -c '%a' "$FILE")）"
echo "之后直接跑 ./scripts/publish-release.sh 即可，不用再带 token。"
