#!/usr/bin/env bash
# erp 拆包：**每批**的验收脚本（秒级，不编译、不连数据库）。
#
# ⭐ 为什么要有它：
#   拆包是 6 批串行改动，每批都要判断「这批能不能留、要不要回退」。
#   而 `mvn clean test` 要 60 秒且由后端/QA 跑 ⇒ 迭代过程中需要一个**秒级**的判据。
#   ⇒ 本脚本跑的是**纯静态**检查：文件数守恒 / 悬空 import / 残留全限定名 / 包结构。
#   ⇒ 它**不能**替代 mvn test，也不能替代「真启动一次」。
#
# ⚠️ 它**不跑** mvn、不改任何文件、不动 git。
#   ⇒ 后端/QA 跑完 `mvn clean test` 之后，本脚本会顺带断言 surefire 的
#     **报告数 / 用例数**没有变（挪包不该改变它们；变了 = 漏收集/重复收集）。
#
# 用法：
#   tools/verify-batch-acceptance.sh [--batch N] [--expect-reports 51] [--expect-cases 409]
#                                   [--skip-surefire]
#
# 退出码：0 = 全部通过；1 = 有项目没过；2 = 用法/环境错误

set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT" || exit 2

# 单测规模基线：**读 tools/test-baseline.json**（与门禁同一份 ⇒ 不会出现两个真相）
# 默认值只在基线文件缺失时兜底；基线文件是唯一真相。
EXPECT_REPORTS=""
EXPECT_CASES=""
BATCH=""
SKIP_SUREFIRE=0

while [ $# -gt 0 ]; do
  case "$1" in
    --batch)          BATCH="${2:-}"; shift 2 ;;
    --expect-reports) EXPECT_REPORTS="${2:-}"; shift 2 ;;
    --expect-cases)   EXPECT_CASES="${2:-}"; shift 2 ;;
    --skip-surefire)  SKIP_SUREFIRE=1; shift ;;
    -h|--help)        sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "未知参数：$1" >&2; exit 2 ;;
  esac
done

command -v node >/dev/null 2>&1 || { echo "缺少 node —— 验收跑不了" >&2; exit 2; }

BASE_FILE="$ROOT/tools/test-baseline.json"
if [ -z "$EXPECT_REPORTS" ] || [ -z "$EXPECT_CASES" ]; then
  if [ -f "$BASE_FILE" ]; then
    [ -z "$EXPECT_REPORTS" ] && EXPECT_REPORTS="$(sed -n 's/.*"reports"[[:space:]]*:[[:space:]]*\([0-9]\+\).*/\1/p' "$BASE_FILE" | head -1)"
    [ -z "$EXPECT_CASES" ]   && EXPECT_CASES="$(sed -n 's/.*"cases"[[:space:]]*:[[:space:]]*\([0-9]\+\).*/\1/p' "$BASE_FILE" | head -1)"
  fi
fi
# 兜底：基线文件缺失且没显式指定 ⇒ 用实测值，并明确提示
[ -z "$EXPECT_REPORTS" ] && EXPECT_REPORTS=51
[ -z "$EXPECT_CASES" ]   && EXPECT_CASES=409
if [ ! -f "$BASE_FILE" ] && [ -z "${2:-}" ]; then
  echo "提示：$BASE_FILE 不存在 ⇒ 本次用兜底值 $EXPECT_REPORTS/$EXPECT_CASES" >&2
fi

PASS=0; FAIL=0; FAILED=()
say()  { printf '\n\033[1m== %s\033[0m\n' "$*"; }
line() { printf '  %s %-26s %s\n' "$1" "$2" "$3"; }

say "erp 拆包批次验收${BATCH:+（第 $BATCH 批）}"

# ---------- 1) 文件数守恒 + 悬空 import ----------
line "·" "跑守恒/悬空 import" "node tools/verify-package-split.mjs"
if node tools/verify-package-split.mjs; then
  line "✓" "守恒 + 悬空 import" "通过"
  PASS=$((PASS + 1))
else
  line "✗" "守恒 + 悬空 import" "失败"
  FAIL=$((FAIL + 1)); FAILED+=("守恒/悬空import")
fi

# ---------- 2) 包结构护栏 ----------
line "·" "跑包结构护栏" "node tools/check-package-size.mjs"
if node tools/check-package-size.mjs; then
  line "✓" "包结构护栏" "通过"
  PASS=$((PASS + 1))
else
  line "✗" "包结构护栏" "失败"
  FAIL=$((FAIL + 1)); FAILED+=("包结构护栏")
fi

# ---------- 3) 引用清单（**信息性，不判失败**）----------
#   ⚠️ 这里**不能**做成判定项，原因是判据本身不成立：
#   「代码里出现了 com.factory.hub.erp.X」在拆包**之前**是完全正常的
#   （现有 96 处 import 都合法）⇒ 拿它当失败条件 = 一上来就误报。
#   ⇒ **真正的判定在 verify-package-split.mjs 的检查 1**：
#      它比对「该类的当前实际位置」与「旧全限定名」⇒ 只有类**真的搬走了**才报。
#      脚本第 79 行明确跳过 import 行（import 由检查 2 管）。
#   ⇒ 本步骤的定位是**工作清单**：告诉操作者「这批要改哪些引用」，秒级、不等待。
#
#   下面分两类列出：
#     · 内联全限定用法（代码里直接写全名）—— 类一搬走它就会失效，**必须改**
#     · import 行 —— 要改成新包名
say "引用工作清单（信息性，不判失败）"

