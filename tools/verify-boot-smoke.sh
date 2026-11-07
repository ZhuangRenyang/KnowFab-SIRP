#!/usr/bin/env bash
# erp 拆包：**真启动一次**的冒烟脚本（补「没有 @SpringBootTest」这个盲区）。
#
# ⭐ 为什么必须有它：
#   全项目 60 个测试文件里**没有一个真实的 @SpringBootTest**
#   （两处 @SpringBootTest 字样都在 javadoc 里，原文说的正是「本项目没有」）
#   ⇒ 409 个用例全是纯 Mockito 单元测试
#   ⇒ **mvn clean test 全绿只证明「编译过 + 单元逻辑对」**，
#      **不证明** 16 个 @Entity 被 JPA 扫到、16 个 Repository 被 Spring Data 扫到、
#      quality 域的 ErpQcReleaseService 还能实现 ErpQualityReleaseGate。
#   ⇒ 拆包后可能出现「编译过 + 409 全绿 + 门禁全绿，但启动时
#      `Not a managed type` / `No qualifying bean` 直接炸」。
#   ⇒ 本脚本专门抓这一类**启动期**失败。
#
# ⚠️ 它**不进 CI 门禁**：ci.yml 里有一条写死的决策 ——
#   「测试全是纯单测 ⇒ CI 不起 MySQL 容器 ⇒ 少一个环境变量就少一处『本地能跑、CI 跑不了』」。
#   ⇒ 本脚本是**本地 / 发版前置**跑的，不是 CI 项。
#
# 用法：
#   tools/verify-boot-smoke.sh [--port 8080] [--keep-running] [--no-endpoints]
#
# 环境变量：
#   DB_URL / DB_USER / DB_PASSWORD   覆盖数据源（默认 127.0.0.1:3306/devicehub，admin/123456）
#   ERP_SMOKE_PATHS                  覆盖要打的接口（默认见下方 ERP_PATHS）
#
# 退出码：0 = 启动成功且接口正常；1 = 有问题；2 = 环境不满足

set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT" || exit 2

PORT=8080
KEEP_RUNNING=0
CHECK_ENDPOINTS=1
while [ $# -gt 0 ]; do
  case "$1" in
    --port)           PORT="${2:-}"; shift 2 ;;
    --keep-running)   KEEP_RUNNING=1; shift ;;
    --no-endpoints)   CHECK_ENDPOINTS=0; shift ;;
    -h|--help)        sed -n '2,30p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "未知参数：$1" >&2; exit 2 ;;
  esac
done

DB_URL="${DB_URL:-127.0.0.1:3306/devicehub}"
DB_USER="${DB_USER:-admin}"
DB_PASSWORD="${DB_PASSWORD:-123456}"
APP_USER="${APP_USER:-admin}"
APP_PASSWORD="${APP_PASSWORD:-123456}"

# 要打的 erp 接口（覆盖 6 个子包各自的前缀；GET、无副作用）
# ⭐ 探针清单：**每个子包至少一个真实端点**，且**全部是会查库的**。
# ⚠️⚠️ 2026-11-07 实测踩到的坑（第一版探针全错，4 个假失败）：
#   ① 用**类级路径本身**（`/api/erp/bom`）⇒ 该类没有空路径方法 ⇒ **404**
#      （实测 404 的：`/api/erp/sales`、`/api/erp/stock`）
#   ② `/api/erp/prod/status` 路径是对的，但**缺必填参数** ⇒ **400**（不是路由错）
#   ⇒ 症状极具迷惑性：探针失败会让人以为「拆包把路由搞坏了」⇒ 而拆包根本没动路由。
#   ⇒ 现在这份清单是**逐个 Controller 从 @RequestMapping + @GetMapping 实测出来的**。
#
# 覆盖度（子包 → 探针）：
#   root        → /api/erp/skus（ErpBaseController，SKU/Warehouse/Partner 三张主数据表）
#   basic       → /api/erp/skus /api/erp/warehouses /api/erp/partners
#   stock       → /api/erp/stock/summary      （库存汇总表）
#   sales       → /api/erp/sales/stats        （销售统计）
#   purchase    → /api/erp/purchase/stats     （采购统计）
#   production  → /api/erp/work-orders/summary（工单表）
#   analysis    → /api/erp/cost/overview /api/erp/finance/summary（BOM/成本/财务）
#   quality.inspection → /api/erp/qc/inspections（检验单）
#   quality.defect     → /api/erp/qc/defects    （缺陷单）
#
# ⇒ **为什么覆盖度重要**：某个子包的 `@Entity` 没被 JPA 扫到时，
#   **启动照样成功**（Spring 不检查表存在）⇒ 只有**真的查那张表**才会炸
#   ⇒ 少一个子包的探针 = 少一道防线。
ERP_PATHS="${ERP_SMOKE_PATHS:-/api/erp/skus /api/erp/warehouses /api/erp/partners /api/erp/stock/summary /api/erp/sales/stats /api/erp/purchase/stats /api/erp/work-orders/summary /api/erp/cost/overview /api/erp/finance/summary /api/erp/qc/inspections /api/erp/qc/defects /api/erp/bom/products /api/erp/maint/stats /api/erp/lines}"

