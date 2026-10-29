#!/usr/bin/env bash
#
# 一条命令安装并启动（Linux / macOS Intel）
#
#   curl -fsSL https://gitee.com/ZhuangRenyang/KnowFab-SIRP/raw/main/tools/portable/install.sh | bash
#   curl -fsSL https://raw.githubusercontent.com/ZhuangRenyang/KnowFab-SIRP/main/tools/portable/install.sh | bash
#
# 找最新发行版里的 portable 包 → 解压到 ~/KnowFabSIRP → 启动
# 首次启动会自动下载 JRE 与 MariaDB（见 start.sh），之后秒起。
# 找包时先问 Gitee 发行版、失败再问 GitHub（两个源是同一个包）。
set -euo pipefail

OWNER="ZhuangRenyang"
REPO="KnowFab-SIRP"
DEST="${KNOWFAB_DIR:-$HOME/KnowFabSIRP}"

say() { printf '\n== %s\n' "$*"; }
die() { printf '\n失败：%s\n' "$*" >&2; exit 1; }

command -v curl >/dev/null || die "需要 curl"
command -v unzip >/dev/null || die "需要 unzip（apt install unzip / yum install unzip）"

pick_url() {
  local api="$1"
  curl -fsSL --max-time 20 -H 'User-Agent: knowfab-installer' "$api" 2>/dev/null \
    | tr ',' '\n' | grep -o '"browser_download_url":"[^"]*portable\.zip"' \
    | head -1 | cut -d'"' -f4 || true
}

say '查找最新便携包'
# 手动下载了 zip 的（内网/离线）：KNOWFAB_PORTABLE_URL=/path/to/xxx-portable.zip 直接指过去
URL="${KNOWFAB_PORTABLE_URL:-}"
SRC='自定义'
if [ -z "$URL" ]; then
  URL="$(pick_url "https://gitee.com/api/v5/repos/$OWNER/$REPO/releases/latest")"
  SRC='Gitee'
fi
if [ -z "$URL" ]; then
  URL="$(pick_url "https://api.github.com/repos/$OWNER/$REPO/releases/latest")"
  SRC='GitHub'
fi
[ -n "$URL" ] || die '没找到 portable 包（发行版需上传 device-hub-<版本>-portable.zip）'
echo "   $SRC: $(basename "$URL")"

say '下载'
TMP="$(mktemp -d)"
ZIP="$TMP/portable.zip"
if [ -f "$URL" ]; then
  cp "$URL" "$ZIP"
  echo "   使用本地包：$URL"
else
  curl -fL --retry 3 -o "$ZIP" "$URL" || die "下载失败：$URL"
fi
echo "   $(du -h "$ZIP" | cut -f1)"

say '解压'
unzip -q "$ZIP" -d "$TMP"
SRC_DIR="$(find "$TMP" -maxdepth 1 -type d -name 'device-hub-*-portable' | head -1)"
[ -n "$SRC_DIR" ] || die '压缩包结构不对'
mkdir -p "$DEST"
# 保留已有 runtime（数据库）与 config，只覆盖程序与脚本
cp -R "$SRC_DIR"/* "$DEST"/
# 清理失败不能挡住启动（有些环境的删除守卫会拦住临时目录）
rm -rf "$TMP" 2>/dev/null || true
chmod +x "$DEST/start.sh" "$DEST/stop.sh" 2>/dev/null || true
echo "   已安装到 $DEST"

say '启动'
cd "$DEST"
exec ./start.sh
