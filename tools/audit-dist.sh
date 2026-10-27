#!/usr/bin/env bash
#
# 交付包内容审计。可以在打包前跑，也可以对着一份已经打好的 zip 跑。
#
#   tools/audit-dist.sh dist/device-hub-1.0.0
#   tools/audit-dist.sh dist/device-hub-1.0.0.zip
#
# 为什么要有这个脚本：交付包里混进一个 private.key，就是把这个软件的签发权
# 一起送给了客户；混进 data/ 就是带着自己电脑上的演示数据去见客户；
# 混进 *.sh 的 CRLF 换行，客户机器上第一行就报 "$'\r': command not found"。
# 这些都靠人眼看，迟早会漏一次。所以做成脚本，打包流程里强制跑。
set -uo pipefail

TARGET="${1:-}"
if [ -z "$TARGET" ]; then
  echo "用法：$0 <交付包目录|交付包.zip>"
  exit 1
fi

TMP=""
# 用 python 删临时目录：有些环境（比如 Windows 上的 git-bash）对 rm -rf 有安全包装，
# 会把 mktemp 出来的路径判成"非法"，于是每次审计都留一个 70MB 的解压目录。
cleanup() {
  [ -n "$TMP" ] && [ -d "$TMP" ] || return 0
  python -c "import shutil,sys; shutil.rmtree(sys.argv[1], ignore_errors=True)" "$TMP" 2>/dev/null || true
}
trap cleanup EXIT

# zip 就先解开再查，规则只写一份
case "$TARGET" in
  *.zip)
    TMP="$(mktemp -d)"
    python - "$TARGET" "$TMP" <<'PY' || { echo "解压失败：$TARGET"; exit 2; }
import sys, zipfile
zipfile.ZipFile(sys.argv[1]).extractall(sys.argv[2])
PY
    ROOT="$(find "$TMP" -mindepth 1 -maxdepth 1 -type d | head -1)"
    [ -n "$ROOT" ] || ROOT="$TMP"
    ;;
  *)
    ROOT="$TARGET"
    ;;
esac

[ -d "$ROOT" ] || { echo "目录不存在：$ROOT"; exit 2; }

FAIL=0
WARN=0
pass() { printf '  [通过] %s\n' "$1"; }
fail() { printf '  [阻断] %s\n' "$1"; FAIL=$((FAIL + 1)); }
warn() { printf '  [提醒] %s\n' "$1"; WARN=$((WARN + 1)); }

echo "审计目标：$ROOT"
echo

# ---------- 1. 必需文件 ----------
echo "一、必需文件"
for f in device-hub.jar install.sh bin/apply-pending.sh bin/upgrade.sh README.md; do
  if [ -f "$ROOT/$f" ]; then
    pass "$f"
  else
    fail "缺 $f（客户机器上装不起来或升不了级）"
  fi
done

# ---------- 2. 绝对不能出现的 ----------
echo
echo "二、禁止出现的文件"
BLOCK_START=$FAIL
# 私钥：泄露等于谁都能自己签授权
while IFS= read -r p; do
  [ -n "$p" ] && fail "发现私钥：${p#$ROOT/}（泄露=任何人可自签授权）"
done <<EOF
$(find "$ROOT" -type f \( -iname 'private.key' -o -iname '*private*key*' -o -iname '*.p12' -o -iname '*.jks' -o -iname '*.pfx' \) 2>/dev/null)
EOF

# 签发工具与源码
while IFS= read -r p; do
  [ -n "$p" ] && fail "发现源码/工具：${p#$ROOT/}（LicenseKeygen / PackUpdate 不能给客户）"
done <<EOF
$(find "$ROOT" -type f \( -name '*.java' -o -name '*.kt' -o -name 'LicenseKeygen*' -o -name 'PackUpdate*' \) 2>/dev/null)
EOF
[ -d "$ROOT/tools" ] && fail "发现 tools/ 目录（签发、打包工具都在这，绝不给客户）"

# 客户数据与授权
[ -d "$ROOT/data" ] && fail "发现 data/ 目录（可能带着你本机的演示或客户数据）"
[ -d "$ROOT/license" ] && fail "发现 license/ 目录（含授权文件或试用状态，必须由客户现场生成）"
[ -d "$ROOT/updates" ] && fail "发现 updates/ 目录（升级暂存，现场会自己生成）"
[ -d "$ROOT/backups" ] && fail "发现 backups/ 目录（含旧 jar）"

# 开发/测试脚本
while IFS= read -r p; do
  [ -n "$p" ] && fail "发现开发脚本：${p#$ROOT/}"
done <<EOF
$(find "$ROOT" -type f \( -name 'mock-*.js' -o -name '*.bat' -o -name '*.cmd' -o -name 'run*.js' -o -name '*.ps1' \) 2>/dev/null)
EOF

# 日志、数据库、构建产物
while IFS= read -r p; do
  [ -n "$p" ] && fail "发现日志/数据文件：${p#$ROOT/}"
done <<EOF
$(find "$ROOT" -type f \( -name '*.log' -o -name '*.mv.db' -o -name '*.trace.db' -o -name '*.sql' \) 2>/dev/null)
EOF
[ -d "$ROOT/src" ] && fail "发现 src/ 目录（源码不该进交付包）"
[ -d "$ROOT/target" ] && fail "发现 target/ 目录（构建产物，只要最终 jar）"