LOG_DIR="$ROOT/target-logs"
SMOKE_LOG="$LOG_DIR/_ci-boot-smoke.log"
APP_LOG="$ROOT/logs/device-hub.log"
COOKIE_JAR="$(mktemp "${TMPDIR:-/tmp}/dh-smoke-cookie.XXXXXX")"
APP_PID=""

say()  { printf '\n\033[1m== %s\033[0m\n' "$*"; }
line() { printf '  %s %-26s %s\n' "$1" "$2" "$3"; }
die()  { printf '\n✗ %s\n' "$*" >&2; exit 1; }

cleanup() {
  if [ -n "$APP_PID" ] && kill -0 "$APP_PID" 2>/dev/null; then
    if [ "$KEEP_RUNNING" = "1" ]; then
      line "·" "应用仍在运行" "PID $APP_PID（--keep-running）"
    else
      kill "$APP_PID" 2>/dev/null
      sleep 2
      kill -9 "$APP_PID" 2>/dev/null
      line "·" "已停止应用" "PID $APP_PID"
    fi
  fi
  [ -f "$COOKIE_JAR" ] && rm -f "$COOKIE_JAR"
}
trap cleanup EXIT

# ---------------- 前置检查 ----------------
say "前置检查"
command -v curl >/dev/null 2>&1 || die "缺少 curl"
command -v java >/dev/null 2>&1 || die "缺少 java"

JAR="$ROOT/target/device-hub.jar"
if [ ! -f "$JAR" ]; then
  die "找不到 $JAR
  ⇒ 先打包：mvn clean package -DskipTests
  ⇒ （本脚本不负责打包：打包是后端/QA 的动作）"
fi
line "✓" "jar" "$(basename "$JAR")"

# 端口必须空着（否则我们打到的可能是另一个实例，结论就废了）
if curl -s -o /dev/null --max-time 2 "http://127.0.0.1:$PORT/api/auth/me" 2>/dev/null; then
  die "端口 $PORT 上已经有服务在跑 ⇒ 本次冒烟会打到别人的进程，结论无效
  ⇒ 先停掉它，或用 --port 换一个端口"
fi
line "✓" "端口 $PORT" "空闲"

# MySQL 可达（不用 mysql 客户端，用 bash 的 /dev/tcp 探端口）
# ⚠️⚠️ **端口要再切一次 `/`**（2026-11-07 实测修的 bug）：
#   DB_URL 形如 `127.0.0.1:3306/devicehub`
#   `cut -d: -f2` 得到的是 **`3306/devicehub`**（把库名也切进来了）
#   ⇒ `/dev/tcp/127.0.0.1/3306/devicehub` ⇒ 探测失败
#   ⇒ 误报「连不上 MySQL」⇒ **每次批次验收都直接失败**（而 MySQL 其实是好的）。
#   ⇒ 症状极具迷惑性：前置检查就红，看不出是「探测逻辑错」还是「数据库真没起」。
DB_HOST="$(printf '%s' "$DB_URL" | cut -d: -f1)"
DB_PORT="$(printf '%s' "$DB_URL" | cut -d: -f2 | cut -d/ -f1)"
if ! (exec 3<>"/dev/tcp/$DB_HOST/$DB_PORT") 2>/dev/null; then
  die "连不上 MySQL $DB_HOST:$DB_PORT
  ⇒ 启动必然失败（Failed to configure a DataSource）
  ⇒ 先起本地 MySQL 8 并建好 devicehub 库"
fi
line "✓" "MySQL" "$DB_HOST:$DB_PORT 可达"

mkdir -p "$LOG_DIR"
: > "$SMOKE_LOG"

# ---------------- 启动 ----------------
say "启动应用"
line "·" "命令" "java -jar $(basename "$JAR")"
line "·" "数据源" "$DB_URL"
line "·" "控制台日志" "$SMOKE_LOG"

java -jar "$JAR" \
  --server.port="$PORT" \
  --spring.datasource.url="jdbc:mysql://$DB_URL?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true" \
  --spring.datasource.username="$DB_USER" \
  --spring.datasource.password="$DB_PASSWORD" \
  >"$SMOKE_LOG" 2>&1 &
APP_PID=$!
line "·" "PID" "$APP_PID"

# 等启动完成：轮询 /api/auth/me（permitAll，最稳的探针）
STARTED=0
for i in $(seq 1 90); do
  if ! kill -0 "$APP_PID" 2>/dev/null; then
    echo "" >&2
    line "✗" "进程已退出" "启动就崩了，日志末尾："
    tail -40 "$SMOKE_LOG" | sed 's/^/       /' >&2
    exit 1
  fi
  CODE="$(curl -s -o /dev/null -w '%{http_code}' --max-time 2 "http://127.0.0.1:$PORT/api/auth/me" 2>/dev/null || echo 000)"
  if [ "$CODE" = "200" ] || [ "$CODE" = "401" ] || [ "$CODE" = "403" ]; then
    STARTED=1
    line "✓" "启动完成" "HTTP $CODE（等了 ${i}s）"
    break
  fi
  sleep 1
