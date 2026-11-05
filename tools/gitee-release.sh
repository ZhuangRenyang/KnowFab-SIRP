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

# ---------- 只传 update.zip（Gitee 附件配额只有 1 GB）----------
# 我们每个版本有 3 个包 × 80MB。全传到 Gitee，连发 4 版就 960MB → 撑爆配额 →
# 新版附件传不上去 → 国内客户（默认更新源就是 Gitee）根本升不了级。
# 所以默认**只传 update.zip**（升级够用）；installer/portable 让客户走 GitHub 或本地拷贝。
# 要全传：GITEE_ALL=1
if [ "${GITEE_ALL:-0}" != "1" ]; then
  ONLY=()
  for f in "${FILES[@]}"; do
    case "$(basename "$f")" in *-update.zip) ONLY+=("$f");; esac
  done
  if [ "${#ONLY[@]}" -gt 0 ]; then
    FILES=("${ONLY[@]}")
    echo "   （Gitee 默认只传 update.zip；三个包全传请加 GITEE_ALL=1）"
  fi
fi

# ---------- 发新版前清掉旧版本的附件（释放配额）----------
# 注意：Gitee 私人令牌**默认没有删除权限**（DELETE 会 401）→
# 删不掉就在下面明确提示，让用户去后台手动清。
python3 - "$API" "$GITEE_TOKEN" "$TAG" <<'PYEOF'
import json, os, sys, urllib.request, urllib.error
api, token, keep = sys.argv[1], sys.argv[2], sys.argv[3]
for k in ("http_proxy", "https_proxy", "HTTP_PROXY", "HTTPS_PROXY"):
    os.environ.pop(k, None)
op = urllib.request.build_opener(urllib.request.ProxyHandler({}))

def call(method, path):
    req = urllib.request.Request(api + path, method=method,
                                 headers={"User-Agent": "x", "access_token": token})
    try:
        with op.open(req, timeout=60) as r:
            raw = r.read()
            return r.status, (json.loads(raw) if raw else {})
    except urllib.error.HTTPError as e:
        return e.code, {}
    except Exception:
        return 0, {}

st, rels = call("GET", "/releases?per_page=20")
if st != 200 or not isinstance(rels, list):
    print("   （取不到发行版列表，跳过清理）")
    raise SystemExit(0)
removed = failed = 0
for rel in rels:
    if rel.get("tag_name") == keep:
        continue
    st2, files = call("GET", "/releases/%s/attach_files" % rel.get("id"))
    if st2 != 200 or not isinstance(files, list):
        continue
    for f in files:
        if not str(f.get("name", "")).startswith("device-hub-"):
            continue
        st3, _ = call("DELETE", "/releases/%s/attach_files/%s" % (rel.get("id"), f.get("id")))
        if st3 in (200, 204):
            print("   已清旧附件 %s/%s" % (rel.get("tag_name"), f.get("name")))
            removed += 1
        else:
            failed += 1
if removed:
    print("   共清理 %d 个旧附件" % removed)
elif failed:
    print("   ⚠ 旧附件清不掉（令牌无删除权限）——请到 Gitee 后台手动删旧版本附件")
PYEOF

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
    if printf '%s' "$resp" | grep -q '配额'; then
      echo "   → Gitee 附件配额是 1 GB，已经满了。" >&2
      echo "     请到 Gitee 仓库 → 发行版 → 把旧版本的附件删掉（保留最新一版即可）。" >&2
      echo "     私人令牌默认没有删除权限，脚本清不掉，得手动来。" >&2
    fi
    exit 1
  fi
done

cat <<EOF

完成。发行版地址：
  https://gitee.com/$OWNER/$REPO/releases/tag/$TAG

客户机「检查更新」会读取：
  https://gitee.com/api/v5/repos/$OWNER/$REPO/releases/latest
EOF
