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

##### ⚠️ 但这条断言有三条边界，不写清楚会被读成「漂移从此不可能」（那是过度承诺）

**边界 A：它验的是「源码对源码」，不是「交付物对交付物」。**
断言读的是同一份仓库里的后端源码；交付的却是两样**独立产物**（前端静态包 + 后端 jar）。
若前端包构建于「`ProtocolDefaults` 还是 4840」时、jar 构建于「改成 4841」之后，
**构建期断言是绿的，交付出去的那一对已经漂了**。
⚠️ 而且**过期的那一侧会赢**：前端副本会被预填进设备行、落库成**显式端口** ⇒
后端的新默认值对这台设备**根本不会被调用** ⇒ 设备连到的是**前端那个旧值**。
⇒ 保证只在「前后端**同一 commit** 出包、且产物能绑定到该 commit」时成立 ——
**这条前端无能为力**，属发布流程 / devops。
⇒ 措辞建议：**「构建期断言 + 前后端同 commit 出包 = 漂移不可能；缺了后半句，断言只覆盖源码。」**

**边界 B：它保证的是「两份默认端口表一致」，不保证「后端真的会按协议兜底」。**
它比的是 `defaultPort(协议)` 这个**值**，
**没有**验证 `DeviceController.apply()` 在端口未填时真的去调 `ProtocolDefaults.defaultPort()`，
也没验证驱动真的用了解析出来的端口。
⇒ 后端要是把 `apply()` 改成不走 `defaultPort`，前端断言照样绿。
⇒ 端到端要靠**后端行为测试**（造一台 opcua 设备、不填端口、断言 `DriverContext.port == 4840`）
＋ 后端那条端口字面量门禁。
⇒ **两条互补，不是替代。** 必须写明，否则将来有人会以为「前端门禁覆盖了端口正确性」
把后端行为测试删了 —— 那才是真事故。

**边界 C：它对「后端端口表的数据来源形态」有耦合 —— 这正是「不暴露 API」的代价。**
断言读的是 Java 源码文本。后端若把端口表挪进 `application.yml` 或改用代码生成，
断言读不到 ⇒ **红，且是 `[解析失败]` 的响亮红**（不是静默）⇒ 需要同步改解析器。
⇒ 说白了：**运行时接口的代价 = 多一个网络依赖 + 一个失败模式；
构建期断言的代价 = 前端得跟着后端的内部形状走。**
⇒ 现在已经是**格式级耦合**而非实现级（认字面量、认 `Integer`、认映射式与分支式、剥掉注释再解析），
但耦合还在 —— **如实写，别写成「零耦合」。**


### 4.4 护栏：口径收好了不代表不会再漂

**这是本节最容易漏掉的一步。** `ProtocolDefaults` 把口径收成一处之后，
如果**没有任何东西盯着它**，半年后有人新写一个 `? 502 :`、或把 `ProtocolDefaults` 改回硬编码，
没人会知道 —— 直到现场新建 OPC UA 设备连不上。

⇒ 前后端各配了一条对称的门禁：

| 侧 | 检查 | 位置 |
|---|---|---|
| 前端 | 表单不许把「未填端口」兜成 502；哨兵不许改成 0；`:min="1"` 不许去掉；模板套用必须走 `normPort`；**跑真实 element-plus 验「null 不被夹 / 0 被夹成 1」** | `frontend/scripts/check-port-semantics.mjs`（`npm run check:port`，已接进 `tools/ci-gate.sh`） |

前端那条门禁分**四类断言**，值得单列 —— 顺序是有讲究的：

| 类 | 断言什么 | 抓什么 |
|---|---|---|
| ① 源码断言 | 提交不许兜 502、哨兵不许改成 0、`:min="1"` 不许去掉、模板套用必须走 `normPort` | 「静默退回旧写法」 |
| ② **行为断言** | 跑**真实 element-plus**：null 不被夹、0 被夹成 1 | 「控件行为变了」—— element-plus 哪天改了夹取逻辑，门禁会红提醒重新评估，而不是默默失效 |
| ③ 数据断言 | 9 个内置模板的 port 与协议默认逐个对 | 「模板数据与口径脱节」 |
| ④ **一致性断言** | 前后端 `defaultPort(协议)` 的**有效值**必须一致；**解析不出有效值 ⇒ 直接红** | 「前端副本与后端真相漂移」 |

