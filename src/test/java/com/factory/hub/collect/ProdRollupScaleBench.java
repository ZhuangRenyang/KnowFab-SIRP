package com.factory.hub.collect;

import com.factory.hub.erp.stock.ErpProdInboundService;
import com.factory.hub.model.DeviceEntity;
import com.factory.hub.report.DevCounterStateRepository;
import com.factory.hub.report.ProdHourly;
import com.factory.hub.report.ProdHourlyRepository;
import com.factory.hub.report.ProdRollup;
import com.factory.hub.service.DeviceService;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 采集规模压测 L4：**真实 {@link ProdRollup} 类 + 真实 MySQL**（改造前 vs 改造后对比）。
 *
 * <p><b>为什么要有 L4</b>（2026-11-07）：
 * L1 用 Mockito 把 {@code ProdRollup} 整个换掉了 ⇒ 它测的是
 * 「<b>假设</b> todayOutputOf 持锁且每次慢 1ms」⇒ <b>结构上不可能</b>验证
 * 「把 todayOutputOf 改成读快照」这种<b>生产代码内部</b>的改动。
 * <p>⇒ 所以 L1 改造前后跑出来几乎一样（2686ms → 2580ms）—— <b>那不是改造无效</b>，
 * 那是<b>测不到</b>。
 * <p>⇒ L4 用<b>真实的 {@code ProdRollup} 类</b>（不是 mock），配一个
 * <b>用 JDBC 实现 {@code ProdHourlyRepository} 接口</b>的替身
 * ⇒ SQL、索引、连接池、事务全都是真的 ⇒ 只有「JPA 的实体装配」没测到（那部分只会更慢）。
 *
 * <p><b>测两种模式</b>：
 * <ul>
 *   <li><b>旧模式</b>：每台设备每轮查一次库，<b>在全局锁内</b>（= 改造前的样子）</li>
 *   <li><b>新模式</b>：每秒一次批量查询 + 发布不可变快照，读取时<b>不持锁</b>（= 改造后）</li>
 * </ul>
 * ⇒ 对比两者的「一轮耗时」与「锁等待」，这才是改造有效性的直接证据。
 */
public final class ProdRollupScaleBench {

    /** 压测专用库 —— 与交付库无关，测完整库 DROP */
    private static final String PERF_DB = "devicehub_perf";

