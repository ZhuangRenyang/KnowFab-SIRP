# 框架版本升级 Runbook（Spring Boot / Vue）

> 定稿：2026-11-07　｜　上次实战：Spring Boot 3.x → **4.1.2**（2026-11-06 合并）
> 适用：Spring Boot 小版本（4.1→4.2）、大版本（4→5）；Vue 3.x 线（3.5→3.6/4.0）
> 配套：发版流程见 `docs/RELEASE.md`；门禁见 `tools/ci-gate.sh`

---

## 0. 一页速查

### 0.1 先判断：这次该不该升

| 类型 | 判断 | 动作 |
|---|---|---|
| 安全补丁（CVE 命中本项目） | 必升 | 只动补丁号，不夹带任何其它改动 |
| 小版本（4.1 → 4.2） | 跟 | 先读 Migration Guide 的 breaking changes，再排期 |
| 大版本（Boot 4 → 5、Vue 3 → 4） | 看生态 | **等 milo / Element Plus / POI 先跟进**，别当第一波。上次 3→4 就是等 milo 0.6.11 就位才动 |
| 只是「有新版本」 | 不升 | 本项目是**单安装包交付给工厂现场**，升级成本在客户机器上，不在 CI |

### 0.2 三条硬判据（全过才算升完）

```
① 交付 jar 里该有的依赖真的在      → 见 H1 的两条命令，看产物不看 pom
② 真启动起来 + 14 个端点全 200     → bash tools/verify-boot-smoke.sh
③ 老库与新库都能起来、迁移对象真的存在 → 见 H3 的双路径验证
```

> ⚠️ **`mvn clean test` 全绿不是判据**。全仓 409 个用例是纯 Mockito，**零 `@SpringBootTest`** ⇒
> 容器能不能起来、`@Entity`/Repository 有没有被扫到，`mvn test` 结构上测不到。

### 0.3 当前基线（2026-11-07 实测，升级后用来对照）

| 项 | 值 |
|---|---|
| Spring Boot | 4.1.2（parent）｜Java 17 |
| 外部重依赖 | milo 0.6.11（OPC UA）、POI 5.2.5（Excel）、mysql-connector-j |
| Netty（由 Boot BOM 调解） | **4.2.19.Final**（milo 声明 4.1.97，取高者） |
| JAXB 共存 | `jaxb-api 2.3.1`（compile，补 `javax.xml.bind`）+ `jaxb-runtime 4.0.9`（jakarta） |
| 前端 | Vue 3.5.45 / Vite 8.3.6 / Element Plus 2.14.8 / Pinia 4.1.0 / vue-router 5.4.0 / TS 5.9.3 |
| 测试 | **409 用例 / 51 报告**（`tools/test-baseline.json` 钉死） |
| 门禁 | 14 项（含 2 项拆包护栏） |
| 迁移脚本 | 16 个（`db/migrations/1.9.x.sql`） |

---

## 1. 四个高危面（按爆炸半径排序，都有实战依据）

### H1 依赖缝隙 —— 最高危，上次两个真缺陷都在这

本项目**不是纯 Spring Boot**：Boot 的 BOM 会抬高或替换 milo / POI 的传递依赖，而这几条缝隙
`mvn test` 一条都测不到（test scope 的类路径和生产不一样）。

**已发生过的两个缺陷**（Boot 3→4 时）：

1. **JAXB 换包名**：Boot 4 用 jakarta ⇒ `javax.xml.bind` 从**生产**类路径整个消失
   ⇒ milo 的 `NodeId.parse("ns=2;b=QUJDRA==")` 运行时 `NoClassDefFoundError`
   ⇒ 现场表现「设备在线但读不到任何点」，极难定位
   ⇒ 修法：`javax.xml.bind:jaxb-api:2.3.1` 提到 `compile`（与 jakarta 的 4.0.9 包名不同，可共存）
2. **Netty 被抬高**：Boot 4.1.2 → 4.2.19.Final，milo 声明 4.1.97
   ⇒ 官方说 4.2 对 4.1 largely backwards compatible，实测二进制兼容（驱动只走 `SecurityPolicy.None`）

**检查命令（看产物，不看 pom）**：

