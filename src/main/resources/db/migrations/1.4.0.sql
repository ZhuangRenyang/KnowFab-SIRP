-- =====================================================================
-- 1.4.0  需量告警重启丢失：订正历史上被误关的告警（ROADMAP DH-29）
--
-- 背景：需量超合同是**系统告警**——它的配置在 AppSetting("energy.demand")
-- （合同需量、预警比例、开关），不在 alarm.rules 里。告警引擎重启时按规则表
-- 找规则，找不到就把未恢复的告警自动关掉，关闭理由写「规则已删除或停用」。
-- 可规则根本没被删过——那句话是假的。客户「昨天超合同需量」的告警一重启就
-- 查不到，追溯时看到的理由还会把人带偏。
--
-- 这里只订正**说错话的关闭理由**，不动告警本身的状态、级别、发生时间：
-- 那些数据是对的，只有那句「规则已删除或停用」是假的。
--
-- 为什么能无歧义地识别：这个理由是 AlarmEngine.restore() 独占写的，
-- 所以「需量告警 + 这句话」= 「被系统重启关掉的需量告警」。
-- 只改 rule_id = 'r-demand-over' 的，其它规则被同样理由关掉的那是真的
-- （用户确实删了规则），一个字都不动。
--
-- 新装环境：alarm_event 表还不存在时整段跳过（Hibernate 稍后按实体建表）。
-- 重复执行：WHERE 条件匹配不到已订正的记录，第二遍影响 0 行。
-- =====================================================================

SET @has_table := (
  SELECT COUNT(*) FROM information_schema.tables
  WHERE table_schema = DATABASE() AND table_name = 'alarm_event'
);

SET @ddl_reason := IF(@has_table > 0,
  'UPDATE alarm_event SET clear_reason = ''系统重启时自动关闭（当时是否已回落已无法追溯）''
   WHERE rule_id = ''r-demand-over'' AND clear_reason = ''规则已删除或停用''',
  'DO 0');

PREPARE stmt_reason FROM @ddl_reason;

EXECUTE stmt_reason;

DEALLOCATE PREPARE stmt_reason;
