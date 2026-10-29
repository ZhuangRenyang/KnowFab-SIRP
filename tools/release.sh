#!/usr/bin/env bash
#
# 一键发版：构建 → 打签名升级包 → 提交打 tag → GitHub Release → Gitee Release（同步）
#
#   bash tools/release.sh 1.0.2 "修复 xxx；新增 yyy"
#   bash tools/release.sh 1.0.2 "..." --no-build      # 用现有 jar，快速重发
#   bash tools/release.sh 1.0.2 "..." --dry-run       # 只构建+打包，不上传不打 tag
#
# 产出两个附件（名字有讲究，别改）：
#   device-hub-<版本>-update.zip      签名升级包（检查更新只认这个）
#   device-hub-<版本>-installer.zip   新客户安装包（含 install.sh/README）
#
# 为什么必须分开命名：两者都以 device-hub- 开头 .zip 结尾，混用会让客户机
# 下载到安装包去验签，直接失败（UpdateService 已优先挑 update 附件，但命名仍要规范）。
#
# 需要：
#   - GitHub CLI（gh auth login）→ 没有就手动传，脚本会打印命令
#   - GITEE_TOKEN（Gitee → 设置 → 私人令牌，勾 projects）→ 没有就跳过 Gitee
#
# Gitee 说明：Gitee 的「仓库镜像」只同步代码，不同步 Release 附件，
# 所以 Gitee 侧由 tools/gitee-release.sh 通过 API 建发行版 + 传附件。
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
cd "$ROOT"

OWNER="ZhuangRenyang"
REPO="KnowFab-SIRP"

VERSION="${1:-}"
NOTES="${2:-}"
[ -n "$VERSION" ] || { echo "用法：bash tools/release.sh <版本> <更新说明> [--no-build] [--dry-run]" >&2; exit 1; }
shift 2 2>/dev/null || true

DO_BUILD=1
DRY_RUN=0
while [ $# -gt 0 ]; do
  case "$1" in
    --no-build) DO_BUILD=0 ;;
    --dry-run)  DRY_RUN=1 ;;
    -h|--help)  sed -n '2,26p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "未知参数：$1" >&2; exit 1 ;;
  esac
  shift
done

say() { printf '\n== %s\n' "$*"; }
ok()  { printf '   %s\n' "$*"; }
die() { printf '失败：%s\n' "$*" >&2; exit 1; }

TAG="v$VERSION"
ZIP_INSTALLER="dist/device-hub-$VERSION-installer.zip"
ZIP_UPDATE="dist/device-hub-$VERSION-update.zip"

# ---------- 0. 前置检查 ----------
[ -f tools/keys/private.key ] || die "找不到签名私钥 tools/keys/private.key（丢了就升不了级，先恢复备份）"
if git rev-parse "$TAG" >/dev/null 2>&1 && [ "$DRY_RUN" -eq 0 ]; then
  die "tag $TAG 已存在（同一版本不能重复发；要重传附件请单独跑 gh release upload --clobber）"
fi

# ---------- 1. 改版本号 ----------
say "版本号设为 $VERSION"
python - "$VERSION" <<'PY'
import io, re, sys
ver = sys.argv[1]
p = "pom.xml"
s = io.open(p, encoding="utf-8").read()
# 只改项目自身 version（父 pom 的 <version> 在 <parent> 里，缩进不同）
s2 = re.sub(r'(?m)^    <version>[^<]+</version>$', f'    <version>{ver}</version>', s, count=1)
if s2 == s:
    print("   pom 版本已是", ver)
else:
    io.open(p, "w", encoding="utf-8", newline="\n").write(s2)
    print("   pom.xml 已更新")
PY

# ---------- 2. 构建 ----------
if [ "$DO_BUILD" -eq 1 ]; then
  say "构建（前端 + 后端 + 安装包）"
  bash tools/make-dist.sh --build
