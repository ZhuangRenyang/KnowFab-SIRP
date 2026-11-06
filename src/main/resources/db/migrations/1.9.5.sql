-- =====================================================================
-- 1.9.5  修正「协议切了但端口没跟着切」的设备（OPC UA 却留着 Modbus 的 502）
--
-- 背景：
--   502 是 Modbus TCP 的端口，4840 是 OPC UA 的端口。以前「默认端口」被写成一个
--   协议无关的全局常量 502（散在实体/控制器/模板/上下文等 9 处），
--   于是**新建一台 OPC UA 设备不填端口 ⇒ 落库 502 ⇒ 驱动去连 502 ⇒ 必然连不上**。
--   前端表单预填 4840 只是遮住了 UI 那一路，API 建设备、脚本导入、老数据仍是 502。
--
--   这条迁移的作用不是「修一批坏数据」，而是让
--   **「协议切了、端口没跟着改」这个状态变成不可能**：
--   将来导入/脚本建的设备会被自动纠正，不用等人想起来。
--
-- 写法（照 1.9.2 / 1.9.4 的教训）：
--   · 表不存在时**故意报错**（SELECT 一张不存在的表）→ 记 ok=0 → 下次启动重试。
--     迁移跑在 Hibernate 建表之前，全新安装时这张表还不存在，报错是预期行为。
--   · 绝不用 'DO 0' 兜底：那会记成 ok=1，数据永远不被纠正且无人知道（DH-84 的坑）。
--   · 幂等：条件写死（protocol='opcua' AND port=502），重复执行结果一样。
--
-- 为什么可以无条件改 502 → 4840：
--   502 是 Modbus TCP 的标准端口，OPC UA server 监听 502 的概率约等于零；
--   即便真有人这么用，这条迁移也只在「protocol='opcua' AND port=502」时才动它。
--
-- ⚠️ 本次执行是 no-op：本地库里 OPC UA 设备数为 0（4 台全是 sim）。
--    这次验证的是**脚本本身能跑通且幂等**，不是「它修了数据」。
-- =====================================================================

SET @has_tbl := (
  SELECT COUNT(*) FROM information_schema.TABLES
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'device'
);

SET @ddl := IF(@has_tbl > 0,
  'UPDATE device SET port = 4840 WHERE protocol = ''opcua'' AND port = 502',
  'SELECT 1 FROM device LIMIT 0');

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

DO 0;
