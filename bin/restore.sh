#!/usr/bin/env bash
# =====================================================================
# 数据库恢复（从 backup.sh 产出的 .sql.gz 恢复）
#
# 用法：
#   ./bin/restore.sh <备份文件.sql.gz> [目标库名]
#
# 例：
#   ./bin/restore.sh backups/devicehub-20261104-023000.sql.gz devicehub_drill
#       → 恢复到一个新库做演练（目标库为空，直接干）
#   ./bin/restore.sh backups/xxx.sql.gz devicehub_drill --force
#       → 目标库已有数据，允许覆盖（会先把现状备份一份）
#
# 为什么要有这个脚本（而不是 README 里那句 gunzip | mysql）：
#   1. 那句命令没有闸门——手滑把生产库名敲进去，几秒内现有数据就没了
#   2. 那句命令不验证——恢复"成功"（退出码 0）不等于数据完整，
#      表少了一半也能返回 0
#   3. 密码放命令行 = 写在墙上（ps 能看到）
#
# 安全闸门分三档（目标库越"贵"，要求越严）：
#   · 目标库不存在 / 是个空库        → 直接恢复
#   · 目标库已有数据                → 必须 --force，且**先自动备份现状**
#   · 目标库就是生产库（application.yml 里那个）→ 必须 --force，还要输入库名确认
#
# 凭据来源与 backup.sh 一致（环境变量优先，其次 application.yml）。
# =====================================================================
set -euo pipefail

DIR="$(cd "$(dirname "$0")/.." && pwd)"
YML="$DIR/application.yml"

read_yml() {  # read_yml <key> —— 与 backup.sh 同一套解析，改一处要改两处
  sed -n 's/^[[:space:]]*'"$1"':[[:space:]]*\(.*\)$/\1/p' "$YML" 2>/dev/null | head -1 | sed "s/^['\"]//;s/['\"]$//"
}

DB_HOST="${DB_HOST:-$(read_yml url | sed -n 's|jdbc:[a-z]*://\([^:/]*\).*|\1|p')}"
DB_PORT="${DB_PORT:-$(read_yml url | sed -n 's|jdbc:[a-z]*://[^:/]*:\([0-9]*\).*|\1|p')}"
DB_NAME="${DB_NAME:-$(read_yml url | sed -n 's|jdbc:[a-z]*://[^/]*/\([a-zA-Z0-9_-]*\).*|\1|p')}"
DB_USER="${DB_USER:-$(read_yml username)}"
DB_PASS="${DB_PASS:-$(read_yml password)}"
DB_HOST="${DB_HOST:-127.0.0.1}"; DB_PORT="${DB_PORT:-3306}"
DB_USER="${DB_USER:-devicehub}"

usage() {
  cat <<'EOF'
用法：./bin/restore.sh <备份文件.sql.gz> [目标库名] [选项]

选项：
  --force     目标库已有数据时也恢复（会先自动备份现状到 backups/）
  --yes, -y   生产库确认环节免交互（脚本里调用时用，人工别用）
  --no-verify 跳过恢复后的表数量核对（不建议）
  -h, --help  显示本帮助

不填目标库名 = 恢复到 application.yml 里配置的那个库（生产库，有二次确认）。
建议先恢复到新库（如 devicehub_drill）核对无误，再决定动不动生产库。
EOF
}

FILE=""; TARGET=""; FORCE=0; YES=0; VERIFY=1
while [ $# -gt 0 ]; do
  case "$1" in
    --force) FORCE=1; shift ;;
    --yes|-y) YES=1; shift ;;
    --no-verify) VERIFY=0; shift ;;
    -h|--help) usage; exit 0 ;;
    -*) echo "未知选项：$1" >&2; usage >&2; exit 2 ;;
    *)
      if [ -z "$FILE" ]; then FILE="$1"
      elif [ -z "$TARGET" ]; then TARGET="$1"
      else echo "参数过多：$1" >&2; exit 2; fi
      shift ;;
  esac
done

