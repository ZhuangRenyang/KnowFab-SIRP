#!/usr/bin/env bash
# 采集规模压测 **L2 / L4**（真实 MySQL）—— L1 场景矩阵见 tools/bench-collect-scale.sh。
#
# 用法:
#   bash tools/bench-db-scale.sh              # 跑 L2 + L4
#   bash tools/bench-db-scale.sh --only L4    # 只跑 L4
#
# ⚠️ 为什么要有这个脚本（2026-11-07）：
#   L2 与 L4 之前**没有可复现的入口** ⇒ 每次重跑都得手敲一长串 java -cp 命令
#   ⇒ 命令一改（比如改了 classpath 的分隔符）就没人跑得出来 ⇒ **结论不可复现**。
#   ⇒ 与「结论要能复现」这条原则直接冲突。
#
# ⚠️ 这两个脚本**不跑在 CI 里**（压测耗时且要占机器资源）：
#   但它们恰恰是最容易「测不出问题」的那种检查 ⇒ 必须有**可复现的入口**，
#   而不是「我本地跑过一次」。
set -u

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

JAVA_HOME="${JAVA_HOME:-/c/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot}"
export JAVA_HOME
PATH="$JAVA_HOME/bin:$PATH"
export PATH

MVN="${MVN:-$(cd "$ROOT/.." && pwd)/tools/apache-maven-3.9.9/bin/mvn}"
LOG_DIR="target-logs/bench"
mkdir -p "$LOG_DIR"

ONLY=""
while [ $# -gt 0 ]; do
  case "$1" in
    --only) ONLY="$2"; shift ;;
    *) echo "未知参数: $1"; exit 2 ;;
  esac
  shift
done

say() { printf '%s\n' "$*"; }

# ---- 编译 + classpath ----
say "[1/3] 编译测试类并生成 classpath ..."
BUILD_LOG="$LOG_DIR/build-db.log"
if ! "$MVN" -o -q test-compile > "$BUILD_LOG" 2>&1; then
  say "  ✗ 编译失败（详见 $BUILD_LOG）："
  grep -a -F ".java:[" "$BUILD_LOG" | head -10
  exit 2
fi
CP_FILE="target/test-cp.txt"
if ! "$MVN" -o dependency:build-classpath -Dmdep.outputFile="$CP_FILE" -Dmdep.includeScope=test \
      > "$LOG_DIR/cp-db.log" 2>&1; then
  say "  ✗ classpath 生成失败（详见 $LOG_DIR/cp-db.log）"
  exit 2
fi
if [ ! -s "$CP_FILE" ]; then
  say "  ✗ classpath 生成失败（$CP_FILE 为空）—— 先修构建，别去查压测"
  exit 2
fi
# ⚠️ class 文件必须**真的存在** —— 否则运行时会报 ClassNotFound，
#   而日志里堆一大段异常，看不出是「工具没编出来」还是「压测不达标」。
if [ ! -f "target/test-classes/com/factory/hub/collect/CollectDbScaleBench.class" ]; then
  say "  ✗ CollectDbScaleBench 没编出来 —— 先修构建，别去查压测"
  exit 2
fi
if [ ! -f "target/test-classes/com/factory/hub/collect/ProdRollupScaleBench.class" ]; then
  say "  ✗ ProdRollupScaleBench 没编出来 —— 先修构建，别去查压测"
  exit 2
fi
CP="target/classes;target/test-classes;$(cat "$CP_FILE")"

DEVICES="${DEVICES:-1000}"
POOL="${POOL:-10}"
POLL_MS="${POLL_MS:-1000}"
ROUNDS="${ROUNDS:-3}"

# ---- L2：真实 MySQL + 真实连接池的三形态对比 ----
if [ -z "$ONLY" ] || [ "$ONLY" = "L2" ]; then
  say ""
  say "[2/3] L2：真实 MySQL 三形态对比（改造前 / 只去锁 / 改造后）..."
  "$JAVA_HOME/bin/java" -cp "$CP" com.factory.hub.collect.CollectDbScaleBench \
      --devices "$DEVICES" --pool "$POOL" --poll-ms "$POLL_MS" --rounds "$ROUNDS" \
      > "$LOG_DIR/L2.log" 2>&1
  rc=$?
  grep -a -E "^( ---- |  第 |  单次|  批量| 一次批量|  连接池|  等待| ✅| ❌)" "$LOG_DIR/L2.log" | head -30
  say "  [L2] 退出码=$rc  日志: $LOG_DIR/L2.log"
  if [ $rc -ne 0 ]; then
    say "  ✗ L2 失败"
    exit $rc
  fi
fi

# ---- L4：真实 ProdRollup 类 + 真实 MySQL 的改造前后对比 ----
if [ -z "$ONLY" ] || [ "$ONLY" = "L4" ]; then
  say ""
  say "[3/3] L4：真实 ProdRollup 类（改造前 vs 改造后）..."
  "$JAVA_HOME/bin/java" -cp "$CP" com.factory.hub.collect.ProdRollupScaleBench \
      --devices "$DEVICES" --pool "$POOL" --poll-ms "$POLL_MS" --rounds "$ROUNDS" \
      > "$LOG_DIR/L4.log" 2>&1
  rc=$?
  grep -a -E "^( ---- |  第 |  一轮|  锁等待|  每轮| ✅| ❌)" "$LOG_DIR/L4.log" | head -30
  say "  [L4] 退出码=$rc  日志: $LOG_DIR/L4.log"
  if [ $rc -ne 0 ]; then
    say "  ✗ L4 失败"
    exit $rc
  fi
fi

# ---- 核对：报告里的数字必须与日志一致 ----
say ""
say "核对报告与日志 ..."
if [ -f "docs/COLLECT-SCALE-BENCH.md" ]; then
  if node tools/bench-report.mjs --check docs/COLLECT-SCALE-BENCH.md > "$LOG_DIR/report-check.log" 2>&1; then
    say "  ✓ 报告与日志一致"
  else
    say "  ✗ 报告与日志不一致（报告在骗人）—— 详见 $LOG_DIR/report-check.log："
    grep -a "✗\|过期\|不一致" "$LOG_DIR/report-check.log" | head -20
    exit 1
  fi
else
  say "  （没有 docs/COLLECT-SCALE-BENCH.md ⇒ 跳过核对）"
fi

say ""
say "完成。"
