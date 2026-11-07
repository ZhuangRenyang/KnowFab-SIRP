# erp 上帝包拆分：批次执行手册（运维）

> 配套脚本：`tools/rollback-erp-split.sh`、`tools/verify-batch-acceptance.sh`、`tools/verify-boot-smoke.sh`
> 门禁：`tools/ci-gate.sh`（`--only-pkg` 只跑护栏，~3 秒）
> 方案（68 文件逐个映射、风险清单、不做清单）：架构师，已发给 team-lead

---

## 0. 边界（先说清楚，避免踩线）

| 归谁 | 做什么 |
|---|---|
| **架构师** | 子包怎么划（映射表）——**不归运维** |
| **后端工程师** | 搬文件、改 `package`、补/改 import ——**不归运维** |
| **QA** | 跑 `mvn clean test`、判定测试对错 ——**不归运维** |
| **运维（本文档作者）** | **怎么执行、怎么回滚、怎么验** |

⇒ 运维侧**不改任何 Java 源文件**，只提供批次编排、回滚机制、验收脚本与门禁。

---

## 1. 前提：三条硬不变式

拆包过程中，这三个数字/状态**变了就是出事**，必须立刻回退：

1. **erp 域文件总数 = 132**（main 106 + test 26）
   （口径 = `com.factory.hub.erp.*` + `com.factory.hub.quality.*` 两个前缀，main + test）
2. **surefire 收集数 = 51 个报告 / 409 个用例**
   （挪包**不应该**改变它；变了 = 漏收集或重复收集）
3. **门禁全绿**（`--only-pkg` + `--skip-be` 两段都要绿）

---

## 2. 批次划分与顺序

| 批次 | 目标子包 | 搬动文件 | erp 内需改 import | 外部主代码 | 外部测试 | 备注 |
|---|---|---|---|---|---|---|
| **B1** | `erp.basic` | 8 | 11 文件 / 36 行 | 5 文件 | 4 文件 | 叶子，不依赖任何人 |
| **B2** | `erp.production` | 21 | 6 文件 / 18 行 | **13 文件** | 4 文件 | **爆炸半径最大 ⇒ 早做，早暴露问题** |
| **B3** | `erp.stock` | 12 | 9 文件 / 34 行 | 6 文件 | **9 文件** | TestKit 与 5 个 `ErpStock*Test` **必须同批** |
| **B4** | `erp.sales` | 8 | 5 文件 / 25 行 | 1 文件 | 0 | |
| **B5** | `erp.purchase` | 8 | 3 文件 / 19 行 | 1 文件 | 0 | |
| **B6** | `erp.analysis` | 11 | 6 文件 / 38 行 | 1 文件 | 0 | |
| | | **68** | | | | |

**排序原则**：① 叶子先行（`basic` 不依赖任何人）；② **外部爆炸半径大的早做**（`production` 影响 13 个外部主代码文件，早做则出问题时已完成批次少、回退代价小）。

---

## 3. 每批的标准动作（6 批都一样）

### 3.0 开始前（只做一次）

```bash
# 打「拆分前基线」tag —— 这是**唯一**的回退起点
tools/rollback-erp-split.sh --tag 0
tools/rollback-erp-split.sh --status      # 确认 tag 在位
```

### 3.1 后端执行本批

- 按架构师的映射表搬文件、改 `package`、改 import
- **测试跟着主代码同批搬**（`erp.stock` 的 `ErpStockTestKit` 与 5 个 `ErpStock*Test` 必须同批）
- 后端自己提交（一个批次 = 一个提交）

### 3.2 验收（秒级，运维侧）

```bash
tools/verify-batch-acceptance.sh --batch 1
```

它做四件事，**全绿才能往下走**：

1. `verify-package-split.mjs` —— 文件数守恒（132）+ 悬空 import + 残留旧全限定名
2. `check-package-size.mjs` —— 包结构护栏（直属 ≤ 24 / 含子包 ≤ 40 / 同类角色 ≤ 12）
3. 打印**引用工作清单**（内联全限定用法 + 待改 import）—— 帮后端定位剩余工作量（信息性，不判失败）
4. 断言 surefire 收集数 ≥ 基线（读 `tools/test-baseline.json`，与门禁同一份）

### 3.3 QA 跑测试

```bash
mvn clean test
```
⇒ 编译零错 + 409 用例全绿 + surefire 仍是 51 报告 / 409 用例。

### 3.4 真启动一次（⚠️ 最容易被跳过、也最关键的一步）

```bash
tools/verify-boot-smoke.sh
```

**为什么不能省**：全项目**没有一个真实的 `@SpringBootTest`**（409 个用例全是纯 Mockito 单元测试）
⇒ `mvn clean test` 全绿**只证明「编译过 + 单元逻辑对」**，
**不证明** 16 个 `@Entity` 被 JPA 扫到、16 个 Repository 被 Spring Data 扫到、
`ErpQcReleaseService` 还能实现 `ErpQualityReleaseGate`。
⇒ 拆包后可能「编译过 + 409 全绿 + 门禁全绿，但启动时 `Not a managed type` / `No qualifying bean` 直接炸」。

冒烟脚本会：探 MySQL 可达 → 起应用 → 扫启动日志找 Spring 装配类致命错误 → 登录 → 打 15 个 `/api/erp/*` 接口 → 停应用。

### 3.5 打 tag（**通过后才打**）

