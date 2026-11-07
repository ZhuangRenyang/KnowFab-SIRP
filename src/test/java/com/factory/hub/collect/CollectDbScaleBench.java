package com.factory.hub.collect;

import com.factory.hub.driver.DriverContext;
import com.factory.hub.driver.DriverRegistry;
import com.factory.hub.driver.ProtocolDriver;
import com.factory.hub.erp.ProdDay;
import com.factory.hub.model.DeviceEntity;
import com.factory.hub.model.PointDef;
import tools.jackson.databind.ObjectMapper;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 采集规模压测（L2：写库层，真实 MySQL）。
 *
 * <p><b>它回答什么</b>：L1（调度层）证明「线程池不是瓶颈」，但 L1 把
 * {@code ProdRollup.todayOutputOf()} 换成了零耗时的替身 ⇒ <b>全局锁与真实查库的成本完全没测</b>。
 * L2 补上这一段：<b>真实 MySQL + 真实 JPA 级别的查询 + 真实的一把全局锁</b>，
 * 看 1000 台设备每轮都调它时会发生什么。
 *
 * <p><b>为什么用 JDBC 而不是 JPA</b>：本项目<b>没有 {@code @SpringBootTest}</b>
 * （全部测试都是 Mockito 单元测试），起一个 Spring 上下文要拉起整个 ERP/能耗/告警链，
 * 变量太多、测出来的数字归因不清。
 * ⇒ 这里用 JDBC 直连真实 MySQL，<b>SQL 与 JPA 生成的那两条完全一致</b>
 * （{@code findByCode} → {@code SELECT ... WHERE code = ?}；
 * {@code findByDeviceCodeAndHourStartBetween} → {@code SELECT ... WHERE device_code = ? AND hour_start >= ? AND hour_start <= ?}），
 * 索引也一致（{@code device.code} 唯一索引、{@code prod_hourly(device_code, hour_start)} 复合索引）。
 * ⇒ 查库成本是真的，<b>但不含 JPA 的实体装配开销</b>（那部分会让真实耗时更高 ⇒ 本工具是乐观下界）。
 *
 * <p><b>⚠️ 这个工具测不到什么（诚实说明）</b>：
 * <ul>
 *   <li>真实 PLC 的网络抖动与重传</li>
 *   <li>JPA 实体装配与事务管理的额外开销（真实值 ≥ 本工具的值）</li>
 *   <li>连接池（Hikari）真实争用 —— 本工具用<b>独立连接</b>模拟，池大小固定为配置值，
 *       但池的借用/归还开销没测</li>
 * </ul>
 *
 * <p><b>它测什么</b>：
 * <ul>
 *   <li>1000 台设备每轮一次 {@code todayOutputOf} ⇒ 每秒 2000 次查库的<b>串行总耗时</b>
 *       （因为 {@code todayOutputOf} 是 synchronized ⇒ 全部串行）</li>
 *   <li>全局锁的<b>实际等待时长</b>（P50/P95/P99）</li>
 *   <li>连接池大小是否够（把池大小作为参数扫）</li>
 * </ul>
 *
 * <p><b>数据库隔离</b>：用独立的 {@code devicehub_perf} 库（<b>不是</b> {@code devicehub} 交付库，
 * <b>不是</b> {@code devicehub_verify}）⇒ 测完直接 DROP 即可，不碰任何既有数据。
 */
public final class CollectDbScaleBench {

    /** 压测专用库 —— 与交付库无关，测完整库 DROP */
    private static final String PERF_DB = "devicehub_perf";

    /**
     * 全局锁对象。
     * <p>⚠️ <b>不要用 {@code String.intern()}</b>：字符串常量池是 JVM 全局的，
     * 别的代码 intern 同一个字面量就会拿到同一把锁 ⇒ 压测的竞争会被无关代码污染，
     * 而且这种污染极难查（表现为「莫名变慢」）。
     */
    private static final Object GLOBAL_MONITOR = new Object();

