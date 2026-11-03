# 质量与不良品处置 · Spec v1.0

> 生成：2026-11-03 | 基于 PM 业务口径 + 架构设计 + UI/UX 设计三份产出，经项目总监裁决
> 状态：**已锁定**（两个分歧已裁决，见 §2）

---

## 1. 目标与范围

**一句话**：让 KnowFab-SIRP 具备完整的质检体系（IQC/FAI/IPQC/FQC + AQL 抽样 + 缺陷分级）与不良品三种处置（报废/返工/让步接收），并把"设备采到的不良数"第一次接进 ERP。

### 范围内（P0）

| # | 功能 | 验收锚点 |
|---|---|---|
| 1 | 缺陷分类主数据（可维护，含分级 CR/MA/MI） | CRUD + 被引用不可删 |
| 2 | AQL 抽样方案（GB/T 2828.1，可配置） | 3 个锚点格子算对 |
| 3 | 检验单（IQC/FAI/IPQC/FQC）建单/录入/提交/判定/作废 | 判定算法全分支 |
| 4 | 不良品记录（双通道：设备采集 + 人工补录） | 设备同步幂等 |
| 5 | 三种处置：报废 / 返工 / 让步接收 | 处置 × 库存状态 6 分支矩阵 |
| 6 | 质量看板（合格率/不良率/待处置/让步率/报废损失 + 帕累托） | 口径写在返回里 |
| 7 | **修既有 P0：不良品被当良品入库** | 入库量 = 总产出 − 不良数 |

### 范围外（明确不做，防止蔓延）

- 批次/序列号追溯（`lot_no` 只是自由文本，不做批次库存）
- 加严/放宽自动转移规则（表里有字段，功能不做）
- SPC 控制图 / CPK
- MRB 多级评审会签
- 新增 `INSPECTOR` 角色（用现有 4 角色映射）

---

## 2. 关键决策（两个分歧已裁决）

### 2.1 裁决：报废**新增** `SCRAP_OUT` 单据类型

**分歧**：PM 主张复用 `OTHER_OUT`（语义已在），架构师主张新增 `SCRAP_OUT`。

**裁决：新增。** 理由：
- 质量成本报表必须能回答"这个月报废损失多少"。用 `OTHER_OUT` + `remark like '%报废%'` 匹配，客户手填的其他出库单只要备注带"报废"二字就会被误计。
- 反对理由（"多一个近义项"）的代价已经被 `isOutbound()` 提取消解：新增出库类型的风险是"漏登记导致方向反了、库存凭空涨且不报错"，提取单一事实来源后这类风险归零。

### 2.2 裁决：让步接收进**独立特采仓**，不新增 `CONCESSION_IN` 类型

**分歧**：PM 主张独立特采仓，架构师主张新类型 + 出货提示不出仓。

**裁决：独立特采仓。** 理由：
- PM 的 AC-07 是 P0：销售出库源仓是特采仓且无客户同意 → **硬拦**。这依赖仓维度；架构师的"只提示不拦"防不住真实事故（仓管发不出去会找经理手工放行）。
- 系统已支持多仓 + TRANSFER，零改造。
- 让步让价损失由 `erp_qc_defect.concession_amount` 承载，不依赖单据类型。

### 2.3 裁决：主计数器口径显式化，新增 `device.counter_mode`

**这是三方都指出的前提事实**：`prod_hourly.output` 到底是"总产出（含不良）"还是"良品数"，取决于现场计数器接线，代码无法判断。

**裁决**：
- 新增 `device.counter_mode`（`TOTAL` / `GOOD`），**默认 `TOTAL`**
- 依据：`OeeCalculator` 的良率公式 `(output − defect) / output` 已经假设 output 含不良。保持全系统同一假设。
- `TOTAL`：入库量 = `output − defect`；`GOOD`：入库量 = `output`
- 设备配置页必须明确提示，选错会导致库存多算/少算

### 2.4 裁决：不良数分两个对象，**绝不相加**