else
  [ -f target/device-hub.jar ] || die "没有 target/device-hub.jar，去掉 --no-build 先构建"
fi

[ -f "dist/device-hub-$VERSION.zip" ] || die "安装包没生成：dist/device-hub-$VERSION.zip"
mv -f "dist/device-hub-$VERSION.zip" "$ZIP_INSTALLER"
ok "安装包 → $ZIP_INSTALLER"

# ---------- 3. 签名升级包 ----------
say "生成签名升级包"
java tools/PackUpdate.java \
  --jar target/device-hub.jar \
  --version "$VERSION" \
  --notes "$NOTES" \
  --key tools/keys/private.key \
  --out "$ZIP_UPDATE" >/dev/null
[ -f "$ZIP_UPDATE" ] || die "升级包没生成"
ok "$ZIP_UPDATE（$(du -h "$ZIP_UPDATE" | cut -f1)）"

# version.json 指向签名升级包（version.json 模式镜像源用得到）
python - "$VERSION" "$NOTES" "$ZIP_UPDATE" <<'PY'
import hashlib, io, json, os, sys
ver, notes, zip_path = sys.argv[1], sys.argv[2], sys.argv[3]
sha = hashlib.sha256(open(zip_path, "rb").read()).hexdigest()
name = os.path.basename(zip_path)
doc = {
    "version": ver,
    "notes": notes,
    "url": f"https://gitee.com/ZhuangRenyang/KnowFab-SIRP/releases/download/v{ver}/{name}",
    "sha256": sha,
}
io.open("dist/version.json", "w", encoding="utf-8", newline="\n").write(
    json.dumps(doc, ensure_ascii=False, indent=2) + "\n")
print("   dist/version.json 已更新，sha256 =", sha)
PY

if [ "$DRY_RUN" -eq 1 ]; then
  say "dry-run：跳过提交、tag、上传"
  ok "两份包都在 dist/，可直接手动上传"
  exit 0
fi

# ---------- 4. 提交 + tag ----------
say "提交并打 tag $TAG"
git add -A
git commit -m "release: $VERSION" >/dev/null 2>&1 || echo "   没有需要提交的改动"
git tag "$TAG"
git push origin main --tags

# ---------- 5. GitHub Release ----------
say "发布到 GitHub Release"
if command -v gh >/dev/null 2>&1 && gh auth status >/dev/null 2>&1; then
  gh release create "$TAG" "$ZIP_UPDATE" "$ZIP_INSTALLER" \
    --title "$TAG" --notes "$NOTES" || {
      echo "   gh 创建失败，尝试补传附件（release 可能已存在）"
      gh release upload "$TAG" "$ZIP_UPDATE" "$ZIP_INSTALLER" --clobber
    }
  ok "GitHub: https://github.com/$OWNER/$REPO/releases/tag/$TAG"
else
  # 没有 gh 就用 REST API（令牌取 git 凭据，本机 push 过就可用）
  echo "   未检测到 gh，改用 REST API 发布"
  bash tools/github-release.sh "$VERSION" "$NOTES" "$ZIP_UPDATE" "$ZIP_INSTALLER"
fi

# ---------- 6. Gitee Release（同步） ----------
say "同步到 Gitee Release"
if [ -n "${GITEE_TOKEN:-}" ]; then
  bash tools/gitee-release.sh "$VERSION" "$NOTES" "$ZIP_UPDATE" "$ZIP_INSTALLER"
else
  echo "   未设置 GITEE_TOKEN，跳过。补发命令："
  echo "   GITEE_TOKEN=你的令牌 bash tools/gitee-release.sh $VERSION \"$NOTES\" $ZIP_UPDATE $ZIP_INSTALLER"
fi

cat <<EOF

发版完成：$TAG
  升级包   $ZIP_UPDATE
  安装包   $ZIP_INSTALLER

客户机「检查更新」读取顺序：Gitee（主）→ GitHub（兜底）
EOF
