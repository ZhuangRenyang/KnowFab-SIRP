-- =====================================================================
-- 1.5.0  OEE 完整版：理想节拍 + 不良数（ROADMAP DH-15）
--
-- 背景：真 OEE = 可用率 × 性能 × 良率，现在系统只有「稼动率」。
-- 缺的输入有两个：
--   ① 标准产能 —— 设备配「理想节拍（秒/件）」，性能率 = 实际产量 ÷（运行时间 ÷ 节拍）
--   ② 不良数 —— 机床有不良品计数器就点表配 role=reject 自动采，没有就用补录录
--
-- 这次只加列，不动任何已有数据：
--   device.ideal_cycle_sec   理想节拍，NULL = 未配置（没配就算不出性能率，报表会明说缺什么）
--   prod_hourly.defect       该小时自动采集的不良数，默认 0
--   manual_entry.defect      人工补录的不良数，默认 0
--
-- 注意：prod_hourly.defect = 0 **不代表「没有不良」**，只代表「没采到」。
-- 报表判断「有没有不良数数据」看的是设备配没配 reject 点位 / 有没有补录记录。
--
-- 新装环境：表还不存在时整段跳过（Hibernate 稍后按实体建列）。
-- 重复执行：information_schema 判断列已存在则 DO 0。
-- =====================================================================

SET @has_device := (
  SELECT COUNT(*) FROM information_schema.tables
  WHERE table_schema = DATABASE() AND table_name = 'device'
);

SET @has_cycle := (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'device' AND column_name = 'ideal_cycle_sec'
);

SET @ddl_cycle := IF(@has_device > 0 AND @has_cycle = 0,
  'ALTER TABLE device ADD COLUMN ideal_cycle_sec DOUBLE NULL COMMENT ''理想节拍（秒/件），NULL=未配置''',
  'DO 0');

PREPARE stmt_cycle FROM @ddl_cycle;

EXECUTE stmt_cycle;

DEALLOCATE PREPARE stmt_cycle;

SET @has_ph := (
  SELECT COUNT(*) FROM information_schema.tables
  WHERE table_schema = DATABASE() AND table_name = 'prod_hourly'
);

SET @has_ph_defect := (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'prod_hourly' AND column_name = 'defect'
);

SET @ddl_ph_defect := IF(@has_ph > 0 AND @has_ph_defect = 0,
  'ALTER TABLE prod_hourly ADD COLUMN defect DOUBLE NOT NULL DEFAULT 0 COMMENT ''该小时的不良品数（自动采集）''',
  'DO 0');

PREPARE stmt_ph_defect FROM @ddl_ph_defect;

EXECUTE stmt_ph_defect;

DEALLOCATE PREPARE stmt_ph_defect;

SET @has_me := (
  SELECT COUNT(*) FROM information_schema.tables
  WHERE table_schema = DATABASE() AND table_name = 'manual_entry'
);

SET @has_me_defect := (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'manual_entry' AND column_name = 'defect'
);

SET @ddl_me_defect := IF(@has_me > 0 AND @has_me_defect = 0,
  'ALTER TABLE manual_entry ADD COLUMN defect DOUBLE NULL COMMENT ''补录的不良品数，NULL=没录（与「录了0件」是两回事）''',
  'DO 0');

PREPARE stmt_me_defect FROM @ddl_me_defect;

EXECUTE stmt_me_defect;

DEALLOCATE PREPARE stmt_me_defect;