| 对象 | 口径 | 数据源 | 用途 |
|---|---|---|---|
| A 生产不良数 | 生产端产生的不良件数 | `prod_hourly.defect` / `manual_entry.defect` | OEE 良率、生产不良率 |
| B 质检判定数 | 判定为不良的件数 | `erp_qc_defect` | 处置、质量成本 |

两套并存（ADR-009 技术债），看板必须**并排展示 + 差异说明 + 一键同步入口**，不许静默合并成一个数。

### 2.5 双通道合并规则（对象 A 内部）

- 设备配了 `reject` 点位且当日有采集 → **以设备为准**，人工补录仅作对照不叠加
- 设备无数据 → 以人工为准
- 两边都 > 0 → **不自动相加**，标记 `challenged` 生成核对待办
- **现状要改**：`ReportService.attachOee` 现在是 `defect = prod_hourly.defect + manual_entry.defect` 直接相加，必须改成本规则

### 2.6 裁决：让步**不新增** `CONCESSION_IN`，进特采仓 + 关联不良单

**分歧**：架构师担心"让步品与正常品在账上无法区分"，主张新增 `CONCESSION_IN`。

**裁决：不新增。** 理由：

- 让步的**核心风险是发货发错**，而特采仓已经硬拦了（AC-07）。这是仓维度解决的事，不是单据类型能解决的。
- 让步的**让价损失本来就不在库存单据里**，在 `erp_qc_defect.concession_amount`。所以"这个月让了多少"从不良品表取，不从单据取——新增类型也解决不了这个问题。
- 少一个单据类型 = 少 5 个登记点 × 2 的风险。`SCRAP_OUT` 必须加是因为报废损失要从单据类型对账；让步不需要。

单据：未入账用 `OTHER_IN`，已入账用 `TRANSFER`，都带 `src_order_no = 不良单号`。追溯链靠这个关联，不靠 `bill_type`。

### 2.7 裁决：混合处置用**处置流水表**，不拆分记录

**场景**：一批 8 件不良，3 件报废 + 5 件返工。这是真实场景，必须支持。

**三个方案对比**：

| 方案 | 问题 |
|---|---|
| 记录只存单值 `disposition` | 一批只能一种处置。填报废则 8 件全废，填返工则 3 件变不存在的工时 |
| 拆分记录（`split_from` 互链） | 能表达，但产生两条同源记录，报表要合并；且原记录数量与实际处置量脱节 |
| **处置流水表** | 原记录 8 件不动，两条流水各记 3/5；`sum(流水.qty) == qty` 时自动结案 |

**裁决：处置流水表 `erp_qc_disposition`（头 + 明细，只追加、只作废）。**

```sql
-- 头表 erp_qc_defect 的两列镜像（写法与 erp_work_order.done_qty 一致，只由处置服务重算）
disposed_qty DECIMAL(18,4) NOT NULL DEFAULT 0
status       VARCHAR(16)   NOT NULL DEFAULT 'PENDING'
             -- 只有三态：PENDING / DISPOSED / CANCELLED
             -- 「处置了一半」不设独立状态，用 disposed_qty 数字表达（已处置 3 · 待处置 5）
             -- 理由：少一个状态就少一批边界情况，尤其"处置全部作废后要从 PARTIAL 退回 PENDING"

-- 明细 erp_qc_disposition
id, defect_id, seq_no,
action        VARCHAR(16),   -- SCRAP / REWORK / CONCESSION
qty           DECIMAL(18,4),
fail_qty      DECIMAL(18,4) DEFAULT 0,   -- 返工失败退回件数（只有 REWORK 用）
stock_bill_no VARCHAR(40),
rework_order_no VARCHAR(40),
fqc_inspection_no VARCHAR(40),
concession_partner_id, concession_so_no, concession_approver, concession_amount,
concession_ack_at, concession_ack_by, concession_ack_bill_no,
cost_amount, cost_partial,
operator, disposed_at, remark,
voided_at, voided_by, void_reason        -- 撤销：冲销单据 + 标记流水作废
```

