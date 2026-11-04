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
# 恢复：**别手工 gunzip | mysql**，用同目录的 restore.sh（有安全闸门 + 恢复后核对）
#   ./bin/restore.sh backups/xxx.sql.gz devicehub_drill   # 先恢复到演练库核对
#   ./bin/restore.sh backups/xxx.sql.gz --force           # 确认无误再覆盖生产库
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
# 引擎识别：原来写的是 sed -n 's|jdbc:/([a-z]*/):.*|/1|p'
# （少一个斜杠、捕获组写成了 /1），实测对 jdbc:mysql://… 输出空串 → 落到「不认识的数据库引擎」
# 就 exit 1 —— 结果是这个备份脚本从来没跑成功过，生产库其实一直没有备份。
# 现在用不带分组的 POSIX 写法，Linux/macOS/BusyBox 的 sed 都能用。
ENGINE="$(printf '%s' "$(read_yml url)" | sed -n 's|^jdbc:||p' | cut -d: -f1)"

[ -n "$DB_NAME" ] || { echo "读不到数据库名，请检查 $YML 或设置 DB_NAME 环境变量" >&2; exit 1; }
[ -n "$DB_PASS" ] || { echo "读不到数据库密码，请设置 DB_PASS 环境变量" >&2; exit 1; }
DB_HOST="${DB_HOST:-127.0.0.1}"; DB_PORT="${DB_PORT:-3306}"
DB_USER="${DB_USER:-devicehub}"

STAMP="$(date +%Y%m%d-%H%M%S)"
FILE="$OUT_DIR/${DB_NAME}-${STAMP}.sql.gz"

# ---------- 选工具：MySQL 和 MariaDB 的命令名不一样 ----------
# MariaDB 10.5 起把工具改名为 mariadb-dump / mariadb，
# 官方 mariadb:11 镜像里**没有** mysqldump / mysql 这些名字。
#
# ⚠️ 关键：**URL 的协议名 ≠ 实际数据库**。
# 项目只带了 mysql-connector-j（能连 MariaDB），所以客户用 MariaDB 时
# JDBC URL 通常还是 jdbc:mysql:// —— 从 URL 判出来是 mysql，
# 但那台机器上可能只有 mariadb-dump。所以每个分支都**双向找**：
# 先找本引擎自己的名字，再找另一家的，最后才猜固定路径。
# 没装 MySQL/MariaDB 客户端时**明确说清怎么装**。
# 原来这里给个假路径兜底（/usr/bin/mysqldump），运行时才失败，
# 报错是「command not found」——现场根本不知道该装什么。
die_client() {
  echo "找不到 mysqldump / mysql 客户端（MySQL 客户端没装）" >&2
  echo "  装它：sudo apt-get install -y default-mysql-client   # Debian/Ubuntu" >&2
  echo "        或：sudo yum install -y mysql                    # CentOS/RHEL" >&2
  echo "  没有客户端就没法备份——服务端装了不代表命令行工具装了" >&2
  exit 1
}

pick() {  # pick <候选1> [候选2...] → 第一个存在的绝对路径
  for c in "$@"; do
    p="$(command -v "$c" 2>/dev/null)" && { printf '%s' "$p"; return 0; }
  done
  return 1
}

case "$ENGINE" in
  mysql)
    # 也有可能是「用 jdbc:mysql:// 连 MariaDB」的机器，所以要回退找 mariadb-*
    DUMP_BIN="$(pick mysqldump mariadb-dump || true)"
    [ -n "$DUMP_BIN" ] || die_client
    CLIENT_BIN="$(pick mysql mariadb || true)"
    [ -n "$CLIENT_BIN" ] || die_client
    ;;
  mariadb)
    DUMP_BIN="$(pick mariadb-dump mysqldump || true)"
    [ -n "$DUMP_BIN" ] || die_client
    CLIENT_BIN="$(pick mariadb mysql || true)"
    [ -n "$CLIENT_BIN" ] || die_client
    ;;
  postgresql|pgsql)
    DUMP_BIN="$(pick pg_dump || echo /usr/bin/pg_dump)"
    CLIENT_BIN="$(pick psql || echo /usr/bin/psql)"
    ;;
  *)
    echo "不认识的数据库引擎：$ENGINE（支持 mysql / mariadb / postgresql）" >&2
    exit 1 ;;
esac

# ---------- 选项探测 ----------
# 不同数据库/不同版本支持的选项不一样，**硬写会直接失败**：
# · MariaDB 的 mysqldump 不认 --set-gtid-purged（那是 MySQL 的 GTID 选项，
#   MariaDB 的 GTID 是另一套实现）→ 报 "unknown variable" 并**输出零字节文件**；
# · --no-tablespaces 是 MySQL 5.7.31+ 才有的，老版本/MariaDB 未必认。
# 所以探一下再决定加不加，不猜版本。
# 注意：**不要用 grep -q**。它一匹配上就提前退出，管道另一端的 mysqldump 收到
# SIGPIPE 而非零退出 —— 在 `set -o pipefail` 下整个管道状态变成非 0，
# 于是 supports 永远返回「不支持」，所有选项都被悄悄丢掉。
# 这个 bug 只在 Linux 上出现（mysqldump --help 输出长，够 grep 提前退出），
# Windows 上输出短反而测不出来 —— 而客户机全是 Linux。
# 改成不用 -q，让 grep 读完整个输入，返回码就只反映匹配与否。
supports() { "$DUMP_BIN" --help 2>&1 | grep -F -- "$1" >/dev/null; }

