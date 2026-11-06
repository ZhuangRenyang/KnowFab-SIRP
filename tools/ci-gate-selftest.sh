#!/usr/bin/env bash
#
# 门禁的自检：证明 tools/ci-gate.sh **真的会红**。
#
#   tools/ci-gate-selftest.sh
#
# 为什么必须做这个：
#   「跑一下 exit 0」不能证明门禁有效。本项目已经栽过一次 ——
#   暂存区里的 check-alarmcodes.mjs 是个 117 行空壳，跑一下就 exit 0，
#   什么都拦不住（**假绿比没有门禁更危险**）。
#
# 做法：逐项注入一个已知错误 → 门禁必须 exit 1 → 再还原 → 必须 exit 0。
#   「注入 + 显式还原 + 复跑确认」三步成对做，缺一不可。
#
# 隔离：在 `git archive HEAD` 的副本里做，不碰共享工作树 ——
#   否则别人跑出的是假缺陷（改共享树会让别人的构建突然红）。
#
# ⚠️ 只注入**不依赖数据库**的项（纯静态/纯前端）。
#   后端单测的变异（改业务逻辑让测试挂）需要重跑 mvn，耗时；
#   而且注入方式与「注入已知错误 → exit 1」是同一套道理，
#   本项目的单测已经过变异验证（见 2026-11-06 的三个变异用例）。
#
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="${WORK:-/tmp/ci-gate-selftest}"
SKIP_BE=1     # 默认只测静态/前端项（不重跑 mvn）
SKIP_FE=0

for a in "$@"; do
  case "$a" in
    --with-backend) SKIP_BE=0 ;;
    -h|--help) sed -n '2,26p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "未知参数：$a（--with-backend / -h）" >&2; exit 2 ;;
  esac
done

say()  { printf '\n\033[1m== %s\033[0m\n' "$*"; }
line() { printf '  %s %-34s %s\n' "$1" "$2" "$3"; }

PASS=0; FAIL=0

echo "在隔离副本里做（不碰共享工作树）"
rm -rf "$WORK"
mkdir -p "$WORK" || exit 1
# ⚠️ **必须复制当前工作树**（不是 git archive HEAD），
#   否则副本比工作区旧，自检跑的是旧代码 → 报出「已经修好的错误」，
#   我差点当成新缺陷去查。（2026-11-06 踩过两次：
#   第一次忘了带未提交改动，第二次用 git status 挑文件又漏了未跟踪的新文件。）
#
#   整树 tar 复制，排除构建产物与依赖目录。
#   Windows 没有 rsync，用 tar 的排除规则（Git Bash 自带 tar 支持）。
( cd "$ROOT" && tar -cf - \
    --exclude='./.git' \
    --exclude='./target' \
    --exclude='./node_modules' \
    --exclude='*/node_modules' \
    --exclude='./frontend/dist' \
    --exclude='*.tmp' \
    --exclude='./.ssh-test' \
    . ) | tar -xf - -C "$WORK"
echo "  副本：$WORK"

# 预装一次依赖：后面 8 次门禁调用会检测到 node_modules 存在而跳过安装。
# （CI 里没有 node_modules，CI 一定会真跑 npm ci —— 行为不变，只是自检快些。）
if [ "$SKIP_FE" -eq 0 ]; then
  echo "  预装前端依赖（只装一次）……"
  ( cd "$WORK/frontend" && npm ci --no-fund --no-audit >/tmp/_cgs-npm.log 2>&1 ) \
    || { echo "  预装失败，日志 /tmp/_cgs-npm.log" >&2; exit 2; }
fi

# 门禁在这个副本里必须 exit 1
expect_red() {
  local name="$1"
  local t0=$SECONDS
  if ( cd "$WORK" && bash tools/ci-gate.sh $SKIP_ARG ) >/tmp/_cgs.log 2>&1; then
    line "✗" "$name" "门禁没有变红 —— 门禁失效了（假绿）"
    FAIL=$((FAIL + 1))
    return 1
  fi
  line "✓" "$name" "变红了（$((SECONDS - t0)) 秒）"
  PASS=$((PASS + 1))
  return 0
}

expect_green() {
  local name="$1"
  local t0=$SECONDS
  if ( cd "$WORK" && bash tools/ci-gate.sh $SKIP_ARG ) >/tmp/_cgs.log 2>&1; then
    line "✓" "$name" "恢复全绿（$((SECONDS - t0)) 秒）"
    PASS=$((PASS + 1))
    return 0
  fi
  line "✗" "$name" "还原后仍然红 —— 还原失败"
  tail -20 /tmp/_cgs.log | sed 's/^/       /'
  FAIL=$((FAIL + 1))
  return 1
}

