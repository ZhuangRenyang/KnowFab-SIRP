-- =====================================================================
-- 1.8.0  设备维修工单：把「AI 诊断」接成可追溯的闭环
--
-- 背景：`FaultAdvisor` 已经在用 AI 分析故障（给车间大屏的维修看板供文本），
-- 但那份分析有三个缺口：
--   ① 结果只在内存缓存，重启就没了 —— 事后问"当时 AI 说是什么原因"答不上来
--   ② 只给大屏看，看完就消失 —— 没有"转成工单派给谁修"的动作
--   ③ 修好了没人记录实际原因 —— 经验永远不积累，下次同类报警还是从零开始猜
--
-- 本表补的就是这三件事：分析落库可追溯、能转工单、修完回填形成样本。
--
-- 关键字段的语义（最易做错的三处）：
--
--   ① `ai_source` 区分 AI / LOCAL：
--      AI 未启用或调用失败时，退回本地规则建议（FaultAdvisor.localAdvice），
--      文本照样有，但**必须标清来源**。把本地规则说成 AI 结论，是骗现场。
--
--   ② `cause_category` 是**统计维度**，不是给好看的：
--      没有分类就没法回答"这台机主要坏在哪一类"，
--      也就没法做备件储备和预防性维护。设计成必填（结案时）。
--
--   ③ `alarm_code` 单独存一份，不靠关联 alarm_event 取：
--      报警事件会被清理（保留期到了就删），但维修记录要长期留着做样本。
--      靠外键取的话，半年后这条维修记录就变成"设备不明、报警不明"。
--
-- 幂等：重复执行不报错、不改已有数据。
-- =====================================================================

CREATE TABLE IF NOT EXISTS `erp_maint_order` (
  `id`              BIGINT       NOT NULL AUTO_INCREMENT,
  `order_no`        VARCHAR(32)  NOT NULL COMMENT '维修单号 MO+日期+序号',
  `alarm_event_id`  BIGINT       NULL     COMMENT '来源报警事件（报警被清理后此列为空但工单仍在）',

  -- 设备与报警的快照：报警事件会过期删除，这些必须自己存一份
  `device_code`     VARCHAR(64)  NOT NULL COMMENT '设备编号',
  `device_name`     VARCHAR(128) NULL     COMMENT '设备名称快照',
  `alarm_code`      VARCHAR(32)  NULL     COMMENT 'PLC 报警码/报警字（alarm_event.metric_value 转成字符串）',
  `alarm_text`      VARCHAR(500) NULL     COMMENT '报警内容快照',

  -- AI 诊断快照
  `ai_advice`       TEXT         NULL     COMMENT '诊断建议原文（AI 或本地规则）',
  `ai_source`       VARCHAR(16)  NULL     COMMENT 'AI / LOCAL / NONE，必须标清来源',
  `ai_at`           BIGINT       NULL     COMMENT '诊断时间',
  `ai_elapsed_ms`   INT          NULL     COMMENT 'AI 调用耗时（毫秒），用于判断响应是否可接受',

  -- 实际原因回填（这块是样本的价值所在）
  `cause_category`  VARCHAR(24)  NULL     COMMENT '原因分类 ELECTRIC/MECHANICAL/PARAM/OPERATION/MATERIAL/OTHER',
  `root_cause`      TEXT         NULL     COMMENT '实际根因（人写的，不是 AI 猜的）',
  `solution`        TEXT         NULL     COMMENT '实际怎么修好的',
  `parts_replaced`  VARCHAR(255) NULL     COMMENT '更换的备件',
  `downtime_min`    INT          NULL     COMMENT '停机分钟数（从报警到恢复）',

  -- 流转
  `status`          VARCHAR(16)  NOT NULL DEFAULT 'ANALYZED'
                    COMMENT 'ANALYZED 已诊断 / IN_PROGRESS 维修中 / RESOLVED 已结案 / CANCELLED 已取消',
  `created_by`      VARCHAR(64)  NULL,
  `created_at`      BIGINT       NOT NULL,
  `started_at`      BIGINT       NULL,
  `resolved_at`     BIGINT       NULL,
  `resolved_by`     VARCHAR(64)  NULL,

  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_maint_no` (`order_no`),
  -- 查历史样本的路径：同设备 + 同报警码 + 已结案
  KEY `idx_maint_sample` (`device_code`, `alarm_code`, `status`),
  KEY `idx_maint_status` (`status`, `created_at`),
  KEY `idx_maint_alarm` (`alarm_event_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='设备维修工单（AI 诊断闭环）';