`fail_qty` 是这个设计的关键：6 件返工、1 件失败 → REWORK 行记 `qty=6, fail_qty=1`（有效消费 5），可处置额度回到 1 → 再建一条 `SCRAP 1`。守恒 `(2+5)+1 = 8`。**全程只追加 + 作废，不改任何已记录的数量。**

**为什么不用"拆分记录"（8 → 3+5 两张单）**：
- 会打穿设备通道的幂等键 `DEV:设备:小时`。拆分后设备同步按"PENDING 则覆盖 qty"会把数量覆盖回去，合计凭空多 5 件
- 会级联成树（8 → 2 报废 + 6 返工 → 1 件复检失败转报废 = 三层父子链，算总损失要递归）
- 会改写历史数字（上月的不良率被今天的拆分动作改掉）

派生规则（不存冗余状态）：
```
已处置 = sum(qty - fail_qty) where voided_at is null
待处置 = 头表 qty - 已处置
待处置 == 0  → DISPOSED（否则 PENDING）
```

「处置了一半」用数字表达（`已处置 3 · 待处置 5`），要筛这部分记录用参数 `?partial=true`
（后端翻译成 `disposed_qty > 0 AND disposed_qty < qty`），不设独立状态。

**看板"待处置"必须按件算，不能按条算**：`pendingQty = sum(qty - disposed_qty)`，另给 `pendingRecords = count(status in (PENDING, PARTIAL))`。只给一个 `pending` 的话，8 件的单处置 3 件后会被算成"8 件待处置"。

`erp_qc_defect` 上**不再有** `disposition` 字段（避免两份真值）。

### 2.8 裁决：AQL 的 Ac **永远比件数**，不比缺陷项数

GB/T 2828.1 的判定单位是**不合格品件数**，不是缺陷个数。

- 1 件产品带 6 个 MA 缺陷 → 在 AQL 判定里算 **1 件不合格品**
- 按项数算 6 会判不合格，按件数算 1 判合格 —— **正确答案是件数**
- 所以 `defect_qty`（不合格品数）是唯一判定依据，`defect_cr/ma/mi`（项数）只用于缺陷分析

CR 同理：1 件带 CR 缺陷 = 1 件不合格品，因 `Ac = -1` 直接 FAIL。

### 2.9 裁决：页面用 1 条路由 + 4 个 tab，检验单详情用 drawer

- `/erp/quality` 一条路由，4 个 tab：看板 / 检验单 / 不良品 / 质量字典
- 检验单详情用 drawer（判定岗要连判 20 张，drawer 不丢列表滚动位置），加 `?qc=单号` 深链 + 上一张/下一张
- **不加 5 个一级菜单**：`nav.groupErp` 现有 11 项，加到 16 项会让 1366×768 一体机上出滚动条

### 2.11 裁决：`blocked_warehouse`（禁发仓）+ fail-safe 出库判定

**改名**：原来叫"特采仓 / concession_warehouse"，现在装两类货（已让步待发 + 待判定退回），名字装不下了。改为 **`blocked_warehouse`（禁发仓）**，语义是"此仓货默认不可出库，放行需依据"。

`warehouse` 表加 `kind VARCHAR(16) NOT NULL DEFAULT 'STANDARD'`：

| kind | 含义 | 可售 | 装什么 |
|---|---|---|---|
| `STANDARD` | 良品仓 | 是 | 正常库存 |
| `BLOCKED` | **禁发仓** | **默认否** | 让步待发品、待判定退货 |

**不能靠 `code`/仓名字符串匹配**——客户会改名，匹配不到就"全都不拦"，界面正常、无报错，三个月后复盘才发现。**主数据缺失一律报错，不做字面量兜底。**

#### fail-safe 出库判定（这是 AC-07 的真正实现）

```
禁发仓的货默认不可出库。
唯一放行条件：该 SKU 存在未作废让步流水 且 该流水有客户同意凭证。
```