SKIP_ARG="--skip-be"
[ "$SKIP_BE" -eq 0 ] && SKIP_ARG=""

# ---------- 变异 1：i18n 少一个键 ----------
say "变异 1：i18n 少一个键"
if [ "$SKIP_FE" -eq 0 ]; then
  LOC="$WORK/frontend/src/locales/en-US.ts"
  cp "$LOC" "$LOC.bak"
  BEFORE=$(grep -c "^    reqFail:" "$LOC")
  python - "$LOC" <<'PY'
import io, sys, re
p = sys.argv[1]
s = io.open(p, encoding='utf-8', newline='').read()
# 删掉第一个 reqFail（common 块里的那个）。
# ⚠️ 这个文件里有**两个** reqFail（common 块 + 另一个块），缩进一样。
#    所以断言不能写成「还找得到 reqFail 就是没生效」—— 那个永远成立。
m = re.search(r"^(\s*)reqFail: '[^']*',\r?\n", s, re.M)
if not m:
    print("  找不到锚点 reqFail，变异失败")
    sys.exit(1)
s = s[:m.start()] + s[m.end():]
io.open(p, 'w', encoding='utf-8', newline='').write(s)
print("  已删掉 en-US 的第一个 reqFail 键")
PY
  # 断言注入真的生效：用**计数**（原来 2 个，注入后应剩 1 个）
  AFTER=$(grep -c "^    reqFail:" "$LOC")
  if [ "$AFTER" -ne $((BEFORE - 1)) ]; then
    line "✗" "i18n 变异" "注入没生效（reqFail 数量 $BEFORE → $AFTER）"
    FAIL=$((FAIL + 1))
  else
    expect_red "i18n 少一个键 → 必须红"
    mv "$LOC.bak" "$LOC"
    RESTORED=$(grep -c "^    reqFail:" "$LOC")
    if [ "$RESTORED" -ne "$BEFORE" ]; then
      line "✗" "i18n 还原" "还原后键数不对（$RESTORED ≠ $BEFORE）"
      FAIL=$((FAIL + 1))
    else
      expect_green "i18n 还原后 → 必须绿"
    fi
  fi
else
  line "—" "i18n 变异" "跳过（--skip-fe 未启用）"
fi

# ---------- 变异 2：前端类型错误 ----------
say "变异 2：制造一个 TS 类型错误"
if [ "$SKIP_FE" -eq 0 ]; then
  TARGET="$WORK/frontend/src/components/energy/DemandCalcBar.vue"
  cp "$TARGET" "$TARGET.bak"
  python - "$TARGET" <<'PY'
import io, sys
p = sys.argv[1]
s = io.open(p, encoding='utf-8', newline='').read()
# ⚠️ 必须插在 `<script setup lang="ts">` **之后**。
#   插在 `<script setup` 之后会把 `lang="ts"` 顶到下一行 →
#   整个文件按 JS 处理 → 类型错误根本不报 → 门禁当然不会红。
#   （2026-11-06 踩过：注入"成功"但门禁没反应，根因在这里。）
marker = '<script setup lang="ts">'
idx = s.find(marker)
if idx < 0:
    print("  找不到 <script setup lang=\"ts\">，变异失败")
    sys.exit(1)
inject = marker + "\nconst __ciMutation: number = 'not-a-number'\n"
s = s[:idx] + inject + s[idx + len(marker):]
io.open(p, 'w', encoding='utf-8', newline='').write(s)
print("  已注入 TS 类型错误")
PY
  # 断言注入真的生效：不能只信脚本自己说成功
  if ! grep -q "__ciMutation" "$TARGET"; then
    line "✗" "TS 变异" "注入没生效（文件里找不到注入的语句）"
    FAIL=$((FAIL + 1))
  else
    expect_red "TS 类型错误 → 必须红"
    mv "$TARGET.bak" "$TARGET"
    expect_green "TS 还原后 → 必须绿"
  fi
else
  line "—" "TS 变异" "跳过（--skip-fe 未启用）"
fi

# ---------- 变异 3：报警码字典写错 ----------
say "变异 3：报警码字典写错"
# 用校验器自己支持的环境变量覆盖（ALARM_DIR / ALARM_MANIFEST）——
# 改的是临时副本，**不碰真文件**。
ALARM_TMP="$WORK/_alarm_mut"
rm -rf "$ALARM_TMP"; mkdir -p "$ALARM_TMP"
cp "$WORK/src/main/resources/alarmcodes/"* "$ALARM_TMP/" 2>/dev/null
cp "$WORK/src/main/resources/alarmcode-manifest.json" "$WORK/_alarm_manifest_mut.json" 2>/dev/null

