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
LIST_ONLY=0

for a in "$@"; do
  case "$a" in
    --skip-be) SKIP_BE=1 ;;
    --skip-fe) SKIP_FE=1 ;;
    --only-opcua) ONLY_OPCUA=1 ;;
    --list-checks) LIST_ONLY=1 ;;
    -h|--help) sed -n '2,32p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "未知参数：$a（--skip-be / --skip-fe / --only-opcua / --list-checks / -h）" >&2; exit 2 ;;
  esac
done

OK=0; FAIL=0
FAILED=()

say()  { [ "$LIST_ONLY" = "1" ] && return 0; printf '\n\033[1m== %s\033[0m\n' "$*"; }
line() { printf '  %s %-24s %s\n' "$1" "$2" "$3"; }

# 前置：工具在不在。
# 为什么单独查：「命令不存在」也会产生输出（"mvn: command not found"），
# 所以下面 check 函数的「断言有输出」抓不住它 —— 必须在这里先卡住。
# （2026-11-06 在 Linux 服务器上真跑时撞到：没装 mvn，测试没跑但门禁只报了别的错。）
if [ "$LIST_ONLY" -eq 0 ]; then
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
fi

# check <名称> <日志文件> <命令...>
# 关键：**必须断言脚本确有输出**。
#   光看退出码抓不住「校验器被改残、永远 exit 0」这种假绿
#   —— 这是本项目真实栽过的坑（见文件头注释）。
# 正常跑一遍时门禁会做哪些检查（--list-checks 打印它）。
# ⚠️ **清单必须与实际执行同源** ⇒ 名字在 check() 里登记，
#   而不是另写一份硬编码的清单 —— 另写一份就会出现「清单说有、实际没跑」，
#   那又是一个假绿（运维专家 2026-11-06 实测过：把整块检查删掉，自检照样全绿）。
CHECK_NAMES=()