**第 ② 类是「护栏要断言行为，不能只断言源码长什么样」的落地** ——
只断言源码的话，element-plus 改了行为，源码一个字没动，门禁照样绿，但产品已经坏了。

**第 ④ 类的「有效值」这个词很关键**：比的不是「后端表里有没有这个协议」，
而是「后端 `defaultPort(协议)` 到底返回几」——
因为没单独列出的协议走**兜底值**，拿「表里有没有」去比会误报。
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

#### 护栏自己也要被变异验证（绿比红危险）

前端那条一致性断言在**看起来很严谨**之后，仍被变异验证打出**两个真·假绿**：
正则不带锚点 ⇒ `= 4840 + 1` 被读成 4840（**尾部丢掉**）；
类注释里举的例子被当成真代码计数 ⇒ 判据失准。

⇒ **护栏的解析逻辑同样有洞，同样会静默放过真实漂移。**
⇒ **每加一条护栏，必须配套一组变异**：把被守的东西改成错的，护栏必须红。
⇒ 而且要有一个变异专门守「注释/文档里的示例不算代码」这类**边角**。

**绿灯不是「没问题」，是「没人验证过它会不会亮」。**
红至少会被人看见；绿从来不会有人多看一眼 —— **所以护栏的风险在绿那一侧，不在红那一侧。**

#### 做漂移验证的手法本身有副作用

验证「前后端口径漂移能否被抓住」时，最直接的手法是**临时改后端常量**。
但这个手法**会留下陈旧的编译产物**（见 §5.5），而且**改回去之后 class 未必重编**。

⇒ **规则**：
1. 改完常量**立刻 `mvn clean`**；CI 永远从 clean 开始
2. **更好的做法：不要改后端源码** —— 给校验脚本传参模拟漂移
   （如 `--simulate-backend-port=4841`），源码一行不动 ⇒ 没有陈旧产物、没有并发构建风险
3. 怀疑产物与源码不一致时，**判据是 `javap` 读编译产物，不是读源码**：
   ```
   $ javap -p -constants -cp target/classes com.factory.hub.driver.ProtocolDefaults
       public static final int OPC_UA_PORT = 4841;    ← 产物
   $ grep -n 'OPC_UA_PORT =' src/main/java/.../ProtocolDefaults.java
       public static final int OPC_UA_PORT = 4840;    ← 源码
   ```

#### 两个可直接抄的护栏手法

**① 「下限」**：给检查加一个**命中数下限**，命中数低于下限即红。
例：端口字面量门禁给每条白名单一个 `max`（最多允许几处），
「白名单允许 N 处但只匹配到 M<N ⇒ 判白名单过期 ⇒ 红」。
⇒ **它防的是「工具失效 ⇒ 扫不到任何东西 ⇒ 静默通过」**：
正则哪天坏了，命中数掉到 0 < max ⇒ 一样会红。
⇒ 变异脚本同理：**注入失败必须报错退出**（不能把「没注入成功」混进「门禁是绿的」）。

**② 「变异矩阵」本身要落进仓库**，不能只在本地跑过就算数。
仓库里已有先例：`scripts/mutation-check-alarmcodes.mjs`
（头部就写着判据：「**故意注入一个错误 → 校验器必须 exit 1**」，
并记着上一次栽的坑：「暂存区里的校验器是个 117 行空壳，跑一下就 exit 0，什么都拦不住」，
以及一条容易被忽略的前提：「**基线：未变异必须 exit 0**，否则下面全都不算」）。
⇒ 端口语义门禁的变异矩阵已落进仓库：`frontend/scripts/mutation-check-port-semantics.mjs`
（14 个变异，`npm run check:port:mutation`，实测 11 秒）。

**③ 变异矩阵要有两类，缺一半就不成立**

| 类型 | 注入什么 | 期望 | 只做这一半会怎样 |
|---|---|---|---|
| **正向变异** | 真实的漂移/错误 | 必须红 | 门禁可能因为「什么都拦」而红 —— 那是噪音不是鉴别力 |
| **反向对照** | 与结论无关的东西 | 必须**仍绿** | 门禁可能因为「什么都不拦」而绿 —— 那是假绿 |