```bash
# ① 实际调解后的版本（不是 pom 里声明的版本）
/d/mywebss/maven-ext/apache-maven-3.9.9/bin/mvn.cmd -o dependency:tree \
  -Dincludes=io.netty:netty-handler,javax.xml.bind,jakarta.xml.bind,com.fasterxml.jackson.core

# ② 交付 fat jar 里到底有没有（⚠️ 必须钻进嵌套 jar，直接 grep 包名永远是 0）
unzip -l target/device-hub.jar | grep -E "jaxb-api|jaxb-runtime"
unzip -p target/device-hub.jar BOOT-INF/lib/jaxb-api-2.3.1.jar > /tmp/jaxb.jar
unzip -l /tmp/jaxb.jar | grep -c "javax/xml/bind/DatatypeConverter"   # >0 才算真的可达
```

> ⚠️ **判据陷阱**：`unzip -l target/device-hub.jar | grep -c "javax/xml/bind"` 恒为 **0**
> （fat jar 里依赖是嵌套 jar，不是散类）⇒ 用它判断会得出「坏了」的错误结论。
> **正确做法是解出嵌套 jar 再查**，或直接看 `BOOT-INF/lib/` 下有没有那个 jar。

**Boot 大版本升级必查**：Jackson 2→3 换代（包名 `com.fasterxml` → `tools.jackson`）、
JAXB 换 jakarta、Netty 抬高、`spring.factories` 机制变化。

### H2 零 `@SpringBootTest` ⇒ 必须真启动

```bash
bash tools/verify-boot-smoke.sh          # 启动 + 登录 + 14 个端点（覆盖全部 8 个包）
```

判据（脚本已内置，别只看「启动成功」）：
- 启动日志无 `Not a managed type` / `No qualifying bean` / `ConflictingBeanDefinitionException` /
  `Unknown entity` / `MappingException`
- 探针端点**全部查库**：`/api/erp/qc/inspections`、`/api/erp/qc/defects` 等
  （升级若让 `@Entity` 漏扫，启动照样成功，只有真查表才炸）

### H3 老库迁移静默跳过（DH-84 的复现风险）

升级常带新迁移脚本。上次的坑：**迁移跑在 Hibernate 建表之前** ⇒ 全新库还没表 ⇒
守卫「表存在才建」走 `DO 0` ⇒ **静默跳过却记 `ok=1`** ⇒ 索引永久缺失、永不重跑、无人知道。

⇒ **两条路径都要验，缺一不可**：

| 路径 | 场景 | 怎么造 |
|---|---|---|
| 全新空库 | 新客户安装 | 建空库 → 启动 → 断言全部表与索引都在 |
| 老库升级 | 现有客户 | 用旧版本库启动一次 → 换新 jar 再启动 → 断言新迁移的对象存在 |

**判据不是「`schema_migration` 记了 ok=1」，而是「对象真的存在」**（`SHOW INDEX` / `SHOW TABLES`）。
脚本参考 `tools/verify-dh24.sh`、`tools/verify-dh85.py`、`tools/verify-dh86.py` 的写法。

### H4 客户端自动升级链路

单安装包 + 断网运行 ⇒ 升级链条是：`make-dist.sh` → `release.sh` → 客户端 `dist/version.json` 检查更新。

```bash
tools/verify-release.py            # 按客户端真实路径端到端（丢一个临时库，别用你的库）
```

> ⭐ **「本地全绿」≠「客户端能用」**：曾出现打包成功、审计 0 阻断，但 Gitee raw 的 `version.json`
> 还是旧版本号、Release 404。⇒ **发完必须 curl 一次 raw `version.json` 和 Release 附件链接**。

---

## 2. 执行顺序（六个阶段，照上次成功路径）

### 阶段 0：冻结与基线
```bash
git checkout -b upgrade/spring-boot-4.2      # 分支名带上目标版本
```
- 升级分支**不夹带业务改动**（先把手头改动合进 main）
- 记基线：409 用例 / 51 报告、真启动耗时、14 端点状态（`0.3` 那张表）

### 阶段 1：只改版本号，先看炸在哪
```bash
# 只动 pom 的 parent version，一行代码不改
/d/mywebss/maven-ext/apache-maven-3.9.9/bin/mvn.cmd -o -B clean test-compile
```
**产出「编译错误清单」并按包分组** —— 先看清规模，再决定继续还是放弃。
（上次 3→4 的编译错误集中在 `jakarta.*` 换包名，机械改。）

### 阶段 2：读迁移指南 + 查依赖缝隙
- Spring Boot 官方 Wiki 的 Migration Guide（逐条对照本项目用到的特性）
- 跑 **H1 的两条命令**，把结果记进升级单
- ⭐ **先读那个领域的类注释**：ADR/文档里的「技术前提」经常与代码不符，
  上次 ADR 说「需量按瞬时峰值计费」而实际代码注释写的是「15 分钟平均功率」⇒ 照 ADR 动手等于白做