    public static void main(String[] args) throws Exception {
        int devices = intArg(args, "--devices", 1000);
        int poolSize = intArg(args, "--pool", 10);
        int pollMs = intArg(args, "--poll-ms", 1000);
        int rounds = intArg(args, "--rounds", 3);
        String url = "jdbc:mysql://127.0.0.1:3306/" + PERF_DB
                + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"
                + "&useSSL=false&allowPublicKeyRetrieval=true";
        String user = argValue(args, "--user", "admin");
        String pass = argValue(args, "--pass", "123456");

        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        out.println();
        out.println("=========================================================");
        out.println(" L4：真实 ProdRollup + 真实 MySQL（改造前 vs 改造后）");
        out.println("=========================================================");
        out.printf(" 设备数=%d 连接池=%d 目标周期=%dms 轮数=%d%n", devices, poolSize, pollMs, rounds);
        out.println();

        List<String> failures = new ArrayList<>();

        try {
            // ---------- 准备库与数据 ----------
            out.println("[1/3] 准备压测库（设备表 + 小时行）...");
            Class.forName("com.mysql.cj.jdbc.Driver");
            try (Connection admin = DriverManager.getConnection(
                    url.replace("/" + PERF_DB, "/mysql"), user, pass);
                 Statement st = admin.createStatement()) {
                st.execute("DROP DATABASE IF EXISTS " + PERF_DB);
                st.execute("CREATE DATABASE " + PERF_DB
                        + " DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci");
            }
            try (Connection c = DriverManager.getConnection(url, user, pass);
                 Statement st = c.createStatement()) {
                st.execute("""
                        CREATE TABLE device (
                          id BIGINT NOT NULL AUTO_INCREMENT,
                          code VARCHAR(64) NOT NULL,
                          name VARCHAR(128),
                          protocol VARCHAR(32),
                          host VARCHAR(64),
                          port INT,
                          points_json LONGTEXT,
                          poll_interval_ms INT DEFAULT 1000,
                          offline_after_fails INT DEFAULT 2,
                          prod_reset_hour INT DEFAULT 0,
                          enabled BIT(1) DEFAULT b'1',
                          PRIMARY KEY (id),
                          UNIQUE KEY uk_device_code (code)
                        ) ENGINE=InnoDB""");
                st.execute("""
                        CREATE TABLE prod_hourly (
                          id BIGINT NOT NULL AUTO_INCREMENT,
                          device_code VARCHAR(64) NOT NULL,
                          device_name VARCHAR(128),
                          hour_start BIGINT NOT NULL,
                          run_sec DOUBLE DEFAULT 0,
                          idle_sec DOUBLE DEFAULT 0,
                          stop_sec DOUBLE DEFAULT 0,
                          alarm_sec DOUBLE DEFAULT 0,
                          unknown_sec DOUBLE DEFAULT 0,
                          output DOUBLE DEFAULT 0,
                          backfill DOUBLE DEFAULT 0,
                          defect DOUBLE DEFAULT 0,
                          reject_count DOUBLE DEFAULT 0,
                          PRIMARY KEY (id),
                          KEY idx_prod_code_hour (device_code, hour_start),
                          KEY idx_prod_hour (hour_start)
                        ) ENGINE=InnoDB""");
                st.execute("""
                        CREATE TABLE dev_counter_state (
                          id BIGINT NOT NULL AUTO_INCREMENT,
                          device_code VARCHAR(64) NOT NULL,
                          count_ts BIGINT DEFAULT 0,
                          count_value DOUBLE DEFAULT 0,
                          status_ts BIGINT DEFAULT 0,
                          status_value INT DEFAULT -1,
                          reject_ts BIGINT DEFAULT 0,
                          reject_value DOUBLE DEFAULT -1,
                          PRIMARY KEY (id),
                          UNIQUE KEY uk_dev_counter (device_code)
                        ) ENGINE=InnoDB""");
            }
            seed(url, user, pass, devices, pollMs);
            out.printf("  已插入 %d 台设备 + 每台 24 行小时产量%n", devices);

            // ---------- 跑两种模式 ----------
            out.println("[2/3] 跑两种模式 ...");
            Mode oldMode = runMode(out, url, user, pass, devices, poolSize, rounds, pollMs, false);
            Mode newMode = runMode(out, url, user, pass, devices, poolSize, rounds, pollMs, true);

            // ---------- 对比 ----------
            out.println("[3/3] 对比 ...");
            out.println();
            out.println(" 指标                 旧模式(持锁查库)      新模式(批量快照)      改善");
            out.println(" -------------------  -------------------  -------------------  ---------");
            out.printf(" 一轮平均耗时(ms)      %19.0f  %19.0f  %8.1f%%%n",
                    oldMode.avgRoundMs, newMode.avgRoundMs,
                    oldMode.avgRoundMs <= 0 ? 0 : (oldMode.avgRoundMs - newMode.avgRoundMs) * 100d / oldMode.avgRoundMs);
            out.printf(" 锁等待 P99(ms)        %19.0f  %19.0f  %8s%n",
                    oldMode.p99WaitMs, newMode.p99WaitMs,
                    newMode.p99WaitMs < oldMode.p99WaitMs ? "改善" : "无变化");
            out.printf(" 每轮查库次数          %19d  %19d  %8s%n",
                    oldMode.queriesPerRound, newMode.queriesPerRound,
                    oldMode.queriesPerRound > newMode.queriesPerRound ? "降低" : "无变化");
            out.println();

            if (newMode.avgRoundMs > oldMode.avgRoundMs) {
                failures.add("新模式反而更慢（" + newMode.avgRoundMs + "ms > " + oldMode.avgRoundMs
                        + "ms）⇒ 改造有害");
            }
            if (newMode.queriesPerRound >= oldMode.queriesPerRound) {
                failures.add("新模式没有降低查库次数（" + newMode.queriesPerRound
                        + " vs " + oldMode.queriesPerRound + "）⇒ 改造没达到目的");
            }
            if (newMode.p99WaitMs >= oldMode.p99WaitMs) {
                failures.add("新模式没有降低锁等待（" + newMode.p99WaitMs + "ms vs "
                        + oldMode.p99WaitMs + "ms）⇒ 改造没达到目的");
            }
        } catch (Exception e) {
            failures.add("压测异常: " + e);
            e.printStackTrace();
        } finally {
            out.println();
            out.println(" 清理压测库 ...");
            try {
                Class.forName("com.mysql.cj.jdbc.Driver");
                try (Connection admin = DriverManager.getConnection(
                        url.replace("/" + PERF_DB, "/mysql"), user, pass);
                     Statement st = admin.createStatement()) {
                    st.execute("DROP DATABASE IF EXISTS " + PERF_DB);
                    out.println("  已 DROP " + PERF_DB);
                }
            } catch (Exception e) {
                out.println("  ⚠️ 清理失败: " + e.getMessage());
            }
        }

        out.println();
        out.println("---------------- 判定 ----------------");
        if (failures.isEmpty()) {
            out.println(" ✅ 改造有效：查库次数下降、锁等待下降、一轮耗时下降");
        } else {
            for (String f : failures) {
                out.println(" ❌ " + f);
            }
        }
        out.println();
        System.exit(failures.isEmpty() ? 0 : 1);
    }