实例：往前端那条一致性断言的后端注释里塞一条**假的** `Map.entry("opcua", 9999)`，
断言门禁**必须不受影响**（`仍绿`）。
⇒ 这一条本身就是对「剥注释再解析」这个动作的检验 ——
**剥过头了/剥漏了，反向对照会立刻抓到。**

**④ 断言要验「红得对」，不只是「红」**

只断言退出码非 0，验不出**诊断落在了哪个分类** ——
门禁红了但分类错了（比如该报「口径不一致」却报了「解析失败」），
**人照样会被带错方向**（分类错了比没分类更贵）。
⇒ 前端那条变异矩阵因此不只断言「红了」，还断言**诊断分类正确**
（`[解析失败]` / `[口径不一致]` / 具体文案片段）。
⇒ 这与「门禁红」vs「门禁红得对」是同一个道理，只是往下落到了断言里。

**⑤ 变异锚点要挂在稳定的位置**

锚点挂在**别人会改措辞的地方**，你的变异会静默失效。
现场实例：前端那条变异把锚点挂在后端 `ProtocolDefaults` 类注释里的一句话上，
**后端改了一句 javadoc 的措辞，模式串就匹配不上了** ——
注入根本没发生，脚本却照样跑出一句「绿」。
⇒ **锚点要挂在类声明、函数签名这类不会随手改的地方**，别挂在注释措辞上。

**⑥ 注入失败必须报错退出**

上面那个案例之所以没变成假绿，是因为脚本末尾有一道闸：
**注入没成功 ⇒ 直接 exit 1，而不是记成「通过」**。
⇒ **这道闸不是理论，是当场咬人的**：前端那条变异第一次运行就被它抓到
（`FAIL H 注入失败：模式没匹配上 —— 这一项不算数，不是「通过」`）。
⇒ 若没有它，H 会被记成「通过」，而实际上它什么都没验。

**⑦ 变异脚本与行为断言要分工**

**行为断言（跑真实 element-plus 验「哨兵 null 不被夹、0 被夹成 1」）无法被变异** ——
它守的是第三方库的内部行为，变异它没有意义。
⇒ 它由**常规门禁每次运行直接验证**；变异脚本只负责源码/数据/一致性三类。
⇒ 为此前端加了 `PORT_CHECK_FAST=1`（**只有变异脚本用**）：跳过 SSR 探针，28.6 秒 → 3.3 秒；
基线那一轮不带这个开关 ⇒ 行为断言照跑。

#### 两类「白名单过期」保护，不必重复加

- **端口字面量门禁**用的是「**白名单过期也红**」（允许 N 处但只匹配到 M<N ⇒ 红）——
  因为它**直接扫源码**，正则一旦失效，命中数会掉到 0 < N ⇒ 一样会红。
- **i18n 键检查 / 报警码字典**没有同类过期检查，但它们有**另一层保护**：
  自检的「变异 4：把校验器改残」会抓到（校验器被改残 ⇒ 门禁必须红）。

⇒ **记录一下，避免以后重复加。** 两类门禁的保护机制不同，但都能防「工具失效 ⇒ 假绿」。

#### 错误分类是护栏的一部分

一条检查的输出**至少要分三类**，而且三类不能互相冒充：

| 类别 | 含义 | 读者该做什么 |
|---|---|---|
| `[解析失败]` | 工具**读不懂**被验对象（含被验文件路径 + 该怎么办） | 更新解析器 |
| `[口径不一致]` | 读懂了，但两边**真的不同**（两边值 + 出处） | 改被测的那一侧 |
| 一致 | 通过 | — |

#### 分类还要再分一维：**同一类里，谁该改**

「不一致」这一类里还藏着**动作完全不同**的两种情况，报在一起等于让人瞎猜：

| 情况 | 后端 `defaultPort(X)` 算得出吗 | 正确归类 | 该改哪边 |
|---|---|---|---|
| 前端定了 X，后端**解析器**读不出 X | 算不出 | `[解析失败]` | 改**解析器** |
| 后端**删掉了** X 的定义 | 算得出（走兜底值） | `[口径不一致]` | **人**来判断改哪边（后端补一条，或前端改成兜底值） |
| 后端单列了 X，只是**数字不同** | 算得出 | `[口径不一致]` | 改**数字** |

