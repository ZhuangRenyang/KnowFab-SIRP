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
# ⚠️ **别在主工作树跑构建的同时跑这个自检**（2026-11-06 踩过）：
#   自检靠「把当前工作树整树 tar 复制到副本里」来隔离。
#   若此时主工作树正在被 mvn 构建（写 target/），tar 可能读到变化中的目录树而
#   **拷贝被截断**（实测：副本只剩 4 个文件）⇒ 后面 6 条变异全部以
#   「No such file or directory」失败 —— **看起来像门禁坏了，其实是我的锅**。
#   ⇒ 跑自检前先让主工作树闲下来。
#
# ⚠️ 只注入**不依赖数据库**的项（纯静态/纯前端）。
#   后端单测的变异（改业务逻辑让测试挂）需要重跑 mvn，耗时；
#   而且注入方式与「注入已知错误 → exit 1」是同一套道理，
#   本项目的单测已经过变异验证（见 2026-11-06 的三个变异用例）。
#
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# 隔离副本目录：**每次运行用一个全新的目录**。
# ⚠️ **为什么不用固定目录 + rm -rf 清空**（2026-11-06 实测踩过）：
#   · `rm -rf` 一个几千文件的目录，会被**环境的删除保护**打断（报 Permission denied）
#     ⇒ 旧副本残留下来，新 tar 又覆盖在上面 ⇒ **副本是「新旧混合」的**：
#       残留的只读文件删不掉、npm ci 也因此失败（它要先清 node_modules）。
#   · 而且旧副本里还留着上一次没排除 ./dist 时拷进来的 3 GB 发版包。
#   ⇒ **改成「每次一个新目录」就不用删任何东西**，彻底绕开删除保护。
WORK="${WORK:-/tmp/ci-gate-selftest/run-$(date +%s)-$$}"
mkdir -p "$WORK"
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
# ⚠️ 这里**不再 rm -rf**：每次用新目录（见上面 WORK 的定义），不需要清空旧副本，
#   也就不受环境的删除保护影响。
mkdir -p "$WORK" || exit 1
# ⚠️ **必须复制当前工作树**（不是 git archive HEAD），
#   否则副本比工作区旧，自检跑的是旧代码 → 报出「已经修好的错误」，
#   我差点当成新缺陷去查。（2026-11-06 踩过两次：
#   第一次忘了带未提交改动，第二次用 git status 挑文件又漏了未跟踪的新文件。）
#
#   整树 tar 复制，排除构建产物与依赖目录。
#   Windows 没有 rsync，用 tar 的排除规则（Git Bash 自带 tar 支持）。
# ⚠️ **必须排除 ./dist**：它是**已提交进仓库**的历史发版包（1.0.29 那次把 3 GB 的
#   dist/ 提交进去了，git ls-files dist 能查到）。
#   不排除的话，副本会是 3 GB ⇒ tar 拷贝被拖垮/被截断 ⇒ 副本里**随机少一堆文件**
#   ⇒ 后面每条变异都以「No such file or directory」失败 ——
#   **看起来像门禁坏了，其实是我的锅**（2026-11-06 实际栽过两轮，排查了很久）。
#   ⇒ 排除之后副本约 160 MB，秒级完成。
( cd "$ROOT" && tar -cf - \
    --exclude='./.git' \
    --exclude='./target' \
    --exclude='./node_modules' \
    --exclude='*/node_modules' \
    --exclude='./frontend/dist' \
    --exclude='./dist' \
    --exclude='./.portable-test' \
    --exclude='*.tmp' \
    --exclude='./.ssh-test' \
    . ) | tar -xf - -C "$WORK"
echo "  副本：$WORK（$(find "$WORK" -type f | wc -l | tr -d ' ') 个文件）"

# ⚠️ **副本完整性自检**：拷贝被截断时，后面每条变异都会以「文件不存在」失败，
#   那看起来像门禁坏了 ⇒ 必须在这里就明确报「副本不完整」并退出。
for must in tools/ci-gate.sh tools/ci-gate-selftest.sh pom.xml \
            scripts/check-alarmcodes.mjs scripts/check-protocol-port-literals.mjs \
            tools/verify-opcua-boot4.sh \
            src/main/resources/alarmcodes/index.txt \
            frontend/src/locales/en-US.ts \
            frontend/src/api/deviceTemplates.ts; do
  if [ ! -f "$WORK/$must" ]; then
    echo "  ✗ 副本不完整：缺少 $must —— tar 拷贝被截断（多半是没排除 ./dist）" >&2
    echo "    ⇒ 这不是门禁的问题，是隔离副本没拷全；先修副本，别去查门禁。" >&2
    exit 2
  fi
done
echo "  副本完整性自检通过"