# 内联用法：排除 import 行、排除注释行（javadoc 的 {@link xxx} 不算代码引用）
INLINE="$(grep -rn 'com\.factory\.hub\.erp\.[A-Z]' src/main/java src/test/java 2>/dev/null \
          | grep -v ':[0-9]*:[[:space:]]*import ' \
          | grep -v ':[0-9]*:[[:space:]]*[*/]' \
          | grep -v '{@link' || true)"
INLINE_N="$(printf '%s' "$INLINE" | grep -c . || true)"
line "·" "内联全限定用法" "$INLINE_N 处（类搬走后就失效，必须改成短名+import）"
if [ "$INLINE_N" -gt 0 ]; then
  printf '%s\n' "$INLINE" | head -12 | sed 's/^/       /'
  [ "$INLINE_N" -gt 12 ] && line "·" "还有" "$((INLINE_N - 12)) 处未显示"
fi

# import 行：拆包时的主要工作量
IMPORTS="$(grep -rn 'com\.factory\.hub\.erp\.[A-Z]' src/main/java src/test/java 2>/dev/null \
           | grep ':[0-9]*:[[:space:]]*import ' || true)"
IMPORTS_N="$(printf '%s' "$IMPORTS" | grep -c . || true)"
IMPORTS_FILES="$(printf '%s' "$IMPORTS" | cut -d: -f1 | sort -u | grep -c . || true)"
line "·" "待改 import" "$IMPORTS_N 处 / $IMPORTS_FILES 个文件"
line "·" "提示" "「残留内联引用」由 verify-package-split.mjs 的检查 1 判定（它按「类是否真的搬走」判）"

# ---------- 4) surefire 报告数 / 用例数（不跑 mvn，只断言数字）----------
say "surefire 收集数不变式"
if [ "$SKIP_SUREFIRE" = "1" ]; then
  line "—" "surefire 数字" "已按 --skip-surefire 跳过"
elif [ ! -d target/surefire-reports ]; then
  # ⚠️ 这里**不能**默认放过：报告不存在 = 还没跑 mvn clean test，
  #   而「没跑」不等于「通过」⇒ 否则就是一条永远绿的验收。
  line "✗" "surefire 数字" "找不到 target/surefire-reports —— 还没跑 mvn clean test？
  ⇒ 先让后端/QA 跑完 mvn clean test 再验收；确实只想跑静态段就加 --skip-surefire。"
  FAIL=$((FAIL + 1)); FAILED+=("surefire数字(未跑)")
else
  REPORT_N="$(ls target/surefire-reports/*.txt 2>/dev/null | wc -l | tr -d ' ')"
  CASE_N="$(grep -h 'Tests run:' target/surefire-reports/*.txt 2>/dev/null \
            | sed -n 's/.*Tests run: \([0-9]\+\).*/\1/p' | awk '{s+=$1} END {print s+0}')"
  # 判据用 **>=**（与 tools/ci-gate.sh 一致）：少了一眼（漏收集）⇒ 红；
  # 多了（新加了测试）⇒ 绿。**不引入「拆包窗口内 ==、拆完后 >=」那种人为约定** ——
  # 多一个人为约定就多一个出错的地方。
  if [ "$REPORT_N" -ge "$EXPECT_REPORTS" ] && [ "$CASE_N" -ge "$EXPECT_CASES" ]; then
    line "✓" "surefire 数字" "$REPORT_N 个报告 / $CASE_N 个用例（基线 $EXPECT_REPORTS / $EXPECT_CASES）"
    PASS=$((PASS + 1))
  else
    line "✗" "surefire 数字" "实际 $REPORT_N 报告 / $CASE_N 用例，基线 $EXPECT_REPORTS / $EXPECT_CASES"
    line "·" "含义" "挪包**不应该**让收集数变少 ⇒ 少了 = 漏收集（包名写错 / 测试类没跟着搬）⇒ 立刻回退查"
    FAIL=$((FAIL + 1)); FAILED+=("surefire数字")
  fi
fi

# ---------- 汇总 ----------
say "汇总"
line "·" "通过" "$PASS 项"
if [ "$FAIL" -gt 0 ]; then
  line "✗" "失败" "$FAIL 项"
  for n in "${FAILED[@]}"; do echo "    - $n" >&2; done
  echo ""
  echo "  ⇒ 没过就别打批次 tag。回退：tools/rollback-erp-split.sh --from-batch ${BATCH:-N} --dry-run" >&2
  exit 1
fi
line "✓" "全部通过" "$PASS 项"

cat <<'EOF'

  ⇒ 别忘了剩下两项（脚本替代不了）：
    · mvn clean test          —— 后端/QA 跑（编译零错 + 409 用例全绿）
    · 真启动一次               —— tools/verify-boot-smoke.sh
      （全项目没有一个真实 @SpringBootTest ⇒ 单测全绿**证明不了 Spring 容器起得来**）
EOF
exit 0
