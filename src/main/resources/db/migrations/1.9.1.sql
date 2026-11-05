-- =====================================================================
-- 1.9.1  设备计数器处理水位：重启后接着上次读数算增量（DH-81）
--
-- 背景（这是**每次重启都在发生**的丢数，不是理论风险）：
--   `ProdRollup.scan()` 每秒读一次 PLC 的产量计数器，把「本次读数 - 上次读数」
--   当成本拍的产量，同时写进两张表：`prod_hourly`（产量报表）和
--   ERP 的 PROD_IN 入库单据（DH-44 联动，`ErpProdInboundService.onProduced`）。
--
--   「上次读数」原先只存在内存字段 `DevState.count` 里，初值 -1；
--   `scan()` 的判据是 `st.count >= 0 && ts > st.countTs`。
--   于是重启后（升级 / 崩溃 / OOM / 断电）：
--
--       重启前最后处理到 100  →  重启后第一次采样读到 110
--       st.count = -1  →  判据不成立  →  **不推增量**
--       ⇒ 这 10 件永久丢失，而且**两边同时少记**：
--         产量报表少 10 件 + ERP 入库单据少 10 件（pendingQty 也一起归零）
--
--   `scan()` 1 秒一拍，重启窗口正好落在一拍之间，所以每次重启必丢
--   「上次读数 → 重启瞬间读数」这一段。升级一次丢一批，现场只会觉得
--   "报表比实际少，但查不出少在哪"——因为根本没留下任何痕迹。
--
-- 修法：把「上次已处理到的读数 + 该读数的时间戳」落到这张表，
--       启动时灌回内存（`ProdRollup.restore()`），重启后第一次采样就能算出正确增量。
--       每台设备只有一行，随 `scan()` 同步 upsert（滞后写会把已处理过的增量重算一遍 → 重复计数）。
--
--   ⚠️ 为什么**不能**用「prod_hourly 产量 vs PROD_IN 入库单据对账补差」代替：
--      演示库实测  prod_hourly 合计 75738 件，PROD_IN 合计 31243 件，差 44495。
--      原因是历史口径不同（产量从 10-04 就开始记，入库联动 DH-44 之后 10-27 才有），
--      10-28 那天更是产量 2603 / 入库 28213（历史补录 + 测试数据）。
--      按差额补单会给客户补出一张几万件的错单 —— 那条路口径上就是错的。
--
-- 表设计取舍：
--   · 主键就是 device_code：一台设备一行。**不存历史流水**——
--     这张表只回答「上次处理到哪儿」，多存历史只会让"哪一行才是当前水位"变含糊。
--   · `count_value` 用 DOUBLE 不用 INT：计数器点位可能是浮点（0.1 件精度、
--     某些 PLC 用 REAL 传计数），截断成整数会让误差越滚越大。
--   · `count_ts` 存毫秒：`scan()` 用 `ts > st.countTs` 防同一笔数据重复计数，
--     精度到毫秒才能和 `PointValue.ts` 一一对应。
--   · `updated_at` 只排障用（"这台设备的水位多久没动了"），不参与业务判断。
--   · 无外键、不级联：设备删了行还在也无害（`purgeByCode` 会一并清掉），
--     加外键反而会让设备表上的一次删除把采集主链路卡住。
--
-- 已知边界（不是本方案的缺陷）：设备计数器清零/换表的同时重启，
--   持久化的是旧读数、新读数是 0，`delta < 0` 会按既有规则记 0 + resets++，
--   那一段产量补不回来 —— 这是计数器的物理限制（读数本身丢了），不是持久化没做好。
--
-- 幂等：表已存在则跳过并打印提示，不报错、不动数据。
-- 新装环境：由本脚本建表（Hibernate ddl-auto=update 随后看到结构已对，不会重复建）。
-- =====================================================================

SET @has_tbl := (
  SELECT COUNT(*) FROM information_schema.TABLES
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'dev_counter_state'
);

SET @sql := IF(@has_tbl = 0,
  'CREATE TABLE `dev_counter_state` (
     `device_code` VARCHAR(64) NOT NULL COMMENT ''设备编号，一台设备一行'',
     `count_value` DOUBLE      NOT NULL COMMENT ''上次已处理到的累计产量计数器读数（重启后从这里接着算增量）'',
     `count_ts`    BIGINT      NOT NULL COMMENT ''该读数的时间戳 epoch ms（防同一笔数据重复计数）'',
     `updated_at`  BIGINT      NOT NULL COMMENT ''本行最后更新时间 epoch ms，仅排障用'',
     PRIMARY KEY (`device_code`)
   ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
     COMMENT=''设备产量计数器处理水位：让 ProdRollup 重启后接着上次读数算增量，不丢产量''',
  'SELECT ''dev_counter_state 已存在，跳过（幂等）'' AS msg');

PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
