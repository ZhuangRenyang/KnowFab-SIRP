#!/usr/bin/env bash
# =====================================================================
# 数据库备份（每日跑一次，保留 N 天）
#
# 用法：
#   ./bin/backup.sh [保留天数]        # 默认 14 天，输出到 $DIR/backups/
#
# 凭据来源（按顺序取第一个非空的）：
#   1. 环境变量 DB_USER / DB_PASS / DB_NAME / DB_HOST / DB_PORT
#   2. 自动解析 $DIR/application.yml（install.sh 安装时写好的那份）
#
# 建议加进 crontab（每天 02:30）：
#   30 2 * * * /opt/device-hub/bin/backup.sh >> /opt/device-hub/logs/backup.log 2>&1
#
# 恢复（先停服务或确认业务低峰，谨慎操作）：
#   gunzip < backups/xxx.sql.gz | mysql -u<user> -p <库名>
# =====================================================================
set -euo pipefail

DIR="$(cd "$(dirname "$0")/.." && pwd)"
KEEP="${1:-14}"
OUT_DIR="$DIR/backups"
mkdir -p "$OUT_DIR"

YML="$DIR/application.yml"
read_yml() {  # read_yml <key>  —— 从 application.yml 里抓 spring.datasource 下的值
  sed -n 's/^[[:space:]]*'"$1"':[[:space:]]*\(.*\)$/\1/p' "$YML" 2>/dev/null | head -1 | sed "s/^['\"]//;s/['\"]$//"
}

DB_HOST="${DB_HOST:-$(read_yml url | sed -n 's|jdbc:[a-z]*://\([^:/]*\).*|\1|p')}"
DB_PORT="${DB_PORT:-$(read_yml url | sed -n 's|jdbc:[a-z]*://[^:/]*:\([0-9]*\).*|\1|p')}"
DB_NAME="${DB_NAME:-$(read_yml url | sed -n 's|jdbc:[a-z]*://[^/]*/\([a-zA-Z0-9_-]*\).*|\1|p')}"
DB_USER="${DB_USER:-$(read_yml username)}"
DB_PASS="${DB_PASS:-$(read_yml password)}"
ENGINE="$(read_yml url | sed -n 's|jdbc:/([a-z]*/):.*|/1|p')"

[ -n "$DB_NAME" ] || { echo "读不到数据库名，请检查 $YML 或设置 DB_NAME 环境变量" >&2; exit 1; }
[ -n "$DB_PASS" ] || { echo "读不到数据库密码，请设置 DB_PASS 环境变量" >&2; exit 1; }
DB_HOST="${DB_HOST:-127.0.0.1}"; DB_PORT="${DB_PORT:-3306}"
DB_USER="${DB_USER:-devicehub}"

STAMP="$(date +%Y%m%d-%H%M%S)"
FILE="$OUT_DIR/${DB_NAME}-${STAMP}.sql.gz"

case "$ENGINE" in
  mysql)
    MYSQLDUMP="$(command -v mysqldump || echo /usr/bin/mysqldump)"
    MYSQL="$MYSQLDUMP"; ;;
  postgresql|pgsql)
    MYSQL="$(command -v pg_dump || echo /usr/bin/pg_dump)"; ;;
  *)
    echo "不认识的数据库引擎：$ENGINE" >&2; exit 1; ;;
esac

echo "[$(date '+%F %T')] 备份 $DB_NAME@$DB_HOST:$DB_PORT → $FILE"
if [ "$ENGINE" = "mysql" ]; then
  "$MYSQL" -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USER" -p"$DB_PASS" \
    --single-transaction --routines --triggers --set-gtid-purged=OFF \
    --default-character-set=utf8mb4 "$DB_NAME" | gzip > "$FILE"
else
  PGPASSWORD="$DB_PASS" "$MYSQL" -h"$DB_HOST" -p"$DB_PORT" -U"$DB_USER" -d"$DB_NAME" | gzip > "$FILE"
fi

SIZE="$(du -h "$FILE" | cut -f1)"
echo "[$(date '+%F %T')] 完成：$SIZE"

# 清理过期备份
find "$OUT_DIR" -name "${DB_NAME}-*.sql.gz" -mtime +"$KEEP" -print -delete |
  while read -r f; do echo "已删除过期备份：$f"; done
