-- =====================================================================
-- 1.7.0  质量与不良品处置（QC-SPEC）——三个增量列的前置准备
--
-- 背景：本次要给两张已有表加 not null 的列。ddl-auto=update 自己加不了：
-- 它会对非空表执行「alter table ... add column x varchar(16) not null」，
-- 表里有行时这句在 MySQL 严格模式下直接失败（服务起不来），
-- 非严格模式下则把存量行填成空串 —— 于是 isRework() / 入库口径判断拿到空值，
-- 行为和预期相反，而且一句报错都没有。
-- 所以这两个列必须由本脚本先带着默认值加好、把存量行填成明确的值，
-- Hibernate 随后只会看到「列已经对了」。
--
-- 本脚本做六件事：
--   ① erp_work_order.order_type       VARCHAR(16) NOT NULL DEFAULT 'PROD'
--   ② erp_work_order 存量行回填 'PROD'（NULL 与空串都填，否则 isRework() 拿到空值）
--   ③ device.counter_mode             VARCHAR(8)  NOT NULL DEFAULT 'TOTAL'
--   ④ erp_warehouse.kind              VARCHAR(16) NOT NULL DEFAULT 'STANDARD' + 按仓名推断禁发仓
--   ⑤ idx_wo_type / idx_erp_wh_kind 索引兜底创建（已存在则跳过）
--
-- 注意 counter_mode 与 kind 也必须在脚本里加：它们同样是 not null 新列，
-- 只加 order_type 的话，存量库升级时 Hibernate 会卡在 device / erp_warehouse 表上起不来。
--
-- 新装环境：表还不存在时整段跳过（Hibernate 稍后按实体建列，默认值由实体给出）。
-- 幂等：information_schema 判断列/索引已存在则 DO 0；回填与推断语句第二遍影响 0 行。
-- =====================================================================

-- ---------- ① erp_work_order.order_type ----------

SET @has_wo := (
  SELECT COUNT(*) FROM information_schema.tables
  WHERE table_schema = DATABASE() AND table_name = 'erp_work_order'
);

SET @has_wo_type := (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'erp_work_order' AND column_name = 'order_type'
);

SET @ddl_wo_type := IF(@has_wo > 0 AND @has_wo_type = 0,
  'ALTER TABLE erp_work_order ADD COLUMN order_type VARCHAR(16) NOT NULL DEFAULT ''PROD'' COMMENT ''工单类型：PROD 正常生产 / REWORK 返工''',
  'DO 0');

PREPARE stmt_wo_type FROM @ddl_wo_type;

EXECUTE stmt_wo_type;

DEALLOCATE PREPARE stmt_wo_type;

-- ---------- ② 存量行回填：NULL 与空串都填 PROD ----------
-- 不填的后果：isRework() 拿到空值仍然安全（返回 false），
-- 但报表里按工单类型分组会出现一个空类型的分组，看起来像脏数据，
-- 而现场没办法把它改回去（列表里没有"空类型"这个筛选项）。

SET @fix_wo_type := IF(@has_wo > 0,
  'UPDATE erp_work_order SET order_type = ''PROD'' WHERE order_type IS NULL OR order_type = ''''',
  'DO 0');

PREPARE stmt_fix_wo_type FROM @fix_wo_type;

EXECUTE stmt_fix_wo_type;

DEALLOCATE PREPARE stmt_fix_wo_type;

-- ---------- ③ device.counter_mode ----------

SET @has_device := (
  SELECT COUNT(*) FROM information_schema.tables
  WHERE table_schema = DATABASE() AND table_name = 'device'
);

SET @has_counter_mode := (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'device' AND column_name = 'counter_mode'
);

SET @ddl_counter_mode := IF(@has_device > 0 AND @has_counter_mode = 0,
  'ALTER TABLE device ADD COLUMN counter_mode VARCHAR(8) NOT NULL DEFAULT ''TOTAL'' COMMENT ''主计数器口径：TOTAL 总产出含不良 / GOOD 仅良品''',
  'DO 0');

PREPARE stmt_counter_mode FROM @ddl_counter_mode;

EXECUTE stmt_counter_mode;

DEALLOCATE PREPARE stmt_counter_mode;