    /** 一轮模式的结果 */
    private static final class Mode {
        double avgRoundMs;
        double p99WaitMs;
        long queriesPerRound;
    }

    /**
     * 跑一种模式。
     * @param snapshotMode true = 新模式（批量快照 + 无锁读）；false = 旧模式（每台持锁查库）
     */
    private static Mode runMode(PrintStream out, String url, String user, String pass,
                                int devices, int poolSize, int rounds, int pollMs,
                                boolean snapshotMode) throws Exception {
        out.println();
        out.println(" ---- " + (snapshotMode ? "新模式：批量快照 + 无锁读" : "旧模式：每台持锁查库") + " ----");

        // 真实 repo（JDBC 实现）+ 真实 ProdRollup
        JdbcRepo repoHandler = new JdbcRepo(url, user, pass, poolSize);
        ProdHourlyRepository repo = repoHandler.proxy();
        DeviceService deviceService = new StubDeviceService(devices);
        ProdRollup rollup = new ProdRollup(new SnapshotStore(), deviceService, repo,
                stubInbound(), stubCounterRepo());

        AtomicInteger queryCount = repoHandler.counter;

        ConcurrentLinkedQueue<Long> waits = new ConcurrentLinkedQueue<>();
        Mode m = new Mode();

        for (int round = 0; round < rounds; round++) {
            if (snapshotMode) {
                // 新模式：先刷新一次快照（= scan() 每秒做的事），再让 1000 线程无锁读
                refreshSnapshot(rollup, repoHandler, deviceService, devices, url, user, pass);
            }
            int qBefore = queryCount.get();
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(devices);
            for (int i = 0; i < devices; i++) {
                final String code = String.format("PERF-%05d", i);
                Thread t = new Thread(() -> {
                    try {
                        start.await();
                        long t0 = System.nanoTime();
                        if (snapshotMode) {
                            // 新模式：无锁读快照（真实 ProdRollup.todayOutputOf 内部就是无锁的）
                            rollup.todayOutputOf(code);
                            waits.add(System.nanoTime() - t0);
                        } else {
                            // 旧模式：**持锁**查库（复刻改造前 todayOutputOf 的形状）
                            synchronized (GLOBAL_MONITOR) {
                                long waited = System.nanoTime() - t0;
                                waits.add(waited);
                                rollup.todayOutputOf(code);
                            }
                        }
                    } catch (Exception ignored) {
                        // 压测里忽略单台异常（只看整体吞吐）
                    } finally {
                        done.countDown();
                    }
                }, "l4-" + i);
                t.setDaemon(true);
                t.start();
            }
            long rt0 = System.currentTimeMillis();
            start.countDown();
            done.await();
            long roundMs = System.currentTimeMillis() - rt0;
            long qThis = queryCount.get() - qBefore;

            out.printf("  第 %d 轮: %d 台并发，耗时 %d ms（目标 %d ms）%s  本轮查库 %d 次%n",
                    round + 1, devices, roundMs, pollMs,
                    roundMs > pollMs ? "  <-- 超期" : "", qThis);
            m.avgRoundMs += (double) roundMs / rounds;
            m.queriesPerRound = qThis;
        }

        long[] w = waits.stream().mapToLong(Long::longValue).sorted().toArray();
        if (w.length > 0) {
            int idx = (int) Math.ceil(0.99 * w.length) - 1;
            m.p99WaitMs = w[Math.max(0, Math.min(w.length - 1, idx))] / 1_000_000d;
        }
        repoHandler.close();
        return m;
    }

    private static final Object GLOBAL_MONITOR = new Object();

    /** 手动触发一次快照刷新（真实 ProdRollup 的 refreshTodaySnapshot 是 private） */
    private static void refreshSnapshot(ProdRollup rollup, JdbcRepo repoHandler,
                                        DeviceService deviceService, int devices,
                                        String url, String user, String pass) throws Exception {
        try {
            java.lang.reflect.Method m = ProdRollup.class.getDeclaredMethod("refreshTodaySnapshot");
            m.setAccessible(true);
            m.invoke(rollup);
        } catch (Exception e) {
            throw new IllegalStateException("调 refreshTodaySnapshot 失败", e);
        }
    }

