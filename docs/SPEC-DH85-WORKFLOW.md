# Spec — DH-85 工作流最小版（派工 + 待办 + AI 写操作）

> 生成日期：2026-11-06 ｜ 依据：`docs/decisions/ADR-001-product-direction.md`
> 状态：已确认（用户 2026-11-06「p1 开始，先用模拟数据」）

---

## 1. 目标与范围

**一句话**：让维修工单「有人负责、有人看得见」，并让 AI 能替人把这件事**办掉**，而不只是给一段建议。

现状（2026-11-06 核实）：`ErpMaintOrder` 已有报警关联（`alarmEventId`）、AI 建议（`aiAdvice`/`aiSource`/`aiAt`）、根因与方案（`rootCause`/`solution`）、四态状态机（`ANALYZED`/`IN_PROGRESS`/`RESOLVED`/`CANCELLED`）、解决人（`resolvedBy`）。
**缺的是**：没有「派给谁」（只有事后的 `resolvedBy`，没有事前的责任人）、没有待办视图、AI 碰不到写操作。

### 范围内（P0）

| # | 能力 | 说明 |
|---|------|------|
| 1 | **派工** | 工单可指派给具体人（`assignee`），记录谁派的、什么时候派的 |
| 2 | **我的待办** | 按当前登录人查「派给我的未完成工单」（`ANALYZED` + `IN_PROGRESS`） |
| 3 | **指派通知** | 指派后复用现有 `AlarmNotifier.sendBulletin` 发一条公告（有渠道配置才发，没配就只落待办） |
| 4 | **AI 建单** | AI 工具 `create_maint_order`：从报警事件创建维修工单，可同时指定负责人 |
| 5 | **AI 派单** | AI 工具 `assign_maint_order`：把现有工单指派给人 |
| 6 | **写操作必审计** | AI 的每一次写操作都落 `audit_log`，记「由 AI 代表 xxx 执行」，含参数摘要 |

### 范围外（明确不做，防止蔓延）

| 不做 | 原因 |
|------|------|
| **通用审批流 / BPMN 引擎** | 这是最容易把产品做成「流程平台」的地方。真实需求点是「超金额的采购/付款要上级批」，不是维修工单 |
| **多级审批 / 会签 / 加签** | 小厂就 3-5 个人，两级已足够；会签没有场景 |
| **SLA / 超时自动升级** | `AlarmNotifier.escalate` 已有报警升级，工单侧没有场景数据支撑 |
| **待办推送（微信/钉钉/App）** | 只做「打开页面能看到」。推送渠道复用现有公告，不新建 |
| **新建待办表** | 待办 = 查工单表的条件，不冗余存储（避免两处状态不同步，这是 DH-80 的老教训） |
| **工单评论/协作讨论** | 没有场景 |

### 与 ADR-001 的偏差（**要改 ADR**）

ADR-001 写的是「审批 + 派工 + 通知」三类动作。落地时**砍掉审批**：

- 维修工单不需要审批——派了就去修，加一道审批只会让车间绕过去
- 采购/付款的「把关」需求，`erp_purchase_order` 已有五态（草稿→已确认→部分入库→已完成/已取消），「确认」就是那道关
- 通用审批是最容易长成 BPMN 的入口

⇒ ADR-001 的 P1 描述要改成「**派工 + 待办 + 通知 + AI 写操作**」，审批挪到「等明确金额场景再做」。

---

## 2. 关键裁决

### 2.1 指派到**人**，不指派到角色

派给「LEADER」等于没人负责——三个 LEADER 会互相以为对方在做。
`assignee` 存 `username`（与 `sys_user.username` 对应），可空（= 未指派）。

### 2.2 AI 的权限**不放大**

AI 代表**当前登录用户**执行。工具的角色集合决定「哪些人能通过 AI 做这件事」，执行时再用当前用户的真实角色校验一次。
**绝不给 AI 一个超级账号**——否则「AI 帮我建单」会变成绕过权限的后门。

### 2.3 写操作**必写审计**，且审计要能看出是 AI 干的

`audit_log` 记 `action = "ai.create_maint_order"`（带 `ai.` 前缀），`operator` 记真实用户名。
人工点按钮则是 `maint.assign`。**两类必须能区分**，否则出了事查不清是人还是模型。

### 2.4 待办**不建表**

`my` 接口直接查 `erp_maint_order where assignee = ? and status in ('ANALYZED','IN_PROGRESS')`。
理由：DH-80 的教训——同一个事实存两份，迟早不同步。

### 2.5 通知**复用** `AlarmNotifier.sendBulletin`

不新造通知渠道。`sendBulletin(title, content)` 已存在，走已配置的渠道（微信/钉钉/webhook）。
**没配渠道时不报错**，只落待办（否则小厂没配 webhook 就派不了工）。

### 2.6 指派不改变工单状态

`ANALYZED` + 指派 = 仍是 `ANALYZED`（只是有了责任人）。
状态流转仍由 `start` / `resolve` / `cancel` 控制。**指派不是状态机的一部分**——否则状态会变成两套口径。

---

## 3. 数据模型