[ -n "$FILE" ] || { usage >&2; exit 2; }
[ -f "$FILE" ] || { echo "备份文件不存在：$FILE" >&2; exit 1; }
[ -n "$DB_PASS" ] || { echo "读不到数据库密码，请检查 $YML 或设置 DB_PASS 环境变量" >&2; exit 1; }

PROD_DB="$DB_NAME"
TARGET="${TARGET:-$PROD_DB}"

# 只允许常规库名，挡住误敲的空格/分号/引号（后面要拼进 SQL 和命令行）
case "$TARGET" in
  *[!A-Za-z0-9_-]*) echo "库名只能包含字母、数字、下划线和短横线：$TARGET" >&2; exit 2 ;;
esac

# 引擎与客户端：MariaDB 10.5 起客户端叫 mariadb（官方 mariadb:11 镜像里
# 根本没有 mysql 这个命令），只找 mysql 会 "command not found"。
ENGINE="$(printf '%s' "$(read_yml url)" | sed -n 's|^jdbc:||p' | cut -d: -f1)"
pick() {
  for c in "$@"; do
    p="$(command -v "$c" 2>/dev/null)" && { printf '%s' "$p"; return 0; }
  done
  return 1
}

# 两个名字都要找：URL 写 jdbc:mysql:// 也可能连的是 MariaDB，
# 而 MariaDB 10.5+ 的客户端叫 mariadb（官方镜像里没有 mysql 命令）
case "${ENGINE:-mysql}" in
  mysql)   MYSQL_CLIENT="$(pick mysql mariadb || echo /usr/bin/mysql)" ;;
  mariadb) MYSQL_CLIENT="$(pick mariadb mysql || echo /usr/bin/mariadb)" ;;
  *)       MYSQL_CLIENT="$(pick mysql mariadb || echo /usr/bin/mysql)" ;;
esac
run_sql() {  # run_sql <库名|""> <SQL>
  if [ -n "$1" ]; then
    MYSQL_PWD="$DB_PASS" "$MYSQL_CLIENT" -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USER" \
      --default-character-set=utf8mb4 -N -B -e "$2" "$1"
  else
    MYSQL_PWD="$DB_PASS" "$MYSQL_CLIENT" -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USER" \
      --default-character-set=utf8mb4 -N -B -e "$2"
  fi
}

echo "═══════════════════════════════════════════════════════"
echo " 恢复备份"
echo "   文件   : $FILE"
echo "   目标库 : $ENGINE:$TARGET@$DB_HOST:$DB_PORT"
[ "$TARGET" = "$PROD_DB" ] && echo "   ⚠ 这就是 application.yml 里配置的生产库"
echo "═══════════════════════════════════════════════════════"

# ---------- ① 校验备份文件 ----------
echo "[1/5] 校验压缩包完整性（gzip -t）…"
if ! gzip -t "$FILE" 2>/dev/null; then
  echo "  ✗ 压缩包损坏或不是 gzip 格式：$FILE" >&2
  echo "    这种文件恢复出来会是半截数据，不要继续。" >&2
  exit 1
fi
SIZE="$(du -h "$FILE" | cut -f1)"
echo "  ✓ 完整（$SIZE）"

# ---------- ② 判断目标库现状 ----------
echo "[2/5] 检查目标库现状…"
DB_EXISTS="$(run_sql "" "select count(*) from information_schema.schemata where schema_name='$TARGET'")"
TABLE_CNT=0
if [ "$DB_EXISTS" != "0" ]; then
  TABLE_CNT="$(run_sql "" "select count(*) from information_schema.tables where table_schema='$TARGET'")"
fi
echo "  目标库存在：$([ "$DB_EXISTS" = "0" ] && echo 否 || echo 是)，现有表 $TABLE_CNT 个"

