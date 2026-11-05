-- =====================================================================
-- 1.9.0  生产工单排程：给工单加计划时间，并检测同产线冲突
--
-- 背景：工单已经有五态状态机、BOM 展开、领料、产出、成本核算，
-- 但**没有"什么时候做"**——多张工单可以同时挂在同一条产线上（line_code），
-- 谁也不让谁，结果就是两批货在一条线上撞车，而且从界面上**看不出**什么时候做哪张。
--
-- 本次补的是「排程登记 + 冲突可见 + 甘特视图」，**不是自动排产**：
-- 自动排产（CP-SAT / 启发式）是另一个量级的工程，本次明确不做（见 Out-of-Scope）。
--
-- 关键字段的语义（最易做错的几处）：
--
--   ① **NULL = 未排程**（不是 0、不是 1970-01-01）
--      甘特图里未排程的工单单独列一栏，不混进时间轴。
--      存 0 会让 1970 年的工单出现在图的最左边，看着像"排了五十年"。
--
--   ② **半开区间 [plan_start, plan_end)**
--      前一张的 plan_end == 后一张的 plan_start **不算冲突**——
--      交接班/换型时刻是正常衔接。两张都算重叠会误报一��。
--
--   ③ **只有排了程的工单才参与冲突判定**
--      plan_start 为空的工单不占产线，也不与任何工单冲突。
--
--   ④ **只有非终态工单参与冲突判定**
--      已取消（CANCELLED）与已完工（DONE）的工单不占产线。
--      不排除的话，历史上做过的一批单会把现在的排程全判成冲突。
--
--   ⑤ **冲突判定粒度是产线（line_code），不是设备**
--      产线上的设备本来可以并行工作（不同工位同时干），
--      按设备判冲突会把正常的并行生产误报成冲突。
--      一张工单没指定产线（line_code 为空）→ 不参与冲突判定。
--
-- 幂等：information_schema 判断列已存在则 DO 0。
-- =====================================================================

-- ---------- erp_work_order：计划开始 / 计划结束 ----------

SET @has_wo := (
  SELECT COUNT(*) FROM information_schema.tables
  WHERE table_schema = DATABASE() AND table_name = 'erp_work_order'
);

SET @has_plan_start := (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'erp_work_order' AND column_name = 'plan_start'
);

SET @ddl_plan_start := IF(@has_wo > 0 AND @has_plan_start = 0,
  'ALTER TABLE erp_work_order ADD COLUMN plan_start BIGINT NULL COMMENT ''计划开始时间（毫秒时间戳）；NULL=未排程''',
  'DO 0');

PREPARE stmt_plan_start FROM @ddl_plan_start;
EXECUTE stmt_plan_start;
DEALLOCATE PREPARE stmt_plan_start;

SET @has_plan_end := (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'erp_work_order' AND column_name = 'plan_end'
);

SET @ddl_plan_end := IF(@has_wo > 0 AND @has_plan_end = 0,
  'ALTER TABLE erp_work_order ADD COLUMN plan_end BIGINT NULL COMMENT ''计划结束时间（毫秒时间戳）；与 plan_start 一起构成半开区间 [start, end)''',
  'DO 0');

PREPARE stmt_plan_end FROM @ddl_plan_end;
EXECUTE stmt_plan_end;
DEALLOCATE PREPARE stmt_plan_end;

-- ---------- 索引：按产线 + 计划时间查冲突要扫这张表 ----------

SET @has_idx := (
  SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'erp_work_order'
    AND index_name = 'idx_wo_line_plan'
);

SET @ddl_idx := IF(@has_wo > 0 AND @has_idx = 0,
  'CREATE INDEX idx_wo_line_plan ON erp_work_order (line_code, plan_start, plan_end)',
  'DO 0');

PREPARE stmt_idx FROM @ddl_idx;
EXECUTE stmt_idx;
DEALLOCATE PREPARE stmt_idx;
