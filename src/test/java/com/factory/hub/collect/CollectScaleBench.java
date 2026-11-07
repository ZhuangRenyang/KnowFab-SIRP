package com.factory.hub.collect;

import com.factory.hub.driver.DriverContext;
import com.factory.hub.driver.DriverRegistry;
import com.factory.hub.driver.ProtocolDriver;
import com.factory.hub.model.DeviceEntity;
import com.factory.hub.model.PointDef;
import com.factory.hub.web.WsHandler;
import tools.jackson.databind.ObjectMapper;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 采集规模压测（L1：调度层）。
 *
 * <p><b>它回答什么</b>：共享线程池改造之后，「1000 台设备 × 1 秒周期」跑不跑得动。
 *
 * <p><b>它不回答什么</b>：真实 PLC 的网络抖动、真实 MySQL 的并发上限。
 * 那部分由 L2（真实 MySQL）负责。L1 的绿灯<b>不构成</b>「1000 台够用」的结论。
 *
 * <p><b>⚠️ 本工具踩过的坑（照抄它的护栏，别改回去）</b>：
 * <ol>
 *   <li><b>假驱动必须返回递增值</b>：{@code PointValue.sameAs()} 只比 value 与 quality、
 *       <b>不比 ts</b> ⇒ 驱动若返回常量值，第二拍起 {@code changed} 恒空 ⇒
 *       {@code pushValues} 提前 return ⇒ <b>{@code todayOutputOf} 一次都不会被调用</b> ⇒
 *       全局锁那条最热的路径<b>根本没被测到</b>，而压测照样绿。
 *       ⇒ 本工具用 {@code --const-values} 专门跑一次<b>负向对照</b>，它<b>必须红</b>。</li>
 *   <li><b>线程数要在 shutdown 之前数</b>：{@code shutdown()} 会 {@code shutdownNow()} 把池线程杀掉，
 *       关掉之后再数只会得到 0 —— 而 0 &lt;= poolSize 恒成立 ⇒ 又一个假绿。</li>
 *   <li><b>不许用「加总 &gt; 0」判轮次</b>：一台设备采到了就能掩盖另外 999 台饿死。
 *       ⇒ 全部逐台判定。</li>
 *   <li><b>样本要够</b>：早期版本每 100ms 遍历 1000 台取样，20 秒只拿到 403 个样本 ⇒
 *       P95/P99 纯属噪声。⇒ 现在由驱动在<b>每次 read 时</b>顺手记录（O(1) 追加），不轮询。</li>
 * </ol>
 */
public final class CollectScaleBench {

    /** 每台设备保留最近多少轮的起止时刻（用于算轮间隔分布） */
    private static final int ROUNDS_KEPT = 40;

