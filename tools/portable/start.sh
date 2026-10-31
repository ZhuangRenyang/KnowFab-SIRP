#!/usr/bin/env bash
#
# 知造 SIRP 便携启动器（Linux x86_64 / macOS 走 Homebrew 数据库）
#
#   ./start.sh                  默认 8080 端口，数据库 127.0.0.1:3307
#   PORT=9000 ./start.sh
#   RESET_DB=1 ./start.sh       重建数据库（会清空业务数据）
#
# 自动完成：Java 17（缺则下载）→ MariaDB 便携版（缺则下载并初始化到 runtime/data）
#          → 生成 config/application.yml（随机数据库密码）→ 启动 → 打开浏览器（有 GUI 时）
#
# 全部数据都在本目录：runtime/data 是数据库，runtime/logs 是日志；卸载=删目录。
# 不需要 Docker / MySQL / Python / Git。
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
cd "$HERE"

PORT="${PORT:-8080}"
DB_PORT="${DB_PORT:-3307}"
RESET_DB="${RESET_DB:-0}"

JAR="$HERE/device-hub.jar"
RT="$HERE/runtime"
JRE_DIR="$RT/jre"
DB_DIR="$RT/mariadb"
DATA_DIR="$RT/data"
LOG_DIR="$RT/logs"
CONF_DIR="$HERE/config"
CONF_FILE="$CONF_DIR/application.yml"

# ── 平台检测（2026-10-31 新增）────────────────────────────────────────────
# 之前这里写死了 Linux x64 的下载地址，但注释写着"Linux / macOS"——
# 在 Mac 上会把 Linux 版 JRE 和 mariadbd 拉下来，然后报一堆看不懂的错。
# 现在按平台分派：Linux 用便携二进制；macOS 用 macOS 版 JRE + Homebrew 的 MariaDB
# （MariaDB 官方自 11.x 起不再发布 macOS 二进制包，只能走 brew）。
OS="$(uname -s)"
ARCH="$(uname -m)"
DB_MODE="bundled"

case "$OS" in
  Linux)
    if [ "$ARCH" != "x86_64" ]; then
      die "Linux 便携包只提供 x86_64（检测到 $ARCH）。请用源码方式：java -jar device-hub.jar"
    fi
    JRE_URL="${JRE_URL:-https://mirrors.tuna.tsinghua.edu.cn/Adoptium/17/jre/x64/linux/OpenJDK17U-jre_x64_linux_hotspot_17.0.21_9.tar.gz}"
    DB_URL="${DB_URL:-https://mirrors.aliyun.com/mariadb/mariadb-11.4.13/bintar-linux-systemd-x86_64/mariadb-11.4.13-linux-systemd-x86_64.tar.gz}"
    ;;
  Darwin)
    case "$ARCH" in
      arm64)  JRE_URL="${JRE_URL:-https://mirrors.tuna.tsinghua.edu.cn/Adoptium/17/jre/aarch64/mac/OpenJDK17U-jre_aarch64_mac_hotspot_17.0.21_9.tar.gz}" ;;
      x86_64) JRE_URL="${JRE_URL:-https://mirrors.tuna.tsinghua.edu.cn/Adoptium/17/jre/x64/mac/OpenJDK17U-jre_x64_mac_hotspot_17.0.21_9.tar.gz}" ;;
      *) die "不支持的 Mac 架构：$ARCH（支持 arm64 / x86_64）" ;;
    esac
    DB_MODE="brew"
    DB_URL=""   # macOS 用 Homebrew，不下载便携库
    ;;
  *)
    die "只支持 Linux x86_64 与 macOS（Intel / Apple Silicon）。当前平台：$OS $ARCH
     其他平台请用源码方式运行：java -jar device-hub.jar（需要 JDK 17+ 与 MySQL/MariaDB）"
    ;;
esac

say() { printf '\n== %s\n' "$*"; }
ok()  { printf '   %s\n' "$*"; }
die() { printf '\n失败：%s\n' "$*" >&2; exit 1; }

need() {
  command -v "$1" >/dev/null 2>&1 || die "缺少命令 $1（apt install $1 / yum install $1）"
}

download() { # url out
  say "下载 $(basename "$2")"
  need curl
  curl -fL --retry 3 --connect-timeout 20 -o "$2.part" "$1" || die "下载失败：$1"
  mv -f "$2.part" "$2"
  ok "$(du -h "$2" | cut -f1)"
}

port_busy() { # port
  (echo >/dev/tcp/127.0.0.1/"$1") >/dev/null 2>&1
}

rand_pwd() {
  # 16 位，去掉易混字符
  LC_ALL=C tr -dc 'a-km-zA-HJ-NP-Z2-9' </dev/urandom | head -c 16
}

[ -f "$JAR" ] || die "找不到 device-hub.jar（请把脚本放在解压后的目录里）"
mkdir -p "$RT" "$LOG_DIR"