# 配置文件：只允许 install.sh 生成，避免两份模板打架（曾导致打印的初始密码与配置里不一致）
[ -f "$ROOT/application.yml" ] && fail "发现 application.yml（配置由 install.sh 生成，包里带一份会出现密码不一致）"

# 交付包自带的校验清单必须和文件对得上，否则客户核验时会被误导
if [ -f "$ROOT/SHA256SUMS" ]; then
  if ( cd "$ROOT" && sha256sum -c SHA256SUMS >/dev/null 2>&1 ); then
    pass "SHA256SUMS 与实际文件一致"
  else
    fail "SHA256SUMS 与实际文件对不上（打包后文件被改过，或清单没更新）"
  fi
else
  warn "没有 SHA256SUMS（客户无法自行核验完整性）"
fi
[ -f "$ROOT/VERSION.txt" ] && pass "含 VERSION.txt" || warn "没有 VERSION.txt（客户看不出装的是哪版）"

[ "$FAIL" -eq "$BLOCK_START" ] && pass "没有私钥/源码/开发脚本/客户数据/日志/配置模板"

# ---------- 3. 换行与权限 ----------
echo
echo "三、Linux 上能不能跑"
# CRLF 是交付事故里最常见的一个：Windows 上打包，Linux 上第一行就报错
while IFS= read -r p; do
  [ -z "$p" ] && continue
  # 用 tr 数 CR 字节，不要用 grep $'\r'：
  # MSYS/Windows 上那个写法会被外层 shell 吃掉转义，变成"每行都命中"，
  # 结果好包被判死刑、坏包倒可能漏过去。实测过一次，记忆深刻。
  CRS=$(tr -cd '\r' < "$p" | wc -c | tr -d ' ')
  if [ "${CRS:-0}" -gt 0 ]; then
    fail "${p#$ROOT/} 有 $CRS 个 CR 字节（CRLF 换行），Linux 上会报 command not found，用 dos2unix 转一下"
  fi
done <<EOF
$(find "$ROOT" -type f -name '*.sh' 2>/dev/null)
EOF

if [ -f "$ROOT/install.sh" ] && [ ! -x "$ROOT/install.sh" ]; then
  fail "install.sh 没有执行权限（chmod 755）"
fi
for f in "$ROOT"/bin/*.sh; do
  [ -e "$f" ] || continue
  [ -x "$f" ] || fail "${f#$ROOT/} 没有执行权限（chmod 755）"
done
[ "$FAIL" -eq 0 ] && pass "换行符与执行权限正常"

# ---------- 4. jar 里面 ----------
echo
echo "四、jar 内部"
JAR="$ROOT/device-hub.jar"
if [ -f "$JAR" ]; then
  SIZE=$(wc -c < "$JAR" | tr -d ' ')
  if [ "$SIZE" -lt 20000000 ]; then
    fail "jar 只有 $((SIZE / 1024 / 1024)) MB，可能是没 repackage 的瘦 jar（客户跑不起来）"
  else
    pass "jar 大小 $((SIZE / 1024 / 1024)) MB"
  fi

  JAR_LIST="$(python - "$JAR" <<'PY'
import sys, zipfile
try:
    with zipfile.ZipFile(sys.argv[1]) as z:
        print("\n".join(z.namelist()))
except Exception as e:
    print("ERROR " + str(e))
PY
)"
  if printf '%s' "$JAR_LIST" | grep -q '^ERROR'; then
    fail "jar 打不开，可能损坏"
  else
    printf '%s\n' "$JAR_LIST" | grep -q 'BOOT-INF/classes/static/index.html' \
      && pass "含前端页面" || fail "jar 里没有 static/index.html（页面会 404）"
    printf '%s\n' "$JAR_LIST" | grep -q 'META-INF/spring.factories' \
      && pass "含 spring.factories（数据库迁移靠它注册）" \
      || fail "jar 里没有 META-INF/spring.factories（升级时迁移不会执行）"
    if printf '%s\n' "$JAR_LIST" | grep -qiE 'private\.key|\.p12$|\.jks$'; then
      fail "jar 里打进密钥文件"
    fi
    if printf '%s\n' "$JAR_LIST" | grep -qE '\.java$'; then
      fail "jar 里打进 .java 源码"
    fi
    # MySQL 驱动必在（H2 已废弃 2026-10-27），PG 可选；H2 驱动出现反而是事故
    if printf '%s\n' "$JAR_LIST" | grep -qE 'BOOT-INF/lib/h2-'; then
      fail "jar 里还有 H2 驱动（已废弃，应从 pom 移除）"
    fi
    printf '%s\n' "$JAR_LIST" | grep -qE 'BOOT-INF/lib/mysql-connector' \
      && pass "含 MySQL 驱动" \
      || fail "jar 里没有 MySQL 驱动（默认库连不上）"
  fi
else
  warn "没有 jar，跳过内部检查"
fi

# ---------- 5. 体积 ----------
echo
echo "五、体积"
TOTAL=$(du -sk "$ROOT" | awk '{print $1}')
if [ "$TOTAL" -gt 300000 ]; then
  warn "解压后 $((TOTAL / 1024)) MB，偏大（多半是文档或多余文件）"
else
  pass "解压后 $((TOTAL / 1024)) MB"
fi

echo
if [ "$FAIL" -gt 0 ]; then
  echo "审计不通过：$FAIL 项阻断，$WARN 项提醒。这个包不能发给客户。"
  exit 1
fi
echo "审计通过：0 项阻断，$WARN 项提醒。"
exit 0
