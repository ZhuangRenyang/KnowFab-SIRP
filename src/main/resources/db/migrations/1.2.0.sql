-- =====================================================================
-- 1.2.0  单据业务日期 biz_date（ROADMAP DH-57）
--
-- 背景：报表按 created_at（录入时间）统计，月底补录 25 号的领料单会被算到 30 号，
-- 当月出库凭空变多、上月少一笔。加一列 biz_date 记业务日期，报表取
-- coalesce(biz_date, created_at)，created_at 继续只做审计。
--
-- 新装环境：表还不存在（Hibernate 稍后按实体建列），整段跳过。
-- 重复执行：information_schema 判断列已存在则执行 DO 0。
-- 注意：加列不动任何数据，历史单据 biz_date 为 NULL 时行为与升级前完全一致。
-- =====================================================================

SET @has_table := (
  SELECT COUNT(*) FROM information_schema.tables
  WHERE table_schema = DATABASE() AND table_name = 'erp_stock_bill'
);

SET @has_col := (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'erp_stock_bill'
    AND column_name = 'biz_date'
);

SET @ddl_add := IF(@has_table > 0 AND @has_col = 0,
  'ALTER TABLE erp_stock_bill ADD COLUMN biz_date BIGINT NULL COMMENT ''单据业务日期；NULL=按录入时间''',
  'DO 0');

PREPARE stmt_add FROM @ddl_add;

EXECUTE stmt_add;

DEALLOCATE PREPARE stmt_add;
