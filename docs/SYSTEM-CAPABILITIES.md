# 系统能力清单（话本唯一依据）

> 原则：**这里每一条都能在代码里找到出处**。话本只说这份清单里有的，
> 清单里没有的、或者标了"限制"的，话本里也不许说。
> 核对时间：2026-10-31 · 版本 1.0.3

---

## 一、设备接入

| 能力 | 细节 | 出处 | 限制 |
|------|------|------|------|
| Modbus TCP | 功能码 1/2/3/4 读，支持 u16/s16/u32/s32/f32/bool | `driver/modbus/ModbusTcpDriver` | — |
| Modbus RTU | 串口直连 | `ModbusRtuDriver` | 需要串口 |
| Modbus RTU over TCP | 485 转网口模块透传 | `ModbusRtuOverTcpDriver` | 模块需设 TCP Server |
| OPC UA | 客户端接入 | `driver/` 下对应实现 | 需服务端开启匿名或账号 |
| 模拟设备 | 无硬件也能演示 | `SimulatedDriver` | 仅演示 |
| 自动发现 | 扫网段 + Modbus 探测 | `discovery/ModbusTcpProbe` | 同网段 |
| 点表驱动 | 加设备只改 JSON 点表，不改代码 | `PointDef` + 前端点表编辑 | — |
| 设备模板 | 内置模板 + 自己存的模板 | `DeviceTemplateController` | — |

**可以说的**：不改 PLC 程序（只读）、老设备加 485 转网口也能接、新机老机都行。
**不能说的**：支持所有协议（只支持上面列的四类）。

---

## 二、数据采集与统计

| 能力 | 细节 | 出处 |
|------|------|------|
| 采集周期 | 按设备配置（秒级） | `CollectScheduler` |
| 离线判定 | 连续失败计数 → 离线 | `DeviceCollector` |
| **累计值增量统计** | 计数器变大取差值；变小按重置处理（不会算成负数） | `ProdRollup` |
| 今日产量 | 内存实时 + DB 重算（重启不丢） | `ProdRollup.today`（live+DB 混合口径） |
| 小时产量行 | 每设备每小时一行，含状态时长分配 | `ProdHourly` |
| **设备可用率** | `countedSec` / `availability`（有效采集时长 / 运行时长） | `ProdHourly.availability()` |
| 产量目标 | 日目标 + 完成率 | 设备配置 |

**可以说的**：寄存器几十万也能算今日产量、不用清零 PLC。
**不能说的**：能算 OEE（当前只有可用率与状态时长，没有完整的 OEE 三要素）。

---

## 三、报警

| 能力 | 细节 | 出处 |
|------|------|------|
| 报警规则 | 可配阈值/条件、增删改 | `alarm/AlarmRuleStore` |
| 报警列表与确认 | 按状态查、单条/批量确认 | `AlarmController.ack / ackAll` |
| 通知配置 | 可配通知方式 | `AlarmController.notifyConfig` |
| **报警码字典** | 原始报警码 → 中文含义，按品牌 | `screen/AlarmCodeDict` |
| 字典覆盖品牌 | **台达伺服、三菱伺服**（加品牌 = 加一个 JSON 文件） | `resources/alarmcodes/*.json` |
| **AI 故障顾问** | 给报警码 + 事实，AI 给出原因与处理建议（带缓存） | `screen/FaultAdvisor` |

**可以说的**：伺服报警码翻成人话 + AI 给处理建议。
**限制（重要）**：报警码字典目前只有台达、三菱伺服两个品牌。其它品牌靠 AI 猜测，
所以**不能说"支持所有品牌报警码"**。

---

## 四、能耗

| 能力 | 细节 | 出处 |
|------|------|------|
| 分时电价 | 峰/谷/平多时段方案，可保存切换 | `energy/TariffStore`、`TariffPlan` |
| 电量统计 | 累计电量寄存器增量 → kWh | `energy/EnergyDelta` |
| 异常检测 | 读数频繁变小 → `suspicious()` + 提示 | `EnergyDelta.suspicious/hint` |
| 功率/负荷 | 有功功率 kW 展示 | `role=power` 点位 |
| 能耗总览/设备对比 | 按天/按设备 | `EnergyController` |

**可以说的**：按峰谷平分时算电费、能看出哪台设备费电。
**限制**：电量靠累计寄存器增量，寄存器不能清零（清零按重置处理，会少算一段）。

---

## 五、需量管理（两块制电价）

| 能力 | 细节 | 出处 |
|------|------|------|
| 本月最大需量 | 取当月峰值 | `demand/DemandService` |
| **两种交费方式对比** | 按需量交 vs 按容量交，哪个便宜摆出来 | `DemandConfig.demandCost / capacityCost` |
| 合同需量余量 | 离合同值还有多少 kW | `DemandService.overview` |
| 超需量预警 | 超合同需量会加倍收费，系统提示 | `demand/DemandMonitor` |

