#!/usr/bin/env bash
#
# 打一份可以直接交给客户的包。
#
#   tools/make-dist.sh                 用现有的 target/device-hub.jar 打包
#   tools/make-dist.sh --build         先 mvn package 再打包
#   tools/make-dist.sh --version 1.1.0 手工指定版本（一般不用，版本从 jar 里读）
#
# 包里只有五样东西：jar、install.sh、bin/ 两个升级脚本、README。
# 打完立刻跑 audit-dist.sh，审计不过就删掉 zip —— 宁可这次不发，也不要发一个
# 带着私钥或演示数据的包出去。
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
BASE="$ROOT/dist"

VERSION=""
DO_BUILD=0
while [ $# -gt 0 ]; do
  case "$1" in
    --version) VERSION="${2:-}"; shift ;;
    --build)   DO_BUILD=1 ;;
    -h|--help) sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "未知参数：$1"; exit 1 ;;
  esac
  shift
done

say() { printf '\n== %s\n' "$*"; }
ok()  { printf '   %s\n' "$*"; }
die() { printf '失败：%s\n' "$*" >&2; exit 1; }

JAR="$ROOT/target/device-hub.jar"

# ---------- 1. 构建 ----------
if [ "$DO_BUILD" -eq 1 ]; then
  MVN_BIN="${MVN:-}"
  if [ -z "$MVN_BIN" ]; then
    if command -v mvn >/dev/null 2>&1; then
      MVN_BIN="mvn"
    elif [ -x "$ROOT/../tools/apache-maven-3.9.9/bin/mvn" ]; then
      MVN_BIN="$ROOT/../tools/apache-maven-3.9.9/bin/mvn"
    else
      die "找不到 mvn。用 MVN=/path/to/mvn 指定，或者先自己 mvn package"
    fi
  fi
  [ -n "${JAVA_HOME:-}" ] || command -v java >/dev/null 2>&1 \
    || die "需要 JAVA_HOME 或 PATH 里有 java"
  # ---------- 前端构建（Vue 3 + Vite，ADR-002）：frontend/ 存在则必须成功 ----------
  if [ -d "$ROOT/frontend" ]; then
    command -v npm >/dev/null 2>&1 \
      || die "frontend/ 存在但本机没有 npm。装 Node 20+，或临时移走 frontend/ 目录"
    say "构建前端（frontend/ → static/app/）"
    NPM_REG="${NPM_REGISTRY:-https://registry.npmmirror.com}"
    ( cd "$ROOT/frontend" \
        && { npm ci --registry="$NPM_REG" --no-fund --no-audit >/dev/null 2>&1 \
             || npm install --registry="$NPM_REG" --no-fund --no-audit; } \
        && npm run build ) || die "前端构建失败——jar 会缺新版界面，已中止打包"
    ok "前端构建完成"
  fi

  say "构建 jar（$MVN_BIN）"
  ( cd "$ROOT" && "$MVN_BIN" -q -DskipTests package ) || die "构建失败，看上面的报错"
  ok "构建完成"
fi

[ -f "$JAR" ] || die "没有 $JAR，先跑 tools/make-dist.sh --build"

# ---------- 2. 版本号 ----------
# 从 jar 里的 pom.properties 读，和运行时显示的版本是同一个来源。
# 从 pom.xml 里 sed 会先抓到 <parent> 的版本，闹过笑话。
if [ -z "$VERSION" ]; then
  VERSION="$(python - "$JAR" <<'PY'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1]) as z:
    for n in z.namelist():
        if n.endswith("pom.properties") and "device-hub" in n:
            for line in z.read(n).decode("utf-8", "ignore").splitlines():
                if line.startswith("version="):
                    print(line.split("=", 1)[1].strip())
                    break
            break
PY
)"
fi
[ -n "$VERSION" ] || die "读不到版本号，用 --version 手工指定"

STAGE="$BASE/device-hub-$VERSION"
ZIP="$BASE/device-hub-$VERSION.zip"

say "打包 device-hub $VERSION"

# ---------- 3. 摆文件 ----------
rm -rf "$STAGE"
mkdir -p "$STAGE/bin"