    /**
     * 用 JDBC 实现的 {@link ProdHourlyRepository}（真实 SQL + 真实索引 + 真实连接池）。
     * <p>
     * ⚠️ 用<b>动态代理</b>而不是「继承接口写实现类」：
     *   {@code JpaRepository} 有几十个方法，手写实现要么漏（编译不过）、
     *   要么全抛异常（但没人知道哪些会被调）⇒ 代理只处理真正用到的方法，
     *   其余一律明确抛「不支持」⇒ <b>用到没实现的方法会立刻暴露</b>，不会静默走空。
     */
    static final class JdbcRepo implements java.lang.reflect.InvocationHandler {
        private final String url;
        private final String user;
        private final String pass;
        private final ArrayBlockingQueue<Connection> pool;
        private final AtomicInteger counter;

        JdbcRepo(String url, String user, String pass, int poolSize) throws Exception {
            this.url = url;
            this.user = user;
            this.pass = pass;
            this.pool = new ArrayBlockingQueue<>(poolSize);
            for (int i = 0; i < poolSize; i++) {
                pool.add(DriverManager.getConnection(url, user, pass));
            }
            this.counter = new AtomicInteger();
        }

        ProdHourlyRepository proxy() {
            return (ProdHourlyRepository) java.lang.reflect.Proxy.newProxyInstance(
                    ProdHourlyRepository.class.getClassLoader(),
                    new Class<?>[]{ProdHourlyRepository.class},
                    this);
        }

        @Override
        public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) throws Throwable {
            switch (method.getName()) {
                case "findByDeviceCodeAndHourStartBetween":
                    return byCode((String) args[0], (Long) args[1], (Long) args[2]);
                case "findByDeviceCodeInAndHourStartBetween":
                    return byCodes((java.util.Collection<String>) args[0], (Long) args[1], (Long) args[2]);
                default:
                    throw new UnsupportedOperationException(
                            "压测里没实现这个方法: " + method.getName()
                                    + "（用到它说明压测的模型与生产不符，要补）");
            }
        }

        private Connection borrow() throws Exception {
            Connection c = pool.poll(5, java.util.concurrent.TimeUnit.SECONDS);
            if (c == null) {
                throw new IllegalStateException("连接池借不到（池已耗尽）");
            }
            return c;
        }