OPTS="--single-transaction --routines --triggers --default-character-set=utf8mb4"
supports --set-gtid-purged && OPTS="$OPTS --set-gtid-purged=OFF"
supports --no-tablespaces && OPTS="$OPTS --no-tablespaces"

echo "[$(date '+%F %T')] 备份 $ENGINE:$DB_NAME@$DB_HOST:$DB_PORT → $FILE"
echo "[$(date '+%F %T')] 工具 $DUMP_BIN $OPTS"

# 半截文件不能留：mysqldump 失败 / 磁盘满时这里会退出，
# 但 gzip 已经把文件创建出来了。留着它，下次出事后可能被当成"有效备份"拿去恢复。
cleanup_partial() { [ -f "$FILE" ] && rm -f "$FILE" && echo "已删除不完整的备份文件：$FILE" >&2; }
trap cleanup_partial ERR

# stderr 单独收：mysqldump 有些错误是 **warning 级别**（退出码 0），
# 光看退出码会以为备份成功。实测 MySQL 8 用非 root 账号跑，
# 默认会报「Access denied; you need PROCESS privilege ... when trying to dump tablespaces」，
# 而数据其实是完整的 —— 但日志里天天出现 "Error:" 字样，运维要么被吓到、
# 要么习惯性忽略（后者更糟：真出错时也没人看）。
# 所以两件事一起做：加 --no-tablespaces 消掉这个已知警告 + 抓 stderr 里有没有 Error。
# 用固定路径而不是 mktemp：
# ① mktemp 在 /tmp，某些加固环境 /tmp 是 noexec 或不可写
# ② 清理命令失败会影响脚本退出码（`trap ... EXIT` 里的失败会传染），
#    而 cron 是按退出码判断成败的 —— 备份好好的却报失败，久了就没人看了
ERRFILE="$OUT_DIR/.backup.err"
: > "$ERRFILE" || { echo "无法写入 $ERRFILE" >&2; exit 1; }

if [ "$ENGINE" = "mysql" ]; then
  # 密码用 MYSQL_PWD 环境变量传，不放命令行：ps 能看见同机的全部命令行参数，
  # `-p密码` 等于把库密码写在墙上。
  # $OPTS 故意不加引号：它是一串要拆开的参数（上面按探测结果拼的）
  # shellcheck disable=SC2086
  MYSQL_PWD="$DB_PASS" "$DUMP_BIN" -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USER" \
    $OPTS "$DB_NAME" 2>"$ERRFILE" | gzip > "$FILE"
else
  PGPASSWORD="$DB_PASS" "$DUMP_BIN" -h"$DB_HOST" -p"$DB_PORT" -U"$DB_USER" -d"$DB_NAME" \
    2>"$ERRFILE" | gzip > "$FILE"
fi

# mysqldump 报了 Error 就算失败——哪怕退出码是 0、哪怕文件看起来完整。
# "看起来完整的坏备份"比"明确的失败"危险得多：前者会在真出事那天才发现。
if [ -s "$ERRFILE" ] && grep -qi 'error' "$ERRFILE"; then
  echo "[$(date '+%F %T')] ✗ mysqldump 报错（退出码可能是 0，但内容不可信）：" >&2
  sed 's/^/    /' "$ERRFILE" >&2
  cleanup_partial
  exit 1
fi
# 有非 Error 的提示也打出来（如 warning），但不阻断
if [ -s "$ERRFILE" ]; then
  echo "[$(date '+%F %T')] 提示（不影响备份）：" >&2
  sed 's/^/    /' "$ERRFILE" >&2
fi
# 清理临时错误文件：|| true 是必须的，失败不能传染退出码
rm -f "$ERRFILE" || true

# ---------- 校验：备份文件必须真的能用 ----------
# 只看到"写入成功"不够：gzip 能通过校验但 dump 是半截的情况是有的
# （mysqldump 遇到某些错误会写出合法的 gzip 流，只是内容不完整）。
# 这里查两件事：压缩包完整 + dump 以 mysqldump 的收尾标记结尾。
if ! gzip -t "$FILE" 2>/dev/null; then
  echo "[$(date '+%F %T')] ✗ 备份文件校验失败（压缩包损坏）：$FILE" >&2
  cleanup_partial
  exit 1
fi
# mysqldump 正常结束会写一行 "-- Dump completed on ..."。
# 查不到它就说明导出中途断了 —— 这种文件恢复出来会缺表。
if ! gzip -dc "$FILE" | tail -n 40 | grep -q 'Dump completed'; then
  echo "[$(date '+%F %T')] ✗ 备份文件缺少收尾标记，导出可能中途失败：$FILE" >&2
  cleanup_partial
  exit 1
fi
trap - ERR

SIZE="$(du -h "$FILE" | cut -f1)"
echo "[$(date '+%F %T')] 完成并通过校验：$SIZE"

# ---------- 清理过期备份 ----------
# ⚠️ 只在本次备份**校验通过之后**才清理。
# 顺序反了的话：新备份是坏的 → 脚本继续往下跑 → 删掉过期旧备份 → 手里一份能用的都没有。
find "$OUT_DIR" -name "${DB_NAME}-*.sql.gz" -mtime +"$KEEP" -print -delete |
  while read -r f; do echo "已删除过期备份：$f"; done
