#!/usr/bin/env bash
#
# CI 门禁：一条命令跑完「不该进主干」的那些检查。
#
#   tools/ci-gate.sh              # 全跑
#   tools/ci-gate.sh --skip-fe    # 跳前端（只用后端）
#   tools/ci-gate.sh --skip-be    # 跳后端
#
# 为什么要有这个脚本（而不是把命令直接写进 yml）：
#   ① **门禁逻辑只有一份**。写在 yml 里的话，本地跑一遍和 CI 跑一遍是两条路径，
#      迟早会走偏。现在本地和 CI 调的是同一个脚本。
#   ② **可以验证门禁真的会红**。判据只有一个：注入已知错误 → 必须 exit 1。
#      「跑一下 exit 0」不能证明门禁有效 —— 本项目已经栽过一次
#      （暂存区里的校验器是个空壳，跑一下就 exit 0，什么都拦不住）。
#      验证方法见 tools/ci-gate-selftest.sh。
#   ③ CI 的日志噪音少：失败时 yml 里直接看到「哪一项、什么错」。
#
# 检查项及各自能拦住什么：
#   后端单测    —— 业务逻辑改错（328 个用例）
#   报警码校验  —— 字典写错（手写维护的 JSON，最容易漂）
#   校验器变异  —— 校验器自己失效（防「假绿」）
#   i18n 键     —— 少一个 key（界面会显示键路径原文，不报错、很难发现）
#   前端类型    —— 类型错误（历史上一直红着，等于没有门禁）
#   前端构建    —— 编译不过（这个不装前端就没法交付）
#
# 注意：这些检查**不需要数据库、不需要 MySQL**。
#   测试全是纯单测（没有 @SpringBootTest），字典校验和 i18n 是零依赖 node 脚本。
#   所以 CI 不必起 MySQL 容器 —— 少一个环境变量就少一处「本地能跑、CI 跑不了」。
#
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT" || exit 1

MVN="${MVN:-mvn}"
SKIP_BE=0
SKIP_FE=0
ONLY_OPCUA=0

for a in "$@"; do
  case "$a" in
    --skip-be) SKIP_BE=1 ;;
    --skip-fe) SKIP_FE=1 ;;
    --only-opcua) ONLY_OPCUA=1 ;;
    -h|--help) sed -n '2,32p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "未知参数：$a（--skip-be / --skip-fe / --only-opcua / -h）" >&2; exit 2 ;;
  esac
done

OK=0; FAIL=0
FAILED=()

say()  { printf '\n\033[1m== %s\033[0m\n' "$*"; }
line() { printf '  %s %-24s %s\n' "$1" "$2" "$3"; }

# 前置：工具在不在。
# 为什么单独查：「命令不存在」也会产生输出（"mvn: command not found"），
# 所以下面 check 函数的「断言有输出」抓不住它 —— 必须在这里先卡住。
# （2026-11-06 在 Linux 服务器上真跑时撞到：没装 mvn，测试没跑但门禁只报了别的错。）
for tool in node npm; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "缺少 $tool —— 门禁跑不了" >&2
    exit 2
  fi
done
if { [ "$SKIP_BE" -eq 0 ] || [ "$ONLY_OPCUA" -eq 1 ]; } \
   && ! command -v "$MVN" >/dev/null 2>&1 && [ ! -x "$MVN" ]; then
  echo "缺少 Maven（$MVN）—— 用 MVN=/path/to/mvn 指定" >&2
  exit 2
fi

# check <名称> <日志文件> <命令...>
# 关键：**必须断言脚本确有输出**。
#   光看退出码抓不住「校验器被改残、永远 exit 0」这种假绿
#   —— 这是本项目真实栽过的坑（见文件头注释）。
check() {
  local name="$1"; shift
  local log="$1"; shift
  local t0=$SECONDS
  mkdir -p "$(dirname "$log")"
  if "$@" >"$log" 2>&1; then
    if [ ! -s "$log" ]; then
      line "✗" "$name" "退出码 0 但没有任何输出 —— 门禁可能已失效（假绿）"
      FAIL=$((FAIL + 1)); FAILED+=("$name")
      return 1
    fi
    line "✓" "$name" "通过（$((SECONDS - t0)) 秒）"
    OK=$((OK + 1))
    return 0
  fi
  line "✗" "$name" "失败（$((SECONDS - t0)) 秒，日志 $log）"
  grep -aE "ERROR|FAIL|error|错误|失败|缺失|未通过" "$log" | tail -8 | sed 's/^/       /'
  FAIL=$((FAIL + 1)); FAILED+=("$name")
  return 1
}

START=$SECONDS
say "环境"
line "·" "node" "$(node -v 2>/dev/null || echo '缺失')"
line "·" "java"  "$(java -version 2>&1 | head -1)"
line "·" "npm"   "$(npm -v 2>/dev/null || echo '缺失')"