SET @fix_counter_mode := IF(@has_device > 0,
  'UPDATE device SET counter_mode = ''TOTAL'' WHERE counter_mode IS NULL OR counter_mode = ''''',
  'DO 0');

PREPARE stmt_fix_counter_mode FROM @fix_counter_mode;

EXECUTE stmt_fix_counter_mode;

DEALLOCATE PREPARE stmt_fix_counter_mode;

-- ---------- ④ erp_warehouse.kind ----------
-- 禁发仓标记。为什么必须是一列而不是按仓名判断：客户会改仓库名、改编码，
-- 靠 name like '%特采%' 认仓，改个名字硬拦就静默失效，而且失效时不报错。

SET @has_wh := (
  SELECT COUNT(*) FROM information_schema.tables
  WHERE table_schema = DATABASE() AND table_name = 'erp_warehouse'
);

SET @has_wh_kind := (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'erp_warehouse' AND column_name = 'kind'
);

SET @ddl_wh_kind := IF(@has_wh > 0 AND @has_wh_kind = 0,
  'ALTER TABLE erp_warehouse ADD COLUMN kind VARCHAR(16) NOT NULL DEFAULT ''STANDARD'' COMMENT ''仓类型：STANDARD 正常仓 / BLOCKED 禁发仓（让步品与待判定退货）''',
  'DO 0');

PREPARE stmt_wh_kind FROM @ddl_wh_kind;

EXECUTE stmt_wh_kind;

DEALLOCATE PREPARE stmt_wh_kind;

SET @fix_wh_kind := IF(@has_wh > 0,
  'UPDATE erp_warehouse SET kind = ''STANDARD'' WHERE kind IS NULL OR kind = ''''',
  'DO 0');

PREPARE stmt_fix_wh_kind FROM @fix_wh_kind;

EXECUTE stmt_fix_wh_kind;

DEALLOCATE PREPARE stmt_fix_wh_kind;

-- 按仓名推断禁发仓，省掉客户逐个手改。
-- 方向刻意偏保守：误设成 BLOCKED → 那个仓发不出货（当场就有人喊，能发现）；
-- 误设成 STANDARD → 放行了不该放的货（事后对账才发现）。
-- 所以名字沾上"待处理/退货/待检/特采/让步"的都先设成禁发，再由启动日志提示人确认。

SET @infer_wh_kind := IF(@has_wh > 0,
  'UPDATE erp_warehouse SET kind = ''BLOCKED''
   WHERE kind = ''STANDARD'' AND (
     name LIKE ''%待处理%'' OR name LIKE ''%退货%'' OR name LIKE ''%待检%''
     OR name LIKE ''%特采%'' OR name LIKE ''%让步%'')',
  'DO 0');

PREPARE stmt_infer_wh_kind FROM @infer_wh_kind;

EXECUTE stmt_infer_wh_kind;

DEALLOCATE PREPARE stmt_infer_wh_kind;

-- ---------- ⑤ erp_work_order.src_defect_no ----------
-- 返工单指向的不良单号。与 src_order_no（来源销售订单）分开两列：
-- 填在同一列里，报表会把返工单的来源算成销售订单，数字对不上还不报错。

SET @has_wo_defect_no := (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'erp_work_order' AND column_name = 'src_defect_no'
);

SET @ddl_wo_defect_no := IF(@has_wo > 0 AND @has_wo_defect_no = 0,
  'ALTER TABLE erp_work_order ADD COLUMN src_defect_no VARCHAR(40) NULL COMMENT ''返工单指向的不良单号''',
  'DO 0');

PREPARE stmt_wo_defect_no FROM @ddl_wo_defect_no;

EXECUTE stmt_wo_defect_no;

DEALLOCATE PREPARE stmt_wo_defect_no;

-- ---------- ⑥ erp_qc_aql_plan 的核对留痕 ----------
-- verified 之外还要记"谁核对的、什么时候核的"：审计的价值是能追到人。
-- 只记一个 verified=true，半年后说不清这一格是标准本来就对、还是有人拍过板——
-- 而这一格直接决定一批货判合格还是拒收。两列都可空，不影响存量行。

SET @has_aql_plan := (
  SELECT COUNT(*) FROM information_schema.tables
  WHERE table_schema = DATABASE() AND table_name = 'erp_qc_aql_plan'
);

SET @has_vby := (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'erp_qc_aql_plan' AND column_name = 'verified_by'
);

SET @ddl_vby := IF(@has_aql_plan > 0 AND @has_vby = 0,
  'ALTER TABLE erp_qc_aql_plan ADD COLUMN verified_by VARCHAR(64) NULL COMMENT ''确认核对的人''',
  'DO 0');

PREPARE stmt_vby FROM @ddl_vby;

EXECUTE stmt_vby;

DEALLOCATE PREPARE stmt_vby;

SET @has_vat := (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'erp_qc_aql_plan' AND column_name = 'verified_at'
);

