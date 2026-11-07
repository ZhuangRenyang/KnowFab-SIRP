#!/usr/bin/env bash
# 采集规模压测（L1 调度层）——场景矩阵 + 负向对照。
#
# 用法:
#   bash tools/bench-collect-scale.sh              # 跑完整矩阵（较久）
#   bash tools/bench-collect-scale.sh --quick      # 快速版（缩短预热/测量，用于冒烟）
#   bash tools/bench-collect-scale.sh --only S2    # 只跑某个场景
#
# ⚠️ 这个脚本**不跑在 CI 里**（CI 门禁查 13 项，不含压测）——
#   压测耗时且要占机器资源，但它恰恰是最容易「测不出问题」的那种检查，
#   所以必须有一份**可复现的入口**，而不是「我本地跑过一次」。
set -u

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

JAVA_HOME="${JAVA_HOME:-/c/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot}"
export JAVA_HOME
PATH="$JAVA_HOME/bin:$PATH"
export PATH

MVN="${MVN:-$(cd "$ROOT/.." && pwd)/tools/apache-maven-3.9.9/bin/mvn}"
CP_FILE="target/test-cp.txt"
LOG_DIR="target/bench"
mkdir -p "$LOG_DIR"

QUICK=0
ONLY=""
while [ $# -gt 0 ]; do
  case "$1" in
    --quick) QUICK=1 ;;
    --only)  ONLY="$2"; shift ;;
    *) echo "未知参数: $1"; exit 2 ;;
  esac
  shift
done

# 预热/测量时长：完整版按 QA 口径（预热 >= 30s、测量 >= 120s），
# 快速版只用于「脚本能跑通」的冒烟，**它的结果不能作为性能结论**。
if [ "$QUICK" = "1" ]; then
  WARMUP=5; MEASURE=20
  QUICK_NOTE="\n  ⚠️ **快速模式：预热/测量被缩短到 ${WARMUP}s/${MEASURE}s**"
  QUICK_NOTE="${QUICK_NOTE}\n     ⇒ 错峰铺开还没完成、样本量也不够 ⇒ **这些数字不是性能结论**，"
  QUICK_NOTE="${QUICK_NOTE}\n     只用来验证「脚本能跑通 / 工具没坏」。"
else
  WARMUP=30; MEASURE=120
  QUICK_NOTE=""
fi

say() { printf '%s\n' "$*"; }

say "=========================================================="
say " 采集规模压测 L1（场景矩阵）"
say " 设备数=1000 池=32 目标周期=1000ms 预热=${WARMUP}s 测量=${MEASURE}s"
printf '%b' "$QUICK_NOTE"
say "=========================================================="

# ---- 编译 + classpath ----
say "[1/3] 编译测试类并生成 classpath ..."
# ⚠️ **不要把 mvn 的输出管道进 grep 再取 $?** ——
#   管道的退出码是 grep 的，不是 mvn 的 ⇒ mvn 失败了也会被当成成功。
#   ⇒ 这里直接跑 mvn，把日志落文件，再单独 grep；判定看文件内容 + class 文件是否存在。
BUILD_LOG="$LOG_DIR/build.log"
if ! "$MVN" -o -q test-compile > "$BUILD_LOG" 2>&1; then
  say "  ✗ 编译失败（详见 $BUILD_LOG）："
  grep -a -F ".java:[" "$BUILD_LOG" | head -10
  exit 2
fi
if ! "$MVN" -o dependency:build-classpath -Dmdep.outputFile="$CP_FILE" -Dmdep.includeScope=test \
      > "$LOG_DIR/cp.log" 2>&1; then
  say "  ✗ classpath 生成失败（详见 $LOG_DIR/cp.log）"
  exit 2
fi
if [ ! -s "$CP_FILE" ]; then
  say "  ✗ classpath 生成失败（$CP_FILE 为空）—— 先修构建，别去查压测"
  exit 2
fi
# ⚠️ class 文件必须**真的存在** —— 上一次矩阵就是「编译失败被 grep 吞掉」，
#   然后 5 个场景全部以 ClassNotFound 失败，而汇总只说「未通过」。
if [ ! -f "target/test-classes/com/factory/hub/collect/CollectScaleBench.class" ]; then
  say "  ✗ CollectScaleBench 没编出来 —— 先修构建，别去查压测"
  exit 2
fi
if [ ! -f "target/test-classes/com/factory/hub/collect/CollectDbScaleBench.class" ]; then
  say "  ✗ CollectDbScaleBench 没编出来 —— 先修构建，别去查压测"
  exit 2
fi
CP="target/classes;target/test-classes;$(cat "$CP_FILE")"