# ---------- Maven 路径解析 ----------
# 为什么需要这一步：
#   变异 6 要跑 OPC UA 那道检查（--only-opcua），它**需要 maven**。
#   而副本在 /tmp/ci-gate-selftest ⇒ 相对路径（如 ../tools/apache-maven-3.9.9/bin/mvn）
#   从副本里跑会找不到；机器上没装 mvn（PATH 里没有）时，门禁会直接
#   「缺少 Maven —— 用 MVN=/path/to/mvn 指定」exit 2 ⇒ 变异跑不起来。
# ⇒ 这里把 MVN 解析成**绝对路径**；PATH 里没有 mvn 时，回落到仓库自带的那份。
if [ -n "${MVN:-}" ] && [ -x "$ROOT/$MVN" ]; then
  MVN="$(cd "$(dirname "$ROOT/$MVN")" && pwd)/$(basename "$ROOT/$MVN")"
  export MVN
  echo "  用仓库自带的 maven：$MVN"
elif [ -z "${MVN:-}" ] && ! command -v mvn >/dev/null 2>&1; then
  for cand in "$ROOT/../tools/apache-maven-3.9.9/bin/mvn" "$ROOT/tools/apache-maven-3.9.9/bin/mvn"; do
    if [ -x "$cand" ]; then
      MVN="$(cd "$(dirname "$cand")" && pwd)/$(basename "$cand")"
      export MVN
      echo "  PATH 里没有 mvn，用仓库自带的：$MVN"
      break
    fi
  done
fi

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

# ---------- 常态断言：门禁的检查项清单里必须含关键项 ----------
# ⭐ **为什么必须有「常态断言」这一层**（架构师 + 运维专家 2026-11-06 两次实验坐实）：
#   运维专家把 ci-gate.sh 里**整块 OPC UA 检查删掉**，自检 12/0 全绿，还自夸「门禁确实会红」。
#   根因：**所有变异都在验「脚本会红」，没有一条验「门禁会不会调它」**。
#   而且我原来的变异 6 删的是**脚本文件**、红的是**旁路** ⇒ 跟主干被删毫无关系
#   ⇒ 「一个证明不了它想证明那件事的变异，不只是无效，是误导」。
#   架构师的原话：「删掉之后没人叫，才是真正的假绿 —— 因为此时连被验的对象都不存在了，
#   『跑起来红不红』这个问题本身失去意义。」
# ⇒ 所以结构必须是：**先有常态断言（清单里必须含它），再用变异证明这条断言真的有鉴别力**。
#   只加变异不加常态断言，那个变异就是「删掉它 ⇒ 当然少了一项」—— 自证，没意义。
#
# 判据用「清单里有没有这一项」而不是「跑起来红不红」：
#   检查块被删了 ⇒ 它根本不会跑 ⇒ 谈不上红不红 ⇒ 只能问「它还在不在」。
#
# 用法：expect_check_list <present|absent> <说明>
expect_check_list() {
  local want="$1"; shift
  local note="$1"; shift
  local out rc missing=""
  out="$( cd "$WORK" && bash tools/ci-gate.sh --list-checks 2>&1 )"
  rc=$?
  if [ "$rc" -ne 0 ]; then
    line "✗" "检查项清单" "--list-checks 退出码 $rc（应为 0）：$note"
    echo "$out" | tail -8 | sed 's/^/       /'
    FAIL=$((FAIL + 1))
    return 1
  fi
  for w in "OPC UA 生产类路径" "端口字面量检查"; do
    echo "$out" | grep -qF "$w" || missing="$missing [$w]"
  done
  if [ "$want" = "present" ]; then
    if [ -n "$missing" ]; then
      line "✗" "检查项清单" "清单里缺少：$missing —— $note"
      echo "$out" | sed 's/^/         /'
      FAIL=$((FAIL + 1))
      return 1
    fi
    line "✓" "检查项清单" "含关键项（OPC UA 生产类路径 / 端口字面量检查）：$note"
    PASS=$((PASS + 1))
    return 0
  fi
  # 期望「不在清单里」
  if echo "$out" | grep -qF "OPC UA 生产类路径"; then
    line "✗" "检查项清单" "清单里仍有「OPC UA 生产类路径」—— 常态断言没有鉴别力：$note"
    FAIL=$((FAIL + 1))
    return 1
  fi
  line "✓" "检查项清单" "按预期：清单里已没有「OPC UA 生产类路径」：$note"
  PASS=$((PASS + 1))
  return 0
}

SKIP_ARG="--skip-be"
[ "$SKIP_BE" -eq 0 ] && SKIP_ARG=""

# ---------- 常态断言（基线）：清单里必须有关键项 ----------
# ⚠️ 这条**先跑**：清单不对的话，后面的变异都建在坏基础上。
say "常态断言：门禁检查项清单必须含关键项"
expect_check_list present "基线"

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
    # 走 **--only-opcua**（门禁自己那条路径），判据更精确也更快；
    # ⚠️ 关键是**经过 ci-gate.sh**：直接调 verify 脚本验的是「脚本能发现降级」，
    #    跟「门禁会不会调它」是两件事（运维专家 2026-11-06 的实验就栽在这）。
    if ( cd "$WORK" && bash tools/ci-gate.sh --only-opcua ) >/tmp/_cgs.log 2>&1; then
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
      if ( cd "$WORK" && bash tools/ci-gate.sh --only-opcua ) >/tmp/_cgs.log 2>&1; then
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