⇒ 判据不能只是「一致 / 不一致」，还要**说清是哪一种、以及下一步该动什么**。
⇒ 一个具体实现细节：**「写法形式」要认形式本身，不能只认条目** ——
后端若把映射删空（`Map.ofEntries()` 里没有条目），只认 `Map.entry(` 的解析器会判成
「改用了不认识的写法」⇒ 报 `[解析失败]` ⇒ **把人引到「改解析器」那条路上，而实际上该改的是后端或前端。**

#### 把「读不懂」混进「不一致」会**同时**造成两个坏结果：

- **假绿**：读不到就「没有差异」直接放行 ⇒ 漂移跑到现场才炸
- **假红**：读不到就拿兜底值凑一条「不一致」⇒ **结论是编的**，人去查一个不存在的漂移

⇒ **假红为什么也必须修**：它本身不产生生产事故，但它**消耗「门禁可信度」这个共享资源**。
人反复遇到「红但查不到」⇒ 默认反应从「红 = 有问题，去查」变成「红 = 又误报，忽略」
⇒ **下一次真红（真缺陷）也被忽略** ⇒ 假红连带把假绿变成真事故。
⇒ **「门禁红」和「门禁红得对」是两件事，后者才是护栏。**

⇒ 修法就是**把「解析失败」单独分类报出，不让它参与比对** ——
分类对了，假绿和假红**一起被堵住**（这是「把两种坏结果用一个动作修掉」的典型例子）。
而且分类本身就是诊断：两种错误的修复动作完全不同，报在一起等于让人瞎猜。


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
| 断言「后端表里有没有这个协议」来检查前后端口径一致 | **误报** —— 后端 `defaultPort()` 对没单独列出的协议走**兜底值**，「表里没有 modbus-tcp」≠ 分叉。改成算 `defaultPort(协议)` 到底返回几（显式条目优先，没有就取兜底值） |
| **工具读不懂时，它的输出被当成结论** | **假绿与假红同时发生**：① 读不到就「没有差异」直接放行 ⇒ 漂移跑到现场；② 读不到就拿兜底值凑一条「不一致」⇒ **结论是编的**，人去查一个不存在的漂移 |
| **陈旧的编译产物让测试跑的不是源码那份代码** | 有人临时把 `OPC_UA_PORT` 改成 4841 试漂移检测 ⇒ 编出一个 4841 的 class ⇒ 源码改回 4840 ⇒ **`target/classes` 里那个 class 还在**，测试跑的根本不是源码那份代码（实测 5 个测试红，值全是「期望 4840、实际 4841」）。**更糟的方向是反过来**：源码坏、class 好 ⇒ **测试全绿而代码是坏的** |
| **在主工作树跑构建的同时跑门禁自检** | 自检靠 tar 复制工作树做隔离 ⇒ 并发构建会让 tar 读到**变化中的目录树** ⇒ 拷贝被截断（实测副本只剩 4 个文件）⇒ 后面所有变异以「No such file or directory」失败，**看起来像门禁坏了，其实是自己的锅** |
| **验证工具的「通过」可能是「它什么都没做」** | **同一句话的第三次出现**：① `jdeps` 输出含非 UTF-8 字节 → `grep` 当二进制静默跳过 → 从不完整的扫描推出「milo 不用 Jackson」；② 校验器被改残 → 永远 `exit 0`；③ 变异注入的**模式没匹配上、注入根本没发生**，脚本却照样跑出一句「绿」。⇒ **任何「通过」都要问一句「它到底做了什么」，而不是「它返回了 0」** |
| **护栏自己也会变成「工具说没有其实是扫描失败」的受害者** | 它的解析逻辑同样有洞，同样会**静默放过**真实漂移。前端那条一致性断言用变异验证打出两个真·假绿：① 端口常量正则不带锚点 ⇒ `= 4840 + 1` 只读到前缀 4840、**把 `+1` 丢掉** ⇒ 后端实际返回 4841 而门禁认定 4840；② 类注释里举的例子（`Map.entry("协议", 常量名)`）**被当成真代码**计数 ⇒ 「一条都没解析出来」的判据失准 |
| 门禁自检只验「检查会红」，不验「检查挂在链路上」 | **实测坐实的假绿** —— 把 `ci-gate.sh` 里整块 OPC UA 检查删掉，自检照样 10/0 全绿，还自夸「门禁确实会红，可以信任」。因为变异 5 直接调脚本、绕过了门禁，而自检用 `--skip-be` 跑时那道检查被跳过 |
| **共享仓库里 `git commit -F 文件` 不带 pathspec** | **会把暂存区里所有人的文件一起提交** —— 本轮实际发生了一次（后端提交时捎带了前端暂存的两个文件）。修法：`git reset --soft HEAD~1` + `git restore --staged <别人的文件>` + 用原 message 重提 |