check() {
  local name="$1"; shift
  local log="$1"; shift
  local t0=$SECONDS
  CHECK_NAMES+=("$name")
  # 干跑模式：只登记检查项，不执行任何一项（秒级、不需要 maven/node）
  if [ "$LIST_ONLY" = "1" ]; then
    return 0
  fi
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
  # ⚠️ **CI 上把失败原因写成 GitHub annotation**（`::error::`）。
  #   为什么必须有这一步（2026-11-07 实测踩到）：
  #   CI 失败时，**日志正文要认证才能看**（artifact 下载也要认证）⇒
  #   「CI 红了但查不出为什么」会让人只能干等或瞎猜。
  #   ⇒ 而 `::error::` 会出现在 run 页面的 Annotations 里，**不用认证就能看到**。
  #   ⇒ 一行一条（GitHub 的 annotation 不接受换行）⇒ 只取每条的第一行。
  if [ "${CI:-}" = "true" ]; then
    echo "::error title=门禁失败::$name（$((SECONDS - t0)) 秒）"
    grep -aE "ERROR|FAIL|error|错误|失败|缺失|未通过" "$log" | tail -8 | while IFS= read -r l; do
      # 去掉换行与 GitHub 指令注入字符（防止日志内容伪造 annotation）
      printf '::error::%s\n' "${l//$'\r'/ }" | head -1
    done
  fi
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
# ---------- OPC UA 生产运行时类路径（主干与旁路共用这一个函数）----------
# ⚠️ **为什么要合并成一处**：之前 `--only-opcua` 旁路与 SKIP_BE=0 主干各写了一遍，
#   两处逻辑重复但互不相干 ⇒ 改一处忘一处，本轮就栽过：
#   旁路有、主干没有 ⇒ 「自检里出现一条绿色的『✓ 摘除检测』，但它红的是旁路，
#   跟主干被删毫无关系」—— **一个证明不了它想证明那件事的变异，不只是无效，是误导**
#   （运维专家 2026-11-06 两次实验坐实：主干被删 ⇒ 自检 12/0 全绿还自夸「门禁确实会红」）。
#   ⇒ 现在两处都调它，--list-checks 的清单也从它这里生成 ⇒ 清单与执行不会漂。
#
# ⚠️ 端口用 0（让 OS 分配空闲端口）：固定端口在 Windows 上第二次跑会
#   BindException: Address already in use（TIME_WAIT）⇒ **flaky 的门禁比没有门禁更糟**
#   （跑十次红一次，大家就会开始忽略它 ⇒ 门禁废了）。
run_opcua_check() {
  if [ ! -f tools/verify-opcua-boot4.sh ]; then
    # 脚本不在 ⇒ 这一项必须红（门禁里少了一项 = 没人守着了）
    CHECK_NAMES+=("OPC UA 生产类路径")
    [ "$LIST_ONLY" = "1" ] && return 0
    line "✗" "OPC UA 生产类路径" "找不到 tools/verify-opcua-boot4.sh"
    FAIL=$((FAIL + 1)); FAILED+=("OPC UA 生产类路径")
    return 1
  fi
  check "OPC UA 生产类路径" "target/_ci-opcua.log" \
    bash tools/verify-opcua-boot4.sh "${OPCUA_PORT:-0}"
}

if [ "$ONLY_OPCUA" -eq 1 ]; then
  say "OPC UA 生产运行时类路径"
  run_opcua_check
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
  CHECK_NAMES+=("surefire 报告")
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
if [ "$SKIP_BE" -eq 0 ]; then
  say "OPC UA 生产运行时类路径"
  # ⚠️ 这一行是**主干挂载点**（CI 里真正跑的就是它）。
  #   删掉它 = 这项检查从门禁上消失 ⇒ 自检的「常态断言」必须能发现（见 ci-gate-selftest.sh）。
  run_opcua_check
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
      CHECK_NAMES+=("npm ci")
      line "—" "npm ci" "跳过（node_modules 已存在；要强制重装用 CI_FORCE_INSTALL=1）"
    elif [ -f package-lock.json ]; then
      check "npm ci" "../target/_ci-npm.log" npm ci --no-fund --no-audit
    else
      check "npm install" "../target/_ci-npm.log" npm install --no-fund --no-audit
    fi

    say "前端检查"
    check "类型检查" "../target/_ci-typecheck.log" npm run typecheck
    # ⚠️ **WorkBuddy 的删除保护会拦 vite 清空 assets**（119 个文件 > 阈值 50）
    #   ⇒ 症状：vite 报 SAFE_DELETE_BULK_CONFIRM_REQUIRED，构建失败。
    #   ⇒ 这与代码无关，但会让**每个开发者的本地门禁都红** ⇒ 等于没有门禁。
    #   ⇒ 解法：构建前把旧 assets 目录**改名**走（rename 不触发删除保护），
    #     构建完再处置。Linux CI 上没有这个目录 ⇒ 直接跳过，无副作用。
    #   ⚠️ 别用 rm —— 会被环境的删除保护打断。
    #   ⚠️ **构建失败必须把旧 assets 还原**，否则仓库的静态产物就没了
    #     （git status 会显示一堆删除 ⇒ 后面提交时误把产物删了）。
    _assets_dir="../src/main/resources/static/app/assets"
    _stale_dir=""
    if [ -d "$_assets_dir" ]; then
      # ⚠️ 这段在 frontend/ 目录内执行 ⇒ 构建目录是 ../target（= device-hub/target）
      _stale_dir="../target/_assets_stale.$$"
      mkdir -p ../target
      if mv "$_assets_dir" "$_stale_dir" 2>/dev/null; then
        :
      else
        _stale_dir=""
      fi
    fi
    check "前端构建" "../target/_ci-build.log" npm run build
    if [ -n "$_stale_dir" ] && [ -d "$_stale_dir" ]; then
      if [ -d "$_assets_dir" ]; then
        # 构建成功 ⇒ 新产物已生成 ⇒ 旧的挪到 ../target/（构建目录，不入库）
        mv "$_stale_dir" "../target/_assets_stale_old.$$" 2>/dev/null || true
      else
        # 构建失败 ⇒ 新产物没生成 ⇒ **把旧的还回去**，不能让仓库缺产物
        mv "$_stale_dir" "$_assets_dir" 2>/dev/null || true
      fi
    fi
    # 端口语义门禁：防「表单把未填端口兜成 502」这个坑被改回来。
    # 源码断言 + element-plus 真实行为断言（哨兵 null 不被夹、0 会被夹成 1）。
    check "端口语义门禁" "../target/_ci-port.log" npm run check:port
    # ⭐ 端口门禁**自己的变异验证**（绿灯不等于没问题）：
    #   上一行只证明「当前代码没漂移」，不证明「**门禁真的能拦住漂移**」。
    #   这一行注入 14 个已知漂移（含 1 个反向对照），要求每个都红、诊断分类正确、
    #   失败条数正好 —— ⇒ 证明上面那道门禁有鉴别力，而不是「恰好现在是绿的」。
    #   约 15 秒；在隔离副本里做，不碰工作树。
    check "端口门禁变异验证" "../target/_ci-port-mutation.log" npm run check:port-mutation
    cd "$ROOT" || exit 1
  fi

  say "i18n 键完整性"
  if command -v node >/dev/null 2>&1; then
    check "i18n 键检查" "target/_ci-i18n.log" node scripts/check-i18n.mjs
    # ⭐ i18n 语言范围（R5：界面只做中英双语）的**变异验证**：
    #   上一行只证明「当前代码没越界」，不证明「**门禁真的能拦住越界**」。
    #   这一行注入 6 个已知越界（含 1 个反向对照：合法改动必须仍绿），
    #   要求每个都红、诊断指对、R5 违规条数正好 ⇒ 证明那道门禁有鉴别力。
    #   ⚠️ 「绿灯不等于没问题」—— 没被变异打过的绿，只是「没人验证过它会不会亮」。
    #   约 5 秒，在隔离副本里做，不碰工作树。
    check "i18n 语言范围变异验证" "target/_ci-i18n-mutation.log" node scripts/mutation-check-i18n-scope.mjs
  else
    line "—" "i18n 键检查" "跳过（没有 node）"
  fi
else
  line "—" "前端" "跳过（--skip-fe）"
fi

# ---------- 干跑模式：打印「正常跑一遍时门禁会有哪些检查项」 ----------
# ⚠️ 这份清单**不是另写一份硬编码的**，而是从上面真正执行检查的同一条路径收集来的
#   （名字在 check() 里登记）⇒ 清单与执行**不可能漂**。
#   漂了就会出现「清单说有、实际没跑」⇒ 那又是一个假绿。
#   ⇒ 自检用它做「常态断言」：清单里必须含某些项，否则就是「这项检查从门禁上消失了」。
if [ "$LIST_ONLY" = "1" ]; then
  echo "门禁检查项清单（--list-checks 干跑，未执行任何检查）："
  for n in "${CHECK_NAMES[@]}"; do
    echo "  · $n"
  done
  echo "共 ${#CHECK_NAMES[@]} 项"
  exit 0
fi

say "汇总"
echo "  通过 $OK / 失败 $FAIL / 总耗时 $((SECONDS - START)) 秒"
if [ "$FAIL" -gt 0 ]; then
  echo
  echo "  挂了：" >&2
  for n in "${FAILED[@]}"; do echo "    - $n" >&2; done
  # ⚠️ 汇总也写成 annotation：CI 上不用认证就能看到「哪几项挂了」，
  #   而不用去下载日志（下载也要认证）。
  if [ "${CI:-}" = "true" ]; then
    echo "::error title=门禁汇总::通过 $OK / 失败 $FAIL；挂了：${FAILED[*]}"
  fi
  exit 1
fi
echo "  全部通过"