# ---------- 变异 6：OPC UA 的 verify 脚本文件不在 ⇒ 门禁必须红 ----------
# ⚠️ **这条的范围比它的名字窄**：它验的是「**脚本文件不在时**门禁会红」，
#   **不是**「主干上挂载点被删时门禁会红」（那是变异 7 验的）。
#   一条变异只该声称它证明的那件事 —— 名字比范围大，就会变成「装饰性的变异」。
#   （我第一版把它叫「把 OPC UA 那道检查从门禁里摘掉」，但它删的是脚本文件，
#     红的是旁路 ⇒ 名字大了一圈 ⇒ 架构师指出这是「误导」。已改名收窄。）
# 做法：把副本里的 tools/verify-opcua-boot4.sh 删掉
#   ⇒ ci-gate.sh 的「找不到 tools/verify-opcua-boot4.sh」分支会亮红
#   ⇒ 断言：门禁红，**且红的正是「OPC UA 生产类路径」这一项**（不是别的项红）。
# ⚠️ 判据要精确：如果只断言「门禁红了」，那别的项红也算通过 ⇒ 验的还是别的东西。
# 用 --only-opcua 跑：只跑这一步，不需要 mvn test ⇒ 比跑完整门禁快得多。
say "变异 6：OPC UA 的 verify 脚本文件不在 ⇒ 门禁必须红"
VERIFY="$WORK/tools/verify-opcua-boot4.sh"
if [ ! -f "$VERIFY" ]; then
  line "✗" "OPC UA 摘除变异" "副本里找不到 tools/verify-opcua-boot4.sh，变异无法进行"
  FAIL=$((FAIL + 1))
else
  cp "$VERIFY" "$VERIFY.bak"
  rm -f "$VERIFY"
  if ( cd "$WORK" && bash tools/ci-gate.sh --only-opcua ) >/tmp/_cgs.log 2>&1; then
    line "✗" "OPC UA 摘除 → 必须红" "门禁没有变红 —— 这道检查已经不在门禁链路上了（假绿）"
    FAIL=$((FAIL + 1))
  else
    # 判据要精确：红的必须是「OPC UA 生产类路径」这一项
    if grep -aq "OPC UA 生产类路径" /tmp/_cgs.log && grep -aq "找不到 tools/verify-opcua-boot4.sh" /tmp/_cgs.log; then
      line "✓" "OPC UA 摘除 → 必须红" "变红了，且红的正是「OPC UA 生产类路径」这一项"
      PASS=$((PASS + 1))
      grep -aE "OPC UA 生产类路径|找不到" /tmp/_cgs.log | head -3 | sed 's/^/       /'
    else
      line "✗" "OPC UA 摘除 → 必须红" "红了，但红的不是「OPC UA 生产类路径」这一项 —— 判据不精确"
      grep -aE "✓|✗|失败" /tmp/_cgs.log | head -8 | sed 's/^/       /'
      FAIL=$((FAIL + 1))
    fi
  fi
  # 还原
  mv "$VERIFY.bak" "$VERIFY"
  if [ ! -f "$VERIFY" ]; then
    line "✗" "OPC UA 还原" "还原失败（文件没了）"
    FAIL=$((FAIL + 1))
  else
    if ( cd "$WORK" && bash tools/ci-gate.sh --only-opcua ) >/tmp/_cgs.log 2>&1; then
      line "✓" "OPC UA 还原后 → 必须绿" "恢复全绿"
      PASS=$((PASS + 1))
    else
      line "✗" "OPC UA 还原后 → 必须绿" "仍然红 —— 还原失败"
      tail -12 /tmp/_cgs.log | sed 's/^/       /'
      FAIL=$((FAIL + 1))
    fi
  fi
fi

# ---------- 变异 7：删掉 ci-gate.sh 主干上的 OPC UA 检查挂载点 ----------
# ⭐ **这条才是真正堵住运维专家那个盲区的**：
#   他的实验：把 ci-gate.sh 里 SKIP_BE=0 主干那段**整块删掉** ⇒ 自检 12/0 全绿，
#   还自夸「门禁确实会红，可以信任」。
#   我原来的变异 6 删的是**脚本文件**、红的是**旁路** ⇒ 跟主干被删毫无关系
#   ⇒ 「看起来像堵上了」比裸奔更糟（架构师：证明不了它想证明那件事的变异是误导）。
#
# 为什么判据是「清单里还有没有这一项」而不是「跑起来红不红」：
#   检查块被删了 ⇒ 它根本不会跑 ⇒ 谈不上红不红 ⇒ 只能问「它还在不在」。
#   ⇒ 用 --list-checks 的清单 + 常态断言来判。
#
# 注入方式：**只删主干那一个挂载点**（`if [ "$SKIP_BE" -eq 0 ]` 那个分支里的 run_opcua_check），
#   不能碰 `--only-opcua` 旁路里的那一个（旁路还在 ⇒ 变异跑的是旁路 ⇒ 又是错方向）。
say "变异 7：删掉 ci-gate.sh 主干上的 OPC UA 检查挂载点（验它真挂在主干上）"
GATE="$WORK/tools/ci-gate.sh"
if [ ! -f "$GATE" ]; then
  line "✗" "主干挂载点变异" "副本里找不到 tools/ci-gate.sh，变异无法进行"
  FAIL=$((FAIL + 1))
