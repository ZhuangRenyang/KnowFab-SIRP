#!/usr/bin/env bash
#
# 发布 GitHub Release（不依赖 gh CLI）。
#
#   bash tools/github-release.sh 1.0.2 "更新说明" \
#        dist/device-hub-1.0.2-update.zip dist/device-hub-1.0.2-installer.zip
#
# 令牌来源（按顺序）：
#   1) 环境变量 GH_TOKEN / GITHUB_TOKEN
#   2) git 已保存的 github.com 凭据（`git credential fill`）——本机 push 过就有
#
# 为什么不用 gh：不少现场机器没装 gh；而 git 凭据通常已经在了，直接走 REST API 更省事。
# 注意：本机若走公司代理，curl(schannel) 需要 --ssl-no-revoke 才能过吊销检查，
# 脚本会自动探测该开关是否存在（Linux 的 curl 没有此参数，会跳过）。
set -euo pipefail

OWNER="${GH_OWNER:-ZhuangRenyang}"
REPO="${GH_REPO:-KnowFab-SIRP}"
API="https://api.github.com/repos/$OWNER/$REPO"

VERSION="${1:-}"; NOTES="${2:-}"
shift 2 2>/dev/null || true
FILES=("$@")

[ -n "$VERSION" ] || { echo "用法：bash tools/github-release.sh <版本> <说明> <文件...>" >&2; exit 1; }
[ "${#FILES[@]}" -gt 0 ] || { echo "至少给一个文件" >&2; exit 1; }
TAG="v$VERSION"

# ---- 令牌 ----
TOKEN="${GH_TOKEN:-${GITHUB_TOKEN:-}}"
if [ -z "$TOKEN" ]; then
  TOKEN="$(printf 'protocol=https\nhost=github.com\n\n' | git credential fill 2>/dev/null | sed -n 's/^password=//p')"
fi
[ -n "$TOKEN" ] || { echo "没有 GitHub 令牌：设 GH_TOKEN，或先 git push 一次让凭据进钥匙串" >&2; exit 1; }

# ---- curl 兼容（schannel 需要跳过吊销检查） ----
CURL=(curl -sS)
if curl --help all 2>/dev/null | grep -q -- '--ssl-no-revoke'; then
  CURL+=(--ssl-no-revoke)
fi

say() { printf '\n== %s\n' "$*"; }
api() { "${CURL[@]}" -H "Authorization: Bearer $TOKEN" -H "Accept: application/vnd.github+json" "$@"; }

say "查找 GitHub 上是否已有 $TAG"
REL_ID="$(api "$API/releases/tags/$TAG" | python -c "import json,sys
try: print(json.load(sys.stdin).get('id') or '')
except Exception: print('')")"

if [ -z "$REL_ID" ]; then
  say "创建 Release $TAG"
  body="$(python -c "import json,sys; print(json.dumps({'tag_name':'$TAG','name':'$TAG','body':sys.argv[1],'target_commitish':'main','draft':False,'prerelease':False}, ensure_ascii=False))" "$NOTES")"
  REL_ID="$(api -X POST "$API/releases" -d "$body" | python -c "import json,sys; print(json.load(sys.stdin).get('id') or '')")"
  [ -n "$REL_ID" ] || { echo "创建失败（检查令牌是否具备 contents:write）" >&2; exit 1; }
  echo "   release id=$REL_ID"
else
  echo "   已存在，复用 id=$REL_ID"
fi

# 已有同名附件先删（GitHub 不允许同名覆盖）
existing="$(api "$API/releases/$REL_ID/assets" | python -c "import json,sys
try:
    print(' '.join(f\"{a['id']}:{a['name']}\" for a in json.load(sys.stdin)))
except Exception: print('')")"
for pair in $existing; do
  aid="${pair%%:*}"; aname="${pair#*:}"
  for f in "${FILES[@]}"; do
    if [ "$(basename "$f")" = "$aname" ]; then
      say "删除旧附件 $aname"
      api -X DELETE "$API/releases/assets/$aid" >/dev/null
    fi
  done
done

for f in "${FILES[@]}"; do
  [ -f "$f" ] || { echo "文件不存在：$f" >&2; exit 1; }
  say "上传 $(basename "$f")（$(du -h "$f" | cut -f1)）"
  code="$("${CURL[@]}" -o /tmp/gh-asset.json -w '%{http_code}' -X POST \
    -H "Authorization: Bearer $TOKEN" -H "Accept: application/vnd.github+json" \
    -H "Content-Type: application/zip" \
    --data-binary "@$f" \
    "https://uploads.github.com/repos/$OWNER/$REPO/releases/$REL_ID/assets?name=$(basename "$f")")"
  if [ "$code" = "201" ]; then
    echo "   已上传"
  else
    echo "   上传失败 HTTP $code：$(head -c 200 /tmp/gh-asset.json)" >&2
    exit 1
  fi
done

echo
echo "完成：https://github.com/$OWNER/$REPO/releases/tag/$TAG"
