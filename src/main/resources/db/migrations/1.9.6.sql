-- =====================================================================
-- 1.9.6  设备增加「本机源地址」（可选）—— 多网段接 PLC 时不再改本机 IP
--
-- 背景：
--   现场的 PLC 常分布在**不同网段**（比如新产线用 192.168.20.x）。
--   本机若没有那个网段的地址，直连就连不上 ⇒ 以前只能**改本机主 IP**，
--   改完又得改回去，WiFi 上网也受影响，现场为此反复折腾。
--
--   正解是**给本机加 IP 别名**（tools/net/plc-on.*，一行命令配好所有网段）。
--   但别名配好之后仍有一个场景需要「精确指定用哪个本机 IP 出去」：
--   同一台机器上挂了**两个同网段的物理链路**（比如两条网线各接一条产线，
--   或一台 PLC 双网卡），此时源地址由路由表决定，可能挑错那条链路。
--   ⇒ 这条迁移给设备留一个**可选**的 source_address：
--     · 留空 = 走系统路由表自动选源地址（**绝大多数情况这样就行**）
--     · 填了 = 驱动 bind 到该地址，精确指定从哪条链路出去
--
-- 为什么不默认填：
--   填错一个本机不存在的地址，connect 会直接失败（Cannot assign requested address），
--   比不填更难排查。⇒ 默认留空，让路由表决定；只在真需要时才填。
--
-- 写法（照 1.9.2 / 1.9.4 / 1.9.5 的教训）：
--   · 表不存在时 ALTER 会**故意报错** → 记 ok=0 → 下次启动重试。
--     迁移跑在 Hibernate 建表之前，全新安装时这张表还不存在，报错是预期行为。
--   · 绝不用 'DO 0' 兜底：那会记成 ok=1，列永远不被加上且无人知道（DH-84 的坑）。
--   · 幂等：先查 information_schema.COLUMNS，列在就不动。
-- =====================================================================

SET @has_col := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'device'
    AND COLUMN_NAME = 'source_address'
);

SET @ddl := IF(@has_col = 0,
  'ALTER TABLE device ADD COLUMN source_address VARCHAR(64) NULL COMMENT ''本机源地址（可选，留空=按系统路由自动选）''',
  'SELECT 1 FROM device LIMIT 0');

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
