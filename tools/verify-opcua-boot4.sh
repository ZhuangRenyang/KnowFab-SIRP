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
# 探针会连读三类节点：
#   ns=2;s=TestTag1  字符串节点号
#   ns=2;s=TestTag2  字符串节点号
#   ns=2;b=AAEC      ByteString 节点号 ← 走 javax.xml.bind.DatatypeConverter.parseBase64Binary，
#                    这个类在 Boot 4 的类路径上曾经整个消失（现在 pom 里补在 runtime 作用域）
#
# 用法： bash tools/verify-opcua-boot4.sh [端口]
# 退出码：0 = 驱动在生产运行时类路径上可用；1 = 不可用

set -uo pipefail

cd "$(dirname "$0")/.." || exit 1

PORT="${1:-14840}"
MVN="${MVN:-../tools/apache-maven-3.9.9/bin/mvn}"
JAVA_HOME="${JAVA_HOME:-/c/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot}"
JAVA="${JAVA_HOME}/bin/java"

if [ ! -f "$MVN" ]; then
  echo "[FAIL] 找不到 maven：$MVN" >&2
  exit 1
fi
if [ ! -x "$JAVA" ]; then
  echo "[FAIL] 找不到 java：$JAVA" >&2
  exit 1
fi

echo "== 1/5 生成生产运行时类路径（剔除所有 test/provided 作用域的 jar） =="
"$MVN" -o -q dependency:list -DoutputFile=target/deplist.txt || exit 1
"$MVN" -o -q dependency:build-classpath -Dmdep.outputFile=target/cp-all.txt || exit 1

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
tr ';' '\n' < target/cp-all.txt | awk '
  BEGIN { while ((getline line < "target/excluded-artifacts.txt") > 0) if (line != "") drop[line] = 1 }
  {
    if ($0 == "") next
    norm = $0; gsub(/\\/, "/", norm)
    n = split(norm, p, "/")
    art = p[n - 2]
    if (!(art in drop)) { if (out != "") out = out ";"; out = out $0 }
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
echo "OK 生产运行时类路径：$(tr ';' '\n' < target/cp-runtime.txt | wc -l) 个 jar，无 sdk-server，含 jaxb-api"

echo "== 2/5 生成测试类路径（Server 端要用） =="
"$MVN" -o -q dependency:build-classpath -Dmdep.includeScope=test \
      -Dmdep.outputFile=target/cp-test.txt || exit 1

echo "== 3/5 编译 =="
"$MVN" -o -q test-compile || exit 1

TEST_CP="target/test-classes;target/classes;$(cat target/cp-test.txt)"
RUNTIME_CP="target/test-classes;target/classes;$(cat target/cp-runtime.txt)"

echo "== 4/5 启动本地 OPC UA Server（测试类路径） =="
rm -f target/opcua-server.log
"$JAVA" -cp "$TEST_CP" com.factory.hub.driver.opcua.OpcUaServerLauncher "$PORT" \
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

echo "== 5/5 用生产运行时类路径跑驱动探针 =="
PROBE_OUT="$("$JAVA" -cp "$RUNTIME_CP" com.factory.hub.driver.opcua.OpcUaRuntimeProbe "$PORT" 2>&1)"
PROBE_RC=$?
echo "$PROBE_OUT"

if [ "$PROBE_RC" != "0" ]; then
  echo "[FAIL] 生产运行时类路径上驱动不可用（退出码 $PROBE_RC）" >&2
  exit 1
fi
echo "[PASS] 驱动在 Spring Boot 4.1.2 的生产运行时类路径上可用"
exit 0
