-- =====================================================================
-- 1.1.0  出入库单据支持"一单多行"（ROADMAP DH-43b）
--
-- 背景：升级前 erp_stock_bill 是"一行即一单"，bill_no 上建的是唯一索引；
-- 现在同一张单可以有多行商品（采购单 3 个 SKU、盘点单 N 项差异、调拨单源仓+目标仓两行），
-- 唯一索引会让第二行直接撞键。这里把它改成普通索引。
--
-- 新装环境：表还不存在（Hibernate 稍后建库时按实体建普通索引），整段自动跳过。
-- 重复执行：两道 information_schema 判断，已经是普通索引时执行 DO 0 空操作。
--
-- 注意：本脚本只动索引，不碰任何数据。
-- =====================================================================

SET @has_table := (
  SELECT COUNT(*) FROM information_schema.tables
  WHERE table_schema = DATABASE() AND table_name = 'erp_stock_bill'
);

SET @has_unique := (
  SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'erp_stock_bill'
    AND index_name = 'idx_erp_bill_no' AND non_unique = 0
);

SET @ddl_drop := IF(@has_table > 0 AND @has_unique > 0,
  'ALTER TABLE erp_stock_bill DROP INDEX idx_erp_bill_no', 'DO 0');

PREPARE stmt_drop FROM @ddl_drop;

EXECUTE stmt_drop;

DEALLOCATE PREPARE stmt_drop;

SET @has_plain := (
  SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'erp_stock_bill'
    AND index_name = 'idx_erp_bill_no'
);

SET @ddl_create := IF(@has_table > 0 AND @has_plain = 0,
  'CREATE INDEX idx_erp_bill_no ON erp_stock_bill (bill_no)', 'DO 0');

PREPARE stmt_create FROM @ddl_create;

EXECUTE stmt_create;

DEALLOCATE PREPARE stmt_create;
