#!/usr/bin/env bash
# OPC UA 驱动在 Spring Boot 4.1.2 下的「生产运行时类路径」验证。
#
# 为什么要有这个脚本（而不是只跑单元测试）：
#   单元测试跑在**测试类路径**上，那条路径上多了一个测试作用域的依赖
#   （org.eclipse.milo:sdk-server —— 用来在本地起 OPC UA Server）。
#   而**交付到客户现场的类路径上没有它**。所以「测试绿了」不等于「现场能用」。
#   这个脚本把两件事拆到两个进程里：
#     进程 A（测试类路径）：起一个真的 milo OPC UA Server
#     进程 B（生产运行时类路径）：用项目里的 OpcUaDriver 去连、去读、去写
#   进程 B 的类路径 = 全量依赖类路径里剔除所有 test/provided 作用域的 jar
#   —— 这才是现场真实的类路径。
#
# 它要接进 CI（ubuntu-latest），所以**不能有任何 Windows 假设**：
#   classpath 分隔符 Windows 是 ';'、Linux 是 ':'；写死会让 Linux 上整条类路径
#   粘成一段，java 直接报「找不到主类」—— 这是「本地全绿 ≠ CI 能跑」的典型。
#
# 探针会连读三类节点：
#   ns=2;s=TestTag1  字符串节点号
#   ns=2;s=TestTag2  字符串节点号
#   ns=2;b=AAEC      ByteString 节点号 ← 走 javax.xml.bind.DatatypeConverter.parseBase64Binary，
#                    这个类在 Boot 4 的类路径上曾经整个消失（现在 pom 里补在 runtime 作用域）
#
# 用法： bash tools/verify-opcua-boot4.sh [端口]
#        bash tools/verify-opcua-boot4.sh --dry-run     只打印解析到的环境，不干活
# 环境变量：MVN（maven 路径）、JAVA_HOME、PORT
# 退出码：0 = 驱动在生产运行时类路径上可用；1 = 不可用

set -uo pipefail

cd "$(dirname "$0")/.." || exit 1

DRY_RUN=0
PORT="${PORT:-}"
if [ "${1:-}" = "--dry-run" ]; then
  DRY_RUN=1
elif [ -n "${1:-}" ]; then
  PORT="$1"
fi
[ -n "$PORT" ] || PORT=14840

# ---------------------------------------------------------------- 跨平台解析
# classpath 分隔符：Windows 是 ';'，Linux 是 ':'
case "$(uname -s 2>/dev/null || echo unknown)" in
  MINGW*|MSYS*|CYGWIN*|Windows_NT) CP_SEP=';' ;;
  *)                                     CP_SEP=':' ;;
esac

resolve_mvn() {
  if [ -n "${MVN:-}" ]; then
    echo "$MVN"
    return
  fi
  # 本机（仓库根 tools/ 下有一份 maven）；CI 上没有，回落到 PATH 里的 mvn
  for cand in ../tools/apache-maven-3.9.9/bin/mvn tools/apache-maven-3.9.9/bin/mvn; do
    if [ -f "$cand" ]; then
      echo "$cand"
      return
    fi
  done
  echo "mvn"
}

resolve_java() {
  if [ -n "${JAVA_HOME:-}" ]; then
    # JAVA_HOME 末尾可能带 / 或 \，先去掉，免得拼出 "…jdk\ /bin/java" 这种混合分隔符
    local home="${JAVA_HOME%/}"
    home="${home%\\}"
    for cand in "$home/bin/java" "$home/jre/bin/java"; do
      if [ -x "$cand" ]; then
        echo "$cand"
        return
      fi
    done
  fi
  echo "java"
}

MVN_RESOLVED="$(resolve_mvn)"
JAVA_RESOLVED="$(resolve_java)"

if [ "$DRY_RUN" = "1" ]; then
  echo "CP_SEP=$CP_SEP"
  echo "MVN=$MVN_RESOLVED"
  echo "JAVA=$JAVA_RESOLVED"
  echo "PWD=$PWD"
  exit 0