# ---------- 只跑「OPC UA 生产运行时类路径」这一步 ----------
# 为什么要有这个模式：
#   ① **自检需要它**。自检默认用 `--skip-be` 跑（不重跑 mvn test，快），
#      而 OPC UA 那道检查挂在 SKIP_BE 分支里 ⇒ 自检覆盖不到它
#      ⇒ 没人能证明「这道检查真的挂在门禁链路上」（变异 5 只验了脚本本身会红，
#      没验门禁会不会调它）。加了 --only-opcua，自检就能便宜地跑这一步。
#   ② **开发时也用得上**：只想确认「生产类路径这条路还通不通」时，
#      不用等整个门禁跑完。
# ⚠️ 它仍然要 maven（verify 脚本要算类路径），但**不需要 mvn test**。
if [ "$ONLY_OPCUA" -eq 1 ]; then
  say "OPC UA 生产运行时类路径"
  if [ ! -f tools/verify-opcua-boot4.sh ]; then
    line "✗" "OPC UA 生产类路径" "找不到 tools/verify-opcua-boot4.sh"
    FAIL=$((FAIL + 1)); FAILED+=("OPC UA 生产类路径")
  else
    check "OPC UA 生产类路径" "target/_ci-opcua.log" \
      bash tools/verify-opcua-boot4.sh "${OPCUA_PORT:-14840}"
  fi
  say "汇总"
  echo "  通过 $OK / 失败 $FAIL / 总耗时 $((SECONDS - START)) 秒"
  if [ "$FAIL" -gt 0 ]; then
    echo
    echo "  ⇒ 门禁不可信，必须先修" >&2
    exit 1
  fi
  exit 0
fi