        private List<ProdHourly> byCode(String deviceCode, long from, long to) {
            counter.incrementAndGet();
            List<ProdHourly> out = new ArrayList<>();
            Connection c = null;
            try {
                c = borrow();
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT device_code, device_name, hour_start, output, backfill, defect,"
                                + " run_sec, idle_sec, stop_sec, alarm_sec, unknown_sec"
                                + " FROM prod_hourly WHERE device_code = ? AND hour_start >= ? AND hour_start <= ?")) {
                    ps.setString(1, deviceCode);
                    ps.setLong(2, from);
                    ps.setLong(3, to);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            out.add(readRow(rs));
                        }
                    }
                }
            } catch (Exception e) {
                throw new IllegalStateException(e);
            } finally {
                if (c != null) {
                    pool.offer(c);
                }
            }
            return out;
        }

        private List<ProdHourly> byCodes(java.util.Collection<String> codes, long from, long to) {
            counter.incrementAndGet();
            List<ProdHourly> out = new ArrayList<>();
            if (codes == null || codes.isEmpty()) {
                return out;
            }
            StringBuilder sb = new StringBuilder("SELECT device_code, device_name, hour_start, output, backfill,"
                    + " defect, run_sec, idle_sec, stop_sec, alarm_sec, unknown_sec"
                    + " FROM prod_hourly WHERE hour_start >= ? AND hour_start <= ? AND device_code IN (");
            for (int i = 0; i < codes.size(); i++) {
                sb.append(i > 0 ? ",?" : "?");
            }
            sb.append(')');
            Connection c = null;
            try {
                c = borrow();
                try (PreparedStatement ps = c.prepareStatement(sb.toString())) {
                    ps.setLong(1, from);
                    ps.setLong(2, to);
                    int i = 3;
                    for (String code : codes) {
                        ps.setString(i++, code);
                    }
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            out.add(readRow(rs));
                        }
                    }
                }
            } catch (Exception e) {
                throw new IllegalStateException(e);
            } finally {
                if (c != null) {
                    pool.offer(c);
                }
            }
            return out;
        }

        private static ProdHourly readRow(ResultSet rs) throws Exception {
            ProdHourly p = new ProdHourly(rs.getString(1), rs.getString(2), rs.getLong(3));
            p.setOutput(rs.getDouble(4));
            p.setBackfill(rs.getDouble(5));
            p.setDefect(rs.getDouble(6));
            p.setRunSec(rs.getDouble(7));
            p.setIdleSec(rs.getDouble(8));
            p.setStopSec(rs.getDouble(9));
            p.setAlarmSec(rs.getDouble(10));
            p.setUnknownSec(rs.getDouble(11));
            return p;
        }

        void close() {
            for (Connection c : pool) {
                try {
                    c.close();
                } catch (Exception ignored) {
                    // 压测收尾，忽略
                }
            }
        }
    }

    /** 设备服务替身：只提供 all() 与 byCode()（不查库 ⇒ 与生产不同，但两模式一致 ⇒ 对比公平） */
    static final class StubDeviceService extends DeviceService {
        private final List<DeviceEntity> devices;

        StubDeviceService(int n) {
            super(null, null);
            List<DeviceEntity> list = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                DeviceEntity d = new DeviceEntity();
                d.setCode(String.format("PERF-%05d", i));
                d.setName(d.getCode());
                d.setProdResetHour(0);
                d.setEnabled(true);
                list.add(d);
            }
            this.devices = list;
        }

        @Override
        public List<DeviceEntity> all() {
            return devices;
        }

        @Override
        public DeviceEntity byCode(String code) {
            for (DeviceEntity d : devices) {
                if (d.getCode().equals(code)) {
                    return d;
                }
            }
            return null;
        }
    }

    private static ErpProdInboundService stubInbound() {
        return null;
    }

    private static DevCounterStateRepository stubCounterRepo() {
        return null;
    }

    /** 造数据 */
    private static void seed(String url, String user, String pass, int devices, int pollMs)
            throws Exception {
        Random rnd = new Random(20261107L);
        long now = System.currentTimeMillis();
        long curHour = now - (now % 3600000L);
        try (Connection c = DriverManager.getConnection(url, user, pass)) {
            c.setAutoCommit(false);
            try (PreparedStatement d = c.prepareStatement(
                    "INSERT INTO device (code, name, protocol, host, port, points_json,"
                            + " poll_interval_ms, offline_after_fails, prod_reset_hour, enabled)"
                            + " VALUES (?,?,?,?,?,?,?,?,?,b'1')");
                 PreparedStatement p = c.prepareStatement(
                         "INSERT INTO prod_hourly (device_code, device_name, hour_start, run_sec,"
                                 + " idle_sec, stop_sec, alarm_sec, unknown_sec, output, backfill, defect)"
                                 + " VALUES (?,?,?,?,?,?,?,?,?,?,?)")) {
                for (int i = 0; i < devices; i++) {
                    String code = String.format("PERF-%05d", i);
                    d.setString(1, code);
                    d.setString(2, code);
                    d.setString(3, "sim");
                    d.setString(4, "127.0.0.1");
                    d.setInt(5, 502);
                    d.setString(6, "[{\"key\":\"count\",\"role\":\"count\",\"dataType\":\"u32\",\"address\":0}]");
                    d.setInt(7, pollMs);
                    d.setInt(8, 2);
                    d.setInt(9, 0);
                    d.executeUpdate();
                    for (int h = 23; h >= 0; h--) {
                        p.setString(1, code);
                        p.setString(2, code);
                        p.setLong(3, curHour - h * 3600000L);
                        p.setDouble(4, 1800);
                        p.setDouble(5, 120);
                        p.setDouble(6, 60);
                        p.setDouble(7, 0);
                        p.setDouble(8, 0);
                        p.setDouble(9, rnd.nextInt(500));
                        p.setDouble(10, 0);
                        p.setDouble(11, rnd.nextInt(10));
                        p.addBatch();
                    }
                }
                p.executeBatch();
            }
            c.commit();
        }
    }

    private static int intArg(String[] args, String name, int def) {
        String v = argValue(args, name, null);
        return v == null ? def : Integer.parseInt(v);
    }

    private static String argValue(String[] args, String name, String def) {
        for (int i = 0; i < args.length - 1; i++) {
            if (name.equals(args[i])) {
                return args[i + 1];
            }
        }
        return def;
    }

    private ProdRollupScaleBench() {
    }
}