```bash
tools/rollback-erp-split.sh --tag 1        # 第 1 批打完
```
- tag 是**回退锚点**，**不可覆盖、不可手删**（脚本会拒绝覆盖已存在的 tag）
- 批次号**不能跳号**（跳号 ⇒ 中间批次没锚点 ⇒ 出事时无法只回退真正想回退的那段）

### 3.6 不过怎么办

```bash
tools/rollback-erp-split.sh --from-batch 3 --dry-run   # 先看会回退什么
tools/rollback-erp-split.sh --from-batch 3 --yes        # 真回退
```

---

## 4. 回滚（⚠️ 不能「只回滚某一批」）

批次是**串行**的：B3 之后，B4/B5/B6 的文件已经在子包里、import 指向 `erp.stock.*` 这类新包名。
⇒ 只回滚 B3（把 `stock` 搬回 `com.factory.hub.erp`）会让后面几批的 import **全部指向不存在的包** ⇒ **编译不过**。

⇒ **回滚粒度只能是「从第 N 批起，往后全部回退」**：

```bash
tools/rollback-erp-split.sh --from-batch N --dry-run   # 看计划
tools/rollback-erp-split.sh --from-batch N --yes        # 执行（reset --hard，改写历史）
tools/rollback-erp-split.sh --from-batch N --revert --yes  # 已推送：倒序 revert，不改写历史
```

**绝不要**用 `git checkout <旧sha> -- src/main/java/com/factory/hub/erp` 做「单批回滚」：
那只搬回文件、**忘了改留下那侧的 import** ⇒ 半回滚状态 ⇒ 编译不过。

脚本的硬保护（会拒绝执行并说明原因）：
- 工作树有**已跟踪文件**的改动 ⇒ 拒绝（回退会丢掉它们）
- 目标 tag 不存在 / 不是 HEAD 的祖先 ⇒ 拒绝（历史非线性，reset 不适用）
- 非交互环境没加 `--yes` ⇒ 拒绝
- 批次号跳号打 tag ⇒ 拒绝

回退后必做三步：`verify-batch-acceptance.sh` → `mvn clean test` → `verify-boot-smoke.sh`。

---

## 5. 全部 6 批做完之后（⚠️ 别忘了这一步）

**必须人工下调 `tools/check-package-size.mjs` 里的 `BASELINE`**，否则 erp 的护栏是空壳：

```js
// 拆完后：erp 直属文件变 0，文件全在 6 个子包里
erp: { direct: 0, total: 68, kinds: { Controller: 14, Service: 14, Repository: 16 } },
// 并给 6 个新子包各加一条（现在它们走的是 MAX_* 兜底阈值，也能过，
// 但显式基线能给出更准的「不许恶化」信号）：
'erp/basic':      { direct: 8,  total: 8,  kinds: { ... } },
'erp/production': { direct: 21, total: 21, kinds: { ... } },
'erp/stock':      { direct: 12, total: 12, kinds: { ... } },
'erp/sales':      { direct: 8,  total: 8,  kinds: { ... } },
'erp/purchase':   { direct: 8,  total: 8,  kinds: { ... } },
'erp/analysis':   { direct: 11, total: 11, kinds: { ... } },
```

**为什么必须做**：脚本的基线「自动下调」其实**只是打印一条提示**（`↻ 基线可下调`），**常量不会被自动改**。
⇒ 若不人工改，`BASELINE.erp.direct` 仍是 68 ⇒ 护栏允许 erp 从 0 **悄悄长回 68** 个文件而不报
⇒ 脚本头注释自己写的「3 个月后它会长回去 ⇒ 拆了等于没拆」被**重新打开一个口子**。

⚠️ 这**不要**放进自检变异 —— 自检要证明的是「门禁会红」，不是「基线会被自动改」。

---

## 6. 范围提醒：若最终决定把 `quality` 的 38 个 `Erp*` 也收进 `erp/quality/`

- `verify-package-split.mjs` **不受影响**：守恒口径是「两前缀合计」，quality 的文件在 `com.factory.hub.quality`
  还是 `com.factory.hub.erp.quality` 都在口径内 ⇒ 守恒数仍是 **132**。
- `check-package-size.mjs` **会当场红**：`erp/quality` 是新包，38 个直属文件 > `MAX_FILES_PER_DIR`(24)。
  ⇒ 出路二选一：① 给 `erp/quality` 加一条 BASELINE 条目；② 把 quality 再拆小
  （它 Controller 7 / Service 10 / Repository 6，单类 ≤ 12 是够的，卡住的是总数 38 > 24）。
- ⇒ **这条在动手前必须先定**，否则第 3 步就会红，而红的原因会指向错误的方向。

---

## 7. 一页速查

```bash
# 开始拆之前
tools/rollback-erp-split.sh --tag 0

# 每批做完（后端已提交）
tools/verify-batch-acceptance.sh --batch N     # 秒级四项
mvn clean test                                 # QA
tools/verify-boot-smoke.sh                     # 真启动
tools/rollback-erp-split.sh --tag N            # 全绿才打 tag

# 出事了
tools/rollback-erp-split.sh --from-batch N --dry-run
tools/rollback-erp-split.sh --from-batch N --yes

# 状态
tools/rollback-erp-split.sh --status

# 只跑护栏（~3 秒，不需要 maven）
bash tools/ci-gate.sh --only-pkg
```