| 源仓 | 让步流水 | 客户同意 | 结果 |
|---|---|---|---|
| 良品仓 | — | — | 放行 |
| 禁发仓 | 有 | 有 | 放行 |
| 禁发仓 | 有 | 无 | 拦 `NO_ACK`（动作：登记客户同意） |
| 禁发仓 | 无 | — | 拦 `NO_RELEASE`（动作：去不良品页处置） |

**为什么比"多查一张退货表"好**：
1. 不用每加一种挂起原因（盘盈待判、客诉待查、召回）就改一次查询——那迟早变成第二个"两份白名单"（F3 的毛病）
2. **失效方向安全**：查不到 → 拦。反过来就是事故
3. 只依赖仓一个维度：仓是物理事实，不会因某条记录漏写而失效

#### 货的走向是单向的

```
QUALITY 退货 → 禁发仓
  处置「可再销售」→ TRANSFER 到良品仓（这一步就是放行决定，有人签字）
  处置「让步放行」→ 留在禁发仓 + 让步流水 + 客户同意凭证
  处置「报废」    → SCRAP_OUT 出库
```

"货能不能发"永远只有一个答案来源：**它在哪个仓**。

### 2.12 裁决：销售可用量必须排除禁发仓

`ErpSalesQueryService` 的可用量（现存量 − 订单占用）现在按 SKU 汇总所有仓。特采品进仓后如果不排除：

- 销售看到"可承诺 50"，其中 20 是特采品 → 承诺交期 → 从特采仓发货被 AC-07 拦 → 交不了货
- 这不是数据错，是**销售被系统坑了**

修法：可用量加 `warehouse.kind = 'STANDARD'` 条件；禁发仓的量单独给字段 `blockedQty`（"另有 20 件在禁发仓，需放行依据"），**不计入可承诺量**。

### 2.10 裁决：撤销处置权限放宽到 `canWrite`（ADMIN|MANAGER）

架构师原定"撤销仅 ADMIN"，但库存单据作废（`voidBill`）的权限门是 `canWrite`。

**结果会是**：主任能作废那张报废出库单，却撤不了处置 → 他会绕路去库存页手动作废 → 处置状态和库存分家，比不给撤销更糟。

**裁决**：撤销处置 = `canWrite`，但必须：
- 强制填原因
- 同时冲销处置产生的库存单据（复用 `voidBill`，零新造成本）
- 留撤销历史（谁/何时/原因/冲销单号），列表显示"已撤销 N 次"

---

## 3. 数据模型（6 张新表 + 3 个增量列）

### 3.1 表清单

| 表 | 用途 | 关键约束 |
|---|---|---|
| `erp_qc_defect_code` | 缺陷分类主数据 | `uk(code)`；`severity` CR/MA/MI；被引用不可删，只能停用 |
| `erp_qc_aql_plan` | 抽样方案（GB/T 2828.1） | `uk(level, strict_level, lot_from, aql)`；`lot_to` 用 `999999999` 哨兵不用 NULL |
| `erp_qc_inspection` | 检验单头 | `uk(inspection_no)`；抽样参数快照，判定只用快照 |
| `erp_qc_inspection_item` | 检验项明细 | `uk(inspection_id, line_no)` |
| `erp_qc_defect` | 不良品记录 | `uk(source_key)` 幂等；`in_stock` 决定处置动不动库存 |
| `erp_qc_disposition` | 处置流水（一单可多次处置） | 支持分批处置 |

### 3.2 三个增量列

| 表 | 列 | 说明 |
|---|---|---|
| `device` | `counter_mode VARCHAR(8) DEFAULT 'TOTAL'` | 主计数器口径 |
| `erp_work_order` | `order_type VARCHAR(16) DEFAULT 'PROD'` | PROD / REWORK |
| `erp_work_order` | `src_work_order_no VARCHAR(40)` | 返工单指向原工单 |

### 3.3 关键字段语义（最易做错的三处）