if [ -f "$ALARM_TMP/index.txt" ] && [ -f "$WORK/_alarm_manifest_mut.json" ]; then
  # 注入：index.txt 指向一个不存在的 json 文件（漏登记 / 文件丢失）
  printf '__ci_missing_file__.json\n' >> "$ALARM_TMP/index.txt"
  # 断言注入真的生效
  if ! grep -q "__ci_missing_file__" "$ALARM_TMP/index.txt"; then
    line "✗" "字典变异" "注入没生效"
    FAIL=$((FAIL + 1))
  else
    # 校验器要读到这两个环境变量才认临时目录
    if ( cd "$WORK" && ALARM_DIR="$ALARM_TMP" ALARM_MANIFEST="$WORK/_alarm_manifest_mut.json" \
          node scripts/check-alarmcodes.mjs ) >/tmp/_cgs.log 2>&1; then
      line "✗" "字典文件缺失" "校验器没有报错 —— 门禁失效了（假绿）"
      FAIL=$((FAIL + 1))
    else
      line "✓" "字典文件缺失" "变红了"
      PASS=$((PASS + 1))
      grep -aE "错误|缺失|不存在|✗|❌" /tmp/_cgs.log | head -3 | sed 's/^/       /'
    fi
    # 还原并确认绿
    cp "$WORK/src/main/resources/alarmcodes/index.txt" "$ALARM_TMP/index.txt"
    if ( cd "$WORK" && ALARM_DIR="$ALARM_TMP" ALARM_MANIFEST="$WORK/_alarm_manifest_mut.json" \
          node scripts/check-alarmcodes.mjs ) >/tmp/_cgs.log 2>&1; then
      line "✓" "字典还原后" "恢复全绿"
      PASS=$((PASS + 1))
    else
      line "✗" "字典还原后" "仍然红 —— 还原失败"
      FAIL=$((FAIL + 1))
    fi
  fi
else
  line "✗" "字典变异" "锚点不存在（index.txt 或 manifest）"
  FAIL=$((FAIL + 1))
fi

# ---------- 变异 4：门禁脚本自身失效（假绿检测）----------
say "变异 4：把校验器改残（验证「断言有输出」能抓到假绿）"
CK="$WORK/scripts/check-alarmcodes.mjs"
cp "$CK" "$CK.bak"
python - "$CK" <<'PY'
import io, sys, re
p = sys.argv[1]
s = io.open(p, encoding='utf-8', newline='').read()
# 只留注释头 + 函数定义，删掉末尾的 main() 调用 ——
# 这正是历史上真实发生过的失效（校验器变成 117 行空壳，跑一下就 exit 0）。
#
# ⚠️ 必须用 newline='' 读、且正则要容忍 \r\n：
#   文件是 CRLF 行尾，正则里的 $ 只匹配 \n 前，
#   用默认读法（换行转换）会把 \r\n 变成 \n、$ 能匹配 —— 但那样写回会破坏行尾；
#   而用 newline='' 读则保留 \r\n，$ 匹配不到 → **注入静默失败**
#   （2026-11-06 踩过：脚本打印「已去掉」，实际文件没变，门禁当然不会红）。
s2 = re.sub(r"\r?\n\s*main\(\);\s*\r?\n?\s*$", "\n", s)
if s2 == s:
    # 兜底：逐行找，最后一个非空行是 main(); 就删掉它
    lines = s.splitlines(keepends=True)
    for i in range(len(lines) - 1, -1, -1):
        if lines[i].strip():
            if lines[i].strip() == 'main();':
                del lines[i]
                s2 = ''.join(lines)
            break
if s2 == s:
    print("  找不到 main(); 变异失败")
    sys.exit(1)
io.open(p, 'w', encoding='utf-8', newline='').write(s2)
print("  已去掉末尾的 main() 调用")
PY
# 断言文件真的变了 —— 注入脚本自己说成功不算数
# 用计数：原来有 1 个 main(); 调用，注入后应为 0
MAIN_N=$(grep -c "main();" "$CK" || true)
if [ "$MAIN_N" -ne 0 ]; then
  echo "  注入后文件里仍有 main(); —— 变异无效" >&2
  exit 2