[ -f "$ROOT/install.sh" ] || die "缺 install.sh"
[ -f "$ROOT/bin/apply-pending.sh" ] || die "缺 bin/apply-pending.sh"
[ -f "$ROOT/bin/upgrade.sh" ] || die "缺 bin/upgrade.sh"
[ -f "$ROOT/README.md" ] || die "缺 README.md"

cp "$JAR" "$STAGE/device-hub.jar"
cp "$ROOT/install.sh" "$STAGE/install.sh"
cp "$ROOT/bin/apply-pending.sh" "$STAGE/bin/apply-pending.sh"
cp "$ROOT/bin/upgrade.sh" "$STAGE/bin/upgrade.sh"
cp "$ROOT/README.md" "$STAGE/README.md"

chmod 755 "$STAGE/install.sh" "$STAGE/bin/apply-pending.sh" "$STAGE/bin/upgrade.sh"
chmod 644 "$STAGE/device-hub.jar" "$STAGE/README.md"

JAR_SHA="$(sha256sum "$STAGE/device-hub.jar" | awk '{print $1}')"
cat > "$STAGE/VERSION.txt" <<EOF
device-hub $VERSION
构建时间：$(date '+%Y-%m-%d %H:%M:%S')
jar sha256：$JAR_SHA

安装：
  sudo ./install.sh

安装前请先读 README.md 第十节（部署与升级）。
EOF
chmod 644 "$STAGE/VERSION.txt"

# 逐个文件的校验和，客户可以自己核对（防传输损坏，也防中间被替换）
( cd "$STAGE" && find . -type f ! -name SHA256SUMS -exec sha256sum {} \; | sort -k2 ) > "$STAGE/SHA256SUMS"
chmod 644 "$STAGE/SHA256SUMS"

ok "文件已就位：$(ls -1 "$STAGE" | tr '\n' ' ')"

# ---------- 4. 强制审计 ----------
say "内容审计"
if ! bash "$HERE/audit-dist.sh" "$STAGE"; then
  echo
  die "审计不通过，不产出 zip。目录留在 $STAGE 供你核对"
fi

# ---------- 5. 压缩 ----------
say "生成压缩包"
rm -f "$ZIP"
python - "$STAGE" "$ZIP" <<'PY' || die "压缩失败"
import os, shutil, sys
src, out = sys.argv[1], sys.argv[2]
shutil.make_archive(out[:-4], "zip", root_dir=os.path.dirname(src),
                    base_dir=os.path.basename(src))
PY

ZIP_SHA="$(sha256sum "$ZIP" | awk '{print $1}')"
ZIP_SIZE=$(( $(wc -c < "$ZIP") / 1024 / 1024 ))
ok "已生成 dist/$(basename "$ZIP")（${ZIP_SIZE} MB）"
ok "sha256：$ZIP_SHA"

# ---------- 5.5 更新源清单 version.json ----------
# install.sh --online 靠它发现新版本。url 指向 GitHub Release 的资产：
# 打 Release 时 tag 用 v$VERSION，资产名保持 device-hub-$VERSION.zip，url 即可对上。
RELEASE_URL="https://github.com/ZhuangRenyang/KnowFab-SIRP/releases/download/v${VERSION}/$(basename "$ZIP")"
cat > "$STAGE/version.json" <<JSON
{
  "version": "$VERSION",
  "url": "$RELEASE_URL",
  "sha256": "$ZIP_SHA"
}
JSON
# 同步一份到 dist/ 根：这才是 --online 安装/检查更新读取的更新源清单（进 git）
cp "$STAGE/version.json" "$BASE/version.json"
ok "已生成 dist/version.json（version=$VERSION）"
warn "记得把 zip 上传到 GitHub Release（tag: v$VERSION），version.json 的下载链接才会生效"

cat <<EOF

发给客户的就这一个文件：
  $(basename "$ZIP")
  sha256: $ZIP_SHA

客户侧：
  unzip $(basename "$ZIP")
  cd device-hub-$VERSION
  sha256sum -c SHA256SUMS        # 核对完整性
  sudo ./install.sh

提醒：这个包不带 application.yml，配置由 install.sh 按现场生成（含随机管理员密码）。
EOF