**设计与边界**：不做"本月预计需量"（需量取最大值，不是累加，预测会误导）。

**可以说的**：能告诉你这个月按哪种方式交基本电费更划算。
**这是很硬的功能**——大工业电价里，基本电费选错方式一年差几万。

---

## 六、ERP（小型完整）

| 模块 | 能力 | 出处 |
|------|------|------|
| 基础资料 | 商品/SKU、仓库、往来单位 | `ErpBaseController`、`ErpPartner` |
| 采购 | 采购订单 + 明细 + 收货 | `ErpPurchase*` |
| 销售 | 销售订单 + 明细 + 发货 | `ErpSales*` |
| 库存 | 库存余额（按 SKU+仓）、出入库单据 | `ErpStockService`、`ErpStockBill` |
| 财务 | 应收应付、收付款、账龄 | `ErpFinanceService`、`ErpPayment` |
| 产线 | 产线 + 工位 + 设备归属 | `ErpLineService` |
| 报表 | 经营报表 + **导出 Excel** | `ErpReportService`、`ErpReportExcel` |
| **生产入库联动** | 设备产量 → 自动生成生产入库单 → 库存增加 | `ErpProdInboundService` |

**可以说的**：采购、销售、库存、财务、产线、报表都有；产量能自动进库存和单据。
**限制（重要）**：是**轻量 ERP**，没有成本核算、多组织合并报表、生产工单排程。
被追问时要说清楚，别硬撑。

---

## 七、AI

| 能力 | 细节 | 出处 |
|------|------|------|
| **对话式查询系统数据** | AI 可调用 7 个工具查真实数据 | `ai/AiChatService.tools()` |
| 工具清单 | `query_stock`（库存）、`query_lines`（产线产量）、`query_devices`（设备状态）、`query_alarms`（未处理报警）、`query_purchase_orders`（采购）、`query_sales_orders`（销售）、`query_finance`（应收应付账龄） | 同上 |
| **按角色授权** | 班组长只能查产量/设备/报警；经理以上才能查采购/销售/财务 | `Tool.roles` |
| AI 早报 | 每天定时生成，含昨日产量、未处理报警等，可推微信 | `ai/DigestService`、`DigestController` |
| 报警解读 | `/api/ai/interpret` | `AiChatController.interpret` |
| 故障顾问 | 见「三、报警」 | `screen/FaultAdvisor` |
| 模型接入 | OpenAI 兼容协议（DeepSeek / 通义 / 本地 Ollama 都行） | `ai/AiConfig` |

**可以说的**：AI 能直接用自然语言查产量、库存、报警、财务；按角色分权限；每天早报。
**限制（极重要）**：**AI 需要配置 API Key 才能用**，当前这台机器没配。
所以话本里只能说"带 AI 能力"，**不能说"现在就在给我发早报"**。

---

## 八、车间大屏

| 能力 | 细节 | 出处 |
|------|------|------|
| 24 小时完整轴 | 00~23 点，未到时段空槽 | `ScreenService` + `screen.html` |
| 故障原因置顶 | 顶部横幅显示故障与建议 | 同上 |
| 故障看板 / 停机原因双视图 | 小圆点切换 | 同上 |
| 内容超量滚动 | 设备多时自动滚动 | 同上 |
| 纯 HTML | 不装插件，浏览器打开即用 | `static/screen.html` |

---

## 九、系统与运维

| 能力 | 细节 |
|------|------|
| 三级+权限 | ADMIN / MANAGER / LEADER / SALES（写设备仅 ADMIN+MANAGER） |
| 在线升级 | 检查更新 → 下载 → 签名校验（sha256 + 私钥）→ 重启生效 |
| 双源兜底 | Gitee 主源 + GitHub 兜底，下载失败自动换源 |
| 升级包签名 | 私钥签名，防伪造包 |
| 单文件部署 | 一个 jar + 内置数据目录，不用 Docker |
| **内置数据库** | 便携包内置 **MariaDB 11.4**（MySQL 协议兼容），独立 3307 端口，不碰客户已有的 3306；也支持直接指向客户自己的 MySQL 8 |
| 数据本地 | 断网可用 |
| 对外 API | `/open/v1/**` + API Key（可启停、可重置） |

---

# 话本红线（写台词时对照这张）

| ❌ 不能说的话 | ✅ 改成 |
|--------------|--------|
| 现在就在给我发 AI 早报 | 带 AI 能力，配个 Key 就能用 |
| 支持所有品牌报警码 | 台达、三菱伺服已内置，其它靠 AI 猜 |
| 帮客户算 OEE | 有可用率和状态时长（OEE 要三要素） |
| 完整 ERP，什么都能做 | 采购销售库存财务报表都有，没有成本核算 |
| 让老机器自己报产量 | **让所有机器都报产量** |
| 支持所有协议 | Modbus（TCP/RTU/RTU over TCP）、OPC UA |
| 继电器点一下就能动（没设备时） | 能接继电器，控制默认关闭，点位级授权 |
