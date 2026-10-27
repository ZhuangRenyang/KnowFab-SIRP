#!/usr/bin/env bash
#
# device-hub 一键安装（Ubuntu / Debian / 麒麟 / CentOS 通用）
#
# 用法：
#   sudo ./install.sh --db-pass '你的数据库密码'
#                                     默认装到 /opt/device-hub，端口 8080。
#                                     数据库默认连本机 MySQL：127.0.0.1:3306/device_hub
#                                     （H2 已废弃，MySQL 8 必须先装好、建好库）
#   sudo ./install.sh --db-pass '...' \
#        --db-host 10.0.0.5 --db-port 3306 --db-name device_hub --db-user devicehub
#                                     指定数据库位置和账号
#   sudo ./install.sh --db pg --db-pass '...'   用 PostgreSQL（可选）
#   sudo PORT=9000 ./install.sh --db-pass '...'   换端口
#   sudo ./install.sh --allow-web-restart --db-pass '...'
#                                     允许网页上点"应用升级"直接重启服务
#                                     （只给 devicehub 用户加一条 sudoers：
#                                      仅允许 systemctl restart device-hub，别的都不行）
#
# 这个脚本做四件事：装运行时依赖 → 建用户和目录 → 注册 systemd 服务 → 起服务并自检。
# 全程可重复执行（幂等），装坏了重跑一遍就行，不会把已有数据覆盖掉。
set -euo pipefail

APP="device-hub"
DIR="${DIR:-/opt/device-hub}"
SVC_USER="${SVC_USER:-devicehub}"
PORT="${PORT:-8080}"
ALLOW_WEB_RESTART=0
DB="mysql"             # mysql（默认）| pg。H2 已废弃（2026-10-27），驱动已从包里移除
DB_HOST="127.0.0.1"
DB_PORT=""
DB_NAME="device_hub"
DB_USER="device_hub"
DB_PASS=""
ONLINE=0
SOURCE="global"         # cn（Gitee，建好后替换地址）| global（GitHub，默认）| 自定义目录地址
while [ $# -gt 0 ]; do
  case "$1" in
    --allow-web-restart) ALLOW_WEB_RESTART=1 ;;
    --db)        DB="$2"; shift ;;
    --db-host)   DB_HOST="$2"; shift ;;
    --db-port)   DB_PORT="$2"; shift ;;
    --db-name)   DB_NAME="$2"; shift ;;
    --db-user)   DB_USER="$2"; shift ;;
    --db-pass)   DB_PASS="$2"; shift ;;
    --online)    ONLINE=1 ;;
    --source)    SOURCE="$2"; shift ;;
    -h|--help)
      sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'
      exit 0 ;;
    *) echo "未知参数: $1"; exit 1 ;;
  esac
  shift
done
[ "$DB" = "mysql" ] || [ "$DB" = "pg" ] || {
  [ "$DB" = "h2" ] && die "--db h2 已废弃（2026-10-27）：H2 驱动已从包里移除，本项目默认且只推荐 MySQL 8（可选 pg）"
  echo "--db 只能是 mysql / pg"; exit 1
}
# MySQL 现在是硬前提（要并 ERP、长期数据），密码必须给：数据库连接不是能猜的东西
[ -n "$DB_PASS" ] || die "缺 --db-pass '数据库密码'（MySQL/PG 是必装前提，不再有免安装的 H2 兜底）"
case "$DB_PASS" in
  *"'"*) die "数据库密码请勿包含单引号（会破坏生成的配置文件）" ;;
esac
[ -n "$DB_PORT" ] || { [ "$DB" = "mysql" ] && DB_PORT=3306 || DB_PORT=5432; }

