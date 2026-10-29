#!/usr/bin/env bash
#
# 打便携版 zip：jar + 启动/停止脚本 + 使用说明
#
#   bash tools/make-portable.sh                用 target/device-hub.jar，版本从 jar 读
#   bash tools/make-portable.sh --version 1.0.1
#
# 产物：dist/device-hub-<版本>-portable.zip（约 65MB，不含 JRE/数据库，
#       首次启动自动从国内镜像下载到 runtime/，一台机器只需下一次）
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
cd "$ROOT"

VERSION=""
while [ $# -gt 0 ]; do
  case "$1" in
    --version) VERSION="${2:-}"; shift ;;
    -h|--help) sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "未知参数：$1" >&2; exit 1 ;;
  esac
  shift
done

JAR="target/device-hub.jar"
[ -f "$JAR" ] || { echo "没有 $JAR，先 mvn package" >&2; exit 1; }

if [ -z "$VERSION" ]; then
  VERSION="$(unzip -p "$JAR" META-INF/maven/com.factory/device-hub/pom.properties 2>/dev/null \
    | sed -n 's/^version=//p' | tr -d '\r' | head -1)"
fi
[ -n "$VERSION" ] || { echo "读不到版本号，用 --version 指定" >&2; exit 1; }

STAGE="dist/device-hub-$VERSION-portable"
ZIP="dist/device-hub-$VERSION-portable.zip"

rm -rf "$STAGE"
mkdir -p "$STAGE"
cp "$JAR" "$STAGE/device-hub.jar"
cp tools/portable/start.ps1 tools/portable/start.bat tools/portable/stop.bat \
   tools/portable/start.sh tools/portable/stop.sh \
   tools/portable/README-portable.txt "$STAGE/"
chmod +x "$STAGE/start.sh" "$STAGE/stop.sh" 2>/dev/null || true
mv "$STAGE/README-portable.txt" "$STAGE/使用说明.txt"

rm -f "$ZIP"
if command -v zip >/dev/null 2>&1; then
  (cd dist && zip -qr "$(basename "$ZIP")" "$(basename "$STAGE")")
else
  # Windows Git Bash 常常没有 zip，用 Python 标准库兜底
  PY="$(command -v python3 || command -v python)"
  [ -n "$PY" ] || { echo "没有 zip 也没有 python，打不了包" >&2; exit 1; }
  "$PY" - "$ZIP" "$STAGE" <<'PY'
import os, shutil, sys
out, stage = sys.argv[1], sys.argv[2]
base = os.path.basename(out)[:-4]
shutil.make_archive(os.path.join(os.path.dirname(out), base), "zip",
                    os.path.dirname(stage), os.path.basename(stage))
PY
fi

cat <<EOF

便携版已生成：
  $ZIP
  $(du -h "$ZIP" | cut -f1)

用法：
  Windows：解压 → 双击 start.bat
  Linux  ：解压 → ./start.sh

首次运行会下载 JRE(45MB) + MariaDB(90MB) 到 runtime/，之后秒起。
EOF