else
  cp "$GATE" "$GATE.bak"
  python - "$GATE" <<'PY'
import io, re, sys
p = sys.argv[1]
s = io.open(p, encoding='utf-8', newline='').read()
# ⚠️ 必须锚定 `SKIP_BE -eq 0` 那个分支 —— 不能匹配到 `--only-opcua` 旁路里的同一行。
#   （运维专家上一版正则匹配的是注释块、会误删旁路。）
pat = re.compile(
    r'(if \[ "\$SKIP_BE" -eq 0 \]; then\n  say "OPC UA 生产运行时类路径"\n)'
    r'(?:[ \t]*#[^\n]*\n)*'
    r'[ \t]*run_opcua_check\n', re.S)
s2, n = pat.subn(r'\1', s)
if n != 1:
    print('  注入失败：匹配到 %d 处（应为 1 处）' % n)
    sys.exit(1)
io.open(p, 'w', encoding='utf-8', newline='').write(s2)
print('  已删掉主干那一个挂载点（旁路完好）')
PY
  if [ $? -ne 0 ]; then
    line "✗" "主干挂载点变异" "注入失败"
    FAIL=$((FAIL + 1))
  else
    # 断言注入真的生效（不信脚本自己说成功）：主干那个 if 块里不该再有 run_opcua_check
    if awk '/if \[ "\$SKIP_BE" -eq 0 \]; then/,/^fi$/' "$GATE" | grep -q 'run_opcua_check'; then
      line "✗" "主干挂载点变异" "注入没生效（主干分支里还有 run_opcua_check）"
      FAIL=$((FAIL + 1))
    else
      # ⚠️ 判据要精确：失败的必须是**那条清单断言**，不能是别的项红
      expect_check_list absent "主干挂载点被删 ⇒ 清单里必须不再有「OPC UA 生产类路径」"
    fi
  fi
  # 还原
  mv "$GATE.bak" "$GATE"
  if grep -q 'run_opcua_check' "$GATE"; then
    expect_check_list present "还原后清单必须恢复"
  else
    line "✗" "主干挂载点还原" "还原失败（挂载点没了）"
    FAIL=$((FAIL + 1))
  fi
fi

# ---------- 变异 8：测试里写死「清零时刻」⇒ 时区无关性检查必须红 ----------
# ⚠️ 为什么需要这条（2026-11-07 CI run #17 红的真根因）：
#   `ProdRollupTodaySnapshotTest` 写死了「清零 8 点」⇒ 当**当前本地时间落在 [8:00,9:00)**
#   ⇒ `dayStart(now,8)` 恰好等于「当前小时起点」⇒ 那行被判成「当前小时」⇒ 测试必然红。
#   ⇒ 症状「本地全绿、CI 全红」（CI 用 UTC 时区且在 08:17 跑 ⇒ 正好撞进坏窗口）。
#   ⇒ 修法：清零时刻动态取「当前本地小时 - 1」+ 新增 `tools/verify-hour-independence.mjs`
#      （穷举 28 个时区 ⇒ 本地小时 0~23 全覆盖）。
# ⇒ **但「新加的检查项」自己也要证明有鉴别力**（绿灯最危险）：
#   这里把测试改回写死 ⇒ 该检查**必须红** ⇒ 还原后**必须绿**。
say "变异 8：测试里写死清零时刻 ⇒ 时区无关性检查必须红"
TEST_FILE="$WORK/src/test/java/com/factory/hub/report/ProdRollupTodaySnapshotTest.java"
if [ ! -f "$TEST_FILE" ]; then
  line "✗" "时区无关性变异" "副本里找不到测试文件，变异无法进行"
  FAIL=$((FAIL + 1))
else
  cp "$TEST_FILE" "$TEST_FILE.bak"
  # 把「动态清零时刻」改回「写死 8」⇒ 依赖当前时刻的坏味道回来
  # ⚠️ 锚点要用**整行**（那行后面有行尾注释）⇒ 只匹配前缀会替换不到
  # ⚠️ 判据用「写死的 8 真的写进去了」+「动态那行真的没了」，
  #   **不要**用「currentLocalHour(now) 次数减少」—— 那行用的是已算好的 curLocalHour 变量
  python - "$TEST_FILE" <<'PY'
import io, re, sys
p = sys.argv[1]
s = io.open(p, encoding='utf-8', newline='').read()
pat = re.compile(r'^\s*int rh1 = \(curLocalHour \+ 23\) % 24;.*$', re.M)
s2, n = pat.subn('        int rh1 = 8;', s)
ok = (n == 1) and ('int rh1 = 8;' in s2) and ('int rh1 = (curLocalHour + 23) % 24;' not in s2)
if not ok:
    print('  注入失败：匹配到 %d 处；写死的 8 是否写入=%s；动态那行是否已消失=%s'
          % (n, 'int rh1 = 8;' in s2, 'int rh1 = (curLocalHour + 23) % 24;' not in s2))
    sys.exit(1)
