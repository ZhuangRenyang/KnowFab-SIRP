-- =====================================================================
-- 1.9.4  demand_window 加 estimated 列（DH-86 断线电量摊平的「推算」标记）
--
-- 背景：
--   DH-86 把「断线补偿电量」按时间摊回它真正发生的那些 15 分钟窗口
--   （之前是整段丢弃 → 断网若恰逢用电高峰，那个峰值在需量里完全不存在
--    → 月最大需量低估 → 基本电费少算 + 超容告警不响，客户被电网加收而没人提醒）。
--
--   摊平用的是「这段时间的平均功率」，不是实测峰值，所以必须能被认出来：
--   客户月底拿报表跟供电局对账时，要分得清「电表实测的」和「我们推算的」。
--   只在内存里标是不够的 —— 重启就没了，那就成了无从追溯的假数据。
--
-- 写法（照 1.9.2 的教训）：
--   · 表不存在时**故意报错**（SELECT 一张不存在的表）→ 记 ok=0 → 下次启动重试。
--     迁移跑在 Hibernate 建表之前，全新安装时这张表还不存在，报错是预期行为。
--   · 绝不用 'DO 0' 兜底：那会记成 ok=1，列永久缺失且无人知道（DH-84 的坑）。
--   · 列已存在（老客户升级 / 重复启动）→ 走「已存在，跳过」，幂等。
-- =====================================================================

SET @has_tbl := (
  SELECT COUNT(*) FROM information_schema.TABLES
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'demand_window'
);
SET @has_col := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'demand_window'
    AND COLUMN_NAME = 'estimated'
);

-- 顺序有意义：先看列（已在就不管表），再看表（存在才加），最后才是「表还没有」
SET @ddl := IF(@has_col > 0,
  'SELECT ''demand_window.estimated 已存在，跳过'' AS msg',
  IF(@has_tbl > 0,
     'ALTER TABLE demand_window ADD COLUMN estimated TINYINT(1) NOT NULL DEFAULT 0 COMMENT ''1=该窗口含断线摊平电量（推算值，非实测）''',
     'SELECT 1 FROM demand_window LIMIT 0'));

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

DO 0;
