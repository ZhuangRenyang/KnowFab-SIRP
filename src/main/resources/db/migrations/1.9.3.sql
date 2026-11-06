-- =====================================================================
-- 1.9.3  维修工单派工（DH-85 工作流最小版）
--
-- 背景：`erp_maint_order` 有报警关联、AI 建议、根因/方案、四态状态机、
--   解决人（resolvedBy，事后记录），但**没有「派给谁」** —— 只有事后知道谁解决的，
--   事前没有责任人。车间里"这活归谁"全靠口头喊，工单躺着没人动。
--
-- 本脚本加三列：
--   assignee     负责人 username，NULL = 未指派
--   assigned_at  指派时间（epoch ms）
--   assigned_by  谁派的
-- 以及待办查询用的索引 (assignee, status)。
--
-- ⚠️ 写法遵循 DH-84 的教训：**表不存在时要故意报错**，不要 'DO 0' 静默跳过。
--   迁移跑在 Hibernate 建表**之前**，全新安装时 erp_maint_order 还不存在。
--   静默跳过会被记成 ok=1 → 永不重跑 → 列由 ddl-auto 补上但**索引永久缺失**，
--   而且没人知道。故意报错（SELECT 一张不存在的表）→ 记 ok=0 → 下次启动重试 → 补齐。
-- =====================================================================

SET @has_tbl := (
  SELECT COUNT(*) FROM information_schema.TABLES
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'erp_maint_order'
);

-- ---------- 列 1：assignee ----------
SET @has_c1 := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'erp_maint_order'
    AND COLUMN_NAME = 'assignee'
);
SET @ddl := IF(@has_tbl = 0,
  'SELECT 1 FROM erp_maint_order LIMIT 0',
  IF(@has_c1 = 0,
     'ALTER TABLE `erp_maint_order` ADD COLUMN `assignee` VARCHAR(64) NULL COMMENT ''负责人 username，NULL=未指派（DH-85）''',
     'SELECT ''assignee 已存在，跳过'' AS msg'));
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- ---------- 列 2：assigned_at ----------
SET @has_c2 := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'erp_maint_order'
    AND COLUMN_NAME = 'assigned_at'
);
SET @ddl := IF(@has_tbl = 0,
  'SELECT 1 FROM erp_maint_order LIMIT 0',
  IF(@has_c2 = 0,
     'ALTER TABLE `erp_maint_order` ADD COLUMN `assigned_at` BIGINT NULL COMMENT ''指派时间 epoch ms''',
     'SELECT ''assigned_at 已存在，跳过'' AS msg'));
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- ---------- 列 3：assigned_by ----------
SET @has_c3 := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'erp_maint_order'
    AND COLUMN_NAME = 'assigned_by'
);
SET @ddl := IF(@has_tbl = 0,
  'SELECT 1 FROM erp_maint_order LIMIT 0',
  IF(@has_c3 = 0,
     'ALTER TABLE `erp_maint_order` ADD COLUMN `assigned_by` VARCHAR(64) NULL COMMENT ''谁派的；AI 派单时也是发起人''',
     'SELECT ''assigned_by 已存在，跳过'' AS msg'));
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- ---------- 索引：待办查询每次都用 (assignee, status) ----------
SET @has_idx := (
  SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'erp_maint_order'
    AND INDEX_NAME = 'idx_mo_assignee'
);
SET @ddl := IF(@has_tbl = 0,
  'SELECT 1 FROM erp_maint_order LIMIT 0',
  IF(@has_idx = 0,
     'CREATE INDEX idx_mo_assignee ON erp_maint_order (assignee, status)',
     'SELECT ''idx_mo_assignee 已存在，跳过'' AS msg'));
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

DO 0;