1. **`erp_qc_inspection.defect_qty` ≠ `defect_cr + ma + mi`**
   - `defect_cr/ma/mi` = **不合格项数**（一个产品可能同时有尺寸和外观两个缺陷）
   - `defect_qty` = **不合格品数**，判定唯一依据
   - 混用会让判定结论反转（实测例子：10 项 → 5 件，n=80/Ac=5，正确判合格，错判不合格）

2. **`erp_qc_defect.in_stock`** 决定处置动不动库存
   - `false`：不良从未入账（设备采集、在制品、IQC 判定前）→ 报废不动库存
   - `true`：已入账（退货、库存抽检、FQC 后判）→ 报废必须出库

3. **`sample_source` 三档必须分开**（`erp_qc_inspection.sample_source VARCHAR(8) DEFAULT 'AQL'`）

   | 取值 | 用于 | 规则 |
   |---|---|---|
   | `AQL` | 正常检验 | 走方案表算 n/Ac/Re |
   | `FIXED` | FAI 的 n=1、IPQC 手填 n | **标准规定如此** |
   | `MANUAL` | 主数据缺失时的兜底 | **必须全检（n = lotQty）+ Ac 强制 0/Re 1**，不许手填 Ac |

   **原则：抽样数可以通融（成本问题），允收数不能通融（质量底线）。**
   手填如果连 `Ac` 一起填，手填 `Ac=99` 就永远判合格——赶交期时一定有人这么干。

   `FIXED` 与 `MANUAL` 必须分开，否则看板上"标准规定的抽样"和"主数据缺漏的临时方案"混在一起，永远发现不了主数据维护不到位。配审计 `AQL_MANUAL`。

4. **`Ac = 0` 与 `Ac = -1` 不是一回事**
   - `-1` = 真正的"一件都不许有"（CR 致命缺陷）
   - `0` = 未配置
   - 内部统一存 `-1` 表示零容忍

### 3.4 处置 × 库存状态矩阵（实现必须照此）

| 处置 | `in_stock=false` | `in_stock=true` |
|---|---|---|
| **报废** | 不动库存，只改状态；损失摊入良品单位成本 | `SCRAP_OUT` 出库（qty 负，扣所在仓） |
| **返工** | 建 `REWORK` 工单；返工良品 `PROD_IN` 入库；补料走 `MATERIAL_OUT` | 先 `TRANSFER` 到返工仓，返工后转回 |
| **让步** | `OTHER_IN` 入特采仓 | `TRANSFER` 原仓 → 特采仓 |

### 3.5 退货必须按**原因**分流，不是按"是否让步件"

客户退货里混着两类完全不同的东西。判据必须是**退货原因**，由操作人在退货时选（那时他恰好知道答案）：

| `returnReason` | 是否质量问题 | 生成不良记录 | 入哪个仓 |
|---|---|---|---|
| `QUALITY` 质量问题（尺寸超差、材质不对、功能失效） | 是 | **是**（`source=RETURN`, `in_stock=true`, `PENDING`） | **禁发仓** |
| `OTHER` 订多 / 发错 / 取消 | 否 | 否 | 良品仓 |

**`returnReason` 必选，无默认值。** 默认值会决定行为：默认 `OTHER` → 质量退货被系统性漏掉；默认 `QUALITY` → 队列全是噪音。

**为什么不能"全部都生成"**：订多、发错、取消的退货是良品。全进待处置会让队列堆满噪音，两周后质检就会"一键全选 → 报废"或者干脆不看队列——那比不做还糟。

**为什么不能"只对让步件生成"**：会漏掉最该抓的一类——**客户因质量问题退回的普通良品**。它不是让步件，按原方案会直接入良品仓，重新发给下一个客户，质量问题被抹掉。

> **修正记录**：本条推翻了本文档 v1.1 的裁决（"所有退货都生成不良记录"）。那个判断是错的——它把"退货都是坏的"当成了前提。

---

## 4. API 清单（前缀 `/api/erp/qc/`）

### 4.1 权限（SecurityConfig 必须在 `/api/erp/**` 通配**之前**加）