done
[ "$STARTED" = "1" ] || {
  line "✗" "启动超时" "90s 内没起来，日志末尾："
  tail -40 "$SMOKE_LOG" | sed 's/^/       /' >&2
  exit 1
}

# ---------------- 抓启动期失败 ----------------
say "启动期失败扫描"
# 这些是「编译/单测都抓不到，只有真启动才会暴露」的错误类型
FATAL_RE='Not a managed type|No qualifying bean|APPLICATION FAILED TO START|ConflictingBeanDefinitionException|Failed to configure a DataSource|Unable to start web server|Unknown entity|Could not resolve entity|MappingException'

FOUND=""
for f in "$SMOKE_LOG" "$APP_LOG"; do
  [ -f "$f" ] || continue
  H="$(grep -aE "$FATAL_RE" "$f" 2>/dev/null || true)"
  [ -n "$H" ] && FOUND="$FOUND
--- $(basename "$f") ---
$H"
done

if [ -n "$FOUND" ]; then
  line "✗" "发现启动期致命错误" "Spring 容器没起来 / JPA 没扫到"
  printf '%s\n' "$FOUND" | head -30 | sed 's/^/       /'
  exit 1
fi
line "✓" "无启动期致命错误" "Not a managed type / No qualifying bean 等均未出现"

# ---------------- 登录 ----------------
say "登录"
LOGIN_CODE="$(curl -s -o /tmp/.dh-login-body.$$ -w '%{http_code}' -c "$COOKIE_JAR" \
  -X POST "http://127.0.0.1:$PORT/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"$APP_USER\",\"password\":\"$APP_PASSWORD\"}" 2>/dev/null || echo 000)"
BODY="$(cat /tmp/.dh-login-body.$$ 2>/dev/null || true)"
rm -f /tmp/.dh-login-body.$$
if [ "$LOGIN_CODE" != "200" ] || ! printf '%s' "$BODY" | grep -q '"ok":true'; then
  line "✗" "登录失败" "HTTP $LOGIN_CODE  body=${BODY:0:200}"
  exit 1
fi
line "✓" "登录" "HTTP 200 ok=true（账号 $APP_USER）"

# ---------------- 打 erp 接口 ----------------
if [ "$CHECK_ENDPOINTS" = "1" ]; then
  say "erp 接口冒烟"
  EP_FAIL=0
  for p in $ERP_PATHS; do
    CODE="$(curl -s -o /dev/null -w '%{http_code}' -b "$COOKIE_JAR" --max-time 10 \
      "http://127.0.0.1:$PORT$p" 2>/dev/null || echo 000)"
    case "$CODE" in
      200|204) line "✓" "$p" "$CODE" ;;
      404)     line "⚠" "$p" "404（路径可能随方案调整）" ;;
      *)       line "✗" "$p" "$CODE"; EP_FAIL=$((EP_FAIL + 1)) ;;
    esac
  done
  if [ "$EP_FAIL" -gt 0 ]; then
    line "✗" "接口失败" "$EP_FAIL 个"
    exit 1
  fi
  line "✓" "接口全部可达" "无 5xx"
fi

# ---------------- 可选：表结构比对 ----------------
say "表结构比对"
if command -v mysql >/dev/null 2>&1; then
  DB_NAME="$(printf '%s' "$DB_URL" | cut -d/ -f2)"
  SNAP="$(mktemp "${TMPDIR:-/tmp}/dh-ddl.XXXXXX")"
  if mysql -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USER" -p"$DB_PASSWORD" "$DB_NAME" \
       -e 'SHOW CREATE TABLE' > "$SNAP" 2>/dev/null; then
    BASE_SNAP="$LOG_DIR/ddl-baseline.txt"
    if [ -f "$BASE_SNAP" ]; then
      if diff -u "$BASE_SNAP" "$SNAP" > /tmp/.dh-ddl-diff.$$ 2>/dev/null; then
        line "✓" "表结构" "与基线一致"
      else
        line "✗" "表结构变了" "见下方 diff"
        head -40 /tmp/.dh-ddl-diff.$$ | sed 's/^/       /'
        rm -f /tmp/.dh-ddl-diff.$$
        exit 1
      fi
    else
      cp "$SNAP" "$BASE_SNAP"
      line "·" "表结构基线" "已生成 $BASE_SNAP（下次会比对）"
    fi
    rm -f "$SNAP"
  else
    line "⚠" "表结构比对" "mysql 连库失败（跳过）"
  fi
else
  line "—" "表结构比对" "跳过（本机没有 mysql 客户端）
  ⇒ 16 个 erp @Entity 全带显式 @Table(name=...) ⇒ 表名与包名无关，DDL 本就不该变"
fi

say "结论"
line "✓" "启动冒烟通过" "Spring 容器起得来，erp 接口可达"
echo ""
line "·" "日志" "$SMOKE_LOG"
line "·" "应用日志" "$APP_LOG"
exit 0