# ---- 场景表 ----
# 场景命名与含义（来自「1000 台设备能否支撑 1 秒周期」的推演）：
#   S1 零延迟反向对照 —— 没有排队也不该超期；超了说明**调度器**有问题
#   S2 局域网快设备   —— I/O 30ms，真实 PLC 的常见量级
#   S3 现场慢设备     —— I/O 50ms
#   S4 重负载         —— I/O 100ms
#   S5 抖动           —— 30ms + 0~30ms 抖动，贴近真实网络
#   S6 负向对照       —— 常量值驱动，**必须红**（验证 D-1 陷阱被护栏拦住）
# S7 刻意带 DB 成本：S1~S5 的 DB 耗时都是 0 ⇒ **锁等待的判定分支在交付物里从不触发**
#   （QA 复核指出：那条护栏写了但没被任何交付场景验证过 ⇒ 等于装饰性的）。
#   ⇒ S7 让 todayOutputOf 真的花 1ms ⇒ 锁开始排队 ⇒ 验证 P99 锁等待的判据。
SCENARIOS="S1 S2 S3 S4 S5 S6 S7 S3P"

run_scene() {
  local name="$1"; shift
  local threads="${BENCH_THREADS:-32}"
  if [ -n "$ONLY" ] && [ "$ONLY" != "$name" ]; then return 0; fi
  local log="$LOG_DIR/$name.log"
  say ""
  say "---- 场景 $name : $* ----"
  "$JAVA_HOME/bin/java" -cp "$CP" com.factory.hub.collect.CollectScaleBench \
      --devices 1000 --threads "$threads" --poll-ms 1000 \
      --warmup "$WARMUP" --measure "$MEASURE" "$@" > "$log" 2>&1
  local rc=$?
  grep -a -E "^( |\s*\*|\s*❌|\s*✅| 结论| 负向对照)" "$log" | grep -a -v "^10:" | head -40
  say "  [$name] 退出码=$rc  日志: $log"
  return $rc
}

FAILED=""
say "[2/3] 跑场景矩阵 ..."
run_scene S1 --io-ms 0                     || FAILED="$FAILED S1"
run_scene S2 --io-ms 30                    || FAILED="$FAILED S2"
run_scene S3 --io-ms 50                    || FAILED="$FAILED S3"
run_scene S4 --io-ms 100                   || FAILED="$FAILED S4"
run_scene S5 --io-ms 30 --jitter-ms 30     || FAILED="$FAILED S5"
# S3'：与 S3 完全相同，只把池从 32 调到 64 ⇒ 验证「加线程」这条路径
BENCH_THREADS=64 run_scene S3P --io-ms 50   || FAILED="$FAILED S3P"
# S7：带 DB 成本（1ms/次）⇒ 让全局锁真的排队 ⇒ 验证锁等待的判据
run_scene S7 --io-ms 0 --db-ms 1           || FAILED="$FAILED S7"

# S6 是负向对照：**它必须红**（exit 2 表示护栏没生效），
# 所以这里用 --expect-fail 让工具把「如期变红」记成成功。
say ""
say "---- 场景 S6 : 负向对照（常量值驱动，必须变红）----"
"$JAVA_HOME/bin/java" -cp "$CP" com.factory.hub.collect.CollectScaleBench \
    --devices 200 --threads 32 --poll-ms 1000 --warmup 3 --measure 10 \
    --const-values --expect-fail > "$LOG_DIR/S6.log" 2>&1
rc=$?
grep -a -E "负向对照|❌|\[注\]" "$LOG_DIR/S6.log" | head -10
say "  [S6] 退出码=$rc（期望 0 = 如期变红）"
if [ "$rc" != "0" ]; then
  FAILED="$FAILED S6"
  say "  ✗ S6 没有如期变红 ⇒ 护栏没生效 ⇒ 本轮全部结论作废"
fi

say ""
say "[3/3] 汇总 ..."
if [ -n "$FAILED" ]; then
  say " ❌ 未通过的场景:$FAILED"
  say "    日志在 $LOG_DIR/"
  exit 1
fi
say " ✅ 全部场景通过（日志在 $LOG_DIR/）"
if [ "$QUICK" = "1" ]; then
  say ""
  say " ⚠️ **这是快速模式：上面的数字不是性能结论**，只证明脚本与工具能跑通。"
  say "    要出结论请跑完整模式（预热 30s + 测量 120s）。"
fi
say ""
say " ⚠️ 提醒：L1 通过**不等于**「1000 台够用」。"
say "    L1 不测真实 PLC 网络抖动，也不测真实 MySQL 的并发上限。"
say "    结论要等 L2（真实数据库）跑完才能下。"
exit 0