fi
echo "  注入已生效（main() 调用数：$MAIN_N）"
if [ $? -ne 0 ]; then
  line "✗" "假绿变异" "注入失败"
  FAIL=$((FAIL + 1))
else
  expect_red "校验器失效 → 必须红"
  mv "$CK.bak" "$CK"
  # 还原后必须确认 main() 回来了 —— 否则后面所有变异都建在坏基础上
  MAIN_BACK=$(grep -c "main();" "$CK" || true)
  if [ "$MAIN_BACK" -ne 1 ]; then
    line "✗" "校验器还原" "还原后 main() 调用数不对（$MAIN_BACK ≠ 1）"
    FAIL=$((FAIL + 1))
  else
    expect_green "校验器还原后 → 必须绿"
  fi
fi

# ---------- 变异 5：jaxb-api 被误降级到 test 作用域 ----------
# 这是本轮 OPC UA 验证里**最接近真实事故**的形态：
#   jaxb-api 放 test 作用域 → `mvn test` 全绿（测试类路径上有它）
#                            → 但生产类路径上没有 → 现场 ByteString 节点号炸穿整批采集
#   单元测试**抓不到**它，只有「生产运行时类路径验证」能抓到。
#   ⇒ 这一条变异专门验证：那道门禁真的会在这种情况下变红。
#     如果它不红，说明我们以为有门禁的地方其实没有 —— 那才是真正的假绿。
say "变异 5：jaxb-api 被误降级到 test 作用域"
POM="$WORK/pom.xml"
cp "$POM" "$POM.bak"
python - "$POM" <<'PY'
import io, sys, re
p = sys.argv[1]
s = io.open(p, encoding='utf-8', newline='').read()
# 在 jaxb-api 的 dependency 块里插回 <scope>test</scope>
m = re.search(r"(<groupId>javax\.xml\.bind</groupId>\s*<artifactId>jaxb-api</artifactId>\s*<version>[^<]+</version>)", s)
if not m:
    print("  找不到 jaxb-api 依赖块，变异失败")
    sys.exit(1)
s = s[:m.end(1)] + "\n        <scope>test</scope>" + s[m.end(1):]
io.open(p, 'w', encoding='utf-8', newline='').write(s)
print("  已把 jaxb-api 降级为 test 作用域")
PY
if [ $? -ne 0 ]; then
  line "✗" "jaxb 降级变异" "注入失败"
  FAIL=$((FAIL + 1))
else
  # 断言注入真的生效（不信脚本自己的报告）
  if ! grep -A 3 "jaxb-api" "$POM" | grep -q "<scope>test</scope>"; then
    line "✗" "jaxb 降级变异" "注入没生效（scope 没加上）"
    FAIL=$((FAIL + 1))
  else
    # 这次**不能**用 expect_red —— 它跑的是完整门禁（含前端，慢）。
    # 直接跑 OPC UA 那一道门禁，判据更精确也更快。
    if ( cd "$WORK" && bash tools/verify-opcua-boot4.sh 14877 ) >/tmp/_cgs.log 2>&1; then
      line "✗" "jaxb 降级 → 必须红" "生产类路径验证没有变红 —— 这道门禁失效了（假绿）"
      FAIL=$((FAIL + 1))
    else
      line "✓" "jaxb 降级 → 必须红" "变红了"
      PASS=$((PASS + 1))
      grep -aE "jaxb|FAIL|前提" /tmp/_cgs.log | head -3 | sed 's/^/       /'
    fi
    # 还原
    mv "$POM.bak" "$POM"
    if grep -A 3 "jaxb-api" "$POM" | grep -q "<scope>test</scope>"; then
      line "✗" "jaxb 还原" "还原失败（scope test 还在）"
      FAIL=$((FAIL + 1))
    else
      if ( cd "$WORK" && bash tools/verify-opcua-boot4.sh 14878 ) >/tmp/_cgs.log 2>&1; then
        line "✓" "jaxb 还原后 → 必须绿" "恢复全绿"
        PASS=$((PASS + 1))
      else
        line "✗" "jaxb 还原后 → 必须绿" "仍然红 —— 还原失败"
        tail -12 /tmp/_cgs.log | sed 's/^/       /'
        FAIL=$((FAIL + 1))
      fi
    fi
  fi
fi

say "汇总"
echo "  通过 $PASS / 失败 $FAIL"
if [ "$FAIL" -gt 0 ]; then
  echo
  echo "  ⇒ 门禁不可信，必须先修" >&2
  exit 1
fi
echo "  门禁确实会红，可以信任"
