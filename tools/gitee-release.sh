#!/usr/bin/env bash
#
# 把已经构建好的升级包/安装包发布到 Gitee Releases（GitHub 由 gh 或 Actions 负责）。
#
#   GITEE_TOKEN=xxxx bash tools/gitee-release.sh 1.0.2 "更新说明" \
#        dist/device-hub-1.0.2-update.zip dist/device-hub-1.0.2-installer.zip
#
# 说明：
#   - Gitee 的「仓库镜像」只同步代码（分支/tag），**不会同步 Release 附件**，
#     所以 Gitee 侧必须用 API 单独建发行版并上传附件（这正是本脚本做的事）。
#   - 令牌在 Gitee → 设置 → 私人令牌 生成，勾 projects 权限即可；不要写进仓库。
#   - 同一个 tag 已存在发行版时，脚本会复用它并补传缺失附件（可重跑）。
set -euo pipefail

OWNER="${GITEE_OWNER:-ZhuangRenyang}"
REPO="${GITEE_REPO:-KnowFab-SIRP}"
API="https://gitee.com/api/v5/repos/$OWNER/$REPO"

VERSION="${1:-}"
NOTES="${2:-}"
shift 2 2>/dev/null || true
FILES=("$@")

[ -n "$VERSION" ] || { echo "用法：GITEE_TOKEN=xxx bash tools/gitee-release.sh <版本> <说明> <文件...>" >&2; exit 1; }
[ "${#FILES[@]}" -gt 0 ] || { echo "至少给一个要上传的文件" >&2; exit 1; }
[ -n "${GITEE_TOKEN:-}" ] || { echo "缺少 GITEE_TOKEN（Gitee → 设置 → 私人令牌）" >&2; exit 1; }

TAG="v$VERSION"

say() { printf '\n== %s\n' "$*"; }

json_get_id() {
  python -c "import json,sys; d=json.load(sys.stdin); print(d.get('id') or '')" 2>/dev/null || true
}

say "查找 Gitee 上是否已有 $TAG 的发行版"
REL_ID="$(curl -s --noproxy '*' "$API/releases/tags/$TAG?access_token=$GITEE_TOKEN" | json_get_id)"

if [ -z "$REL_ID" ]; then
  say "创建发行版 $TAG"
  REL_ID="$(curl -s --noproxy '*' -X POST "$API/releases" \
    --data-urlencode "access_token=$GITEE_TOKEN" \
    --data-urlencode "tag_name=$TAG" \
    --data-urlencode "name=$TAG" \
    --data-urlencode "body=$NOTES" \
    --data-urlencode "target_commitish=main" | json_get_id)"
  [ -n "$REL_ID" ] || { echo "创建发行版失败（检查令牌权限/标签是否已存在）" >&2; exit 1; }
  echo "   发行版 id=$REL_ID"
else
  echo "   已存在，复用 id=$REL_ID"
fi

for f in "${FILES[@]}"; do
  [ -f "$f" ] || { echo "文件不存在：$f" >&2; exit 1; }
  say "上传 $(basename "$f")（$(du -h "$f" | cut -f1)）"
  resp="$(curl -s --noproxy '*' -X POST "$API/releases/$REL_ID/attach_files" \
    -F "access_token=$GITEE_TOKEN" \
    -F "file=@$f")"
  if printf '%s' "$resp" | grep -q '"browser_download_url"'; then
    echo "   已上传"
  else
    echo "   上传失败：$(printf '%s' "$resp" | head -c 300)" >&2
    exit 1
  fi
done

cat <<EOF

完成。发行版地址：
  https://gitee.com/$OWNER/$REPO/releases/tag/$TAG

客户机「检查更新」会读取：
  https://gitee.com/api/v5/repos/$OWNER/$REPO/releases/latest
EOF