```
POST /api/erp/qc/inspections          → ADMIN|MANAGER|LEADER   现场录单
PUT  /api/erp/qc/inspections/*        → ADMIN|MANAGER|LEADER
POST /api/erp/qc/inspections/*/submit → ADMIN|MANAGER|LEADER
POST /api/erp/qc/defects              → ADMIN|MANAGER|LEADER   现场登记
POST /api/erp/qc/defects/*/claim      → ADMIN|MANAGER|LEADER   补缺陷代码
```

`GET /api/erp/warehouses` 的返回要加 **`concessionWarehouse: boolean`**——前端靠它本地判断 AC-07 的条件 1（源仓是不是特采仓），不用每次请求后端。

判定/处置/作废/撤销一律**只给 ADMIN|MANAGER**（判定权不外包——班组长自己录自己判等于没质检）。

### 4.2 端点

| Method | Path | 功能 |
|---|---|---|
| GET | `/defect-codes` | 缺陷分类列表 |
| POST/PUT/DELETE | `/defect-codes[/{id}]` | 维护（被引用拒绝删除） |
| GET | `/aql/resolve?level&strictLevel&lotQty&aql` | **算样本量 n 与 Ac/Re**（查不到抛异常，不给默认值） |
| GET | `/aql/plans` | 方案表 |
| POST/PUT/DELETE | `/aql/plans[/{id}]` | 维护 |
| POST | `/aql/reseed` | 恢复国标默认 |
| GET | `/inspections?type&status&from&to&kw&page&size` | 列表 |
| GET | `/inspections/{no}` | 详情（单头 + 项 + 关联不良记录） |
| POST | `/inspections` | 建单（IQC/FQC 走 AQL；FAI n=1/Ac=0；IPQC n 手填/Ac=0） |
| PUT | `/inspections/{no}` | 改草稿（整表替换明细） |
| POST | `/inspections/{no}/submit` | 提交（锁定快照） |
| POST | `/inspections/{no}/judge` | **判定**（PASS/FAIL，自动生成不良记录） |
| POST | `/inspections/{no}/cancel` | 作废（已判定不可作废） |
| GET | `/defects?status&source&from&to&page&size` | 不良品列表 |
| POST | `/defects` | 人工补录 |
| POST | `/defects/{no}/scrap` | 报废 |
| POST | `/defects/{no}/rework` | 返工 |
| POST | `/defects/{no}/concession` | 让步 |
| POST | `/defects/{no}/revoke` | 撤销处置（先作废库存单，再退回 PENDING） |
| POST | `/defects/sync-device` | 手动同步设备通道 |
| GET | `/defects/concession-hit?soNo=&whId=&skuIds=` | 出货前查放行依据。返回顶层 `fromSpecialWh` / `blocked`，`hits[]` 每行带 **`blockReason`**：`NO_ACK`（有让步流水但无客户同意）→ 动作是"登记客户同意"；`NO_RELEASE`（禁发仓但无任何放行依据）→ 动作是"去不良品页处置"。两种下一步完全不同，不能混成一个"被拦了" |
| GET | `/dashboard?from&to` | 看板（含 `caliberNote` 口径说明） |
| GET | `/pareto?by=code|device&from&to` | 帕累托 |

---

## 5. 判定算法（纯函数，全分支单测）

```
输入：defectCr, defectQty, ac, re, fullInspection
1. defectCr > 0                    → FAIL      （致命一票否决，不走 AQL）
2. ac == -1 && defectQty > 0       → FAIL      （零容忍）
3. ac == 0 && defectQty > 0        → FAIL      （未配置 Ac 但确有不良）
4. defectQty <= ac                 → PASS
5. defectQty >= re                 → FAIL
6. 其余（数据不自洽）              → 抛异常，不猜
```

`CONDITIONAL`（让步）**永远由人手动选**，算法不产出——让步是商务决定，不是质量计算结果。

---

## 6. AQL 播种范围与锚点

**只播 `GENERAL_II × NORMAL` × AQL ∈ {0.65, 1.0, 1.5, 2.5, 4.0, 6.5} × 11 个批量档 = 66 行**（够 95% 场景）。