SET @ddl_vat := IF(@has_aql_plan > 0 AND @has_vat = 0,
  'ALTER TABLE erp_qc_aql_plan ADD COLUMN verified_at BIGINT NULL COMMENT ''确认核对的时间（epoch ms）''',
  'DO 0');

PREPARE stmt_vat FROM @ddl_vat;

EXECUTE stmt_vat;

DEALLOCATE PREPARE stmt_vat;

-- ---------- ⑦ 索引兜底 ----------
-- 工单列表与质量成本报表会按类型过滤。ddl-auto 有时不会补索引（表已存在的情况下
-- 部分方言只加列不建索引），这里明确建一次。

SET @has_idx_wo_type := (
  SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'erp_work_order' AND index_name = 'idx_wo_type'
);

SET @ddl_idx_wo_type := IF(@has_wo > 0 AND @has_idx_wo_type = 0,
  'CREATE INDEX idx_wo_type ON erp_work_order (order_type)',
  'DO 0');

PREPARE stmt_idx_wo_type FROM @ddl_idx_wo_type;

EXECUTE stmt_idx_wo_type;

DEALLOCATE PREPARE stmt_idx_wo_type;

-- 销售可用量要按 kind 过滤（排除禁发仓），这个索引是那条查询的入口

SET @has_idx_wh_kind := (
  SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'erp_warehouse' AND index_name = 'idx_erp_wh_kind'
);

SET @ddl_idx_wh_kind := IF(@has_wh > 0 AND @has_idx_wh_kind = 0,
  'CREATE INDEX idx_erp_wh_kind ON erp_warehouse (kind)',
  'DO 0');

PREPARE stmt_idx_wh_kind FROM @ddl_idx_wh_kind;

EXECUTE stmt_idx_wh_kind;

DEALLOCATE PREPARE stmt_idx_wh_kind;

-- ---------- ⑨ erp_qc_defect.recon_state ----------
-- 对账定责的「通道标记」。为什么不是加一个布尔「是否计入」：
-- 两个布尔（已定责 + 是否计入）能组合出「已定责但又计入」的非法态，
-- 而三态 ('' / KEEP / DROP) 不会出现这种组合。
--
-- 三态的含义：
--   ''    未定责（默认）——统计时按「设备通道优先」规则推断
--   KEEP  定责后保留——计入统计
--   DROP  定责后不计入——统计时排除
--
-- 为什么定责是「标记」不是「改数」：设备通道的行是 prod_hourly 的镜像，
-- 改它的 qty，下一轮同步会按镜像值把差额补回去（校准在 5 分钟内被撤销）；
-- 而且 qcQty 本身含 DEVICE 行，DEVICE := qcQty 会让合计越校越大
-- （3+2 → 5+2=7 → 7+2=9）。标记不碰数，定责前后合计口径稳定。

SET @has_qc_defect := (
  SELECT COUNT(*) FROM information_schema.tables
  WHERE table_schema = DATABASE() AND table_name = 'erp_qc_defect'
);

SET @has_qc_recon_state := (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'erp_qc_defect' AND column_name = 'recon_state'
);

SET @ddl_qc_recon_state := IF(@has_qc_defect > 0 AND @has_qc_recon_state = 0,
  'ALTER TABLE erp_qc_defect ADD COLUMN recon_state VARCHAR(16) NOT NULL DEFAULT '''' COMMENT ''对账定责的通道标记：空=未定责 / KEEP=计入 / DROP=不计入''',
  'DO 0');

PREPARE stmt_qc_recon_state FROM @ddl_qc_recon_state;

EXECUTE stmt_qc_recon_state;

DEALLOCATE PREPARE stmt_qc_recon_state;

-- 存量行回填：默认就是「未定责」，NOT NULL DEFAULT '' 已经保证，
-- 这里再显式刷一次是为了兼容「列已存在但有 NULL」的半升级库
-- （老版本加过这一列但没设默认值时会出现 NULL，统计里 NULL <> 'DROP' 为 NULL → 行被静默漏掉）。
-- 用守卫式：新装环境表还不存在，直接 UPDATE 会报错、把整段迁移打断。
SET @ddl_qc_recon_fill := IF(@has_qc_defect > 0 AND @has_qc_recon_state > 0,
  'UPDATE erp_qc_defect SET recon_state = '''' WHERE recon_state IS NULL OR recon_state = ''''',
  'DO 0');

PREPARE stmt_qc_recon_fill FROM @ddl_qc_recon_fill;

EXECUTE stmt_qc_recon_fill;

DEALLOCATE PREPARE stmt_qc_recon_fill;
