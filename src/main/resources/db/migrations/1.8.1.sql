-- =====================================================================
-- 1.8.1  进线（总表）标记：修正需量的计量口径
--
-- 背景（2026-11-04 实测发现的口径缺陷）：
--   `EnergyRollup` 把**所有设备**的能耗都喂给 `DemandTracker`，
--   而 `DemandService.overview` 的全厂口径是把同一窗口各设备的功率**相加**。
--   但机床的电已经包含在总表里了 —— 两者相加等于**重复计算**。
--
--   实测（演示库，2026-11-04）：
--       METER-01（一车间总电表）  峰值 235.8 kW
--       CNC-01 + INJ-01 + PRESS-01  峰值之和 671.0 kW
--       全厂口径 = 906.8 kW  →  **真实值的 3.85 倍**
--
--   后果：①「本月最大需量」虚高近 4 倍 ②基本电费虚高（按峰值×单价算）
--         ③合同需量告警误报「已超合同」（实际没超）
--
-- 修法：给设备加一个「是否总表（进线计量点）」标记。
--   · 全厂需量 = Σ(**标记为总表的**设备)，非总表设备不再计入
--   · 每块总表 = 一条进线，各存一份需量配置（合同需量/单价/容量/容量单价）
--   · 没标任何总表时**退回旧口径**（不动客户现有数字），
--     但接口会返回 meterMode=LEGACY，界面明确提示口径问题
--
-- ⚠️ 本脚本**不自动标记任何设备**：哪些是总表属于客户的现场事实，
--    猜错（把机床标成总表）会让全厂需量直接错成单机功率。
--    演示数据由 DemoSeeder 显式把 METER-01 标为总表。
-- =====================================================================

-- 幂等：已存在就跳过
SET @has_col := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'device' AND COLUMN_NAME = 'is_meter'
);
SET @sql := IF(@has_col = 0,
  'ALTER TABLE `device` ADD COLUMN `is_meter` TINYINT(1) NOT NULL DEFAULT 0
   COMMENT ''是否总表/进线计量点：参与需量与基本电费的全厂口径（机床等分表不要标）''',
  'SELECT ''is_meter 已存在，跳过'' AS msg');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 兜底索引：按标记筛总表很快（需量全厂口径每次都要用）
SET @has_idx := (
  SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'device' AND INDEX_NAME = 'idx_device_is_meter'
);
SET @sql2 := IF(@has_idx = 0,
  'CREATE INDEX `idx_device_is_meter` ON `device` (`is_meter`)',
  'SELECT ''idx_device_is_meter 已存在，跳过'' AS msg');
PREPARE stmt2 FROM @sql2;
EXECUTE stmt2;
DEALLOCATE PREPARE stmt2;