say()  { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
ok()   { printf '\033[1;32m  完成 %s\033[0m\n' "$*"; }
warn() { printf '\033[1;33m  注意 %s\033[0m\n' "$*"; }
die()  { printf '\033[1;31m  失败 %s\033[0m\n' "$*" >&2; exit 1; }

[ "$(id -u)" -eq 0 ] || die "要用 root 运行：sudo ./install.sh"

HERE="$(cd "$(dirname "$0")" && pwd)"

# ---------- 1.5 联网取包（可选） ----------
# 更新源地址：国内默认 Gitee，海外默认 GitHub raw。仓库还没建就把地址换成你自己的。
REPO_CN="${DH_REPO_CN:-https://gitee.com/your-account/KnowFab-SIRP/raw/master/dist}"
# 注意：仓库已转私有（2026-10-27）。私有仓库的 raw 地址需带令牌才能访问，
# --online 仅在仓库公开或客户侧配置只读令牌时可用；主交付路径是 Docker deploy.sh。
REPO_GLOBAL="${DH_REPO_GLOBAL:-https://raw.githubusercontent.com/ZhuangRenyang/KnowFab-SIRP/main/dist}"
if [ "$ONLINE" -eq 1 ]; then
  case "$SOURCE" in
    cn)     BASE="$REPO_CN" ;;
    global) BASE="$REPO_GLOBAL" ;;
    *)      BASE="$SOURCE" ;;
  esac
  BASE="${BASE%/}"
  command -v curl >/dev/null || die "联网安装需要 curl：apt install curl"
  command -v unzip >/dev/null || die "联网安装需要 unzip：apt install unzip"

  say "从更新源下载安装包：$BASE"
  TMPD="$(mktemp -d)"
  if ! curl -fsSL --connect-timeout 8 --max-time 30 "$BASE/version.json" -o "$TMPD/version.json"; then
    rm -rf "$TMPD"
    die "连不上更新源 $BASE（服务器可能没有外网）。可以改成离线安装：把 jar 放本目录，去掉 --online"
  fi
  VER="$(sed -nE 's/.*"version"[[:space:]]*:[[:space:]]*"([^"]+)".*/\1/p' "$TMPD/version.json" | head -1)"
  URL="$(sed -nE 's/.*"url"[[:space:]]*:[[:space:]]*"([^"]+)".*/\1/p' "$TMPD/version.json" | head -1)"
  SHA="$(sed -nE 's/.*"sha256"[[:space:]]*:[[:space:]]*"([^"]+)".*/\1/p' "$TMPD/version.json" | head -1)"
  [ -n "${URL:-}" ] || { rm -rf "$TMPD"; die "version.json 里没有 url"; }
  echo "   最新版本 $VER，下载 $URL"
  if ! curl -fL --connect-timeout 8 --max-time 600 "$URL" -o "$TMPD/pkg.zip"; then
    rm -rf "$TMPD"
    die "下载升级包失败（网络中断或被墙）。可以手工下载后离线安装"
  fi
  unzip -q "$TMPD/pkg.zip" -d "$TMPD/x" || die "升级包解压失败"
  if [ -n "${SHA:-}" ]; then
    ACT="$(sha256sum "$TMPD/x/device-hub.jar" | awk '{print $1}')"
    [ "$ACT" = "$SHA" ] || { rm -rf "$TMPD"; die "jar 校验不通过，包可能损坏（期望 $SHA）"; }
    ok "jar 校验通过"
  fi
  cp "$TMPD/x/device-hub.jar" "$HERE/device-hub.jar"
  rm -rf "$TMPD"
  ok "已取到 device-hub.jar（$VER）"
fi

# ---------- 1. 运行时 ----------
say "检查 Java 运行时"
if ! command -v java >/dev/null 2>&1; then
  warn "没找到 java，尝试用系统源安装 OpenJDK 17（国内源，不联网也需要本机有 apt 源）"
  if command -v apt-get >/dev/null 2>&1; then
    apt-get update -y && apt-get install -y openjdk-17-jre-headless
  elif command -v dnf >/dev/null 2>&1; then
    dnf install -y java-17-openjdk-headless
  elif command -v yum >/dev/null 2>&1; then
    yum install -y java-17-openjdk-headless
  else
    die "装不了 Java，请手工装 JDK 17 后重跑（离线环境可把 jre 目录放到 $DIR/jre）"
  fi
fi

JAVA_BIN="$(command -v java || true)"
if [ -x "$HERE/jre/bin/java" ]; then
  JAVA_BIN="$HERE/jre/bin/java"        # 自带运行时优先，客户机器不用装 Java
fi
[ -n "$JAVA_BIN" ] || die "找不到 java 可执行文件"

JVER="$("$JAVA_BIN" -version 2>&1 | head -1 | sed -E 's/.*"([0-9]+).*/\1/')"
[ "${JVER:-0}" -ge 17 ] 2>/dev/null || die "需要 Java 17 及以上，当前是 $JVER"
ok "$("$JAVA_BIN" -version 2>&1 | head -1)"

# ---------- 2. 用户与目录 ----------
say "准备用户和目录 $DIR"
if ! id "$SVC_USER" >/dev/null 2>&1; then
  useradd --system --home "$DIR" --shell /usr/sbin/nologin "$SVC_USER" 2>/dev/null \
    || useradd -r -d "$DIR" -s /sbin/nologin "$SVC_USER"
  ok "已创建系统用户 $SVC_USER"
fi

mkdir -p "$DIR"/{data,logs,updates/inbox,updates/staged,backups,bin,migrations}
chown -R "$SVC_USER":"$SVC_USER" "$DIR"

# 程序文件：同目录下的 jar；重复安装时先备份正在用的那个
if [ -f "$HERE/$APP.jar" ]; then
  if [ -f "$DIR/$APP.jar" ]; then
    TS="$(date +%Y%m%d-%H%M%S)"
    mkdir -p "$DIR/backups/install-$TS"
    cp "$DIR/$APP.jar" "$DIR/backups/install-$TS/$APP.jar"
    ok "旧程序已备份到 $DIR/backups/install-$TS"
  fi
  cp "$HERE/$APP.jar" "$DIR/$APP.jar"
  ok "程序已复制到 $DIR/$APP.jar"
fi
[ -f "$DIR/$APP.jar" ] || die "$DIR/$APP.jar 不存在，请把 device-hub.jar 和本脚本放同一目录"

# 首次安装写入默认配置；已存在则保留客户改过的配置
if [ ! -f "$DIR/application.yml" ]; then
  # 只有一条路径生成配置：下面这段 heredoc。
  # 以前还会优先用安装目录里的 application.yml，结果是那份文件里 system.admin.password
  # 是空的，装完打印出来的随机密码根本没写进去，客户拿着密码登录不上——
  # 两份模板还容易各自漂移。所以现在只认这一份，外面塞了也忽略。
  if [ -f "$HERE/application.yml" ]; then
    warn "安装目录里有 application.yml，已忽略（配置由本脚本生成，避免两份模板不一致）"
  fi
  ADMIN_PASS="$(head -c 32 /dev/urandom | base64 | tr -dc 'A-Za-z2-9' | head -c 12)"
  if [ "$DB" = "mysql" ]; then
    DB_URL="jdbc:mysql://$DB_HOST:$DB_PORT/$DB_NAME?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true&rewriteBatchedStatements=true"
    DB_DRV="com.mysql.cj.jdbc.Driver"
  else
    DB_URL="jdbc:postgresql://$DB_HOST:$DB_PORT/$DB_NAME?currentSchema=public"
    DB_DRV="org.postgresql.Driver"
  fi
  cat > "$DIR/application.yml" <<YML
server:
  port: 8080
spring:
  datasource:
    url: $DB_URL
    driver-class-name: $DB_DRV
    username: $DB_USER
    password: '$DB_PASS'
    hikari:
      # 采集是异步线程池 + 定时任务，连接数不需要很多；给太多反而把数据库拖慢
      maximum-pool-size: 10
      minimum-idle: 2
      connection-timeout: 10000
  jpa:
    hibernate:
      ddl-auto: update
energy:
  demo-seed: false
report:
  demo-seed: false
demand:
  demo-seed: false
system:
  admin:
    username: admin
    password: "$ADMIN_PASS"
YML
  printf '管理员账号：admin\n初始密码：%s\n\n登录后请立刻在「系统 → 用户管理」里改掉，并删除本文件。\n' \
    "$ADMIN_PASS" > "$DIR/INITIAL_ADMIN.txt"
  chmod 600 "$DIR/INITIAL_ADMIN.txt"
  chown "$SVC_USER":"$SVC_USER" "$DIR/INITIAL_ADMIN.txt"
  ok "已生成管理员初始密码（见 $DIR/INITIAL_ADMIN.txt）"
  chown "$SVC_USER":"$SVC_USER" "$DIR/application.yml"
  ok "已生成配置 $DIR/application.yml"
else
  if grep -q "jdbc:h2" "$DIR/application.yml" 2>/dev/null; then
    die "检测到旧版 H2 配置。H2 已废弃：请先装 MySQL 8 并建库（utf8mb4），把该文件的 datasource 换成 MySQL 连接后重跑本脚本。旧数据如需保留先看 README 10.7 的导出说明"
  fi
  ok "沿用已有配置 $DIR/application.yml"
fi

# 数据库连通性检查：MySQL 现在是硬前提，连不上直接终止——
# 装完起不来的排查成本，比装之前确认一次高得多
if timeout 3 bash -c "cat < /dev/null > /dev/tcp/$DB_HOST/$DB_PORT" 2>/dev/null; then
  ok "数据库 $DB_HOST:$DB_PORT 可连通"
else
  die "连不上数据库 $DB_HOST:$DB_PORT。MySQL/PG 是必装前提（H2 已废弃）：
     1) 确认数据库服务已启动（systemctl status mysql）
     2) 确认账号允许从本机登录（MySQL 8 默认 caching_sha2_password，连接串已带 allowPublicKeyRetrieval）
     3) 确认建库且字符集 utf8mb4：CREATE DATABASE $DB_NAME DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_general_ci;"
fi
warn "确认库已建好且账号 $DB_USER 对 $DB_NAME 有全部权限（GRANT ALL ON $DB_NAME.* TO '$DB_USER'@'%';）"

# 升级包验签用的公钥：从 application.yml 里抽出来转成 PEM，
# 这样停机升级时 upgrade.sh 自己就能验签，不用等服务起来
PUB="$(grep -E '^[[:space:]]*public-key:' "$DIR/application.yml" 2>/dev/null \
        | head -1 | sed -E 's/.*"([^"]+)".*/\1/')"
if [ -n "${PUB:-}" ] && [ "$PUB" != "$(grep -E 'public-key:' "$DIR/application.yml" | head -1)" ]; then
  {
    echo "-----BEGIN PUBLIC KEY-----"
    printf '%s' "$PUB" | fold -w 64
    echo
    echo "-----END PUBLIC KEY-----"
  } > "$DIR/bin/update-pub.pem"
  ok "已生成升级验签公钥 $DIR/bin/update-pub.pem"
else
  warn "application.yml 里没找到 system.update.public-key，离线升级将跳过签名校验"
fi

# ---------- 3. systemd ----------
say "注册 systemd 服务"
for f in apply-pending.sh upgrade.sh; do
  if [ -f "$HERE/bin/$f" ]; then
    cp "$HERE/bin/$f" "$DIR/bin/$f"
    chmod 755 "$DIR/bin/$f"
  fi
done
ok "升级脚本已安装到 $DIR/bin/"

sudoers_file=/etc/sudoers.d/device-hub-restart
if [ "$ALLOW_WEB_RESTART" -eq 1 ]; then
  printf '%s ALL=(root) NOPASSWD: /bin/systemctl restart %s, /usr/bin/systemctl restart %s\n' \
    "$SVC_USER" "$APP" "$APP" > "$sudoers_file"
  chmod 440 "$sudoers_file"
  # systemctl 的实际路径因发行版而异，上面两条覆盖常见情况
  warn "已允许 $SVC_USER 重启本服务（仅此一条命令），网页上点升级会真的重启"
else
  rm -f "$sudoers_file"
fi

cat > "/etc/systemd/system/$APP.service" <<UNIT
[Unit]
Description=知造 SIRP · AI 智慧工厂管理系统 (device-hub)
Documentation=file://$DIR/README.md
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=$SVC_USER
WorkingDirectory=$DIR
Environment=TZ=Asia/Shanghai
Environment=JAVA_TOOL_OPTIONS=-Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai
# 启动前先看有没有待应用的升级包：有就换 jar，换失败就保留旧版本继续起，
# 绝不让"升级"这个动作把服务搞成起不来
ExecStartPre=$DIR/bin/apply-pending.sh
ExecStart=$JAVA_BIN -Xms256m -Xmx1024m -jar $DIR/$APP.jar --server.port=$PORT --spring.config.additional-location=file:$DIR/application.yml
# JVM 收到 SIGTERM 会返回 143，属于正常停止，不要当成崩溃反复拉起
SuccessExitStatus=143
Restart=always
RestartSec=5
LimitNOFILE=65535
StandardOutput=append:$DIR/logs/stdout.log
StandardError=append:$DIR/logs/stderr.log

[Install]
WantedBy=multi-user.target
UNIT

systemctl daemon-reload
systemctl enable "$APP" >/dev/null 2>&1
ok "服务已注册：/etc/systemd/system/$APP.service"

# ---------- 4. 防火墙 ----------
if command -v ufw >/dev/null 2>&1 && ufw status 2>/dev/null | grep -q "Status: active"; then
  ufw allow "$PORT"/tcp >/dev/null 2>&1 && ok "ufw 已放行 $PORT/tcp"
elif command -v firewall-cmd >/dev/null 2>&1 && firewall-cmd --state >/dev/null 2>&1; then
  firewall-cmd --permanent --add-port="$PORT"/tcp >/dev/null 2>&1
  firewall-cmd --reload >/dev/null 2>&1
  ok "firewalld 已放行 $PORT/tcp"
else
  warn "没检测到启用的防火墙；如果客户机器开了 firewalld/ufw，记得放行 $PORT/tcp"
fi

# ---------- 5. 启动与自检 ----------
say "启动服务"
systemctl restart "$APP"

printf '   等待就绪'
for i in $(seq 1 40); do
  if curl -fsS "http://127.0.0.1:$PORT/api/system/version" >/dev/null 2>&1; then
    printf '\n'; ok "服务已就绪（${i} 秒）"
    break
  fi
  printf '.'
  sleep 1
  if [ "$i" -eq 40 ]; then
    printf '\n'
    warn "40 秒还没就绪，看日志：journalctl -u $APP -n 100 或 tail -f $DIR/logs/stdout.log"
  fi
done

IP="$(hostname -I 2>/dev/null | awk '{print $1}')"
cat <<EOF

============================================================
 安装完成

 访问地址   http://${IP:-服务器IP}:$PORT
 安装目录   $DIR
 配置文件   $DIR/application.yml
 日志       $DIR/logs/stdout.log   或   journalctl -u $APP -f

 管理员账号 admin，初始密码见  $DIR/INITIAL_ADMIN.txt（登录后立刻改）

 常用命令
   启停      systemctl {start|stop|restart|status} $APP
   开机自启  systemctl enable $APP          （已自动开启）
   看授权    http://${IP:-服务器IP}:$PORT  → 弹出授权提示页
   升级      网页「系统」页上传升级包 → 点应用
             或命令行：sudo $DIR/bin/upgrade.sh 包路径
   卸载      systemctl disable --now $APP && rm -rf /etc/systemd/system/$APP.service $DIR
             用户数据在 $DIR/data 下，卸载前先备份

 注意：软件按机器码授权。换服务器/重装系统后机器码会变，
       要重新签发授权文件，这是防白嫖的设计，不是故障。
============================================================
EOF