### 3.1 表：`erp_maint_order`（只加 3 列，不新建表）

| 列 | 类型 | 语义 |
|----|------|------|
| `assignee` | `varchar(64) NULL` | 负责人 username，NULL = 未指派 |
| `assigned_at` | `bigint NULL` | 指派时间（epoch ms） |
| `assigned_by` | `varchar(64) NULL` | 谁派的（用户名；AI 派单时也是发起人） |

索引：`idx_mo_assignee (assignee, status)` —— 待办查询每次都用。

### 3.2 迁移脚本 `1.9.3.sql`

⚠️ 按 **DH-84 的教训**写：守卫失败要**故意报错**（`SELECT 1 FROM 不存在的表 LIMIT 0`），不要 `DO 0` 静默跳过 —— 静默跳过会记 `ok=1`，表建好后再也不补，索引永久缺失。

---

## 4. 接口清单

| Method | Path | 功能 | 认证 | 请求 | 响应 |
|--------|------|------|------|------|------|
| POST | `/api/erp/maint/{orderNo}/assign` | 指派负责人 | ADMIN\|MANAGER\|LEADER | `{assignee}`（空字符串=取消指派） | `{ok, assignee, assignedAt}` |
| GET | `/api/erp/maint/my` | 我的待办 | 登录即可 | — | `[{orderNo, deviceCode, alarmText, status, assignedAt, aiAdvice}]` |
| GET | `/api/erp/maint/assignable` | 可选人员 | 登录即可 | — | `[{username, displayName, role}]`（只列可被派的角色：ADMIN/MANAGER/LEADER） |

**权限说明**：`my` 和 `assignable` 登录即可（待办是每个人看自己的）；`assign` 要写数字/改责任人，按项目铁律**必须显式角色规则**且排在 `/api/erp/**` 通配之前。

---

## 5. AI 工具（新增 2 个写工具）

| 工具名 | 角色 | 参数 | 行为 |
|--------|------|------|------|
| `create_maint_order` | ADMIN\|MANAGER\|LEADER | `alarm_event_id`(必填)、`assignee`(可选)、`solution`(可选) | 从报警建工单；设备/报警码从事件带出；给了 assignee 就一并指派 |
| `assign_maint_order` | ADMIN\|MANAGER\|LEADER | `order_no`(必填)、`assignee`(必填) | 指派现有工单；工单不存在/已终态时返回可读错误 |

两者执行后都：写 `audit_log`（`ai.*` 前缀 + 真实 operator）+ 指派时发公告。

---

## 6. 验收标准（EARS）

| 编号 | 验收标准 | 优先级 |
|------|----------|--------|
| AC-01 | While 一个 `ANALYZED` 工单被指派给张三，系统**必须**让 `GET /api/erp/maint/my`（张三登录）返回它 | P0 |
| AC-02 | When 指派给不存在的人，系统**必须**返回 400 + 可读错误，且不落库 | P0 |
| AC-03 | When `LEADER` 登录调 `assign`，系统**必须**返回 200（LEADER 有派工权） | P0 |
| AC-04 | When `SALES` 登录调 `assign`，系统**必须**返回 **403**（无派工权） | P0 |
| AC-05 | When 指派成功，系统**必须**写一条 `audit_log`，action=`maint.assign`，含 orderNo 与 assignee | P0 |
| AC-06 | When AI 调 `create_maint_order`，系统**必须**写 `audit_log`，action=`ai.create_maint_order`，operator=当前用户 | P0 |
| AC-07 | When AI 代表一个 `SALES` 用户调 `create_maint_order`，系统**必须**拒绝（角色不满足） | P0 |
| AC-08 | If 系统没配任何通知渠道，指派**仍须成功**且待办可见（不因通知失败而回滚） | P0 |
| AC-09 | When 工单已 `RESOLVED`/`CANCELLED`，`assign` **必须**返回 400（终态不可派） | P1 |
| AC-10 | While 一个工单未指派，它**不应**出现在任何人的 `my` 列表里 | P1 |

---

## 7. 端到端验证（模拟数据）

```bash
# 0. 干净库 + 演示数据
python tools/seed-demo.py

# 1. 造一条报警 → AI 诊断 → 得到工单
curl -X POST /api/erp/maint/analyze -d '{"alarmEventId": 1}'

# 2. 管理员指派给 leader
curl -X POST /api/erp/maint/<orderNo>/assign -d '{"assignee":"leader"}'
# 断言：200；audit_log 多一条 maint.assign

# 3. leader 登录看我的待办
curl -b leader.cookie /api/erp/maint/my
# 断言：列表里有这个 orderNo

# 4. SALES 调 assign → 必须 403
# 5. AI 用 leader 身份说「帮我建个维修单，派给 leader2」
#    断言：建单成功；audit_log 有 ai.create_maint_order，operator=leader

# 6. 没配通知渠道时再指派一次 → 仍须 200
```

## 8. 变更记录

| 日期 | 变更 | 原因 |
|------|------|------|
| 2026-11-06 | 初版；砍掉审批流 | 维修工单不需要审批；采购单已有五态；通用审批会长成 BPMN |