最后一条是**协作层面的坑**，值得单列，因为它和上面的「工具骗人」是两类问题：
上面那些是**工具/断言骗你**，这条是**协作流程骗你** —— 而且它发生时没有任何报错，
提交成功、退出码 0，不去看 `git status` 就发现不了。

⇒ 共享仓库里的四条铁律（本轮两人都踩过，代价是一次提交拆分）：
1. **`git add` 显式列路径**，不用 `git add .` / `git add -A`
2. **`git commit -F 文件` 必须带 pathspec**（`git commit -F msg.txt -- <路径>`）
3. 提交前 **`git status --short` 看一眼暂存区**，确认没有别人的文件
4. 带错了按上一行的修法拆开重提 —— **历史乱了以后 bisect 和 revert 都跟着乱**

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

**「本地全绿 ≠ CI 能跑」**（1.0.28 就这么漏掉的）。

#### Linux 分支已验过（2026-11-06，运维专家在真 Linux 上跑）

| 项 | 结果 |
|---|---|
| 机器 | 42.192.201.26 / ubuntu / JDK 17.0.20.1 / Node 22.22.0 / Maven 3.9.9 |
| `--dry-run` | `CP_SEP=:` / `MVN=mvn` / `JAVA=java`，exit 0 |
| `verify-opcua-boot4.sh 14860` | **exit 0**，6 步全过 |
| 同上 + **冷缓存 + `CI=true`** | **exit 0** |
| `ci-gate.sh` + 冷缓存 + `CI=true` | **9 通过 / 0 失败**，150 秒 |

⇒ **`CP_SEP` 按 `uname -s` 取的判断是对的**（Linux 上就是 `:`，Windows 本机是 `;`）。
⇒ **「124 个 jar，无 sdk-server，含 jaxb-api」在 Linux 上数字完全一样** —— 与 Windows 本机一致，
这本身是个交叉验证：类路径是确定性的，换平台不该变。

⚠️ **但那次验的是已提交的 HEAD（`fa1bc5b`）**。
验完之后工作树里 `ci-gate.sh` / `ci-gate-selftest.sh` / `verify-opcua-boot4.sh` /
`OpcUaServerLauncher.java` 又有了改动（修「固定端口 TIME_WAIT 导致 flaky」：默认端口改成 0 让 OS 分配、
从 `ENDPOINT` 读回实际端口，并给自检加新变异）——**那部分未在真 Linux 上验过**。
⇒ 读本文时不要把它当成「当前工作树已在 Linux 上验过」。

#### CI 环境的一个事实（运维专家实测）

这台服务器到 `repo.maven.apache.org` 只有 **1-10 kB/s**，
冷缓存下整轮门禁 **793 秒（13 分钟）** ⇒ `ci.yml` 的 `timeout-minutes: 25` 是必要的。
国内 CI 建议配 aliyun 镜像（公司 `tools/settings.xml` 里已有现成的）。

#### ⚠️ 一个被实测坐实的门禁盲区（**这一条比上面所有都重要**）

运维专家在**隔离副本**里把 `ci-gate.sh` 里整块 OPC UA 检查**删掉**，然后跑 `tools/ci-gate-selftest.sh`：

```
已删除 OPC UA 检查块（7063 → 6040 字节）
== 变异 1 i18n == 变红了 / 还原后恢复全绿
== 变异 2 TS 类型错误 == 变红了 / 还原后恢复全绿
== 变异 3 字典写错 == 变红了 / 还原后恢复全绿
== 变异 4 校验器改残 == 变红了 / 还原后恢复全绿
== 变异 5 jaxb 降级 == 变红了 / 还原后恢复全绿
通过 10 / 失败 0
门禁确实会红，可以信任
SELFTEST_EXIT=0
```