fi

if [ ! -f "$MVN_RESOLVED" ] && ! command -v "$MVN_RESOLVED" >/dev/null 2>&1; then
  echo "[FAIL] 找不到 maven：$MVN_RESOLVED（可用 MVN=/path/to/mvn 指定）" >&2
  exit 1
fi
if [ ! -x "$JAVA_RESOLVED" ] && ! command -v "$JAVA_RESOLVED" >/dev/null 2>&1; then
  echo "[FAIL] 找不到 java：$JAVA_RESOLVED（可用 JAVA_HOME 指定）" >&2
  exit 1
fi

echo "== 1/6 生成生产运行时类路径（剔除所有 test/provided 作用域的 jar） =="
# ⚠️ **离线还是联网，取决于 CI 环境**：
#   CI（GitHub Actions 会设 CI=true）必须**联网** ——
#     `dependency:list` / `dependency:build-classpath` 属于 **maven-dependency-plugin**，
#     它**不在 test 生命周期里** ⇒ `mvn test` 不会预先把它拉下来；
#     而 setup-java 的 maven cache key 跟 pom.xml 绑定 ⇒ pom 一改就 miss
#     ⇒ 冷缓存的 runner 上用 `mvn -o` 会直接失败
#     （Cannot access central in offline mode）。
#     **这个坑是在 Linux 冷缓存 runner 上实测出来的**（2026-11-06，运维验证时发现）。
#   本地：保持离线（-o），快 —— 本地跑过一次后插件已经在仓库里。
if [ "${CI:-}" = "true" ]; then
  MVN_OFFLINE=""
else
  MVN_OFFLINE="-o"
fi
"$MVN_RESOLVED" $MVN_OFFLINE -q dependency:list -DoutputFile=target/deplist.txt || exit 1
"$MVN_RESOLVED" $MVN_OFFLINE -q dependency:build-classpath -Dmdep.outputFile=target/cp-all.txt || exit 1

# 从 dependency:list 里取出 test/provided/system 作用域的 artifactId
# 注意两点：
#   1) Maven 生成的清单是 CRLF 行尾
#   2) scope 后面还跟着 " -- module xxx"，要先按空格切出第一个词才是 scope
awk -F: '{ split($5, a, " "); s = a[1]; gsub(/\r/, "", s);
            if (s == "test" || s == "provided" || s == "system") print $2 }' \
  target/deplist.txt | sort -u > target/excluded-artifacts.txt

if [ ! -s target/excluded-artifacts.txt ]; then
  echo "[FAIL] 没从 dependency:list 里解析出任何 test 作用域依赖，类路径算错了" >&2
  exit 1
fi

# 从全量类路径里剔掉这些 artifactId（jar 路径形如 .../<artifactId>/<version>/<artifactId>-<version>.jar）
# 注意分隔符要按平台取（CP_SEP），写死 ';' 在 Linux 上会把整条类路径当成一个路径
tr "$CP_SEP" '\n' < target/cp-all.txt | awk -v sep="$CP_SEP" '
  BEGIN { while ((getline line < "target/excluded-artifacts.txt") > 0) if (line != "") drop[line] = 1 }
  {
    if ($0 == "") next
    norm = $0; gsub(/\\/, "/", norm)
    n = split(norm, p, "/")
    art = p[n - 2]
    if (!(art in drop)) { if (out != "") out = out sep; out = out $0 }
  }
  END { print out }
' > target/cp-runtime.txt

if grep -q 'sdk-server' target/cp-runtime.txt; then
  echo "[FAIL] 生产运行时类路径里出现了 sdk-server（它只该在 test 作用域），验证前提不成立" >&2
  exit 1
fi
if ! grep -q 'jaxb-api' target/cp-runtime.txt; then
  echo "[FAIL] 生产运行时类路径里没有 javax.xml.bind:jaxb-api —— ByteString 节点号会 NoClassDefFoundError" >&2
  exit 1