# ================= 后端 =================
if [ "$SKIP_BE" -eq 0 ]; then
  say "后端单测"
  # 不用 -ntp（no transfer progress）：它会把输出压到几乎空白，
  # 出错时看不到「是哪一步失败」。CI 里慢一点换可诊断性，值得。
  check "后端单测" "target/_ci-test.log" \
    "$MVN" -B test
  # 断言真的跑了（而不是「跑了个 0」）：
  #   surefire 报告文件数 + 用例总数，两个都要 > 0。
  #   报告数 > 0 但用例数 = 0，说明测试被跳过了（例如环境里带了 -DskipTests）。
  REPORT_N=$(ls target/surefire-reports/*.txt 2>/dev/null | wc -l | tr -d ' ')
  CASE_N=$(grep -h "Tests run:" target/surefire-reports/*.txt 2>/dev/null \
           | sed -n 's/.*Tests run: \([0-9]\+\).*/\1/p' | awk '{s+=$1} END {print s+0}')
  if [ "$REPORT_N" -gt 0 ] && [ "$CASE_N" -gt 0 ]; then
    line "✓" "surefire 报告" "$REPORT_N 个文件 / $CASE_N 个用例"
  else
    line "✗" "surefire 报告" "报告 $REPORT_N 个 / 用例 $CASE_N 个 —— 测试可能没跑（假绿）"
    FAIL=$((FAIL + 1)); FAILED+=("surefire 报告")
  fi
else
  line "—" "后端单测" "跳过（--skip-be）"
fi

# ---------- OPC UA 生产运行时类路径验证 ----------
# ⚠️ **单元测试覆盖不到这一层**：`mvn test` 跑在**测试类路径**上，那条路径有
#   test 作用域的 sdk-server（本地起 OPC UA Server 用）。
#   而**交付到客户现场的类路径上没有它**，还有依赖作用域的区别
#   （jaxb-api 必须是 runtime/compile —— 放 test 的话单测照样绿，现场却炸）。
#   ⇒ 只有这个脚本能发现「作用域被误降级」「生产类路径缺依赖」这类问题。
#
# 为什么必须单独跑、不能只靠单测：
#   jaxb-api 在 test 作用域时，**单元测试照样全绿**（测试类路径上有它），
#   只有生产运行时才会炸 ⇒ 「测试绿」根本发现不了「作用域被改回 test」。
#
# ⚠️ **脚本必须跨平台**：classpath 分隔符按 `uname -s` 取（Windows `;` / Linux `:`），
#   写死 `;` 在 Linux 上会把整条类路径拼成一段 → Java 找不到类。
#   ⇒ **「本地全绿 ≠ CI 能跑」**（1.0.28 就是这么漏掉的）。
#   本地跑请用：`bash tools/verify-opcua-boot4.sh`
if [ "$SKIP_BE" -eq 0 ] && [ -f tools/verify-opcua-boot4.sh ]; then
  say "OPC UA 生产运行时类路径"
  # 端口用 14840（脚本默认值）。CI 上并发只有一个 job，不会撞。
  check "OPC UA 生产类路径" "target/_ci-opcua.log" \
    bash tools/verify-opcua-boot4.sh "${OPCUA_PORT:-14840}"
elif [ "$SKIP_BE" -eq 0 ]; then
  line "✗" "OPC UA 生产类路径" "找不到 tools/verify-opcua-boot4.sh"
  FAIL=$((FAIL + 1)); FAILED+=("OPC UA 生产类路径")
else
  line "—" "OPC UA 生产类路径" "跳过（--skip-be）"
fi

# ---------- 报警码字典（纯静态检查，不依赖后端/数据库）----------
# ⚠️ 刻意放在后端块**外面**：它既不需要 Java 也不需要数据库。
#   之前放在 --skip-be 里 → 自检用 --skip-be 跑时整个字典校验被跳过，
#   变异 4（把校验器改残）就抓不到门禁失效 → **自检自己骗了自己**。
#   （2026-11-06 踩过）
say "报警码字典"
if command -v node >/dev/null 2>&1; then
  check "报警码字典校验" "target/_ci-alarmcodes.log" \
    node scripts/check-alarmcodes.mjs
  say "校验器变异验证"
  check "校验器变异验证" "target/_ci-mutation.log" \
    node scripts/mutation-check-alarmcodes.mjs
else
  line "—" "报警码字典" "跳过（没有 node）"
fi

# ---------- 端口字面量检查（纯静态检查，不依赖后端/数据库）----------
# ⚠️ 刻意放在后端块**外面**：它既不需要 Java 也不需要数据库。
#   放回 --skip-be 里的话，自检用 --skip-be 跑时它会被跳过 ⇒ 没人能证明这道门禁还活着
#   （与报警码字典同一个理由：2026-11-06 踩过）。
#
# 拦什么：502 是 Modbus TCP 的端口。它曾被当成「所有协议的默认端口」写死 9 处，
#   于是「新建 OPC UA 设备不填端口 ⇒ 落库 502 ⇒ 驱动去连 502 ⇒ 必然连不上」。
#   口径已收进 ProtocolDefaults（唯一真相），这条门禁防它再漂回去。
#   前端有对称的那条（check-port-semantics.mjs），但它只看前端自己的文件 ⇒ 后端这条不能省。
say "端口字面量"
if command -v node >/dev/null 2>&1; then
  check "端口字面量检查" "target/_ci-port-literals.log" \
    node scripts/check-protocol-port-literals.mjs
else
  line "—" "端口字面量" "跳过（没有 node）"
fi

# ================= 前端 =================
if [ "$SKIP_FE" -eq 0 ]; then
  if [ ! -d frontend ]; then
    line "✗" "前端" "frontend/ 目录不存在"
    FAIL=$((FAIL + 1)); FAILED+=("前端")
  else
    cd frontend || exit 1
    say "安装依赖"
    # node_modules 已存在就跳过安装（CI 里不存在，所以 CI 一定会真跑 npm ci）。
    # 这样 tools/ci-gate-selftest.sh 连跑 8 次门禁时不会每次都花 77 秒重装。
    if [ -d node_modules ] && [ -z "${CI_FORCE_INSTALL:-}" ]; then
      line "—" "npm ci" "跳过（node_modules 已存在；要强制重装用 CI_FORCE_INSTALL=1）"
    elif [ -f package-lock.json ]; then
      check "npm ci" "../target/_ci-npm.log" npm ci --no-fund --no-audit
    else
      check "npm install" "../target/_ci-npm.log" npm install --no-fund --no-audit
    fi

    say "前端检查"
    check "类型检查" "../target/_ci-typecheck.log" npm run typecheck
    check "前端构建" "../target/_ci-build.log" npm run build
    # 端口语义门禁：防「表单把未填端口兜成 502」这个坑被改回来。
    # 源码断言 + element-plus 真实行为断言（哨兵 null 不被夹、0 会被夹成 1）。
    check "端口语义门禁" "../target/_ci-port.log" npm run check:port
    cd "$ROOT" || exit 1
  fi

  say "i18n 键完整性"
  if command -v node >/dev/null 2>&1; then
    check "i18n 键检查" "target/_ci-i18n.log" node scripts/check-i18n.mjs
  else
    line "—" "i18n 键检查" "跳过（没有 node）"
  fi
else
  line "—" "前端" "跳过（--skip-fe）"
fi

say "汇总"
echo "  通过 $OK / 失败 $FAIL / 总耗时 $((SECONDS - START)) 秒"
if [ "$FAIL" -gt 0 ]; then
  echo
  echo "  挂了：" >&2
  for n in "${FAILED[@]}"; do echo "    - $n" >&2; done
  exit 1
fi
echo "  全部通过"