⇒ **门禁被整块删掉，自检照样全绿、还自夸「门禁确实会红，可以信任」。**

这是 MEMORY.md 那条「**『有门禁』≠『门禁在跑』**」的**双向版本**：
不只是「没接上」，是「接上了但被删/被改坏」也一样绿。

两条根因：
1. 变异 5 直接调 `bash tools/verify-opcua-boot4.sh`，**完全不经过 `ci-gate.sh`**
   ⇒ 它验的是「脚本能发现降级」，跟「门禁会不会调它」是两件事
2. 自检用 `--skip-be` 跑门禁，而 OPC UA 检查挂在 `SKIP_BE=0` 分支内 ⇒ 被一起跳过

⇒ **这类盲区只能靠「结构性断言」堵，不能靠「跑起来红不红」**：
检查块被删了，它根本不会跑，也就谈不上红。
判据必须是「**门禁的检查项清单里还有没有这一项**」——
即门禁需要一个干跑的「列出检查项」模式（秒级、不需要 maven），
自检再配一条变异证明这条断言真的有鉴别力
（先有常态断言，再用变异证明它有鉴别力 —— 与「变异 4：把校验器改残」同一个结构）。

**这才是「每条门禁都要有变异」的完整含义**：
不只是「证明这道检查会红」，还要「证明**它确实被挂在链路上**」。

##### ⚠️ 一个更刺眼的实测：变异 6 报绿了，但绿的是旁路

运维专家第二次实验，**精确瞄准主干**（只删 `ci-gate.sh` 里 `SKIP_BE=0` 那段，
脚本文件 `tools/verify-opcua-boot4.sh` 保留）：

```
=== 变异：删掉 ci-gate.sh 里 SKIP_BE=0 主干的那段（脚本文件保留！）===
  已删掉主干那段（11519 → 10467 字节）
  ONLY_OPCUA 出现 4            ← 旁路还在
  脚本文件还在吗：存在 10271 字节
  主干段出现次数: 0
  共享工作树必须原样：共享树主干段: 1
== 变异 1..5 == 变红了 / 还原后恢复全绿
== 变异 6 OPC UA 摘除 == 变红了，且红的正是「OPC UA 生产类路径」这一项
     ✗ OPC UA 生产类路径   找不到 tools/verify-opcua-boot4.sh
== 汇总：通过 12 / 失败 0
   门禁确实会红，可以信任
SELFTEST_EXIT=0
```

⇒ **主干那段被删了，自检 12/0 全绿、还自夸「门禁确实会红，可以信任」。**
⇒ 而变异 6 自己也报了「✓ 摘除 → 必须红」—— **它红得很有说服力，但红的是旁路，
跟主干被删这件事毫无关系。**

**这比没有这条变异更危险**：自检里出现一个绿色的「✓ 摘除检测」，
比压根没有这个检测更容易让人以为「这一类已经被覆盖了」。
⇒ **一个变异如果证明不了它想证明的那件事，它就不只是无效，是误导。**

根因在源码级：`--only-opcua` 是一段**独立的代码路径**（自己判断「脚本在不在」再跑脚本），
主干那段是**另一处**（`if [ "$SKIP_BE" -eq 0 ] && [ -f tools/verify-opcua-boot4.sh ]`）。
两处**逻辑重复但互不相干** ⇒ 变异 6 只钉住了前者。

##### 堵这个洞的可用零件（已演进到第二版，配方变了）

**第一版**（运维专家提供，匹配旧结构）：锚定 `SKIP_BE -eq 0` 那行删掉整个 if 块。
⚠️ 更早一版匹配的是**注释块**，会误删旁路。

**第二版（当前结构，已实测有效）**：后端先把两处重复的检查合并成一个函数 `run_opcua_check()`，
并在主干那个调用点前加了**「主干挂载点」标记注释**。于是配方变成「找到标记，往后删第一行
`run_opcua_check`」—— 精确、不误伤旁路、且不依赖具体行号：

