-- =====================================================================
-- 1.6.0  存量库上 bill_no 的唯一约束没被真正去掉（ROADMAP DH-65）
--
-- 背景：ERP 单据改成「一单多行」后，bill_no 不能再是唯一键。
-- 1.1.0.sql 处理过这件事，但它只认固定名 idx_erp_bill_no。
-- 而老版本实体除 @Index(unique=true) 外还有 @Column(name="bill_no", unique=true)
-- ——Hibernate 会额外生成一个名字就叫「bill_no」的列级唯一索引
-- （MySQL 用列名给自动生成的索引命名，实测确认）。
-- 于是从老库升级上来时：idx_erp_bill_no 被删了，bill_no 那个还在
-- → bill_no 仍然唯一 → 同一张单写第二行就报 Duplicate entry，
-- 而脚本还记「执行成功」，永远不会重试、也不会修。
--
-- 这里按「bill_no 上的唯一索引」把两种可能的来源都清掉，再补一个普通索引。
-- 幂等：已经在新结构上的库，本脚本什么都不做（走 DO 0）。
--
-- 新装环境：表还不存在时整段跳过（Hibernate 稍后按实体建表）。
-- =====================================================================

SET @has_table := (
  SELECT COUNT(*) FROM information_schema.tables
  WHERE table_schema = DATABASE() AND table_name = 'erp_stock_bill'
);

-- ① 列级 unique 生成的索引：名字 = 列名 bill_no
SET @has_col_unique := (
  SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'erp_stock_bill'
    AND index_name = 'bill_no' AND non_unique = 0
);

SET @ddl_drop_col := IF(@has_table > 0 AND @has_col_unique > 0,
  'ALTER TABLE erp_stock_bill DROP INDEX bill_no', 'DO 0');

PREPARE stmt_drop_col FROM @ddl_drop_col;

EXECUTE stmt_drop_col;

DEALLOCATE PREPARE stmt_drop_col;

-- ② 注解生成的唯一索引：名字 = idx_erp_bill_no（老库上可能还在）
SET @has_idx_unique := (
  SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'erp_stock_bill'
    AND index_name = 'idx_erp_bill_no' AND non_unique = 0
);

SET @ddl_drop_idx := IF(@has_table > 0 AND @has_idx_unique > 0,
  'ALTER TABLE erp_stock_bill DROP INDEX idx_erp_bill_no', 'DO 0');

PREPARE stmt_drop_idx FROM @ddl_drop_idx;

EXECUTE stmt_drop_idx;

DEALLOCATE PREPARE stmt_drop_idx;

-- ③ 保证留一个普通索引：单据列表按 bill_no 查
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