# ---------- 1. Java ----------
JAVA_BIN=""
if [ -x "$JRE_DIR/bin/java" ]; then
  JAVA_BIN="$JRE_DIR/bin/java"
elif command -v java >/dev/null 2>&1; then
  V="$(java -version 2>&1 | head -1 | sed -E 's/.*"([0-9]+).*/\1/')"
  if [ "${V:-0}" -ge 17 ] 2>/dev/null; then JAVA_BIN="$(command -v java)"; fi
fi
if [ -z "$JAVA_BIN" ]; then
  say '本机没有 Java 17，下载便携 JRE（约 45MB，只需一次）'
  download "$JRE_URL" "$RT/jre.tar.gz"
  need tar
  rm -rf "$RT"/jdk-17* 2>/dev/null || true
  tar -xzf "$RT/jre.tar.gz" -C "$RT"
  rm -f "$RT/jre.tar.gz"
  INNER="$(find "$RT" -maxdepth 1 -type d -name 'jdk-17*' | head -1)"
  [ -n "$INNER" ] || die 'JRE 解压失败'
  [ -d "$JRE_DIR" ] && rm -rf "$JRE_DIR"
  mv "$INNER" "$JRE_DIR"
  ok "JRE 就绪：$JRE_DIR"
  JAVA_BIN="$JRE_DIR/bin/java"
fi
ok "$("$JAVA_BIN" -version 2>&1 | head -1)"

# ---------- 2. MariaDB ----------
MARIADBD="$DB_DIR/bin/mariadbd"
MARIADB_INITDB="$DB_DIR/scripts/mariadb-install-db"
BASEDIR_ARG="--basedir=$DB_DIR"

if [ "$DB_MODE" = "brew" ]; then
  # macOS 分支：官方没有 mac 二进制包，用 Homebrew 的 MariaDB
  command -v brew >/dev/null 2>&1 || die "macOS 上需要 Homebrew 安装 MariaDB：
     1) 先装 Homebrew：https://brew.sh
     2) 再执行：brew install mariadb
     3) 回来重新运行 ./start.sh"
  if ! command -v mariadbd >/dev/null 2>&1; then
    say '用 Homebrew 安装 MariaDB（约 2 分钟，只需一次）'
    brew install mariadb >/dev/null 2>&1 || die 'brew install mariadb 失败，请手动执行后重试'
  fi
  MARIADBD="$(command -v mariadbd)"
  MARIADB_INITDB="$(command -v mariadb-install-db || command -v mysql_install_db || true)"
  [ -n "$MARIADB_INITDB" ] || die '找不到 mariadb-install-db，请确认 brew install mariadb 成功'
  BASEDIR_ARG=""
  ok "MariaDB 使用 Homebrew 版本"
elif [ ! -x "$MARIADBD" ]; then
  say '首次运行：下载便携 MariaDB（约 120MB，只需一次）'
  download "$DB_URL" "$RT/mariadb.tar.gz"
  need tar
  tar -xzf "$RT/mariadb.tar.gz" -C "$RT"
  rm -f "$RT/mariadb.tar.gz"
  INNER="$(find "$RT" -maxdepth 1 -type d -name 'mariadb-*' | head -1)"
  [ -n "$INNER" ] || die 'MariaDB 解压失败'
  [ -d "$DB_DIR" ] && rm -rf "$DB_DIR"
  mv "$INNER" "$DB_DIR"
  ok "MariaDB 就绪：$DB_DIR"
fi

if [ "$RESET_DB" = "1" ] && [ -d "$DATA_DIR" ]; then
  say '按 RESET_DB=1 重置数据库（业务数据会清空）'
  rm -rf "$DATA_DIR"
fi

if [ ! -d "$DATA_DIR/mysql" ]; then
  say '初始化数据库（第一次约 20 秒）'
  mkdir -p "$DATA_DIR"
  "$MARIADB_INITDB" --no-defaults \
    $BASEDIR_ARG --datadir="$DATA_DIR" \
    --auth-root-authentication-method=normal --skip-test-db \
    >"$LOG_DIR/db-init.log" 2>&1 || die "数据库初始化失败，见 runtime/logs/db-init.log"
  ok '数据库初始化完成（root 初始无密码，仅监听 127.0.0.1）'
fi

if port_busy "$DB_PORT"; then
  ok "数据库已在 $DB_PORT 运行"