**三个锚点必须单测验证**（人工对 GB/T 2828.1 表 2-A 核对，禁止用算法反推）：

| 批量 | 检验水平 | AQL | 字码 | n | Ac | Re |
|---|---|---|---|---|---|---|
| 501~1200 | II | 1.0 | J | 80 | 2 | 3 |
| 501~1200 | II | 2.5 | J | 80 | 5 | 6 |
| 26~50 | II | 0.65 | D | 8 | 0 | 1 |

**CR 致命缺陷：任意 n 一律 Ac = -1 / Re = 1**（发现 1 件即整批拒收）。

---

## 7. 要修的三个既有缺陷（本次一并做）

| # | 缺陷 | 位置 | 严重度 |
|---|---|---|---|
| **F1** | 不良品被当良品入库 | `ErpProdInboundService:231` 传 `BigDecimal.ZERO` 当 defect；`produce` 用总产出入库 | **P0**（当前数据无损失，因为 defect 全 0 且自动入库全关，但路径是真的） |
| **F2** | 良率重复计 | `ReportService:197` `defect = sum.defect + manualDefect` 直接相加 | P1 |
| **F3** | 出库判断两份白名单 | `ErpStockService` 的 `createManual` 与 `checkStockForOut` 各写一份 | P1（新增类型必踩） |
| **F4** | **反冲必须按总产出，不能按良品** | `ErpWorkOrderService.backflush(o, producedQty)` 的入参 | **P0**（见下） |

**F4 是 F1 的连带修正，最容易做错且错了不报错**：

```
output=100, defect=3
良品入库 = 100 − 3 = 97      ← 这个要减
反冲扣料 = 100 × BOM单位用量  ← 这个不能减
```

不良品的料真的消耗了。按 97 反冲，那 3 件的料永远不扣，**材料成本永久少计**，账面看不出任何异常，只是每个产品成本都偏低，客户拿去报价就赔。

实现：`produce()` 内部 `backflush` 时传 `qty + defectQty`，不是 `qty`。

**F3 修法**：提取 `public static boolean isOutbound(String type)`，两处共用。这是本次唯一建议动的既有核心逻辑。

---

## 8. 验收标准（QA 按此验收）

| # | 验收项 | 判定方式 |
|---|---|---|
| AC-01 | 处置 × 库存 6 分支矩阵 | 单测覆盖，每支断言"建了几张单、类型、qty 符号" |
| AC-02 | 设备同步幂等 | 重跑 3 次，记录数与 qty 不变 |
| AC-03 | 不丢数 | 同步后调大 `prod_hourly.defect` 重跑，总入账 = 最终值 |
| AC-04 | 判定全分支 | 含 CR 一票否决、Ac=-1、边界 =ac、=re、不自洽抛异常 |
| AC-05 | AQL 三锚点 | §6 三个格子算对 |
| AC-06 | 返工不重复计料 | 返工单调 issue 必须抛异常；`amountByOrderAndType(返工单, MATERIAL_OUT) == 0` |
| AC-07 | 报废不重复进成本 | 处置前后 `orderCost.totalCost` 不变 |
| AC-08 | 不良品不入成品仓 | 产出 total=10/defect=2 → `PROD_IN` qty 必须为 8 |
| AC-09 | 越权 | LEADER 调 `judge` → 403；LEADER 建检验单 → 200 |
| AC-10 | 库存一致 | 任意处置前后 `sum(qty) where voided_at is null` 变化量 = 该处置应有的移动量 |
| AC-11 | 不破既有 | `smoke-all` 103 条全绿；`selftest` 121 条全绿 |
| AC-12 | 禁发仓 fail-safe | 禁发仓货无让步流水 → 拦 `NO_RELEASE`；有流水无客户同意 → 拦 `NO_ACK`；两者齐 → 放行。**查不到必须拦，不许放行** |
| AC-13 | 退货按原因分流 | `QUALITY` 退货 → 入禁发仓 + 生成 PENDING 不良记录；`OTHER` 退货 → 入良品仓、不生成记录 |
| AC-14 | 退货原因必选 | 不传 `returnReason` → 拒绝，不落默认值 |