```python
lines = src.split('\n')
marker = next(i for i, l in enumerate(lines) if '主干挂载点' in l)
target = next(i for i in range(marker, len(lines)) if lines[i].strip() == 'run_opcua_check')
mut = lines[:target] + lines[target + 1:]
```

**实测（我刚跑的）**：
```
标记行=205   主干挂载点行=207   旁路挂载点行=[153, 207]

变异后 --list-checks：   （无「OPC UA」字样）  共 10 项
原版   --list-checks：  · OPC UA 生产类路径     共 11 项
```

⇒ **删掉主干挂载点 ⇒ 清单里那一项消失** ⇒ 结构性断言（「清单里必须有『OPC UA 生产类路径』」）
**有鉴别力** ⇒ 这个盲区可以堵上了。

**为什么第一版配方失效了，值得记**：两处重复的代码在结构上互不相干时，
「删掉其中一处」是**删除一个调用点**；合并成一个函数之后，「删掉主干」同样是删除一个调用点，
但**匹配锚点必须跟着结构走** —— ⇒ **配方和结构是耦合的，结构一变配方就得重验。**
（这跟「护栏的断言和被验对象的形状耦合」是同一类问题，只是方向相反。）

##### 写门禁变异的两条硬约束（都来自实测，不是推测）

1. **不要让任何一条变异去跑「含 `mvn test` 的完整门禁」** ——
   冷缓存下 577 秒，而且要联网拉依赖（这台机器到 `repo.maven.apache.org` 只有 1-10 kB/s）。
   ⇒ 变异必须走「只跑一步」的旁路；但**旁路只能用来验「脚本会红」，
   验「主干上挂着」必须用结构性断言**（`--list-checks` 清单）—— 两者互补，缺一不可。
2. **打包工作树去真机验时，排除 `dist/`** ——
   `dist/` 里有历史发版包，**3 GB**，不排除的话 tar 出来传都传不动。
   `--exclude='./dist'` 就够（`./frontend/dist` 不够）。

##### 现状：这个盲区已经闭环了（2026-11-07 实测）

后端按上面的方案落地，我逐条实测复核：

| 措施 | 落点 | 状态 |
|---|---|---|
| 两处重复检查合并成一个函数 | `tools/ci-gate.sh` 的 `run_opcua_check()`（主干与旁路共用） | ✅ |
| 清单与执行**同源**（名字在 `check()` 里登记进 `CHECK_NAMES`，不另写硬编码清单） | 同上 | ✅ |
| `--list-checks` 干跑模式（秒级、不需要 maven） | 同上 | ✅ |
| 自检里的**常态断言**（清单里必须有「OPC UA 生产类路径」和「端口字面量检查」） | `tools/ci-gate-selftest.sh` | ✅ |
| **变异 7**：删掉主干上的 OPC UA 检查挂载点 ⇒ 常态断言必须红 | 同上 | ✅ |
| 变异 6 改名成它真正证明的那件事 | 「OPC UA 的 verify 脚本文件不在 ⇒ 门禁必须红」 | ✅ |

⇒ 「删掉主干 ⇒ 自检 12/0 全绿」这个盲区**已堵上**。

**顺带记一个实测撞上的坑**：我跑自检时撞到
```
tools/ci-gate-selftest.sh: line 578: unexpected EOF while looking for matching `"'
SELFTEST_EXIT=2
```
—— **新加的变异 7 里有一个引号没闭合**，脚本在第 578 行崩掉，
**变异 6/7 根本没跑到**，自检以 exit 2 结束而不是正常跑完。

⇒ 这本身就是本文档这一整节要说的事：**「加了一条变异」和「这条变异跑起来了」是两件事。**
（文件随后被修好了，`bash -n` 干净、7 条变异齐全 —— 但那一刻它是坏的，而自检的输出里
只看到「跑了一半」，很容易被当成「环境问题」放过。）

##### 原则的下一层

「护栏要断言行为，不能只断言源码长什么样」——
**下一层是：连「护栏自己在不在」都要用实验去证，
而且证的方式必须是「把护栏删掉，看有没有东西会叫」。**
删掉之后没人叫，才是真正的假绿 —— 因为此时连「被验的对象」都不存在了，
「跑起来红不红」这个问题本身失去意义。

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