### 阶段 3：改代码 + 逐块验收
每改完一块就跑一遍（不要攒到最后）：
```bash
node tools/verify-erp-mapping.mjs        # 包归属（若动了包结构）
node tools/verify-package-split.mjs      # 守恒 + 残留全限定名
node tools/check-package-size.mjs        # 包结构护栏
bash tools/verify-boot-smoke.sh          # 真启动 + 14 端点
MVN=... bash tools/ci-gate.sh            # 14 项门禁
```

### 阶段 4：双路径迁移验证
见 **H3**。「全新空库」和「老库升级」都要过。

### 阶段 5：交付链路端到端
```bash
tools/make-dist.sh --build
tools/verify-release.py
```

### 阶段 6：合并 + 发版
- 合并前跑一次完整门禁（含前端）
- 合并后按 `docs/RELEASE.md` 发版，**发完 curl 验远程**

---

## 3. 前端升级（Vue 线，与后端分开做）

### 现状与顺序

| 包 | 当前 | 升级注意 |
|---|---|---|
| vue | 3.5.45 | 先升它，再升依赖它的 |
| element-plus | 2.14.8 | **与 Vue 版本强绑定**，先看它的 `peerDependencies` 允许范围 |
| vite | 8.3.6 | 大版本会改产物结构 ⇒ 会影响 `src/main/resources/static/app/`（后端要能服务） |
| pinia / vue-router | 4.1.0 / 5.4.0 | 都是新主线，注意 store / 路由 API 迁移 |
| typescript | 5.9.3 | 与 `vue-tsc` 配套升 |

### 命令与判据
```bash
cd frontend
npm outdated                 # 先看差距（别一次全升）
npm ci                       # 锁文件对齐
npx vue-tsc --noEmit         # 类型检查（门禁同款）
npm run build                # 构建（门禁同款）
```
- 门禁里的前端四项：类型检查 / 构建 / 端口语义 / i18n 键完整性
- ⚠️ **Vite 大版本**要额外确认构建产物路径与后端静态资源服务一致（否则客户装机后前端 404）
- ⚠️ **i18n 只允许中英两份**（R5 铁律），升级别顺手把别的语言包捡回来

---

## 4. 常见误判（每条都真踩过）

| 现象 | 实际的坑 |
|---|---|
| 「`mvn test` 全绿，升完了」 | 零 `@SpringBootTest`，容器起没起来根本没测 |
| 「jar 里 grep 不到 `javax/xml/bind`，坏了」 | fat jar 是嵌套 jar，**grep 包名恒为 0** ⇒ 要解出来查 |
| 「迁移记了 `ok=1`，成了」 | DH-84：静默跳过也记成功 ⇒ 判据必须是「对象真的存在」 |
| 「架构师/文档说这条路径不用管」 | 「测过了」≠「测到那条路径了」。上次用字符串节点测 jaxb，压根没进 base64 分支 ⇒ 差点漏掉真缺陷。**两条独立路径吻合才可信** |
| 「本地打包成功就是发出去了」 | 远程 `version.json` / Release 可能是旧的 ⇒ 必须 curl 验 |
| 「参考别人给的版本号写基线」 | 实测数字必须实测。我连犯三次「凭印象写基线」，每次都让护栏误报 |

---

## 5. 升级单模板（每次开一份，跟着走）

```
目标版本：
起点版本：
分支：upgrade/<组件>-<目标版本>

[ ] 阶段0 基线已记（409/51、启动耗时、14 端点）
[ ] 阶段1 只改版本号后的编译错误清单（数量 + 按包分组）
[ ] 阶段2 依赖缝隙核查结果：
      - Netty 调解版本：
      - javax.xml.bind 是否可达（嵌套 jar 条目数）：
      - Jackson 版本与包名：
      - 其它：
[ ] 阶段3 代码改动清单（文件 + 原因）
[ ] 阶段3 四步验收：mvn clean test（409/51）/ 真启动（14 端点）/ 静态护栏 / 门禁 14 项
[ ] 阶段4 全新空库：表与索引全在？
[ ] 阶段4 老库升级：新迁移对象存在？（不看 ok=1，看对象）
[ ] 阶段5 verify-release.py 通过；发完 curl 验 raw version.json
[ ] 阶段6 合并 + 发版
```

---

## 6. 一句话

**这个项目升级的难点不在 Spring Boot 本身，而在「Boot 的 BOM 会动 milo / POI 的依赖」和「测试测不到容器能不能起来」。**
把这两件盯死，其余都是编译驱动的机械改动。