---

## 9. 已知坑（实现时对照）

| 坑 | 防法 |
|---|---|
| 报废方向反了（变入库）且不报错 | 提取 `isOutbound()`，单测断言所有出库类型 `qty < 0` |
| 让步品重复入库 | `in_stock=true` 时禁止入库；`in_stock=true` 但无 `source_bill_no` 且来源不是退货/抽检 → 拒绝保存 |
| AQL 快照丢失 | 判定只读单头快照，永不回查方案表 |
| 返工工单占用设备冲突 | 未完工返修不建单；已完工建单时设备已释放 |
| 未分类记录淹掉帕累托 | 看板固定 `untriagedCount` 卡片，`pareto` 固定含"未分类"行 |
| `prod_hourly.defect` 是 double | 转 BigDecimal 时 `setScale(4, HALF_UP)`，`< 0.0001` 视为 0 |
| 前端 CSS 变量名 | **只用 v3 名**（`--fg/--muted/--border/--accent/--surface-2/--radius-md/--font-mono`），旧名已全部失效（见提交 26531e7） |
| 组件类名 | `.tab-toolbar` / `.kpi-row` / `.cell-main` 等布局类在 `styles/erp.css` 全局定义，页面里**不要**再写 scoped 版 |
| 反冲按良品算 | 见 F4。材料成本永久少计，不报错 |
| FQC 回写 `defect_qty` 不校验状态 | 已 DONE 的工单被回写会让已完工成本分母事后变化，月报对不上。必须校验 `RUNNING` |
| 兜底归属静默入库 | 工单结束后设备仍在转 → 回退固定 SKU 会凭空多一笔收入。至少 `log.warn` 写清设备和数量 |
| `afterCommit` 靠事件转同步 | 要用 `TransactionSynchronizationManager.registerSynchronization` 显式注册，不保证重新开事务 |
| 特采仓默认值 | `1.7.0.sql` 要显式回填 `warehouse.kind`（`WHERE kind IS NULL OR kind = ''`），不能只依赖 ddl-auto |

---

## 9.5 通则：主数据缺失一律报错，不做字面量兜底

这是本次定下的一条通用规矩，适用于所有新代码：

- 仓库类型缺失 → 报错「仓库主数据未升级，无法校验放行」，**禁用提交**，不要按仓名猜
- 单据类型无前缀 → 抛异常，不要 `getOrDefault(type, "BILL")` 生成一个看不出类型的单号
- AQL 方案查不到 → 抛异常，不要给默认 Ac/Re

**理由**：字面量兜底会让错误从显性变隐性。客户把仓叫「让步品仓」，兜底匹配不到 → 全都不拦 → 界面正常、无报错 → 三个月后复盘才发现。**逼它坏在明处。**

## 9.6 已知限制（写出来，别等客户发现）

**让步匹配是 SKU 级，不做数量配平**：订单要 5 件、禁发仓只有 2 件让步品，也会整行拦。方向安全（多拦不少拦），但"部分从良品仓补货"的场景会多拦一次。

## 10. 交付顺序

1. **基础层**：6 张表 + Repository + 迁移脚本 + `isOutbound()` 提取 + AQL Seeder + AQL Service + 缺陷分类 CRUD
2. **检验层**：检验单 CRUD + 判定算法 + 提交锁定
3. **处置层**：不良品记录 + 三种处置 + 库存适配器 + 设备同步
4. **报表层**：看板 + 帕累托 + 质量成本
5. **前端**：9 个组件（`QcBoardTab` / `QcInspectionTab` / `QcInspectionDrawer` / `QcAqlSampling` / `QcItemEditor` / `QcNcrTab` / `QcRegisterDialog` / `QcDispositionDialog` / `QcReconBar`）+ `api/qc.ts` + `utils/aql.ts`
6. **门禁**：selftest 补 Q 组，跑全量