io.open(p, 'w', encoding='utf-8', newline='').write(s2)
print('  已把动态清零时刻改回写死 8（匹配 %d 处）' % n)
PY
  if [ $? -ne 0 ]; then
    line "✗" "时区无关性变异" "注入失败"
    FAIL=$((FAIL + 1))
  else
    # ⚠️ 判据必须精确：**必须是时区无关性检查红**（不是别的项红）
    if ( cd "$WORK" && MVN="${MVN:-mvn}" node tools/verify-hour-independence.mjs >/tmp/_cgs-hour.log 2>&1 ); then
      line "✗" "时区无关性变异" "写死清零时刻后检查仍绿 ⇒ 这条检查没有鉴别力（装饰性的）"
      FAIL=$((FAIL + 1))
    else
      line "✓" "时区无关性变异" "写死清零时刻 ⇒ 检查如期变红"
      PASS=$((PASS + 1))
      grep -aE "个时区失败|反向对照" /tmp/_cgs-hour.log | head -3 | sed 's/^/       /'
    fi
  fi
  # 还原
  mv "$TEST_FILE.bak" "$TEST_FILE"
  if grep -q 'currentLocalHour(now)' "$TEST_FILE"; then
    line "✓" "时区无关性还原" "已还原动态清零时刻"
    PASS=$((PASS + 1))
  else
    line "✗" "时区无关性还原" "还原失败"
    FAIL=$((FAIL + 1))
  fi
fi

# ======================================================================
# 拆包护栏：新增的两项检查也必须有「删了会响」的证据（2026-11-07，运维）
#
# 为什么这组必须有：
#   「包结构护栏」「拆包完整性核对」是新加的两项，而自检的常态断言
#   **只查了「OPC UA 生产类路径」和「端口字面量检查」两个名字**
#   ⇒ 把整个「包结构护栏」块删掉，自检照样全绿 ⇒ 又是运维专家那个盲区。
#   ⇒ 这组补的就是：**新检查项也要有鉴别力证据**，否则它们只是装饰。
#
# ⚠️ 判据一律要精确：红的必须是**指定的那一项**，不能是「门禁红了就算」
#   （变异 6 的教训：名字比范围大，就是误导）。
#   ⇒ 全部用 `--only-pkg`（只跑这两项，~1 秒）⇒ 门禁红时唯一可能红的就是它。
# ======================================================================

# ---------- 清单断言的小工具 ----------
#   为什么不复用 expect_check_list：它把名字写死成 OPC UA / 端口字面量，
#   而这里要断言的是**具体某一项**在不在。
expect_list_has() {
  local want="$1"; shift
  local note="$1"; shift
  local out
  out="$( cd "$WORK" && bash tools/ci-gate.sh --list-checks 2>&1 )"
  if echo "$out" | grep -qF "$want"; then
    line "✓" "检查项清单" "含「$want」：$note"
    PASS=$((PASS + 1)); return 0
  fi
  line "✗" "检查项清单" "清单里缺少「$want」—— $note"
  FAIL=$((FAIL + 1)); return 1
}

expect_list_lacks() {
  local want="$1"; shift
  local note="$1"; shift
  local out
  out="$( cd "$WORK" && bash tools/ci-gate.sh --list-checks 2>&1 )"
  if echo "$out" | grep -qF "$want"; then
    line "✗" "检查项清单" "清单里仍有「$want」—— 常态断言没有鉴别力：$note"
    FAIL=$((FAIL + 1)); return 1
  fi
  line "✓" "检查项清单" "按预期：清单里已没有「$want」：$note"
  PASS=$((PASS + 1)); return 0
}

# ---------- 常态断言（补）：新加的两项必须在清单里 ----------
say "常态断言：新加的两项护栏必须在检查项清单里"
expect_list_has "包结构护栏" "新增的包结构护栏必须登记进 --list-checks"
expect_list_has "拆包完整性核对" "新增的拆包完整性核对必须登记进 --list-checks"

# ---------- 变异 9：删掉 ci-gate.sh 主干上的「包结构护栏」挂载点 ----------
# 验的是「它真挂在主干上」，**不是**「脚本文件不在时会不会红」（那是变异 10/11）。
# 判据用「清单里还有没有这一项」：检查块被删了 ⇒ 它根本不会跑 ⇒ 谈不上红不红。
say "变异 9：删掉 ci-gate.sh 主干上的「包结构护栏」挂载点（验它真挂在主干上）"
GATE2="$WORK/tools/ci-gate.sh"
if [ ! -f "$GATE2" ]; then
  line "✗" "包结构护栏挂载点变异" "副本里找不到 tools/ci-gate.sh，变异无法进行"
  FAIL=$((FAIL + 1))
else
  cp "$GATE2" "$GATE2.bak2"
  python - "$GATE2" <<'PY'
