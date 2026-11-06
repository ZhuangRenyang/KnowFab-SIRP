-- =====================================================================
-- 1.9.2  补建排程索引（1.9.0 在全新安装上被静默跳过了）
--
-- 背景（2026-11-06 交付前演练，全新空库实测）：
--   1.9.0 给 erp_work_order 加 plan_start/plan_end 两列 + idx_wo_line_plan。
--   它的守卫是「表存在 且 索引不存在才建」，否则走 'DO 0'（静默跳过）。
--   但迁移跑在 Hibernate 建表**之前** —— 全新安装时 erp_work_order 表还不存在，
--   于是整段走 'DO 0'，**却被记成 ok=1**。
--
--   实测后果：
--     · 两列由 JPA ddl-auto:update 补上了 → 功能正常
--     · 索引永远不会建 → 排程冲突查询
--       `where line_code=? and plan_start<? and plan_end>?` 全表扫
--     · 因为已记 ok=1，**后续每次启动都不会重跑** → 永久缺失，且无人知道
--
--   对照组（同一轮实测）：1.8.1 在同样场景下是**报错**的
--   （Table 'device' doesn't exist，ok=0），第二次启动重试就自愈了
--   （idx_device_is_meter 实测补上了）。
--   ⇒ **报错比静默跳过好**：报错会重试、会留痕；静默跳过记成功，问题永久存在。
--
-- 本脚本的写法：表不存在时**故意报错**（SELECT 一张不存在的表），
--   让它记 ok=0 → 下次启动重试 → 那时 ORM 已把表建好 → 索引补上。
--   报错信息就是 MySQL 的 "Table ... doesn't exist"，比自定义提示更清楚。
--
-- 幂等性：
--   · 老客户（索引已在）→ 走「已存在，跳过」，ok=1，不动任何东西
--   · 新客户第一次启动 → 表还没建 → 报错 ok=0（预期行为，非故障）
--   · 新客户第二次启动 → 表已在、索引不在 → CREATE INDEX → ok=1
-- =====================================================================

SET @has_wo := (
  SELECT COUNT(*) FROM information_schema.TABLES
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'erp_work_order'
);
SET @has_idx := (
  SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'erp_work_order'
    AND INDEX_NAME = 'idx_wo_line_plan'
);

-- 顺序有意义：先看索引（已在就不管表），再看表（存在才建），最后才是「表还没有」
SET @ddl := IF(@has_idx > 0,
  'SELECT ''idx_wo_line_plan 已存在，跳过'' AS msg',
  IF(@has_wo > 0,
     'CREATE INDEX idx_wo_line_plan ON erp_work_order (line_code, plan_start, plan_end)',
     'SELECT 1 FROM erp_work_order LIMIT 0'));

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

DO 0;