    public static void main(String[] args) throws Exception {
        int devices = intArg(args, "--devices", 1000);
        int poolSize = intArg(args, "--threads", 32);
        int ioDelayMs = intArg(args, "--io-ms", 0);
        int jitterMs = intArg(args, "--jitter-ms", 0);
        int dbDelayMs = intArg(args, "--db-ms", 0);
        int pollMs = intArg(args, "--poll-ms", 1000);
        int warmupSec = intArg(args, "--warmup", 30);
        int measureSec = intArg(args, "--measure", 120);
        boolean constValues = hasFlag(args, "--const-values");
        boolean expectFail = hasFlag(args, "--expect-fail");

        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        out.println();
        out.println("=========================================================");
        out.println(" 采集规模压测 L1（调度层）");
        out.println("=========================================================");
        out.printf(" 设备数=%d 池=%d I/O=%dms 抖动=%dms 模拟DB=%dms 目标周期=%dms%n",
                devices, poolSize, ioDelayMs, jitterMs, dbDelayMs, pollMs);
        out.printf(" 预热=%ds 测量=%ds 常量值(负向对照)=%s%n", warmupSec, measureSec, constValues);
        printEnv(out);

        // ---------------- 设备 ----------------
        List<DeviceEntity> list = new ArrayList<>();
        Map<String, Integer> codeIndex = new LinkedHashMap<>();
        for (int i = 0; i < devices; i++) {
            String code = String.format("BENCH-%05d", i);
            list.add(device(code, pollMs));
            codeIndex.put(code, i);
        }

        // ---------------- 采集记录 ----------------
        // 每轮 read 的 [设备序号, 开始ms, 结束ms]
        Queue<long[]> rounds = new ConcurrentLinkedQueue<>();
        // 每次 todayOutputOf 的锁等待(ns) 与 线程占用时长(ns)
        Queue<Long> lockWaits = new ConcurrentLinkedQueue<>();
        // ⚠️ 必须**按设备**记录 DB 段耗时，否则算不出「单轮线程占用」——
        //   线程占用 = I/O 耗时 + DB 段耗时，而两者要**逐轮配对**才准。
        //   todayOutputOf(code) 收到设备 code ⇒ 能映射回设备序号。
        Queue<long[]> dbByDevice = new ConcurrentLinkedQueue<>();
        AtomicLong todayOutputCalls = new AtomicLong();
        AtomicInteger readCount = new AtomicInteger();

        // ---------------- 驱动 ----------------
        com.factory.hub.service.DeviceService deviceService = mock(com.factory.hub.service.DeviceService.class);
        when(deviceService.enabledDevices()).thenReturn(list);
        when(deviceService.pointsOf(any())).thenReturn(points());
        when(deviceService.contextOf(any())).thenAnswer(inv -> {
            DriverContext c = new DriverContext();
            DeviceEntity d = (DeviceEntity) inv.getArgument(0);
            c.setCode(d.getCode());
            return c;
        });

        DriverRegistry registry = mock(DriverRegistry.class);
        Random rnd = new Random(20261107L);
        // 每次 create 返回**新**驱动实例 ⇒ 每台设备一个独立计数器（否则所有设备共享一个计数，
        // 值会一直变、changed 永远非空 —— 那就测不出 D-1 陷阱了）
        when(registry.create(anyString())).thenAnswer(inv -> {
            ProtocolDriver drv = new BenchDriver(ioDelayMs, jitterMs, constValues, rnd, rounds, readCount, codeIndex);
            return drv;
        });

        SnapshotStore store = new SnapshotStore();
        WsHandler ws = mock(WsHandler.class);

        // ---------------- ProdRollup 替身 ----------------
        // ⚠️ 用 Mockito 的 mock 会让方法**失去 synchronized 语义**（Mockito 生成的 override 没有
        //   synchronized 修饰符）⇒ 拿不到真实的锁竞争。
        //   ⇒ 这里在 Answer 内部**自己加一把共享锁**，忠实复刻 ProdRollup 单例 monitor 的两个特征：
        //      ① 整个方法体在锁内 ② 锁内做一次"DB 查询"（用 sleep 模拟其耗时）。
        //   这样 L1 能暴露「锁饱和 ⇒ 采集周期被拉长」；真值仍由 L2 用真实 MySQL 测。
        final Object prodRollupMonitor = new Object();
        com.factory.hub.report.ProdRollup rollup = mock(com.factory.hub.report.ProdRollup.class);
        when(rollup.todayOutputOf(anyString())).thenAnswer(inv -> {
            todayOutputCalls.incrementAndGet();
            String code = inv.getArgument(0, String.class);
            Integer didx = codeIndex.get(code);
            long t0 = System.nanoTime();
            synchronized (prodRollupMonitor) {
                long waited = System.nanoTime() - t0;
                lockWaits.add(waited);
                if (dbDelayMs > 0) {
                    sleepMs(dbDelayMs);
                }
                dbByDevice.add(new long[]{didx == null ? -1 : didx,
                        waited, System.nanoTime() - t0});
            }
            return 0.0d;
        });

        CollectScheduler scheduler =
                new CollectScheduler(deviceService, registry, store, ws, new ObjectMapper(), rollup, poolSize);
        scheduler.reload();

        // ---------------- 预热 ----------------
        Thread.sleep(warmupSec * 1000L);

        // ---------------- 测量 ----------------
        long t0 = System.currentTimeMillis();
        long[] roundsMark = {count(rounds)};
        long callsMark = todayOutputCalls.get();
        long readMark = readCount.get();

        // 堆采样（判断是否有泄漏：稳态后连续 3 次采样增幅 <= 5%）
        List<Long> heapSamples = new ArrayList<>();
        Thread heapSampler = new Thread(() -> {
            Runtime rt = Runtime.getRuntime();
            while (true) {
                heapSamples.add(rt.totalMemory() - rt.freeMemory());
                try {
                    Thread.sleep(10_000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "bench-heap");
        heapSampler.setDaemon(true);
        heapSampler.start();

        Thread.sleep(measureSec * 1000L);
        long elapsedMs = System.currentTimeMillis() - t0;
        heapSampler.interrupt();

        // ⚠️ **线程数必须在 shutdown 之前数** —— shutdown 会 shutdownNow() 杀掉池线程，
        //   关掉之后再数只会得到 0，而「0 <= poolSize」恒成立 ⇒ 又一个假绿。
        int collectThreads = 0;
        int poolThreads = 0;
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (!t.getName().startsWith("collect") || !t.isAlive()) {
                continue;
            }
            collectThreads++;
            if (t.getName().startsWith("collect-pool")) {
                poolThreads++;
            }
        }
        scheduler.shutdown();

        // ---------------- 逐台统计 ----------------
        long readsInWindow = readCount.get() - readMark;
        long callsInWindow = todayOutputCalls.get() - callsMark;

        // 轮间隔：按设备分组，算相邻两轮开始时刻之差
        long[] roundsByDevice = new long[devices];
        Map<Integer, List<long[]>> byDevice = new LinkedHashMap<>();
        long[][] all = rounds.toArray(new long[0][]);
        for (long[] r : all) {
            if (r[0] < 0 || r[0] >= devices) {
                continue;
            }
            byDevice.computeIfAbsent((int) r[0], k -> new ArrayList<>()).add(r);
        }
        long[] allGaps = new long[Math.max(1, byDevice.size() * (ROUNDS_KEPT - 1))];
        int gi = 0;
        int noData = 0;
        long minRounds = Long.MAX_VALUE;
        long maxRounds = 0;
        long sumRounds = 0;
        for (int i = 0; i < devices; i++) {
            List<long[]> rs = byDevice.get(i);
            if (rs == null || rs.isEmpty()) {
                noData++;
                roundsByDevice[i] = 0;
                continue;
            }
            // 只保留测量窗口内的轮次
            List<long[]> inWin = new ArrayList<>();
            for (long[] r : rs) {
                if (r[1] >= t0) {
                    inWin.add(r);
                }
            }
            roundsByDevice[i] = inWin.size();
            minRounds = Math.min(minRounds, inWin.size());
            maxRounds = Math.max(maxRounds, inWin.size());
            sumRounds += inWin.size();
            if (inWin.size() < 2) {
                continue;
            }
            long[] gaps = new long[inWin.size() - 1];
            for (int k = 1; k < inWin.size(); k++) {
                gaps[k - 1] = inWin.get(k)[1] - inWin.get(k - 1)[1];
            }
            for (long g : gaps) {
                if (gi < allGaps.length) {
                    allGaps[gi++] = g;
                }
            }
        }
        long[] gaps = Arrays.copyOf(allGaps, gi);
        Arrays.sort(gaps);
        if (minRounds == Long.MAX_VALUE) {
            minRounds = 0;
        }

        // ⚠️ **单轮线程占用 = I/O 耗时 + DB 段耗时**（DB 段含等锁，
        //   因为线程在等锁时同样占着工作线程，不能算空闲）。
        //   早期版本只统计了 DB 段（I/O=0 时算出 0ms）⇒ 容量公式 N=1 ⇒ **公式形同虚设**。
        //   ⇒ 现在按设备把 IO 段与 DB 段**逐轮配对**。
        Map<Integer, List<long[]>> dbPerDevice = new LinkedHashMap<>();
        for (long[] d : dbByDevice.toArray(new long[0][])) {
            if (d[0] < 0 || d[0] >= devices) {
                continue;
            }
            dbPerDevice.computeIfAbsent((int) d[0], k -> new ArrayList<>()).add(d);
        }
        List<Double> occList = new ArrayList<>();
        for (int i = 0; i < devices; i++) {
            List<long[]> rs = byDevice.get(i);
            List<long[]> ds = dbPerDevice.get(i);
            if (rs == null || ds == null) {
                continue;
            }
            int n = Math.min(rs.size(), ds.size());
            for (int k = 0; k < n; k++) {
                double io = rs.get(k)[2] - rs.get(k)[1];
                double db = ds.get(k)[2] / 1_000_000d;
                occList.add(io + db);
            }
        }
        double[] occArr = occList.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        double p95Occ = pct(occArr, 95);
        double p99Occ = pct(occArr, 99);
        double p95Io = pctOfRounds(all, 95) ;
        double p99Io = pctOfRounds(all, 99);

        long[] waits = lockWaits.stream().mapToLong(Long::longValue).sorted().toArray();
        double p50Wait = pct(waits, 50) / 1_000_000d;
        double p99Wait = pct(waits, 99) / 1_000_000d;

        double p50Gap = pct(gaps, 50);
        double p95Gap = pct(gaps, 95);
        double p99Gap = pct(gaps, 99);

        // 容量公式：N = ceil(设备数 × P95(线程占用ms) / 目标周期ms)
        int neededThreads = (int) Math.ceil(devices * p95Occ / pollMs);

        double expectedRounds = (double) elapsedMs / pollMs;
        long lostRounds = 0;
        for (int i = 0; i < devices; i++) {
            double diff = expectedRounds - roundsByDevice[i];
            if (diff > 0) {
                lostRounds += (long) diff;
            }
        }
        double lostRate = expectedRounds <= 0 ? 0 : (double) lostRounds / (expectedRounds * devices);

        double cpuSeconds = processCpuSeconds();
        double cpuLoad = cpuSeconds >= 0 ? cpuSeconds / (elapsedMs / 1000d) * 100d : -1d;

        // ---------------- 输出 ----------------
        out.println("---------------- 观测 ----------------");
        out.printf(" 测量时长              : %d ms（期望轮数/台 = %.1f）%n", elapsedMs, expectedRounds);
        out.printf(" 测量窗口内 read 次数  : %d（%.1f 次/秒）%n", readsInWindow, readsInWindow * 1000d / elapsedMs);
        out.printf(" todayOutputOf 调用次数 : %d（期望 >= %.0f）%n", callsInWindow,
                devices * expectedRounds * 0.9);
        out.printf(" 每台轮数              : 最小=%d 最大=%d 平均=%.1f；**完全没数据 %d 台**%n",
                minRounds, maxRounds, devices == 0 ? 0 : (double) sumRounds / devices, noData);
        out.printf(" 轮间隔(ms)            : P50=%.0f P95=%.0f P99=%.0f  (样本 %d)%n",
                p50Gap, p95Gap, p99Gap, gaps.length);
        out.printf(" 单轮 I/O 耗时(ms)     : P95=%.1f P99=%.1f%n", p95Io, p99Io);
        out.printf(" 线程占用(ms)          : P95=%.1f P99=%.1f（I/O + DB段，DB段含等锁）%n", p95Occ, p99Occ);
        out.printf(" **全局锁等待(ms)**      : P50=%.2f P99=%.2f%n", p50Wait, p99Wait);
        out.printf(" 丢轮率                : %.3f%%（目标 <= 0.5%%）%n", lostRate * 100);
        out.printf(" **采集线程数**          : 共 %d（池 %d）%n", collectThreads, poolThreads);
        out.printf(" **容量公式 N**          : ceil(%d × %.1fms / %dms) = %d  (池大小 %d)%n",
                devices, p95Occ, pollMs, neededThreads, poolSize);
        out.printf(" 进程 CPU               : %s%n", cpuSeconds >= 0
                ? String.format("%.1f s / %.1f%% 单核占用（目标 <= 70%%）", cpuSeconds, cpuLoad)
                : "**不可用**（本 JVM 读不到进程 CPU 时间 ⇒ 这一项本次没验，不当 0% 算）");
        out.printf(" 堆(MB)                 : %s%n", heapTrend(heapSamples));
        out.println();

        // 饿死统计必须在**打印与判定之前**算好，且不受 toolBroken 影响 ——
        // 否则报告里会出现「先打印 0 台、后打印 312 台」两行自相矛盾的数字，
        // 而读报告的人往往只看第一行 ⇒ 报告层也能伪造绿灯（QA-2 / QA-10）。
        long half = maxRounds / 2;
        int starved = 0;
        for (int i = 0; i < devices; i++) {
            if (roundsByDevice[i] < half) {
                starved++;
            }
        }
        out.printf(" **饥饿设备(逐台判定)**: %d 台（轮数 < 最大轮数的一半 = %d）%n", starved, half);

        // ---------------- 判定 ----------------
        List<String> failures = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        boolean toolBroken = false;

        // D-1 护栏：路径必须真的被走到
        //
        // ⚠️ 语义要分清（对称于「注入已知错误 → 必须 exit 1」）：
        //   常量值场景下 todayOutputOf 调用次数 ≈ 0 ⇒ 说明**全局锁路径没被走到**
        //   ⇒ 本次数据无效 ⇒ 这本身就是「红」。
        //   所以 const-values 场景**必须红**；--expect-fail 只是把这个「红」
        //   解释成「护栏按预期生效」并转成 exit 0。
        //   反过来，若常量值场景下调用次数却很多 ⇒ D-1 陷阱假设不成立
        //   ⇒ 护栏场景本身失效 ⇒ 无论有没有 --expect-fail 都必须 exit 2。
        boolean negativeControlWorked = false;
        if (constValues) {
            if (callsInWindow > readsInWindow * 0.98) {
                failures.add("【负向对照失效】常量值场景下 todayOutputOf 仍被大量调用（" + callsInWindow
                        + "）⇒ D-1 陷阱假设不成立，护栏场景无效");
            } else {
                negativeControlWorked = true;
                notes.add("【负向对照生效】常量值 ⇒ todayOutputOf 调用 " + callsInWindow
                        + " 次（接近 0）⇒ 已实测确认：常量值驱动会完全绕过全局锁路径");
                failures.add("【预期】常量值场景下全局锁路径未被走到（调用 " + callsInWindow
                        + " 次，期望 >= " + (long) (devices * expectedRounds * 0.9)
                        + "）⇒ 本次数据无效，这是 D-1 陷阱的预期表现");
            }
        } else {
            // ⚠️⚠️ **判据必须是 calls : reads ≈ 1:1，不能是「calls vs 期望轮数」**。
            //   早期版本用后者，结果 S3（I/O=50ms）明明 calls=74406 == reads=74406
            //   （路径**确实走到了**），却被判成「路径没走到」⇒ 报告把
            //   **超载**误报成**工具坏了** ⇒ 结论完全指错方向。
            //   原因：超载时每台设备完成的轮数本来就少于期望轮数，
            //   所以 calls 必然小于「设备数 × 期望轮数」—— 拿它当判据是错的。
            //   ⇒ 正确判据：每次 read 产生变化后必调一次 todayOutputOf ⇒ 比值 ≈ 1。
            double callRatio = readsInWindow <= 0 ? 0 : (double) callsInWindow / readsInWindow;
            out.printf(" **calls:reads 比值**     : %.3f（应 ≈ 1.0，每轮必调一次）%n", callRatio);
            if (readsInWindow > 0 && callRatio < 0.98) {
                toolBroken = true;
                failures.add(String.format("todayOutputOf 调用/read 比值 %.3f < 0.98"
                        + " ⇒ **全局锁路径没被每轮走到**，本次其余结论全部作废"
                        + "（检查假驱动是否返回了递增值）", callRatio));
            }
        }

        if (noData > 0) {
            toolBroken = true;
            failures.add("有 " + noData + " 台设备在测量窗口内一轮都没采到 —— "
                    + "这是**工具/调度坏了**，不是「系统慢」");
        }
        // D-2 护栏：线程数（且必须 >= 1，不能是关掉后数出来的 0）
        if (collectThreads > poolSize) {
            failures.add("采集线程数 " + collectThreads + " > 池大小 " + poolSize
                    + " —— 又退回「一台设备一个线程」的老写法了");
        }
        if (collectThreads < 1 || poolThreads < 1) {
            toolBroken = true;
            failures.add("采集线程数 " + collectThreads + "（池 " + poolThreads
                    + "）⇒ 采集压根没在跑（**注意：线程数必须在 shutdown 之前数**，"
                    + "关掉池之后再数只会得到 0，那个 0 是个假绿）");
        }
        if (gaps.length < devices) {
            toolBroken = true;
            failures.add("轮间隔样本只有 " + gaps.length + " 条（设备数 " + devices
                    + "）⇒ 样本不足以算分位数，P95/P99 不可信");
        }

        if (!toolBroken && !constValues) {
            // L1-1.1~1.3 周期
            if (p50Gap > pollMs * 1.05) {
                failures.add(String.format("轮间隔 P50 = %.0fms 超过目标周期 %dms 的 5%%", p50Gap, pollMs));
            }
            if (p95Gap > pollMs * 1.10) {
                failures.add(String.format("轮间隔 P95 = %.0fms 超过目标周期 %dms 的 10%%", p95Gap, pollMs));
            }
            if (p99Gap > pollMs * 1.50) {
                failures.add(String.format("轮间隔 P99 = %.0fms 超过目标周期 %dms 的 50%%", p99Gap, pollMs));
            }
            // L1-1.4 丢轮率（逐台，不许加总掩盖）
            if (cpuLoad >= 0 && cpuLoad > 70d) {
                failures.add(String.format("CPU 单核占用 %.1f%% 超过 70%%", cpuLoad));
            }
            if (lostRate > 0.005) {
                failures.add(String.format("丢轮率 %.3f%% 超过 0.5%%", lostRate * 100));
            }
            // L1-1.6 容量公式（硬判据）
            if (neededThreads > poolSize) {
                failures.add("**容量不足**：按实测 P95 线程占用 " + String.format("%.1f", p95Occ)
                        + "ms 算，1000 台需要 " + neededThreads + " 个线程 > 池大小 " + poolSize
                        + " ⇒ 线程池是瓶颈（加线程或降周期，二选一）");
            }
            // L1-1.7 全局锁等待
            if (p99Wait > 50d) {
                failures.add(String.format("**全局锁等待 P99 = %.2fms > 50ms** ⇒ 瓶颈是 ProdRollup 的 monitor，"
                        + "不是线程池（加线程只会加剧竞争）", p99Wait));
            }
            if (starved > 0) {
                failures.add("有 " + starved + " 台设备被饿死（轮数不到最快设备的一半 = " + half + "）");
            }
        }

        out.println("---------------- 判定 ----------------");
        for (String n : notes) {
            out.println(" [注] " + n);
        }
        boolean failed = !failures.isEmpty();
        if (!failed) {
            out.println(" ✅ 通过");
        } else {
            for (String f : failures) {
                out.println(" ❌ " + f);
            }
            out.println();
            out.println(" 结论：" + (toolBroken ? "【工具坏了】先修工具，本次数据不可用"
                    : "【性能不达标】"));
        }
        out.println();

        if (expectFail) {
            // 负向对照场景：期望它「红」。红 ⇒ 护栏按预期生效 ⇒ exit 0。
            // 若它绿 ⇒ 护栏没生效 ⇒ 整轮无效 ⇒ exit 2。
            if (!negativeControlWorked) {
                out.println(" 负向对照：**场景本身失效**（常量值下调用次数仍很多）"
                        + " ⇒ D-1 陷阱假设不成立 ⇒ 本轮全部结论作废");
                System.exit(2);
            }
            if (failed) {
                out.println(" 负向对照：如期变红 ✅（exit 0 = 护栏按预期生效）");
                System.exit(0);
            }
            out.println(" 负向对照：**没有变红** ⇒ 护栏没生效，整轮无效");
            System.exit(2);
        }
        System.exit(failed ? 1 : 0);
    }

    // ------------------------------------------------------------------ 辅助

    /** 单轮 I/O 耗时的分位数（毫秒） */
    private static double pctOfRounds(long[][] rounds, int p) {
        if (rounds.length == 0) {
            return 0d;
        }
        double[] costs = new double[rounds.length];
        for (int i = 0; i < rounds.length; i++) {
            costs[i] = rounds[i][2] - rounds[i][1];
        }
        Arrays.sort(costs);
        return costs[Math.max(0, Math.min(costs.length - 1,
                (int) Math.ceil(p / 100.0 * costs.length) - 1))];
    }

    private static double pct(long[] sorted, int p) {
        if (sorted.length == 0) {
            return 0d;
        }
        int i = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, i))];
    }