import io, re, sys
p = sys.argv[1]
s = io.open(p, encoding='utf-8', newline='').read()
# 只删**主干上那一个调用点**。
#
# ⚠️ 为什么用「行首无缩进」来定位（而不是「say 后面一路吃到 fi」）：
#   `say "包结构护栏"` 在主干是**顶格**的，而 --only-pkg 旁路里是**缩进两格**的
#   ⇒ `^say "包结构护栏"` 只匹配主干那一个。
#   （第一版写的是 `^say ... .*?^fi` ⇒ 非贪婪吃到了**前端段**的第一个 ^fi
#     ⇒ 把整个前端块一起删了、脚本被改残 ⇒ 变异本身成了破坏。
#     ⇒ **变异注入必须精确到「删哪一块」，不能「顺手删到下一个 fi」。**）
pat = re.compile(r'^say "包结构护栏"\nrun_pkg_guard\n', re.M)
s2, n = pat.subn('', s)
if n != 1:
    print('  注入失败：匹配到 %d 处（应为 1 处）' % n)
    sys.exit(1)
# 断言注入真的生效（不信脚本自己说成功）：顶格的调用点不该在了
if re.search(r'^say "包结构护栏"\nrun_pkg_guard\n', s2, re.M):
    print('  注入没生效（顶格调用点还在）')
    sys.exit(1)
# 旁路（缩进两格）必须**完好** —— 删旁路等于测错了对象
if '  run_pkg_guard' not in s2:
    print('  注入把 --only-pkg 旁路也删了 ⇒ 测错了对象')
    sys.exit(1)
io.open(p, 'w', encoding='utf-8', newline='').write(s2)
print('  已删掉主干上那一个调用点（--only-pkg 旁路完好）')
PY
  if [ $? -ne 0 ]; then
    line "✗" "包结构护栏挂载点变异" "注入失败"
    FAIL=$((FAIL + 1))
  else
    expect_list_lacks "包结构护栏" "挂载点被删 ⇒ 清单里必须不再有「包结构护栏」"
    expect_list_lacks "拆包完整性核对" "挂载点被删 ⇒ 清单里必须不再有「拆包完整性核对」"
  fi
  # 还原
  mv "$GATE2.bak2" "$GATE2"
  if grep -q 'check "包结构护栏"' "$GATE2"; then
    expect_list_has "包结构护栏" "还原后清单必须恢复"
  else
    line "✗" "包结构护栏挂载点还原" "还原失败"
    FAIL=$((FAIL + 1))
  fi
fi

# ---------- 变异 10：check-package-size.mjs 不在 ⇒ 「包结构护栏」必须红 ----------
say "变异 10：check-package-size.mjs 不在 ⇒ 包结构护栏必须红"
PS="$WORK/tools/check-package-size.mjs"
if [ ! -f "$PS" ]; then
  line "✗" "包结构护栏摘除变异" "副本里找不到 tools/check-package-size.mjs，变异无法进行"
  FAIL=$((FAIL + 1))
else
  cp "$PS" "$PS.bak"
  rm -f "$PS"
  if ( cd "$WORK" && bash tools/ci-gate.sh --only-pkg ) >/tmp/_cgs.log 2>&1; then
    line "✗" "包结构护栏摘除 → 必须红" "门禁没有变红 —— 这道检查已经不在门禁链路上了（假绿）"
    FAIL=$((FAIL + 1))
  else
    if grep -aq "包结构护栏" /tmp/_cgs.log; then
      line "✓" "包结构护栏摘除 → 必须红" "变红了，且红的正是「包结构护栏」这一项"
      PASS=$((PASS + 1))
      grep -aE "包结构护栏|拆包完整性核对" /tmp/_cgs.log | head -4 | sed 's/^/       /'
    else
      line "✗" "包结构护栏摘除 → 必须红" "红了，但红的不是「包结构护栏」这一项 —— 判据不精确"
      grep -aE "✓|✗|失败" /tmp/_cgs.log | head -8 | sed 's/^/       /'
      FAIL=$((FAIL + 1))
    fi
  fi
  mv "$PS.bak" "$PS"
  if [ ! -f "$PS" ]; then
    line "✗" "包结构护栏还原" "还原失败（文件没了）"
    FAIL=$((FAIL + 1))
  fi
fi

# ---------- 变异 11：verify-package-split.mjs 不在 ⇒ 「拆包完整性核对」必须红 ----------
say "变异 11：verify-package-split.mjs 不在 ⇒ 拆包完整性核对必须红"
VPS="$WORK/tools/verify-package-split.mjs"
if [ ! -f "$VPS" ]; then
  line "✗" "拆包完整性摘除变异" "副本里找不到 tools/verify-package-split.mjs，变异无法进行"
  FAIL=$((FAIL + 1))