# ---------- ③ 安全闸门 ----------
if [ "$TABLE_CNT" != "0" ]; then
  if [ "$FORCE" != "1" ]; then
    echo "" >&2
    echo "  ✗ 目标库「$TARGET」里已经有 $TABLE_CNT 张表，直接恢复会把它们覆盖掉。" >&2
    echo "    如果是演练，建议换一个空库名（会自动创建）：" >&2
    echo "        $0 $FILE ${TARGET}_drill" >&2
    echo "    确实要覆盖这个库，加 --force" >&2
    exit 1
  fi

  # 生产库额外二次确认：让人把库名敲一遍，而不是顺手回车
  if [ "$TARGET" = "$PROD_DB" ] && [ "$YES" != "1" ]; then
    echo ""
    echo "  ⚠ 你要覆盖的是**生产库** $TARGET（$TABLE_CNT 张表）。"
    echo "    服务在跑的话请先停掉；恢复期间写进来的数据会丢。"
    printf "    确认请输入库名「%s」：" "$TARGET"
    read -r ANSWER
    if [ "$ANSWER" != "$TARGET" ]; then
      echo "  ✗ 输入不匹配，已取消。" >&2
      exit 1
    fi
  fi

  # 覆盖前先把现状备份一份：恢复本身也可能出错，留个后悔药
  BAK_DIR="$DIR/backups"
  mkdir -p "$BAK_DIR"
  BEFORE="$BAK_DIR/${TARGET}-before-restore-$(date +%Y%m%d-%H%M%S).sql.gz"
  echo "  覆盖前先备份现状 → $BEFORE"
  MYSQL_PWD="$DB_PASS" "$(command -v mysqldump || echo /usr/bin/mysqldump)" \
    -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USER" --single-transaction --routines --triggers \
    --set-gtid-purged=OFF --default-character-set=utf8mb4 "$TARGET" | gzip > "$BEFORE"
  echo "  ✓ 现状已备份（$(du -h "$BEFORE" | cut -f1)）"
fi

# ---------- ④ 建库 + 灌入 ----------
if [ "$DB_EXISTS" = "0" ]; then
  echo "[3/5] 创建目标库 $TARGET…"
  run_sql "" "create database \`$TARGET\` default charset utf8mb4 collate utf8mb4_general_ci"
else
  echo "[3/5] 目标库已存在，直接灌入…"
fi

echo "[4/5] 解压并导入（大库要几分钟，别中断）…"
# 密码走 MYSQL_PWD：不放命令行，ps 看不到
gzip -dc "$FILE" | MYSQL_PWD="$DB_PASS" "$MYSQL_CLIENT" \
  -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USER" --default-character-set=utf8mb4 "$TARGET"
echo "  ✓ 导入完成"

# ---------- ⑤ 核对 ----------
if [ "$VERIFY" = "1" ]; then
  echo "[5/5] 核对表数量…"
  # 备份文件里有多少个 CREATE TABLE，恢复后库里就该有多少张表。
  # 少了就是导入中途断了 —— 而 mysql 客户端在中途断掉时**退出码不一定是非 0**，
  # 光看"导入完成"这句话会骗人。
  WANT="$(gzip -dc "$FILE" | grep -c '^CREATE TABLE' || true)"
  GOT="$(run_sql "" "select count(*) from information_schema.tables where table_schema='$TARGET'")"
  echo "  备份里 $WANT 张表，恢复后 $GOT 张"
  if [ "$WANT" != "$GOT" ]; then
    echo "  ✗ 表数量对不上——导入可能中途失败，别拿这个库当恢复成功。" >&2
    exit 1
  fi
  echo "  ✓ 表数量一致"
else
  echo "[5/5] 已跳过核对（--no-verify）"
fi

echo ""
echo "═══════════════════════════════════════════════════════"
echo " 恢复完成：$TARGET"
echo ""
echo " 下一步："
if [ "$TARGET" = "$PROD_DB" ]; then
  echo "   1) 启动服务： systemctl start device-hub"
  echo "   2) 打开页面确认设备数/单据数/报表与备份时点一致"
else
  echo "   这是演练库，不影响生产。核对无误后，要覆盖生产库需："
  echo "     $0 $FILE $PROD_DB --force"
  echo "   演练库用完可以删：drop database \`$TARGET\`;"
fi
echo "═══════════════════════════════════════════════════════"
