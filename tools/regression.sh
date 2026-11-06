#!/usr/bin/env bash
#
# 一条命令跑完所有业务回归。
#
#   tools/regression.sh                 # 全套（约 10 分钟，含 6 分钟断网演练）
#   tools/regression.sh --quick         # 跳过断网演练（约 3 分钟）
#   tools/regression.sh --keep-going    # 某个挂了也继续跑，最后统一汇总
#   JAR=path/to.jar tools/regression.sh
#
# 为什么要有这个东西：
#   发版前要跑 4 个脚本，每个都自己起进程、都要 8081。以前靠人记得跑哪几个、按什么顺序，
#   而「靠记忆的流程」迟早被跳过 —— 1.0.28 没发布就是这么漏的（本地全绿，Release 没传）。
#   现在一条命令，跑完给结论。
#
# 顺序不是随便排的：
#   dh03 会 **清库并重新灌演示数据**，所以必须放最后，否则它会把前面几个脚本的数据清掉。
#
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT" || exit 1

JAR="${JAR:-target/device-hub.jar}"
PY="${PYTHON:-python}"
QUICK=0
KEEP_GOING=0

for a in "$@"; do
  case "$a" in
    --quick)      QUICK=1 ;;
    --keep-going) KEEP_GOING=1 ;;
    -h|--help)    sed -n '2,16p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *)            echo "未知参数：$a（--quick / --keep-going / -h）" >&2; exit 2 ;;
  esac
done

[ -f "$JAR" ] || { echo "找不到 $JAR —— 先 mvn package，或用 JAR=... 指定" >&2; exit 2; }
mkdir -p target

OK=0; FAIL=0; SKIP=0
FAILED_NAMES=()

say()  { printf '\n\033[1m== %s\033[0m\n' "$*"; }
line() { printf '  %s %-26s %s\n' "$1" "$2" "$3"; }

# 前置：8081 必须空闲，否则第一个脚本起来就撞端口，后面全是噪声
say "前置检查"
if curl -s -m 2 -o /dev/null "http://127.0.0.1:8081/api/system/version" 2>/dev/null; then
  echo "  8081 已被占用 —— 先停掉那个实例（本脚本自己会起进程，不需要你提前起）" >&2
  exit 2
fi
line "OK" "8081 空闲" ""
line "OK" "jar" "$(basename "$JAR") $(du -h "$JAR" | cut -f1)"

run_one() {
  local name="$1"; shift
  local log="target/_reg-$(echo "$name" | tr ' /' '__').log"
  local t0=$SECONDS
  say "$name"
  if "$@" >"$log" 2>&1; then
    line "✓" "$name" "通过（$((SECONDS - t0)) 秒）"
    OK=$((OK + 1))
    return 0
  else
    line "✗" "$name" "失败（$((SECONDS - t0)) 秒，日志 $log）"
    grep -aE "FAIL|小结|挂了" "$log" | tail -6 | sed 's/^/       /'
    FAIL=$((FAIL + 1))
    FAILED_NAMES+=("$name")
    if [ "$KEEP_GOING" -eq 0 ]; then
      echo
      echo "  遇错即停（要跑完所有再加 --keep-going）" >&2
      return 1
    fi
    return 0
  fi
}

START=$SECONDS

# 用 while 包一层，是为了能 `break` 实现 fail-fast：
# run_one 在「失败且没加 --keep-going」时返回 1，`|| break` 就停下来，
# 但后面的「汇总」仍会执行（不会因为一次失败就什么都不告诉你）。
while :; do
  # ---- 需量摊平（DH-86）----
  run_one "DH-86 需量摊平" "$PY" tools/verify-dh86.py "$JAR" || break

  # ---- 工作流（DH-85）----
  run_one "DH-85 工作流" "$PY" tools/verify-dh85.py "$JAR" || break

  # ---- 断网演练（DH-07 可自动化部分，6 分钟）----
  if [ "$QUICK" -eq 1 ]; then
    line "—" "断网演练" "跳过（--quick）"
    SKIP=$((SKIP + 1))
  else
    run_one "断网演练" "$PY" tools/drill-offline.py --jar "$JAR" --offline-min 6 || break
  fi

  # ---- 关演示数据（DH-03）—— 会清库，必须最后 ----
  run_one "DH-03 关演示数据" "$PY" tools/verify-dh03-demo.py --jar "$JAR" || break
  break
done

say "汇总"
echo "  通过 $OK / 失败 $FAIL / 跳过 $SKIP"
echo "  总耗时 $((SECONDS - START)) 秒"
if [ "$FAIL" -gt 0 ]; then
  echo
  echo "  挂了：" >&2
  for n in "${FAILED_NAMES[@]}"; do echo "    - $n" >&2; done
  echo
  echo "  日志都在 target/_reg-*.log" >&2
  exit 1
fi
echo "  全部通过"