else
  cp "$VPS" "$VPS.bak"
  rm -f "$VPS"
  if ( cd "$WORK" && bash tools/ci-gate.sh --only-pkg ) >/tmp/_cgs.log 2>&1; then
    line "✗" "拆包完整性摘除 → 必须红" "门禁没有变红 —— 这道检查已经不在门禁链路上了（假绿）"
    FAIL=$((FAIL + 1))
  else
    if grep -aq "拆包完整性核对" /tmp/_cgs.log; then
      line "✓" "拆包完整性摘除 → 必须红" "变红了，且红的正是「拆包完整性核对」这一项"
      PASS=$((PASS + 1))
      grep -aE "拆包完整性核对|包结构护栏" /tmp/_cgs.log | head -4 | sed 's/^/       /'
    else
      line "✗" "拆包完整性摘除 → 必须红" "红了，但红的不是「拆包完整性核对」这一项 —— 判据不精确"
      grep -aE "✓|✗|失败" /tmp/_cgs.log | head -8 | sed 's/^/       /'
      FAIL=$((FAIL + 1))
    fi
  fi
  mv "$VPS.bak" "$VPS"
  if [ ! -f "$VPS" ]; then
    line "✗" "拆包完整性还原" "还原失败（文件没了）"
    FAIL=$((FAIL + 1))
  fi
fi

# ---------- 变异 12：给 erp 加 3 个假文件 ⇒ 「包结构护栏」必须红 ----------
# 验的是「它真的拦得住文件堆回一个包里」（脚本头注释里承诺的那件事）。
say "变异 12：给 erp 加 3 个假文件 ⇒ 包结构护栏必须红"
FAKE_DIR="$WORK/src/main/java/com/factory/hub/erp"
if [ ! -d "$FAKE_DIR" ]; then
  line "✗" "erp 造假文件变异" "副本里找不到 erp 目录，变异无法进行"
  FAIL=$((FAIL + 1))
else
  for i in 1 2 3; do
    printf 'package com.factory.hub.erp;\npublic class FakeProbe%s {}\n' "$i" > "$FAKE_DIR/FakeProbe$i.java"
  done
  if ( cd "$WORK" && bash tools/ci-gate.sh --only-pkg ) >/tmp/_cgs.log 2>&1; then
    line "✗" "erp 造假文件 → 必须红" "门禁没有变红 —— 包结构护栏没有鉴别力（装饰性的）"
    FAIL=$((FAIL + 1))
  else
    if grep -aq "包结构护栏" /tmp/_cgs.log; then
      line "✓" "erp 造假文件 → 必须红" "变红了，且红的正是「包结构护栏」这一项"
      PASS=$((PASS + 1))
      grep -aE "直属文件数|包结构护栏" /tmp/_cgs.log | head -4 | sed 's/^/       /'
    else
      line "✗" "erp 造假文件 → 必须红" "红了，但红的不是「包结构护栏」这一项 —— 判据不精确"
      grep -aE "✓|✗|失败" /tmp/_cgs.log | head -8 | sed 's/^/       /'
      FAIL=$((FAIL + 1))
    fi
  fi
  rm -f "$FAKE_DIR"/FakeProbe*.java
fi

# ---------- 变异 13：把 erp 域的一个文件挪出域外 ⇒ 「拆包完整性核对」必须红 ----------
# 验的是「守恒检查真的在数文件」（不是只查 import 悬空）。
say "变异 13：把 erp 域的一个文件挪出域外 ⇒ 守恒检查必须红"
MOVE_SRC="$WORK/src/main/java/com/factory/hub/erp/ErpWorkOrder.java"
MOVE_DST_DIR="$WORK/src/main/java/com/factory/hub/zzprobe"
if [ ! -f "$MOVE_SRC" ]; then
  line "✗" "守恒变异" "副本里找不到 ErpWorkOrder.java，变异无法进行"
  FAIL=$((FAIL + 1))
else
  mkdir -p "$MOVE_DST_DIR"
  mv "$MOVE_SRC" "$MOVE_DST_DIR/ErpWorkOrder.java"
  if ( cd "$WORK" && bash tools/ci-gate.sh --only-pkg ) >/tmp/_cgs.log 2>&1; then
    line "✗" "守恒变异 → 必须红" "门禁没有变红 —— 守恒检查没有鉴别力（装饰性的）"
    FAIL=$((FAIL + 1))
  else
    if grep -aq "拆包完整性核对" /tmp/_cgs.log; then
      line "✓" "守恒变异 → 必须红" "变红了，且红的正是「拆包完整性核对」这一项"
      PASS=$((PASS + 1))
      grep -aE "守恒|拆包完整性核对" /tmp/_cgs.log | head -4 | sed 's/^/       /'
    else
      line "✗" "守恒变异 → 必须红" "红了，但红的不是「拆包完整性核对」这一项 —— 判据不精确"
      grep -aE "✓|✗|失败" /tmp/_cgs.log | head -8 | sed 's/^/       /'
      FAIL=$((FAIL + 1))
    fi
  fi
  mv "$MOVE_DST_DIR/ErpWorkOrder.java" "$MOVE_SRC"
  rmdir "$MOVE_DST_DIR" 2>/dev/null || true
fi