    /** double[] 版分位数（毫秒小数数组用） */
    private static double pct(double[] sorted, int p) {
        if (sorted.length == 0) {
            return 0d;
        }
        int i = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, i))];
    }

    private static long count(Queue<long[]> q) {
        return q.size();
    }

    /** 堆趋势：稳态后连续 3 次采样的增幅 */
    private static String heapTrend(List<Long> samples) {
        if (samples.size() < 4) {
            return "样本不足（" + samples.size() + "）";
        }
        StringBuilder sb = new StringBuilder();
        for (long s : samples) {
            sb.append(s / 1024 / 1024).append("MB ");
        }
        long a = samples.get(samples.size() - 4);
        long b = samples.get(samples.size() - 1);
        double growth = a <= 0 ? 0 : (b - a) * 100d / a;
        sb.append(String.format("| 尾段增幅 %.1f%%", growth));
        return sb.toString();
    }

    /**
     * 进程 CPU 秒数（归一到单核）。
     * <p>⚠️ 不用反射调 {@code OperatingSystemMXBean.getProcessCpuTime()}：
     * JDK 9+ 模块系统会拦（"does not exports sun.management"）⇒ 读到 -1，
     * 而 -1 会被打印成「-1.0%」—— **看起来像个数字，其实是没读到**，
     * 这正是「读不到 ≠ 没有」的坑。
     * ⇒ 用 {@link java.lang.management.ThreadMXBean} 的公开 API
     * （{@code getCurrentThreadCpuTime} 只算本线程，不够）⇒
     * 用 {@code com.sun.management.OperatingSystemMXBean} 的公开接口
     * （JDK 提供，无需反射 setAccessible）。
     */
    private static double processCpuSeconds() {
        try {
            java.lang.management.OperatingSystemMXBean os =
                    java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            if (os instanceof com.sun.management.OperatingSystemMXBean sun) {
                return sun.getProcessCpuTime() / 1_000_000_000d;
            }
        } catch (Throwable ignored) {
            // 读不到就返回 -1，下面会明确打印「不可用」而不是当成 0
        }
        return -1d;
    }

    private static void printEnv(PrintStream out) {
        // ⚠️ 不要用反射调 getAvailableProcessors()：JDK 9+ 的模块系统会拦
        //   （"module java.management does not exports sun.management"）⇒ 读不到。
        //   Runtime.availableProcessors() 是公开 API，不需要反射。
        out.printf(" CPU 核数=%d  JVM=%s  堆上限=%dMB%n",
                Runtime.getRuntime().availableProcessors(), System.getProperty("java.version"),
                Runtime.getRuntime().maxMemory() / 1024 / 1024);
    }

    /**
     * 带可控延迟的假驱动。
     * <p>延迟用 {@link Thread#sleep(long, int)} 而不是 {@code Thread.sleep(long)}：
     * 后者在 Windows 上精度差到十几毫秒，会把「模拟 10ms」变成「实际 16ms」。
     * <p>⚠️ <b>值必须递增</b>：{@code PointValue.sameAs()} 只比值不比 ts ⇒
     * 返回常量值会让 {@code changed} 恒空 ⇒ {@code todayOutputOf} 一次都不被调用。
     */
    private static final class BenchDriver implements ProtocolDriver {
        private final int ioDelayMs;
        private final int jitterMs;
        private final boolean constValues;
        private final Random rnd;
        private final Queue<long[]> rounds;
        private final AtomicInteger readCount;
        private final Map<String, Integer> codeIndex;
        private final AtomicInteger counter = new AtomicInteger();
        private boolean connected;

        BenchDriver(int ioDelayMs, int jitterMs, boolean constValues, Random rnd,
                    Queue<long[]> rounds, AtomicInteger readCount, Map<String, Integer> codeIndex) {
            this.ioDelayMs = ioDelayMs;
            this.jitterMs = jitterMs;
            this.constValues = constValues;
            this.rnd = rnd;
            this.rounds = rounds;
            this.readCount = readCount;
            this.codeIndex = codeIndex;
        }

        @Override
        public String type() {
            return "modbus-tcp";
        }

        @Override
        public void connect(DriverContext ctx) {
            connected = true;
        }

        @Override
        public boolean isConnected() {
            return connected;
        }

        @Override
        public Map<String, Object> read(DriverContext ctx, List<PointDef> points) {
            long t0 = System.currentTimeMillis();
            if (ioDelayMs > 0) {
                sleepMs(ioDelayMs);
            }
            if (jitterMs > 0) {
                sleepMs(rnd.nextInt(jitterMs + 1));
            }
            int v = constValues ? 1 : counter.incrementAndGet();
            Map<String, Object> raw = new java.util.LinkedHashMap<>();
            for (PointDef p : points) {
                raw.put(p.getKey(), (double) v);
            }
            long t1 = System.currentTimeMillis();
            Integer idx = codeIndex.get(ctx.getCode());
            rounds.add(new long[]{idx == null ? -1 : idx, t0, t1});
            readCount.incrementAndGet();
            return raw;
        }

        @Override
        public void write(DriverContext ctx, PointDef point, Object value) {
        }

        @Override
        public void disconnect() {
            connected = false;
        }
    }

    private static void sleepMs(int ms) {
        try {
            Thread.sleep(ms, 200_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static DeviceEntity device(String code, int pollMs) {
        DeviceEntity d = new DeviceEntity();
        d.setCode(code);
        d.setName(code);
        d.setProtocol("modbus-tcp");
        d.setHost("127.0.0.1");
        d.setPort(502);
        d.setPollIntervalMs(pollMs);
        d.setOfflineAfterFails(3);
        d.setTimeoutMs(200);
        d.setEnabled(true);
        return d;
    }

    private static List<PointDef> points() {
        PointDef p = new PointDef();
        p.setKey("p1");
        p.setRole("status");
        return List.of(p);
    }

    private static int intArg(String[] args, String name, int def) {
        for (int i = 0; i < args.length - 1; i++) {
            if (name.equals(args[i])) {
                try {
                    return Integer.parseInt(args[i + 1]);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(name + " 需要一个整数，收到: " + args[i + 1]);
                }
            }
        }
        return def;
    }

    private static boolean hasFlag(String[] args, String name) {
        for (String a : args) {
            if (name.equals(a)) {
                return true;
            }
        }
        return false;
    }

    private CollectScaleBench() {
    }
}
