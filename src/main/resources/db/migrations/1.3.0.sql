-- =====================================================================
-- 1.3.0  产线管理：设备挂产线 + 工位序号（ROADMAP DH-40/41）
--
-- 背景：客户口径是"最后一台机器计数 = 整条产线成品数"，需要先把设备组织成产线。
-- 产线实体本身由 Hibernate 按 ErpProdLine 建表（erp_prod_line），
-- 这里只给已有的 device 表补两列。
--
-- 新装环境：device 表还不存在时整段跳过（Hibernate 稍后按实体建列）。
-- 重复执行：information_schema 判断列已存在则 DO 0。
-- 注意：只加列不动数据；历史设备 prod_line_id 为 NULL、line_seq 为 0，行为与升级前一致。
-- =====================================================================

SET @has_table := (
  SELECT COUNT(*) FROM information_schema.tables
  WHERE table_schema = DATABASE() AND table_name = 'device'
);

SET @has_line := (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'device' AND column_name = 'prod_line_id'
);

SET @ddl_line := IF(@has_table > 0 AND @has_line = 0,
  'ALTER TABLE device ADD COLUMN prod_line_id BIGINT NULL COMMENT ''所属产线''',
  'DO 0');

PREPARE stmt_line FROM @ddl_line;

EXECUTE stmt_line;

DEALLOCATE PREPARE stmt_line;

SET @has_seq := (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'device' AND column_name = 'line_seq'
);

SET @ddl_seq := IF(@has_table > 0 AND @has_seq = 0,
  'ALTER TABLE device ADD COLUMN line_seq INT NOT NULL DEFAULT 0 COMMENT ''产线内工位序号，1 起''',
  'DO 0');

PREPARE stmt_seq FROM @ddl_seq;

EXECUTE stmt_seq;

DEALLOCATE PREPARE stmt_seq;