# ---------- 静态断言 1：拆包验收脚本的期望总数默认值不得为 null ----------
# 为什么这条**只能静态抓**：把默认值改回 null ⇒ 不传参数时守恒检查静默跳过，
#   但脚本**照样打印「✓」** ⇒ 门禁照样绿 ⇒ **运行时没有任何现象能暴露它**。
say "静态断言 1：拆包验收脚本的期望总数默认值不得为 null"
SPLIT_MJS="$WORK/tools/verify-package-split.mjs"
if [ ! -f "$SPLIT_MJS" ]; then
  line "✗" "静态断言" "副本里找不到 tools/verify-package-split.mjs"
  FAIL=$((FAIL + 1))
elif grep -E 'EXPECT_TOTAL[[:space:]]*=' "$SPLIT_MJS" | grep -vE '^[[:space:]]*//' | grep -qE ':[[:space:]]*null'; then
  line "✗" "静态断言" "期望总数默认值仍是 null ⇒ 不传参数时守恒检查会静默跳过（永远绿的护栏）"
  FAIL=$((FAIL + 1))
else
  line "✓" "静态断言" "期望总数默认值不是 null"
  PASS=$((PASS + 1))
fi

# ---------- 静态断言 2：端口字面量白名单的每条 file 路径必须仍然存在 ----------
# 为什么需要（架构师 2026-11-07 提出，运维认可）：
#   白名单是**按相对路径索引**的 ⇒ 有人把 driver/modbus/ModbusTcpDriver.java
#   挪个目录 ⇒ 那条豁免**静默失效** ⇒ 该文件里的 502 不再被豁免 ⇒ 门禁红，
#   而红的原因**不指向真正的问题**（看起来像「代码里多了个硬编码端口」）。
#   ⇒ 这条断言把「白名单路径失效」变成一条**指名道姓**的报错。
# ⚠️ 本条**不改**白名单逻辑，只加断言 ⇒ 风险极低。
say "静态断言 2：端口字面量白名单的每条 file 路径必须仍然存在"
if ! python - "$WORK" <<'PY'
import io, os, re, sys
root = sys.argv[1]
src = os.path.join(root, 'src', 'main', 'java')
p = os.path.join(root, 'scripts', 'check-protocol-port-literals.mjs')
if not os.path.exists(p):
    print('  找不到 scripts/check-protocol-port-literals.mjs')
    sys.exit(2)
s = io.open(p, encoding='utf-8', newline='').read()
paths = re.findall(r"file:\s*'([^']+)'", s)
# ⚠️⚠️ **0 条必须报错，不能当通过**（2026-11-07 运维实测差点栽在这）：
#   「一条解析不到任何东西的断言」= 空转 = **永远绿**。
#   ⇒ 万一有人改了白名单的写法（换引号、换字段名、抽成常量），
#     这条断言会「0 条路径全部存在」⇒ 绿 ⇒ 但它**什么都没检查**。
#   ⇒ 解析不到东西 = 断言失效 = 必须红。
if not paths:
    print('  白名单里一条 file 都没解析到 ⇒ 断言空转（写法可能变了）')
    sys.exit(1)
missing = [x for x in paths if not os.path.exists(os.path.join(src, x))]
if missing:
    print('  白名单路径失效（文件已被移动）：')
    for m in missing:
        print('    - %s' % m)
    sys.exit(1)
print('  %d 条白名单路径全部存在' % len(paths))
PY
then
  line "✗" "静态断言" "端口白名单里有路径已失效（文件被移动）—— 门禁会因「豁免失效」而误报"
  FAIL=$((FAIL + 1))
else
  line "✓" "静态断言" "端口白名单路径全部有效"
  PASS=$((PASS + 1))
fi

# ---------- 静态断言 3：CI 的 artifact 上传路径必须包含 target-logs/ ----------
# 为什么需要：门禁有 4 份日志写在 target-logs/（后端单测 + 压测核对 + 两个新护栏）。
#   若 ci.yml 只上传 target/ ⇒ 这些日志 CI 失败时**不会被上传** ⇒
#   「CI 红了但查不出为什么」—— 而这正是 ::error:: 机制当初要解决的那类问题。
say "静态断言 3：CI 的 artifact 上传路径必须包含 target-logs/"
CI_YML="$WORK/.github/workflows/ci.yml"
if [ ! -f "$CI_YML" ]; then
  line "✗" "静态断言" "副本里找不到 .github/workflows/ci.yml"
  FAIL=$((FAIL + 1))
elif grep -q 'target-logs/_ci-\*\.log' "$CI_YML"; then
  line "✓" "静态断言" "target-logs/ 下的门禁日志 CI 失败时会被上传"
  PASS=$((PASS + 1))
else
  line "✗" "静态断言" "ci.yml 没上传 target-logs/_ci-*.log ⇒ 门禁红了拿不到日志（CI 红了查不出为什么）"
  FAIL=$((FAIL + 1))
fi

# 尽力清理副本（失败也无所谓：留着只占磁盘，下次运行会用新目录）
rm -rf "$WORK" 2>/dev/null || true

say "汇总"
echo "  通过 $PASS / 失败 $FAIL"
if [ "$FAIL" -gt 0 ]; then
  echo
  echo "  ⇒ 门禁不可信，必须先修" >&2
  exit 1
fi
echo "  门禁确实会红，可以信任"