else
  say "启动数据库（127.0.0.1:$DB_PORT）"
  "$MARIADBD" --no-defaults \
    $BASEDIR_ARG --datadir="$DATA_DIR" \
    --port="$DB_PORT" --bind-address=127.0.0.1 --skip-name-resolve \
    --character-set-server=utf8mb4 --collation-server=utf8mb4_general_ci \
    --socket="$RT/mysql.sock" --pid-file="$RT/mysql.pid" \
    --log-error="$LOG_DIR/db.err.log" >/dev/null 2>&1 &
  for _ in $(seq 1 60); do sleep 0.5; port_busy "$DB_PORT" && break; done
  port_busy "$DB_PORT" || die "数据库没有起来，见 runtime/logs/db.err.log"
  ok '数据库已就绪'
fi

# ---------- 3. 配置 ----------
if [ ! -f "$CONF_FILE" ]; then
  say '生成配置文件（随机数据库密码，仅本机可见）'
  mkdir -p "$CONF_DIR"
  PWD_DB="$(rand_pwd)"
  cat >"$CONF_FILE" <<YML
# 由便携启动器自动生成（首次运行）。改端口/密码可以直接改这里。
server:
  port: ${PORT}
spring:
  datasource:
    url: jdbc:mysql://127.0.0.1:${DB_PORT}/devicehub?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true&rewriteBatchedStatements=true
    username: devicehub
    password: "${PWD_DB}"
  jpa:
    # 便携版固定用 MariaDB：显式指定方言，避免 Hibernate 自动探测失败
    properties:
      hibernate:
        dialect: org.hibernate.dialect.MariaDBDialect
YML
  ok "已写入 $CONF_FILE"
else
  ok "沿用现有配置：$CONF_FILE"
fi

PWD_DB="$(sed -nE 's/^ *password: *"([^"]+)".*/\1/p' "$CONF_FILE" | head -1)"
[ -n "$PWD_DB" ] || die "配置里读不到数据库密码：$CONF_FILE"

say '确保数据库与账号存在'
SQL="CREATE DATABASE IF NOT EXISTS devicehub DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_general_ci;
CREATE USER IF NOT EXISTS 'devicehub'@'127.0.0.1' IDENTIFIED BY '$PWD_DB';
CREATE USER IF NOT EXISTS 'devicehub'@'localhost' IDENTIFIED BY '$PWD_DB';
ALTER USER 'devicehub'@'127.0.0.1' IDENTIFIED BY '$PWD_DB';
ALTER USER 'devicehub'@'localhost' IDENTIFIED BY '$PWD_DB';
GRANT ALL PRIVILEGES ON devicehub.* TO 'devicehub'@'127.0.0.1';
GRANT ALL PRIVILEGES ON devicehub.* TO 'devicehub'@'localhost';
FLUSH PRIVILEGES;"
"$DB_DIR/bin/mariadb" --no-defaults -u root -h 127.0.0.1 -P "$DB_PORT" -e "$SQL" \
  || die '创建数据库失败（端口被占用或 datadir 损坏；可 RESET_DB=1 重来，注意清数据）'
ok '数据库就绪'

# ---------- 4. 启动 ----------
port_busy "$PORT" && die "端口 $PORT 已被占用，换一个：PORT=8090 ./start.sh"

say "启动知造 SIRP（端口 $PORT）"
nohup "$JAVA_BIN" -Xms256m -Xmx512m -Dfile.encoding=UTF-8 \
  -jar "$JAR" --server.port="$PORT" \
  >"$LOG_DIR/app.log" 2>&1 &
APP_PID=$!
echo "$APP_PID" >"$RT/app.pid"

READY=0
for _ in $(seq 1 90); do
  sleep 1
  if ! kill -0 "$APP_PID" 2>/dev/null; then
    die "应用启动失败，见 runtime/logs/app.log"
  fi
  if curl -fsS -o /dev/null "http://127.0.0.1:$PORT/app/" 2>/dev/null; then READY=1; break; fi
done
[ "$READY" = "1" ] || die "等待启动超时，见 runtime/logs/app.log"

URL="http://localhost:$PORT/app/"
ADMIN_PWD=""
if [ -f "$HERE/data/initial-admin.txt" ]; then
  ADMIN_PWD="$(sed -nE 's/^密码：(.*)$/\1/p' "$HERE/data/initial-admin.txt" | head -1)"
fi
LOGIN_LINE="${ADMIN_PWD:+admin / $ADMIN_PWD（首次登录请改密码）}"
[ -n "$LOGIN_LINE" ] || LOGIN_LINE='admin / 你设置的密码（首次密码在 data/initial-admin.txt）'

cat <<EOF

======================================
  已启动： $URL
  登录：   $LOGIN_LINE
  数据：   $DATA_DIR
  停止：   ./stop.sh
======================================

日志：runtime/logs/app.log（tail -f 可跟）
EOF

# 有图形界面就尝试打开浏览器（服务器无 GUI 时自动跳过）
if command -v xdg-open >/dev/null 2>&1 && [ -n "${DISPLAY:-}${WAYLAND_DISPLAY:-}" ]; then
  xdg-open "$URL" >/dev/null 2>&1 || true
fi
