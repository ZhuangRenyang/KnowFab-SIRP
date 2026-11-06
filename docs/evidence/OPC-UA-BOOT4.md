# OPC UA 驱动在 Spring Boot 4.1.2 下的兼容性验证

> **范围**：`device-hub` 升级 Spring Boot 3.2.5 → 4.1.2 后，唯一未验证的链路 —— OPC UA 驱动（Eclipse Milo）。
> **分支**：`upgrade/boot-41`　**日期**：2026-11-06
> **结论**：**OPC UA 在 Boot 4.1.2 下可用。** 但有一条必须做的依赖修正（`javax.xml.bind:jaxb-api` 提到 runtime 作用域），
> 以及两个必须写进注释的已知边界。详见 [§1 结论](#1-结论)。

本文记录的是**验证过程**，不只是结论。因为结论会过期，而「这条结论是怎么被推翻又重建的」不会 ——
那段过程记录在 [§5 修订记录](#5-修订记录这份结论是怎么被推翻又重建的)，是本文最该被保留的部分。

---

## 1. 结论

| 维度 | 结论 | 证据强度 |
|---|---|---|
| Netty 4.2.19.Final 与 milo 0.6.11（声明 4.1.97.Final）二进制兼容 | 成立 | 官方迁移指南逐条核对 + 生产运行时类路径实测建会话/批量读/写入全通 |
| milo 零 Jackson 依赖，Boot 4 的 Jackson 2→3 换包名不影响它 | 成立 | 8 个 jar 全解包二进制级 grep + `jdeps` 双向核对 |
| `OpcUaClient.create` 第三参是 `Function` 不是 `Consumer` | 与 Boot 版本无关 | `javap` 直读 0.6.11 jar + 编译通过 |
| **必须做的依赖修正** | `javax.xml.bind:jaxb-api:2.3.1` 提到 **runtime/compile** 作用域 | 字节码 + 独立进程实测 + 变异验证钉住 |
| **已知边界 1** | ByteString 节点号（`ns=2;b=...`）需要 `javax.xml.bind`；字符串/数字节点不受影响 | 独立进程实测 + 变异验证钉住 |
| **已知边界 2** | 服务端 endpoint 带子路径时连不上（`Bad_TcpEndpointUrlInvalid`） | 实测矩阵 + 回归用例钉住 |

**⇒ 升级判定：Boot 4.1.2 对 OPC UA 路径不构成阻塞。**

---

## 2. 当时的三个怀疑点

`pom.xml` 里 milo 的依赖注释写着「版本锁 0.6.x：API 稳定，Netty 4.1 与 Spring Boot 3.2 兼容」。
Boot 4.1.2 换掉了 Netty 版本，于是有三个怀疑：

1. **milo 0.6.11 内部用 Jackson 2**，Boot 4 统一成 Jackson 3（包名 `com.fasterxml.jackson` → `tools.jackson`），
   会 `NoClassDefFoundError`。
2. **Boot 4.1.2 把 Netty 抬到了 4.2.x**，milo 当年是照 Netty 4.1 写的，二进制不兼容。
3. **milo 客户端 API 与 Boot 版本有关**（`OpcUaClient.create` 第三参是 `Consumer` 还是 `Function`）。

### 2.1 怀疑点一：milo 到底用不用 Jackson —— 不用

把 milo 的 8 个 jar 全部解包（`sdk-client` / `sdk-core` / `stack-client` / `stack-core` / `sdk-server` /
`stack-server` / `bsd-core` / `bsd-generator`），共 2651 个文件，做二进制级 grep：

```
$ cd /tmp/milox && for j in $(find ~/.m2/repository/org/eclipse/milo -name '*.jar'); do unzip -o -q "$j" -d .; done
$ grep -r -a -i -l "jackson" . | wc -l
0
$ grep -r -a -i -l "fasterxml" . | wc -l
0
$ grep -r -a -i -l "tools.jackson" . | wc -l
0
```

`jdeps -verbose:class -filter:none` 逐 jar 扫描，milo 引用的三方包只有 Guava、Netty、slf4j、BouncyCastle，
**没有任何 Jackson 引用**。milo 的 OPC UA 二进制结构编解码是**自己手写的**
（`org.eclipse.milo.opcua.stack.core.serialization.OpcUaBinaryStreamEncoder/Decoder`）。

⇒ 怀疑点一**不成立**。Boot 4 的 Jackson 换包名只影响项目自己的代码。

### 2.2 怀疑点二：Netty 版本与 4.1 → 4.2 的破坏性变更

```
$ mvn org.apache.maven.plugins:maven-dependency-plugin:3.10.0:tree
+- org.eclipse.milo:sdk-client:jar:0.6.11:compile
|  +- org.eclipse.milo:stack-client:jar:0.6.11:compile
|  |  +- org.eclipse.milo:stack-core:jar:0.6.11:compile
|  |  |  +- io.netty:netty-codec:jar:4.2.19.Final:compile
|  |  |  +- io.netty:netty-handler:jar:4.2.19.Final:compile
|  |  |  +- io.netty:netty-resolver:jar:4.2.19.Final:compile
|  |  +- com.digitalpetri.netty:netty-channel-fsm:jar:0.8:compile
|  +- io.netty:netty-codec-http:jar:4.2.19.Final:compile
|  |     +- io.netty:netty-common:jar:4.2.19.Final:compile
|  |     +- io.netty:netty-codec-base:jar:4.2.19.Final:compile
```

两个版本号的出处：

| 项 | 值 | 出处 |
|---|---|---|
| Boot 4.1.2 管理的 Netty | `4.2.19.Final` | `spring-boot-dependencies-4.1.2.pom` 的 `<netty.version>` |
| milo 0.6.11 声明的 Netty | `4.1.97.Final` | `opc-ua-stack-0.6.11.pom` 的 `<netty.version>` |

Boot 的 BOM 把 milo 的 4.1.97 抬到了 4.2.19。按 Netty 官方 4.2 迁移指南逐条核对本项目：

| Netty 4.2 的破坏性变更 | 对本项目 | 依据 |
|---|---|---|
| TLS 客户端默认开启 hostname 校验 | 不成立 | 驱动只用 `SecurityPolicy.None`，不走 TLS |
| 默认内存分配器改成 adaptive | 不成立 | milo 字节码里显式用 `PooledByteBufAllocator`，4.2.19 里 `PooledByteBufAllocator.DEFAULT` 仍在 |
| BouncyCastle 升 1.80（`*-jdk18on`） | 当前不成立 | 4.2 的 BC 集成是 `optional` 且反射加载，字节码层面零引用 |
| `netty-codec` 拆成多个子模块 | 已自动解决 | `netty-codec-base` 被 Maven 传递带入；`io.netty.handler.codec.*` 包名不变 |
| io_uring transport 改名 | 不成立 | 不用 io_uring |
| 4.1 与 4.2 不能共存于同一 classpath | 不成立 | 调解后只有 4.2.19 一个版本 |

官方原文：*"Netty 4.2 is largely backwards compatible with Netty 4.1"* / *"without any API breakage"*。

milo 引用的 15 个 netty 类在 4.2.19 里逐个验证存在（含已弃用但未删除的 `NioEventLoopGroup`）；
第三方 `netty-channel-fsm:0.8` 只引用 `Channel` / `ChannelFuture` / `IdleState*` 等稳定类。

⇒ 怀疑点二**版本冲突成立、二进制兼容性成立**。

### 2.3 怀疑点三：milo 的客户端 API 与 Boot 无关

milo 是普通三方库，Boot 不改它的 API。`javap` 直读 0.6.11 的 jar：

```java
public static OpcUaClient create(
    String,
    Function<List<EndpointDescription>, Optional<EndpointDescription>>,
    Function<OpcUaClientConfigBuilder, OpcUaClientConfig>)   // 是 Function，不是 Consumer
```

驱动里的 lambda 有返回值（`return cfg;`），与 `Function` 匹配 —— 编译通过即证明。
其余 API（`setApplicationName` / `setApplicationUri` / `setRequestTimeout` / `setSessionTimeout` / `build` /
`read` / `writeValue` / `disconnect` / `getSession`）全部核对存在且签名一致。

⇒ **唯一真实的耦合点是依赖调解（Netty / Jackson / Guava / BouncyCastle），不是 API。**

---

## 3. 端点 URL 的匹配规则（三部分归一化）

这是排查现场「连不上」时最需要的一张表。实测结论：

| URL 部分 | 是否归一化 | 实测 |
|---|---|---|
| **尾斜杠** | 归一 | `opc.tcp://host:port` 能匹配服务端 advertise 的 `opc.tcp://host:port/` |
| **主机名** | 归一（会解析） | 服务端 advertise `127.0.0.1`，驱动连 `localhost` → **照样连上** |
| **路径** | **不归一** | 服务端 advertise `opc.tcp://host:port/subpath`，驱动连 `opc.tcp://host:port` → **错位** |

⇒ 规则是：**「你给客户端的 URL 的『路径部分』必须和服务端 advertise 的 endpointUrl 的路径部分一致；
主机部分与尾斜杠都会被归一。」**
⇒ **端点错位的唯一现实来源是「服务端把 endpoint 挂在子路径下」。**

失败时的异常链（最外层是 Netty 的 `DecoderException`，**很容易被误判成网络不通**）：

```
io.netty.handler.codec.DecoderException: UaException: status=Bad_TcpEndpointUrlInvalid,
    message=unrecognized endpoint url: opc.tcp://127.0.0.1:14840
  Caused by: UaException: status=Bad_TcpEndpointUrlInvalid,
    message=unrecognized endpoint url: opc.tcp://127.0.0.1:14840/discovery
```

**现场排查口径：看到 `Bad_TcpEndpointUrlInvalid` / `unrecognized endpoint url` 就是端点形态对不上，不要去查网线。**

---

## 4. 两个已知边界

### 4.1 ByteString 节点号需要 `javax.xml.bind`

milo 的 `NodeId` 直接调用 JAXB 2 的 `DatatypeConverter` 做 Base64 解码（`javap -c` 实读）：

```
org.eclipse.milo.opcua.stack.core.types.builtin.NodeId:
  154: invokestatic  javax/xml/bind/DatatypeConverter.printBase64Binary:([B)Ljava/lang/String;
  251: invokestatic  javax/xml/bind/DatatypeConverter.parseBase64Binary:(Ljava/lang/String;)[B
```

JDK 11+ 已移除 `javax.xml.bind`，Spring Boot 4 只带 `jakarta.xml.bind` 4.x（包名不同，可共存）。
驱动 `OpcUaDriver` 直接调 `NodeId.parse(raw.trim())`，所以：

| 点表里的 nodeId 写法 | 走哪条分支 | 没有 `jaxb-api` 时 |
|---|---|---|
| `ns=2;s=xxx` 字符串节点 | 不进 base64 分支 | **完全不受影响** |
| `ns=2;i=1234` 数字节点 | 不进 base64 分支 | **完全不受影响** |
| `ns=2;b=xxx` ByteString 节点 | 进 base64 分支 | **抛 `NoClassDefFoundError`** |

**为什么这条特别危险**：`NoClassDefFoundError` 继承 `LinkageError` → `Error`，**不是 `Exception`**，
所以驱动里 `catch (Exception e)` 抓不到它 → 直接从 `read()` 抛出去 →
**点表里只要有一个 ByteString 节点号，这台设备本轮所有点位一个都读不到**，而且每轮采集都抛。
现场表现是「设备在线但读不到任何点」，且是**整台设备的所有点**，不是个别点。

⇒ **必须把 `javax.xml.bind:jaxb-api:2.3.1` 放在 runtime/compile 作用域**（不是 test）。
只放 test 作用域时，`mvn test` 照样全绿（测试类路径上有它），只有生产运行时才会炸。

现在的防护是双层的：
- `pom.xml` 里 `jaxb-api` 在 runtime 作用域（compile 亦可），保证生产类路径上有它
- 驱动侧对 `LinkageError` 做了防护：**坏点只丢自己，同批次其它点照常读到**，不整批失败

### 4.2 服务端 endpoint 带子路径时连不上

驱动的 URL 是写死的 `opc.tcp://<host>:<port>`（`OpcUaDriver` 里拼 URL 的那一行），**不带 path**。
milo 客户端只有两个入口：① 你给它的那个 URL ② 那个 URL + `/discovery`；服务端只认自己 advertise 出来的 endpointUrl。

两个不同的现实场景，要分别覆盖：

| | 场景 | 走到哪 | 期望行为 |
|---|---|---|---|
| **6a** | 用户在主机栏填了 `192.168.1.10/milo` | 拼 URL 之前就拒绝 | `IllegalArgumentException`，消息含「不能带路径」 |
| **6b** | **主机栏是干净的**（用户不知道自己 server 有子路径），服务端 advertise 了 `/subpath` | 走 milo → `Bad_TcpEndpointUrlInvalid` | `IllegalStateException`，消息含「端点 URL 与服务端不匹配」，且 `getCause()` 保留原始 `UaException` |

**6b 不能省**：6a 的前置校验只能拦住「用户主动填了路径」，拦不住「服务端有路径而用户不知道」——
后者才是 6b，也是错误翻译那段代码**唯一**的触发方式。省掉 6b 等于让那段翻译没有回归保护。

实测输出（6b）：

```
server advertise = [opc.tcp://127.0.0.1:14862/subpath]
异常类型: java.lang.IllegalStateException
message : 端点 URL 与服务端不匹配（不是网络不通）：我们拼的是 opc.tcp://127.0.0.1:14862，
          服务端只认自己 advertise 出来的 endpointUrl，与它对不上。…
cause   : org.eclipse.milo.opcua.stack.core.UaException
```

主流 PLC（Kepware / 西门子 S7-1500 / Ignition）都是无子路径的，实际不受影响。

### 4.3 默认端口的归属（已发现并修复）

**这条不在最初的怀疑点里，是查「端口不一致就报错」那条 UX 问题时挖出来的。**

#### 症状与根因

「主机栏填了 `:4840`、端口栏留空 ⇒ 报『主机栏端口与连接参数端口不一致』」——
看起来是那条报错规则太严，其实**根因是「端口栏留空」被默认成了 502**。

`502` 是 **Modbus** 的端口，却被当成**协议无关的全局常量**写进了 9 处
（实体字段默认、控制器、模板控制器、服务、驱动上下文、前端模板、表单初始值、表单编辑回填、表单提交兜底）。

后果两条：
1. **功能缺陷**：新建 OPC UA 设备不填端口 ⇒ 一路 502 ⇒ 驱动去连 `opc.tcp://host:502` ⇒ **必然连不上**。
   连带地，驱动里那句 `port <= 0 ? 4840 : port` 的兜底 **在生产里永远不成立**，是死代码。
2. **每加一个协议漏一处**：加第三个协议还要再改一遍那 9 处，漏改的那个就去连错端口。OPC UA 就是这次漏掉的那个。

#### 修复：口径收回到一处

`ProtocolDefaults`（`src/main/java/com/factory/hub/driver/ProtocolDefaults.java`）是「协议 → 默认端口」的**唯一口径**：
Modbus 502 / OPC UA 4840。实体、控制器、服务、大屏都从这里查，谁也不许再写死 502。
大屏显示端口也用同一个源，避免出现「界面写 502、实际连 4840」。

驱动里各自那份 `port <= 0 ? DEFAULT : port` 是**防御性兜底**（万一上游漏了解析，不至于拼出 `opc.tcp://host:0`），
与 `ProtocolDefaults` 不冲突：正常时走不到。

#### 这里有两个被驳回的建议，值得记下来

**① 「用 0 当未填的哨兵」——错的，会引入更隐蔽的 bug。**

`el-input-number` 挂了 `:min="1"`，它的 `verifyValue`（element-plus 2.14.8 源码）：

```js
let newVal = !value ? Number(value) : Number.parseFloat(String(value));
if (isNil(value) || Number.isNaN(newVal)) return null;    // null → 原样返回
...
if (newVal > max || newVal < min) {
    newVal = newVal > max ? max : min;                      // 0 < min(1) → 夹成 1
    if (newVal !== value) update && emit("update:modelValue", newVal);   // 反向 emit 回父组件
}
```

⇒ 表单里用 0 表示「未填」，**用户会在端口框里看到 1，然后提交 1** ——
从「连不上 502」变成「连不上 1」，后者更难查（1 是特权端口，报的是权限错）。

**最终落地是分层的，两层各用各的表示，但都是「未填」：**

| 层 | 表示 | 为什么 |
|---|---|---|
| **表单内部**（初值 / 编辑回填 / 套模板） | `null` | `el-input-number` 的 `verifyValue` 对 `null` 走 `isNil` 分支原样返回，能显示空框；用 0 会被夹成 1 |
| **提交给后端** | `0` | 后端 `port == null || port <= 0` 两条都覆盖，0 表达「未指定」语义明确 |

前端用一个 `normPort()` 把 0 / null / 非法值统一归一成 `null`（表单内哨兵），三处回填都走它。

**这条是前端专家用 SSR 探针实测出来的，不是我推的** ——
我提「用 0」的时候没有去查 element-plus 的真实行为，**差点让一个更隐蔽的 bug 进生产**。
教训同 §5：**方案不能只靠推理，要用工具实测过再提。**
（后来的前端门禁把这条变成了可执行的事实：跑真实 element-plus 断言「null 不被夹、0 被夹成 1」，
element-plus 哪天改了夹取行为，门禁会红提醒重新评估，而不是默默失效。）

**② 「把 `deviceTemplates.ts` 里的 OPC UA 模板端口改成 4840」——前提就是错的。**

该文件里 9 个模板全是 Modbus（8 个 `modbus-tcp` + 1 个 `modbus-rtu`），端口都是 502，**对 Modbus 是对的，不该动**。
真正的「OPC UA 内置示例」在 `api/devices.ts` 的 `TPL.opcua`。

而它带出一个**更值钱的真 bug**：`useTpl('opcua')` **只填点表，不改协议也不改端口** ⇒
用户点「OPC UA 示例」，得到的是「点表是 OPC UA 的、协议还是 modbus-tcp、端口还是 502」的错配配置 ——
点表里全是 nodeId，却按 Modbus 去读。已改成套用示例时协议与端口一起跟着换。

⇒ **结论：前端的 `PROTO_DEFAULT_PORT` 不是权威副本，只是建议值表**，口径须与后端 `ProtocolDefaults` 对齐。

**但要注意：这份副本目前是「功能性」的，不是纯展示。** 链路：

```
onProto()（DeviceFormDialog.vue:247-250）
    f.port = PROTO_DEFAULT_PORT[f.protocol]      ← 前端副本被写进表单
      → 提交 port: Number(f.port) || 0 = 4840
        → 落库成显式值
          → DeviceController:445 的 if (port == null || port <= 0) 分支根本不走
            → ProtocolDefaults.defaultPort('opcua') 对这台设备从不被调用
```

⇒ 漂移的后果是「**设备连到错端口**」，不是「提示文案对不上」。
⇒ 所以「前后端口径一致」这条**构建期断言是必须的**（不是可选）：漂移要在构建期暴露，不能到现场。

**为什么不改成「后端暴露只读接口、前端运行时拉」？** 因为 `ProtocolDefaults` 是**编译期常量**，
为它加运行时接口 = 给静态常量引入网络依赖 + 加载态 + 新失败模式
（接口挂了前端还是得回落到本地副本，漂移原样回来，只是从构建期变成运行时）；
本项目是「单 jar + 单页、离线可跑」的交付形态。**正解是构建期断言，不是运行时同步。**


### 4.4 护栏：口径收好了不代表不会再漂

**这是本节最容易漏掉的一步。** `ProtocolDefaults` 把口径收成一处之后，
如果**没有任何东西盯着它**，半年后有人新写一个 `? 502 :`、或把 `ProtocolDefaults` 改回硬编码，
没人会知道 —— 直到现场新建 OPC UA 设备连不上。

⇒ 前后端各配了一条对称的门禁：

| 侧 | 检查 | 位置 |
|---|---|---|
| 前端 | 表单不许把「未填端口」兜成 502；哨兵不许改成 0；`:min="1"` 不许去掉；模板套用必须走 `normPort`；**跑真实 element-plus 验「null 不被夹 / 0 被夹成 1」** | `frontend/scripts/check-port-semantics.mjs`（`npm run check:port`，已接进 `tools/ci-gate.sh`） |
| 后端 | 除白名单外，`src/main/java` 里不许再出现硬编码端口字面量 | `scripts/check-protocol-port-literals.mjs`（已接进 `tools/ci-gate.sh`） |

**后端那条的三个设计要点**（比「扫一遍数字」难得多，但少一样就会变成假绿）：

1. **白名单按「文件 + 代码特征 + 理由」匹配，不是按行号。**
   行号会漂 —— 漂了要么误报，要么**给错的那一行开了绿灯**。
2. **每条白名单都必须写「为什么合法」。**
   白名单最大的风险不是「拦不住新东西」，是「过两年没人知道为什么这里能写 502」。
   理由写不出来 ⇒ 说明这处不该在白名单里。
3. **白名单过期也要红**（白名单允许 N 处但代码里只匹配到 M<N ⇒ 也红）。
   白名单会腐烂，得有人盯着。

**扫描范围为什么是「端口语境」而不是「所有数字」**（实测数据）：

| 方案 | 判定 | 理由 |
|---|---|---|
| 只找 `502` | 不够 | 加第三个协议（默认端口比如 9600）时**根本不会被抓到**，门禁悄悄失去保护 |
| 扫所有 3-4 位数字 | 不可行 | 订单号、超时值、寄存器地址全是 3-4 位数字，白名单会失控 |
| **扫「提到 port 的行」里的 3-4 位数字** | 采用 | 实测 **8 处命中，全部是合法的协议端口位置，零误报** |

⇒ 加第三个协议时**不用改门禁**：新协议的端口默认值会被自动扫出来，并被要求进白名单写清理由 ——
这一步本身就逼着人想清楚「这个端口该从 `ProtocolDefaults` 取」。

**这条门禁的残留缺口（不夸大）**：它依赖命名约定（端口常量带 `port` / `PORT`）。
写成 `OPC_UA_DEFAULT = 9600` 这种名字就抓不到。
靠「代码库命名约定 + 格式契约（映射必须保持每行一条 `Map.entry("协议", 常量名)`）」双保险。

**门禁放在 `--skip-be` 块外面** —— 这不是「语义别扭」，是**轴**：
轴是「要不要 Java / 数据库」，不是「是不是后端逻辑」。
纯静态检查（字典校验 / i18n / 端口字面量）一律放块外 ——
否则门禁自检用 `--skip-be` 跑时它会被跳过，**没人能证明这道门禁还活着**
（2026-11-06 在报警码字典校验上踩过：放在块里 → 自检跑不到 → 变异抓不到门禁失效 → 自检自己骗了自己）。

**还有一条不能省的**：每条门禁都要在 `tools/ci-gate-selftest.sh` 里配一条**变异**，
证明它真的会红。手动做过的变异不算数 —— 没落进脚本的，证明就断了。


---

## 5. 修订记录：这份结论是怎么被推翻又重建的

> **这一节是本文最有价值的部分。** 结论三个月后就过期了，「怎么发现自己错了」不会。

### 5.1 初版结论错在哪

初版评估写的是：

> **`javax.xml.bind:jaxb-api` 保持 test 作用域即可，不需要提到 runtime。**
> 依据：jdeps 全量扫描，`javax.xml.bind.DatatypeConverter` 只被 `stack-core` 的 **XML 编解码器**引用，
> 客户端没有任何类引用 `OpcUaXmlStreamDecoder`（客户端走二进制通道）。

**这条是错的。** 被项目总监用独立进程实测纠正，我复现后确认。

### 5.2 错因：工具报「没有」，其实是扫描失败

当时的扫描命令是：

```bash
jdeps -verbose:class -filter:none <milo.jar> | grep 'javax.xml.bind'
```

**`jdeps` 的输出里含非 UTF-8 字节**（缺失类标记），`grep` 把它当成二进制文件处理，
**静默跳过了匹配** —— 命令不报错、退出码是 0，但结果不完整。
我从一个**不完整的扫描**推出了**过宽的结论**，而且没察觉工具报的是「扫描失败」而不是「确实没有」。

正确做法是直接扫字节码常量池：

```bash
grep -r -a -l "javax/xml/bind/DatatypeConverter" <解包后的 milo 目录>
```

结果命中 **3 个** class，不是 1 个：

```
OpcUaXmlStreamDecoder.class      XML 编解码器 —— 只有服务端装标准命名空间时用
ExpandedNodeId.class
NodeId.class                      ← 驱动直接调的就是它，我漏了
```

### 5.3 这不是第一次栽在同一类坑上

项目里已经栽过同一类坑多次：校验器改残、断言太松、变异写成编译红、有翻译代码没用例保护 ——
每次都是「工具报『没有』，其实是扫描失败」。

⇒ **写进方法论：工具报「没有」时，先怀疑工具。**
需要「不存在」的结论时，必须用**另一种手段**复核（这里是字节码常量池扫描，不是 `jdeps` + `grep`）。

### 5.4 同一个错，我连栽三次

这三次是同一个错：**拿「我以为」当「事实」**。

| # | 我做了什么 | 真相 | 怎么发现的 |
|---|---|---|---|
| ① | 用 `jdeps` 扫 milo 对 `javax.xml.bind` 的引用，得出「客户端用不到，只有 XML 编解码器引用」 | **漏了 3 个 class 里的 2 个**，其中 `NodeId` 正是驱动直接调的那个 | team-lead 用独立进程实测纠正；我复现后确认。真因：`jdeps` 输出含非 UTF-8 字节 → `grep` 当二进制文件**静默跳过匹配** |
| ② | 建议前端「用 0 当未填的哨兵」 | **错的，会引入更隐蔽的 bug** —— `el-input-number` 的 `:min="1"` 把 0 夹成 1 并反向 emit，用户会看到并提交端口 1 | 前端专家用 SSR 探针实测 element-plus；我用 2.14.8 源码的 `verifyValue` 复核确认 |
| ③ | 判断「门禁只管 502 就够（B 方案误报太多不可行）」 | **没量 A′ 的误报面就下了结论**。实测「扫提到 port 的行里的 3-4 位数字」只有 **8 处命中、零误报**，比 A 覆盖得全、比 B 噪声小 | 我用门禁同款逻辑（剔注释+剔字符串）实跑统计后改判 |

**① 的教训是「工具报『没有』时先怀疑工具」**（输出被静默吞掉，命令却不报错、退出码是 0）；
**②③ 的教训是「结论不能只靠推理，要有工具证据」**。

三条指向同一件事：**验证工具本身也是会被骗的，而且骗你的时候往往一声不响。**
⇒ 每一条「不存在 / 没有 / 够用」的结论，都要用**另一种手段**复核一遍再写下来。

### 5.5 同类踩坑的其它几个实例（同样留在记录里）

| 看似显然的做法 | 实际结果 |
|---|---|
| `mvn dependency:build-classpath -Dmdep.includeScope=runtime` 拿「生产运行时类路径」 | **不可靠** —— 该参数不生效，输出里仍含 test 作用域的 jar。验证脚本改用 `dependency:list` 解析出 test/provided 的 artifactId 再从全量类路径里剔除 |
| 用「`localhost` vs `127.0.0.1`」造端点错位 | **行不通** —— 主机名会被解析归一，照样连上。端点错位只能靠**路径部分**造 |
| 「milo 服务端启动失败」⇒ 「milo 驱动坏了」 | **不成立** —— milo **服务端**装标准命名空间要用 `javax.xml.bind`，**客户端**不需要。是验证工具的依赖问题，不是被测代码的问题 |
| `mvn dependency:tree`（插件前缀）离线跑 | 插件前缀离线解析不到，要用完整坐标 `org.apache.maven.plugins:maven-dependency-plugin:3.10.0:tree` |

---

## 6. 验证手段与复现步骤

### 6.1 环境要求

| 工具 | 版本 | 备注 |
|---|---|---|
| JDK | 17（Temurin 17.0.20.1 实测） | Boot 4.1.2 的基线是 Java 17 |
| Maven | 3.9.16 实测 | 脚本可用 `MVN=/path/to/mvn` 指定 |
| Node | 22.x 实测 | 仅前端门禁需要；OPC UA 验证不需要 Node |
| MySQL | 不需要 | OPC UA 验证全程不碰数据库 |

> **Node 下载的坑（项目总监在 Linux 上实测踩到）**：直连 `nodejs.org` 下载会被截断（6.6 MB 残包，tar 报 EOF），
> 换 `registry.npmmirror.com/-/binary/node` 才完整。**下载后必须校验完整性**（`tar -tf` 能过才算数），
> 不能只看 curl 退出码。

### 6.2 跑生产运行时类路径验证（这是本轮最关键的一道门禁）

```bash
# Windows（Git Bash / MSYS）
MVN=/c/Users/<user>/.m2/wrapper/dists/apache-maven-3.9.16-bin/*/apache-maven-3.9.16/bin/mvn \
  bash tools/verify-opcua-boot4.sh

# Linux（CI）
MVN=mvn bash tools/verify-opcua-boot4.sh

# 只看环境解析，不干活
bash tools/verify-opcua-boot4.sh --dry-run
```

**这个脚本在干什么**（这是它存在的理由，别当成「又写了个脚本」）：
单元测试跑在**测试类路径**上，那条路径上多了 `org.eclipse.milo:sdk-server`（本地起 OPC UA Server 用），
而**交付到客户现场的类路径上没有它**。所以「测试绿了」不等于「现场能用」。
脚本把两件事拆到两个进程：进程 A（测试类路径）起真的 milo OPC UA Server，
进程 B（**生产运行时类路径** = 全量依赖类路径剔除所有 test/provided 作用域的 jar）用项目里的 `OpcUaDriver` 去连、去读、去写。

实测输出（节选，真实输出）：

```
== 1/6 生成生产运行时类路径（剔除所有 test/provided 作用域的 jar） ==
OK 生产运行时类路径：124 个 jar，无 sdk-server，含 jaxb-api
== 4/6 启动本地 OPC UA Server（测试类路径） ==
ENDPOINT=opc.tcp://127.0.0.1:14840
NAMESPACE_INDEX=2
== 5/6 用生产运行时类路径跑驱动探针 ==
PROBE connected=true
PROBE values={tag1=42.5, tag2=7.25, bstr=66.5}
PROBE byteStringNodeId=66.5
PROBE writeBack=88.25
PROBE OK
== 6/6 对照组：把 jaxb-api 从类路径里剔掉，验证防护有效（坏点只丢一个，不整批失败） ==
PROBE connected=true
PROBE mixedValues={tag1=88.25}
PROBE OK (missing-jaxb 防护有效)
[PASS] 驱动在 Spring Boot 4.1.2 的生产运行时类路径上可用；缺 jaxb 时的防护也生效
```

**第 6 步是「对照组」**：把 `jaxb-api` 从类路径里剔掉再跑一次，验证「坏点只丢一个、同批次其它点照常读到」。
注意 `mixedValues={tag1=88.25}` —— 字节串那个点失败了，**其它点照样读到了**。

> **控制台编码**：Windows 控制台是 GBK，脚本里中文日志会显示成乱码。
> 脚本的 `PROBE` / `ENDPOINT` / `NAMESPACE_INDEX` 开头几行是 ASCII，不受影响。
> 要看清中文请用 `chcp 65001` 或把输出重定向到文件再读。

### 6.3 跑 OPC UA 专项测试

```bash
mvn test -Dtest='com.factory.hub.driver.opcua.*Test' -DfailIfNoSpecifiedTests=false
```

实测输出：

```
[INFO] Tests run: 9,  Failures: 0, Errors: 0, Skipped: 0 -- in com.factory.hub.driver.opcua.OpcUaDriverBoot4ClasspathTest
[INFO] Tests run: 13, Failures: 0, Errors: 0, Skipped: 0 -- in com.factory.hub.driver.opcua.OpcUaDriverHostSanitizeTest
[INFO] Tests run: 11, Failures: 0, Errors: 0, Skipped: 0 -- in com.factory.hub.driver.opcua.OpcUaDriverLocalServerTest
[INFO] Tests run: 2,  Failures: 0, Errors: 0, Skipped: 0 -- in com.factory.hub.driver.opcua.OpcUaDriverMissingDependencyTest
[INFO] Tests run: 35, Failures: 0, Errors: 0, Skipped: 0
```

两条**反向护栏**（防止这次的问题被改回去）：

| 护栏 | 位置 | 作用 |
|---|---|---|
| milo 的 `NodeId` 字节码仍引用 `javax/xml/bind/DatatypeConverter` | `OpcUaDriverBoot4ClasspathTest` 的 `miloNodeIdStillHardReferencesJavaxXmlBind` | 哪天变成 false，说明 milo 换了实现，可以据此删掉 `jaxb-api` 这个生产依赖 |
| `pom.xml` 里 `jaxb-api` 的 scope 不是 `test` | `OpcUaDriverBoot4ClasspathTest` 的 `pomDoesNotDeclareJaxbApiAsTestScope` | **单元测试抓不到「作用域被改回 test」**（测试类路径上有它），只能靠断言 pom 本身 |

### 6.4 跨平台注意（接 CI 前必读）

**classpath 分隔符必须按平台取，不能写死。**
Windows 是 `;`，Linux 是 `:`。写死 `;` 在 Linux 上会让整条类路径粘成一段，Java 直接报「找不到主类」。

验证脚本已按 `uname -s` 取：

```bash
case "$(uname -s 2>/dev/null || echo unknown)" in
  MINGW*|MSYS*|CYGWIN*|Windows_NT) CP_SEP=';' ;;
  *)                                     CP_SEP=':' ;;
esac
```

**「本地全绿 ≠ CI 能跑」**（1.0.28 就这么漏掉的）。接进 CI 时的前置条件见 `tools/ci-gate.sh` 里的对应注释。

**当前状态（2026-11-06）**：这道门禁**还没接进 `tools/ci-gate.sh`**，那一段仍是暂停的。
但要注意 —— 那段注释写的理由是「等脚本跨平台化」，**这个理由已经过期**：
脚本在跨平台改造后已经按 `uname -s` 取 `CP_SEP`、并从 `PATH` / 环境变量回落 `MVN` / `JAVA_HOME`。
**真正剩下的前置条件是「在真 Linux 上跑一遍确认」**（不是「脚本还没跨平台」）。
读那段注释时别被过期的理由误导。

---

## 7. 这个验证方法能覆盖什么 / 覆盖不到什么

**这一节很重要 —— 否则后来人会以为「验过了就等于现场没问题」。**

### 7.1 覆盖到了

| 项 | 怎么覆盖的 |
|---|---|
| milo 与 Boot 4.1.2 的**依赖兼容**（Netty 4.2.19、Jackson 3 换包名） | 依赖树核对 + 字节码扫描 + 生产运行时类路径上真跑 |
| 驱动代码在**生产运行时类路径**上的真实读写 | 拆两个进程：server 走测试类路径，client 走生产类路径 |
| **依赖作用域错误**（jaxb-api 放错作用域） | 门禁第 6 步对照组 + pom 断言 + 门禁自检的变异 |
| milo 客户端与**服务端**的差别（服务端要 jaxb、客户端不要） | 正是要拆两个进程的原因 |
| 端点 URL 形态不匹配的**诊断信息** | 端点匹配规则矩阵 + 错误翻译用例 |

### 7.2 覆盖不到（现场仍可能踩）

| 项 | 为什么覆盖不到 | 现场怎么验 |
|---|---|---|
| **真 PLC 的命名空间索引** | 各品牌 ns 索引不同（`ns=2` / `ns=3` / `ns=4`…），本机 server 用的是测试自己建的 ns=2 | 接真机，按 PLC 实际节点号配点表 |
| **用户名密码认证** | 驱动目前只支持匿名 + `SecurityPolicy.None`（类注释已写明） | 不在 MVP 范围；要用得先改驱动 |
| **加密端点**（`Basic256Sha256` 等） | 同上；另外 Netty 4.2 的 BC 集成要求 BC 1.80，当前类路径是 BC 1.75 | 启用前需把 BC 提到 1.80 并显式配 hostname 校验 |
| **真实现场的网络与超时行为** | 本机 loopback 不代表现场网络（跨网段、防火墙、NAT、丢包） | 接真机，用 `tools/probe-modbus.py` 那类现场探测脚本 |
| **真机的点表配置是否正确**（ns 索引、dataType、scale/offset） | 本机 server 的节点是我们自己造的 | 接真机，逐点比对 |
| **多端点服务端的端点选择** | 本机 server 只 advertise 一个端点 | 接真机（Kepware 等会 advertise 多个） |

⇒ **本验证覆盖的是「框架升级有没有把这条链路弄坏」，不是「现场配得对不对」。**
后者只能靠真机。

---

## 8. 变异验证记录（证明这些用例不是假绿）

按项目方法论「注入 → 红 → 显式还原 → 复跑 → 绿」成对做，**在隔离副本里做**：

| 变异 | 在哪做的 | 结果 |
|---|---|---|
| 端点过滤改成 `Basic256Sha256`（模拟「连不上 / 无可用通道」） | 早期隔离验证副本（已撤回） | 红 → 还原后绿 |
| `read()` 直接返回空 map（模拟「静默读不到值」） | 早期隔离验证副本（已撤回） | 红 → 还原后绿 |
| `jaxb-api` 改回 `<scope>test</scope>` | 早期隔离验证副本 **+ 仓库门禁自检** | 红 → 还原后绿 |
| 删掉 `catch (LinkageError)` 防护 | 仓库合并版 | 红（`NoClassDefFoundError` 从 `read()` 穿透）→ 还原后绿 |
| 路径不报错 / 端口不报错 / IPv6 不补括号 | 仓库合并版 | 各 红 → 还原后绿 |

**门禁自检里也埋了一条同款变异**（`tools/ci-gate-selftest.sh` 变异 5）：
把 `jaxb-api` 降级成 `test` 作用域，断言生产类路径验证**必须变红**。
理由写在脚本注释里：jaxb-api 放 test 作用域时 `mvn test` 照样全绿，
**单元测试抓不到，只有「生产运行时类路径验证」能抓到** ——
如果这条变异不红，说明我们以为有门禁的地方其实没有，那才是真正的假绿。

---

## 9. 文件索引

| 内容 | 路径 |
|---|---|
| 生产运行时类路径验证脚本 | `tools/verify-opcua-boot4.sh` |
| OPC UA 专项测试 | `src/test/java/com/factory/hub/driver/opcua/` |
| 门禁自检（含 jaxb 降级变异） | `tools/ci-gate-selftest.sh` |
| 驱动实现 | `src/main/java/com/factory/hub/driver/opcua/OpcUaDriver.java` |
| 端点 URL 拼装与主机消毒 | `src/main/java/com/factory/hub/driver/opcua/OpcUaEndpointUrl.java` |
| milo 错误翻译成人话 | `src/main/java/com/factory/hub/driver/opcua/OpcUaEndpointErrors.java` |
| 本地 OPC UA Server（测试用） | `src/test/java/com/factory/hub/driver/opcua/OpcUaTestServer.java` |
| 生产运行时类路径探针 | `src/test/java/com/factory/hub/driver/opcua/OpcUaRuntimeProbe.java` |
| 「协议 → 默认端口」唯一口径 | `src/main/java/com/factory/hub/driver/ProtocolDefaults.java` |
| 端口字面量白名单门禁（后端） | `scripts/check-protocol-port-literals.mjs` |
| 端口语义门禁（前端） | `frontend/scripts/check-port-semantics.mjs` |