    public static void main(String[] args) throws Exception {
        int devices = intArg(args, "--devices", 1000);
        int poolSize = intArg(args, "--pool", 10);
        int pollMs = intArg(args, "--poll-ms", 1000);
        int rounds = intArg(args, "--rounds", 3);
        String jdbcUrl = argValue(args, "--url",
                "jdbc:mysql://127.0.0.1:3306/" + PERF_DB
                        + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"
                        + "&useSSL=false&allowPublicKeyRetrieval=true");
        String user = argValue(args, "--user", "admin");
        String pass = argValue(args, "--pass", "123456");

        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        out.println();
        out.println("=========================================================");
        out.println(" 采集规模压测 L2（写库层 / 真实 MySQL）");
        out.println("=========================================================");
        out.printf(" 设备数=%d 连接池=%d 目标周期=%dms 轮数=%d%n", devices, poolSize, pollMs, rounds);
        out.printf(" 库=%s（压测专用，测完 DROP）%n", PERF_DB);
        out.println();

        List<String> failures = new ArrayList<>();
        boolean toolBroken = false;

        try {
            // ---------- 1. 准备压测库 ----------
            out.println("[1/4] 准备压测库与数据 ...");
            Class.forName("com.mysql.cj.jdbc.Driver");
            try (Connection admin = DriverManager.getConnection(
                    jdbcUrl.replace("/" + PERF_DB, "/mysql"), user, pass);
                 Statement st = admin.createStatement()) {
                st.execute("DROP DATABASE IF EXISTS " + PERF_DB);
                st.execute("CREATE DATABASE " + PERF_DB
                        + " DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci");
            }
            // 建表：结构照抄生产（device + prod_hourly，含生产同样的索引）
            try (Connection c = DriverManager.getConnection(jdbcUrl, user, pass);
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
            }
            seedDevices(jdbcUrl, user, pass, devices, pollMs);
            out.printf("  已插入 %d 台设备 + 每台 24 行小时产量%n", devices);

            // ---------- 2. 单次查询的真实成本 ----------
            out.println("[2/4] 测单次 todayOutputOf 的真实成本（两次查询）...");
            double[] perCall = new double[200];
            try (Connection c = DriverManager.getConnection(jdbcUrl, user, pass);
                 PreparedStatement p1 = c.prepareStatement("SELECT code FROM device WHERE code = ?");
                 PreparedStatement p2 = c.prepareStatement(
                         "SELECT * FROM prod_hourly WHERE device_code = ? AND hour_start >= ? AND hour_start <= ?")) {
                long now = System.currentTimeMillis();
                long dayStart = ProdDay.dayStart(now, 0);   // ← 直接用生产函数，口径不可能漂
                String code = "PERF-00000";
                for (int i = 0; i < perCall.length; i++) {
                    long t0 = System.nanoTime();
                    p1.setString(1, code);
                    try (ResultSet rs = p1.executeQuery()) {
                        while (rs.next()) {
                            // 只要真的取到行
                            rs.getString(1);
                        }
                    }
                    p2.setString(1, code);
                    p2.setLong(2, dayStart);
                    p2.setLong(3, now);
                    try (ResultSet rs = p2.executeQuery()) {
                        while (rs.next()) {
                            rs.getLong(1);
                        }
                    }
                    perCall[i] = (System.nanoTime() - t0) / 1_000_000d;
                }
            }
            Arrays.sort(perCall);
            double p50 = pct(perCall, 50);
            double p95 = pct(perCall, 95);
            double p99 = pct(perCall, 99);
            out.printf("  单次 todayOutputOf（2 次查询）: P50=%.3fms P95=%.3fms P99=%.3fms%n", p50, p95, p99);
            out.printf("  => 1000 台**串行**（synchronized 全局锁）= %.0f ms  vs 目标周期 %d ms%n",
                    p50 * devices, pollMs);
            if (p50 * devices > pollMs) {
                failures.add(String.format("**全局锁 + 查库已超周期**：1000 台串行需 %.0fms > 目标 %dms"
                        + " ⇒ 瓶颈是 ProdRollup 的 monitor，不是线程池", p50 * devices, pollMs));
            }

            // ---------- 2b. **批量快照刷新成本**（改造后的关键新增成本）----------
            // ⚠️ 这一项**不能省**：改造后 `todayOutputOf` 不查库了，但
            //   `scan()` 每秒要**批量查一次**（1000 个 IN 参数 + 今日全部小时行）。
            //   ⇒ 那一次查询的代价**跑在 scan() 的锁内** ⇒ 它就是新的锁占用来源。
            //   ⇒ 不测它 ⇒ 报出来的「28ms」是乐观的 ⇒ 结论会骗人。
            out.println();
            out.println("[2b] 测批量快照刷新成本（= 改造后每秒一次的批量查询）...");
            double[] batchCosts = new double[20];
            try (Connection c = DriverManager.getConnection(jdbcUrl, user, pass)) {
                StringBuilder codes = new StringBuilder();
                for (int i = 0; i < devices; i++) {
                    codes.append(i > 0 ? ",?" : "?");
                }
                String sql = "SELECT device_code, device_name, hour_start, output, backfill, defect,"
                        + " run_sec, idle_sec, stop_sec, alarm_sec, unknown_sec"
                        + " FROM prod_hourly WHERE hour_start >= ? AND hour_start <= ? AND device_code IN ("
                        + codes + ")";
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    for (int k = 0; k < batchCosts.length; k++) {
                        long now = System.currentTimeMillis();
                        // ⚠️⚠️ **窗口必须与生产一致**：
                        //   生产用 `ProdDay.dayStart(now, rh)`（rh=0 ⇒ **今天 0 点**）。
                        //   若这里用「当前小时起点」⇒ 只查得到当前小时那一行（1000 行）
                        //   ⇒ 批量成本会**乐观 20 倍**（真实是一天 24 行/台 = 24000 行）。
                        long dayStart = ProdDay.dayStart(now, 0);   // ← 直接用生产函数，口径不可能漂
                        ps.setLong(1, dayStart);
                        ps.setLong(2, now);
                        for (int i = 0; i < devices; i++) {
                            ps.setString(3 + i, String.format("PERF-%05d", i));
                        }
                        long t0 = System.nanoTime();
                        int rows = 0;
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                // ⚠️ 第 1 列是 device_code（VARCHAR）⇒ 用 getLong 读它
                                //    会让驱动去把 "PERF-00000" 解析成数字 ⇒ NumberFormatException
                                rs.getString(1);
                                rows++;
                            }
                        }
                        batchCosts[k] = (System.nanoTime() - t0) / 1_000_000d;
                        if (k == 0) {
                            out.printf("  一次批量查询返回 %d 行（%d 台 × 今日 %d 小时）%n",
                                    rows, devices, 24);
                        }
                    }
                }
            }
            Arrays.sort(batchCosts);
            double bP50 = batchCosts[batchCosts.length / 2];
            double bP95 = batchCosts[(int) Math.ceil(0.95 * batchCosts.length) - 1];
            out.printf("  批量刷新耗时(ms)     : P50=%.1f P95=%.1f  （每秒一次）%n", bP50, bP95);
            out.printf("  ⇒ 它跑在 scan() 的锁内 ⇒ 锁占用率约 %.1f%%%n", bP50 * 100d / pollMs);
            if (bP50 > pollMs * 0.5) {
                failures.add(String.format("**批量刷新本身就很重**：P50=%.0fms（占周期 %.0f%%）"
                        + " ⇒ 它成了新的锁占用来源 ⇒ 需要降低刷新频率或换更省的查询形态",
                        bP50, bP50 * 100d / pollMs));
            }

            // ---------- 3. 并发压测：持锁 vs 无锁（真实 MySQL） ----------
            //
            // ⭐⭐ **双模式是这一轮最重要的补充**（QA 提的缺口）：
            //   「持锁模式」= 改造前：synchronized(全局锁) { 查两次库 } ⇒ 全部串行
            //   「无锁模式」= 改造后：每台各查各的 ⇒ **1000 个线程并发打 DB**
            //   ⚠️ 改造前测出来的「连接池 10 个够用」是**同义反复**：
            //      锁把并发串行化了 ⇒ 同时只有 1 条连接在用 ⇒ 池必然不耗尽。
            //   ⇒ 只有「无锁模式」才能回答：去掉全局锁后，池 10 够不够？
            ConcurrentLinkedQueue<Long> waits = new ConcurrentLinkedQueue<>();
            ConcurrentLinkedQueue<Long> costs = new ConcurrentLinkedQueue<>();
            AtomicLong totalCalls = new AtomicLong();
            AtomicInteger poolWaits = new AtomicInteger();
            AtomicInteger poolTimeouts = new AtomicInteger();
            List<Long> roundTimes = new ArrayList<>();

            // ⚠️ **两种模式共用同一个池** ⇒ 才有可比性
            //   （各自建池的话，池的初始状态/连接数差异会混进对比里）
            ArrayBlockingQueue<Connection> pool = new ArrayBlockingQueue<>(poolSize);
            for (int i = 0; i < poolSize; i++) {
                pool.add(DriverManager.getConnection(jdbcUrl, user, pass));
            }

            // 三种形态并排对比（**这才是有意义的对比**）：
            //   0 = 持锁 + 每台查库      = 改造前
            //   1 = 无锁 + 每台查库      = 「只去锁、没批量」的假设形态
            //   2 = 无锁 + 批量快照      = **改造后的真实形态**（1 次批量查询 + 1000 次内存读）
            for (int mode = 0; mode <= 2; mode++) {
                final int m = mode;
                final boolean useLock = m == 0;
                out.println();
                out.println(" ---- " + (m == 0 ? "模式0 持锁+每台查库（改造前）"
                        : m == 1 ? "模式1 无锁+每台查库（只去锁的假设形态）"
                        : "模式2 无锁+批量快照（改造后的真实形态）") + " ----");
                for (int round = 0; round < rounds; round++) {
                    CountDownLatch startGate = new CountDownLatch(1);
                    CountDownLatch done = new CountDownLatch(devices);
                    AtomicInteger errors = new AtomicInteger();
                    for (int i = 0; i < devices; i++) {
                        final int idx = i;
                        Thread t = new Thread(() -> {
                            try {
                                startGate.await();
                                long t0 = System.nanoTime();
                                if (m == 2) {
                                    // 模式2（改造后）：读已发布的快照 ⇒ **不查库**
                                    //   （快照由「每秒一次批量查询」产生 ⇒ 那一次的代价单列）
                                    waits.add(System.nanoTime() - t0);
                                    costs.add(System.nanoTime() - t0);
                                    totalCalls.incrementAndGet();
                                } else if (useLock) {
                                    // 模式0（改造前）：synchronized(全局锁) { 查两次库 }
                                    synchronized (GLOBAL_MONITOR) {
                                        waits.add(System.nanoTime() - t0);
                                        runTwoQueries(idx, jdbcUrl, user, pass, pool, poolWaits,
                                                poolTimeouts, costs, totalCalls, t0);
                                    }
                                } else {
                                    // 模式1：直接查库 ⇒ 1000 线程并发打 DB ⇒ 考验连接池
                                    waits.add(System.nanoTime() - t0);
                                    runTwoQueries(idx, jdbcUrl, user, pass, pool, poolWaits,
                                            poolTimeouts, costs, totalCalls, t0);
                                }
                            } catch (Exception e) {
                                errors.incrementAndGet();
                            } finally {
                                done.countDown();
                            }
                        }, "perf-" + m + "-" + i);
                        t.setDaemon(true);
                        t.start();
                    }
                    long rt0 = System.currentTimeMillis();
                    startGate.countDown();
                    done.await();
                    long roundMs = System.currentTimeMillis() - rt0;
                    roundTimes.add(roundMs);
                    out.printf("  第 %d 轮: %d 台并发，耗时 %d ms（目标周期 %d ms）%s%n",
                            round + 1, devices, roundMs, pollMs,
                            roundMs > pollMs ? "  <-- **超期**" : "");
                    if (errors.get() > 0) {
                        toolBroken = true;
                        failures.add("第 " + (round + 1) + " 轮有 " + errors.get() + " 个查询出错（工具/DB 有问题）");
                    }
                }
            }

            long[] w = waits.stream().mapToLong(Long::longValue).sorted().toArray();
            long[] cs = costs.stream().mapToLong(Long::longValue).sorted().toArray();
            out.println();
            out.println("---------------- 观测 ----------------");
            out.printf(" 等待/排队(ms)       : P50=%.1f P95=%.1f P99=%.1f%n",
                    pct(w, 50) / 1_000_000d, pct(w, 95) / 1_000_000d, pct(w, 99) / 1_000_000d);
            out.printf(" 单次调用总耗时(ms) : P50=%.1f P95=%.1f P99=%.1f%n",
                    pct(cs, 50) / 1_000_000d, pct(cs, 95) / 1_000_000d, pct(cs, 99) / 1_000_000d);
            out.printf(" 连接池             : 借出 %d 次，**借不到(耗尽)=%d 次**%n",
                    poolWaits.get(), poolTimeouts.get());
            out.printf(" 轮耗时(ms)         : %s%n", roundTimes);
            out.println();

            if (poolTimeouts.get() > 0) {
                failures.add("**连接池耗尽**：有 " + poolTimeouts.get() + " 次借不到连接（池大小 " + poolSize
                        + "）⇒ 生产上会表现为 Connection is not available, request timed out");
            }
            for (long rt : roundTimes) {
                if (rt > pollMs) {
                    failures.add("有一轮并发耗时 " + rt + "ms 超过目标周期 " + pollMs + "ms");
                }
            }

        } catch (Exception e) {
            // 压测本身崩了 ⇒ 本次数据不可用（不能当成「性能不达标」，那是两回事）
            toolBroken = true;
            failures.add("压测过程异常: " + e);
            e.printStackTrace();
        } finally {
            // ---------- 4. 清理 ----------
            out.println("[4/4] 清理压测库 ...");
            try {
                Class.forName("com.mysql.cj.jdbc.Driver");
                try (Connection admin = DriverManager.getConnection(
                        jdbcUrl.replace("/" + PERF_DB, "/mysql"), user, pass);
                     Statement st = admin.createStatement()) {
                    st.execute("DROP DATABASE IF EXISTS " + PERF_DB);
                    out.println("  已 DROP " + PERF_DB);
                }
            } catch (Exception e) {
                out.println("  ⚠️ 清理失败: " + e.getMessage() + " （需手工执行 DROP DATABASE " + PERF_DB + "）");
            }
        }

        out.println("---------------- 判定 ----------------");
        if (failures.isEmpty()) {
            out.println(" ✅ 通过");
        } else {
            for (String f : failures) {
                out.println(" ❌ " + f);
            }
            out.println();
            out.println(" 结论：" + (toolBroken ? "【工具坏了】本次数据不可用" : "【性能不达标】"));
        }
        out.println();
        System.exit(failures.isEmpty() ? 0 : 1);
    }

    /** 灌 1000 台设备 + 每台 24 行小时产量（量级对齐真实运行一天） */
    private static void seedDevices(String url, String user, String pass, int devices, int pollMs)
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
                    // 每台 24 行（今天已过的 24 个小时）
                    // ⚠️⚠️ **小时行必须落在「今天之内」**（不是从当前小时往前 23 小时）：
                    //   查询窗口是 [今天0点, now] ⇒ 若行落在昨天，
                    //   窗口里就只有「当前小时」这一行 ⇒ 批量查询只返回 1000 行
                    //   ⇒ **测出来的批量成本会乐观 20 倍**（真实运行一天后每台 24 行）。
                    //   ⇒ 这里按「今天已经过了几个小时」来铺，铺出真实的一天的量。
                    // ⚠️ 行数按**生产窗口**铺（ProdDay.dayStart(now,0) 到 now），
                    //   否则批量查询的行数与生产不符 ⇒ 成本被低估。
                    long prodDayStart = ProdDay.dayStart(now, 0);
                    int rowsPerDevice = Math.max(1,
                            (int) ((now - prodDayStart) / 3600000L) + 1);
                    for (int h = rowsPerDevice - 1; h >= 0; h--) {
                        p.setString(1, code);
                        p.setString(2, code);
                        // 落在今天之内（用自然小时对齐，避免跨天边界）
                        // 落在「生产窗口」内：从 prodDayStart 起的第 h 个小时
                        p.setLong(3, prodDayStart + h * 3600000L);
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

    private static double pct(double[] sorted, int p) {
        if (sorted.length == 0) {
            return 0d;
        }
        int i = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, i))];
    }

    /** long[] 版分位数（纳秒数组用） */
    private static double pct(long[] sorted, int p) {
        if (sorted.length == 0) {
            return 0d;
        }
        int i = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, i))];
    }

    private static int intArg(String[] args, String name, int def) {
        String v = argValue(args, name, null);
        if (v == null) {
            return def;
        }
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " 需要一个整数，收到: " + v);
        }
    }

    private static String argValue(String[] args, String name, String def) {
        for (int i = 0; i < args.length - 1; i++) {
            if (name.equals(args[i])) {
                return args[i + 1];
            }
        }
        return def;
    }


    /**
     * 一次「todayOutputOf」形状的查询：两次查库 + 连接池借用/归还。
     * <p>⚠️ **连接池建模**（方法论关键）：
     * 每次查询都新建一个 JDBC 连接 ⇒ 测的是 TCP+认证握手（~5-20ms），
     * 而**不是** Hikari 的行为（Hikari 复用连接，借还几乎免费）。
     * ⇒ 那会把「池借用」误测成「握手开销」，得出「1000 台不行」的错误结论。
     * ⇒ 这里用**有界连接池 + 借用/归还**模拟 Hikari，池大小与 application.yml 的
     * {@code maximum-pool-size} 一致 ⇒ 顺带能检出**池耗尽**。
     */
    private static void runTwoQueries(int idx, String jdbcUrl, String user, String pass,
                                      ArrayBlockingQueue<Connection> pool,
                                      AtomicInteger poolWaits, AtomicInteger poolTimeouts,
                                      ConcurrentLinkedQueue<Long> costs, AtomicLong totalCalls,
                                      long startedAt) {
        Connection conn = null;
        try {
            conn = pool.poll(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        if (conn == null) {
            // 借不到 = 池耗尽。生产里表现为
            // "Connection is not available, request timed out after 10000ms"
            poolTimeouts.incrementAndGet();
            costs.add(System.nanoTime() - startedAt);
            totalCalls.incrementAndGet();
            return;
        }
        poolWaits.incrementAndGet();
        try {
            try (PreparedStatement p1 = conn.prepareStatement(
                    "SELECT code FROM device WHERE code = ?");
                 PreparedStatement p2 = conn.prepareStatement(
                         "SELECT * FROM prod_hourly WHERE device_code = ? AND hour_start >= ? AND hour_start <= ?")) {
                long now = System.currentTimeMillis();
                // ⚠️ 窗口与生产一致：ProdDay.dayStart(now, rh=0) = 今天 0 点
                long dayStart = ProdDay.dayStart(now, 0);   // ← 直接用生产函数，口径不可能漂
                p1.setString(1, String.format("PERF-%05d", idx));
                try (ResultSet rs = p1.executeQuery()) {
                    while (rs.next()) {
                        rs.getString(1);
                    }
                }
                p2.setString(1, String.format("PERF-%05d", idx));
                p2.setLong(2, dayStart);
                p2.setLong(3, now);
                try (ResultSet rs = p2.executeQuery()) {
                    while (rs.next()) {
                        rs.getLong(1);
                    }
                }
            }
        } catch (Exception e) {
            // 查询出错：计数交给调用方处理
            throw new IllegalStateException(e);
        } finally {
            if (conn != null) {
                pool.offer(conn);
            }
            costs.add(System.nanoTime() - startedAt);
            totalCalls.incrementAndGet();
        }
    }

    private CollectDbScaleBench() {
    }
}