fi
if ! grep -q 'sdk-client' target/cp-runtime.txt; then
  echo "[FAIL] 生产运行时类路径里连 sdk-client 都没有，类路径算错了" >&2
  exit 1
fi
echo "OK 生产运行时类路径：$(tr "$CP_SEP" '\n' < target/cp-runtime.txt | wc -l) 个 jar，无 sdk-server，含 jaxb-api"

echo "== 2/6 生成测试类路径（Server 端要用） =="
"$MVN_RESOLVED" $MVN_OFFLINE -q dependency:build-classpath -Dmdep.includeScope=test \
      -Dmdep.outputFile=target/cp-test.txt || exit 1

echo "== 3/6 编译 =="
"$MVN_RESOLVED" $MVN_OFFLINE -q test-compile || exit 1

TEST_CP="target/test-classes${CP_SEP}target/classes${CP_SEP}$(cat target/cp-test.txt)"
RUNTIME_CP="target/test-classes${CP_SEP}target/classes${CP_SEP}$(cat target/cp-runtime.txt)"
# 「缺 jaxb」对照组：把 jaxb-api 从生产运行时类路径里剔掉，模拟依赖没进交付包
NO_JAXB_CP="$(tr "$CP_SEP" '\n' < target/cp-runtime.txt | grep -v 'jaxb-api' | tr '\n' "$CP_SEP" | sed "s/${CP_SEP}\$//" || true)"

echo "== 4/6 启动本地 OPC UA Server（测试类路径） =="
rm -f target/opcua-server.log
"$JAVA_RESOLVED" -cp "$TEST_CP" com.factory.hub.driver.opcua.OpcUaServerLauncher "$PORT" \
      > target/opcua-server.log 2>&1 &
SERVER_PID=$!

READY=0
for _ in $(seq 1 60); do
  if grep -q '^READY$' target/opcua-server.log 2>/dev/null; then
    READY=1
    break
  fi
  if ! kill -0 "$SERVER_PID" 2>/dev/null; then
    break
  fi
  sleep 1
done

cleanup() {
  kill "$SERVER_PID" 2>/dev/null
  wait "$SERVER_PID" 2>/dev/null
}
trap cleanup EXIT

if [ "$READY" != "1" ]; then
  echo "[FAIL] OPC UA Server 没起来，日志：" >&2
  tail -40 target/opcua-server.log >&2
  exit 1
fi
grep -a -E '^(ENDPOINT|NAMESPACE_INDEX)=' target/opcua-server.log

echo "== 5/6 用生产运行时类路径跑驱动探针 =="
PROBE_OUT="$("$JAVA_RESOLVED" -cp "$RUNTIME_CP" com.factory.hub.driver.opcua.OpcUaRuntimeProbe "$PORT" 2>&1)"
PROBE_RC=$?
echo "$PROBE_OUT"

if [ "$PROBE_RC" != "0" ]; then
  echo "[FAIL] 生产运行时类路径上驱动不可用（退出码 $PROBE_RC）" >&2
  exit 1
fi

echo "== 6/6 对照组：把 jaxb-api 从类路径里剔掉，验证防护有效（坏点只丢一个，不整批失败） =="
NOJAXB_OUT="$("$JAVA_RESOLVED" -cp "target/test-classes${CP_SEP}target/classes${CP_SEP}${NO_JAXB_CP}" \
      com.factory.hub.driver.opcua.OpcUaRuntimeProbe "$PORT" --expect-missing-jaxb 2>&1)"
NOJAXB_RC=$?
echo "$NOJAXB_OUT"

if [ "$NOJAXB_RC" != "0" ]; then
  echo "[FAIL] 缺 javax.xml.bind 时防护没生效（退出码 $NOJAXB_RC）" >&2
  exit 1
fi

echo "[PASS] 驱动在 Spring Boot 4.1.2 的生产运行时类路径上可用；缺 jaxb 时的防护也生效"
exit 0
