package com.factory.hub.report;

import com.factory.hub.collect.SnapshotStore;
import com.factory.hub.erp.stock.ErpProdInboundService;
import com.factory.hub.erp.ProdDay;
import com.factory.hub.model.DeviceEntity;
import com.factory.hub.model.PointDef;
import com.factory.hub.model.PointValue;
import com.factory.hub.service.DeviceService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * `ProdRollup.todayOutputOf()` 的<b>基线契约测试</b>。
 *
 * <p><b>为什么必须有它</b>：2026-11-07 的 1000 台压测发现，
 * {@code todayOutputOf} 是全局串行点（方法级 synchronized + 每台每轮两次查库），
 * 1000 台时锁占用率 71.5%。要把它改成「批量查库 + 快照发布」，
 * 就必须先有一份<b>能证明「改造前后数值完全一致」的基线</b> ——
 * 改造前全仓对 {@code todayOutputOf} <b>零测试覆盖</b>（grep 确认），
 * ⇒ 改坏了没人知道，而「今日产量」错了客户会立刻发现（与报表对不上）。
 *
 * <p><b>它钉住的是什么</b>：不是「实现长什么样」，而是<b>数值语义</b> ——
 * 下面 6 条契约，任何一条变了都必须改这个测试并说明原因（不能默默变）。
 *
 * <p><b>四条分支</b>（改造时最容易漏的就是第 3、4 条）：
 * <ol>
 *   <li>DB 有多行 ⇒ 累加</li>
 *   <li>DB 行里<b>当前小时</b>那行 ⇒ 用<b>内存实时值</b>覆盖它</li>
 *   <li>DB <b>没有</b>当前小时行、但内存桶有值 ⇒ 把内存值<b>加</b>上去</li>
 *   <li>DB 有当前小时行、但内存桶<b>无</b>值 ⇒ 保留 DB 的值</li>
 * </ol>
 */
class ProdRollupTodayOutputTest {

    private static final long HOUR_MS = 3_600_000L;

    /** 造一台设备 */
    private static DeviceEntity device(String code, int prodResetHour) {
        DeviceEntity d = new DeviceEntity();
        d.setCode(code);
        d.setName(code);
        d.setProdResetHour(prodResetHour);
        d.setEnabled(true);
        return d;
    }

    /** 造一条小时行 */
    private static ProdHourly row(String code, long hourStart, double output) {
        ProdHourly p = new ProdHourly(code, code, hourStart);
        p.setOutput(output);
        return p;
    }

    /** 往内存桶里塞一个当前小时的累计值（= liveOutput 可见） */
    private static void putLiveBucket(ProdRollup rollup, String code, long curHour, double output) {
        ProdHourly p = new ProdHourly(code, code, curHour);
        p.setOutput(output);
        // 用反射塞进 buckets（bucket() 是 private，且会查库）
        try {
            java.lang.reflect.Field f = ProdRollup.class.getDeclaredField("buckets");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, ProdHourly> buckets = (Map<String, ProdHourly>) f.get(rollup);
            buckets.put(code + "|" + curHour, p);
        } catch (Exception e) {
            throw new IllegalStateException("塞内存桶失败", e);
        }
    }

    /**
     * 造一个 ProdRollup：repo 按设备返回指定的行，deviceService 返回指定设备。
     */
    private static ProdRollup rollupWith(Map<String, List<ProdHourly>> rowsByCode,
                                          Map<String, DeviceEntity> devices) {
        ProdHourlyRepository repo = mock(ProdHourlyRepository.class);
        when(repo.findByDeviceCodeAndHourStartBetween(anyString(), anyLong(), anyLong()))
                .thenAnswer(inv -> {
                    String code = inv.getArgument(0);
                    long from = (Long) inv.getArgument(1);
                    long to = (Long) inv.getArgument(2);
                    List<ProdHourly> rows = rowsByCode.get(code);
                    if (rows == null) {
                        return List.of();
                    }
                    // ⚠️ **必须模拟 JPA 的窗口过滤**（`hourStart >= from AND <= to`）。
                    //   不模拟的话，契约⑤ 测的就不是「生产代码有没有算对起点」，
                    //   而是「我的 mock 有没有听话」⇒ 测了个假的。
                    List<ProdHourly> in = new ArrayList<>();
                    for (ProdHourly p : rows) {
                        if (p.getHourStart() >= from && p.getHourStart() <= to) {
                            in.add(p);
                        }
                    }
                    return in;
                });
        DeviceService deviceService = mock(DeviceService.class);
        when(deviceService.byCode(anyString()))
                .thenAnswer(inv -> devices.get((String) inv.getArgument(0)));

        return new ProdRollup(new SnapshotStore(), deviceService, repo,
                mock(ErpProdInboundService.class), mock(DevCounterStateRepository.class));
    }

    @Test
    @DisplayName("契约①：DB 多行 ⇒ 累加所有行的产量")
    void sumsAllRows() {
        long now = System.currentTimeMillis();
        long curHour = now - (now % HOUR_MS);
        // ⚠️⚠️ **「上一小时」必须用生产同口径（本地时区）构造，不能用 epoch 对齐**：
        //   生产的「今天起点」是 ProdDay.dayStart(now, rh)（本地时区口径），
        //   而 curHour = now - now%HOUR_MS 是 **epoch 对齐** ⇒ 在**非整小时时区**
        //   （如 Asia/Kolkata +5:30）两者错位半小时 ⇒ 「上一小时」可能落在今天起点之前
        //   ⇒ 被正确地裁掉 ⇒ 期望值就错了 ⇒ 本地绿、CI 红的另一个来源。
        //   ⇒ 做法：清零时刻取「当前本地小时 - 1」，「上一小时」= dayStart(now, rh)
        //     ⇒ 它恒 ≥ 今天起点、且恒 ≠ 当前小时（本地整点 ≠ epoch 整点）⇒ 所有时区都成立。
        int rh = (currentLocalHour(now) + 23) % 24;
        long prevHour = ProdDay.dayStart(now, rh);

        Map<String, List<ProdHourly>> rows = new HashMap<>();
        rows.put("D1", List.of(row("D1", prevHour, 10d), row("D1", curHour, 5d)));
        Map<String, DeviceEntity> devs = Map.of("D1", device("D1", rh));

        ProdRollup r = rollupWith(rows, devs);
        // 当前小时没有内存桶 ⇒ 保留 DB 的 5
        assertEquals(15d, r.todayOutputOf("D1"), 1e-9,
                "DB 两行（10 + 5）应当相加，且当前小时没有内存值时保留 DB 的值");
    }

    @Test
    @DisplayName("契约②：DB 当前小时行 ⇒ 用内存实时值覆盖它（不是相加）")
    void liveValueOverridesCurrentHourRow() {
        long now = System.currentTimeMillis();
        long curHour = now - (now % HOUR_MS);
        // ⚠️⚠️ **「上一小时」必须用生产同口径（本地时区）构造，不能用 epoch 对齐**：
        //   生产的「今天起点」是 ProdDay.dayStart(now, rh)（本地时区口径），
        //   而 curHour = now - now%HOUR_MS 是 **epoch 对齐** ⇒ 在**非整小时时区**
        //   （如 Asia/Kolkata +5:30）两者错位半小时 ⇒ 「上一小时」可能落在今天起点之前
        //   ⇒ 被正确地裁掉 ⇒ 期望值就错了 ⇒ 本地绿、CI 红的另一个来源。
        //   ⇒ 做法：清零时刻取「当前本地小时 - 1」，「上一小时」= dayStart(now, rh)
        //     ⇒ 它恒 ≥ 今天起点、且恒 ≠ 当前小时（本地整点 ≠ epoch 整点）⇒ 所有时区都成立。
        int rh = (currentLocalHour(now) + 23) % 24;
        long prevHour = ProdDay.dayStart(now, rh);

        Map<String, List<ProdHourly>> rows = new HashMap<>();
        rows.put("D1", List.of(row("D1", prevHour, 10d), row("D1", curHour, 5d)));
        Map<String, DeviceEntity> devs = Map.of("D1", device("D1", rh));

        ProdRollup r = rollupWith(rows, devs);
        putLiveBucket(r, "D1", curHour, 8d);

        // 10 + 8（8 替换 5，不是 10+5+8）
        assertEquals(18d, r.todayOutputOf("D1"), 1e-9,
                "当前小时的 DB 值要被内存实时值**替换**，不能既算 DB 又加内存");
    }

    @Test
    @DisplayName("契约③：DB 没有当前小时行、但内存桶有值 ⇒ 把内存值加上去")
    void liveValueAddedWhenNoCurrentHourRow() {
        long now = System.currentTimeMillis();
        long curHour = now - (now % HOUR_MS);
        // ⚠️⚠️ **「上一小时」必须用生产同口径（本地时区）构造，不能用 epoch 对齐**：
        //   生产的「今天起点」是 ProdDay.dayStart(now, rh)（本地时区口径），
        //   而 curHour = now - now%HOUR_MS 是 **epoch 对齐** ⇒ 在**非整小时时区**
        //   （如 Asia/Kolkata +5:30）两者错位半小时 ⇒ 「上一小时」可能落在今天起点之前
        //   ⇒ 被正确地裁掉 ⇒ 期望值就错了 ⇒ 本地绿、CI 红的另一个来源。
        //   ⇒ 做法：清零时刻取「当前本地小时 - 1」，「上一小时」= dayStart(now, rh)
        //     ⇒ 它恒 ≥ 今天起点、且恒 ≠ 当前小时（本地整点 ≠ epoch 整点）⇒ 所有时区都成立。
        int rh = (currentLocalHour(now) + 23) % 24;
        long prevHour = ProdDay.dayStart(now, rh);

        Map<String, List<ProdHourly>> rows = new HashMap<>();
        rows.put("D1", List.of(row("D1", prevHour, 10d)));   // 只有上一小时
        Map<String, DeviceEntity> devs = Map.of("D1", device("D1", rh));

        ProdRollup r = rollupWith(rows, devs);
        putLiveBucket(r, "D1", curHour, 7d);

        assertEquals(17d, r.todayOutputOf("D1"), 1e-9,
                "DB 没有当前小时行时，内存实时值要被**加**上去（10 + 7）");
    }

    @Test
    @DisplayName("契约④：设备查不到 ⇒ 返回 0")
    void unknownDeviceReturnsZero() {
        ProdRollup r = rollupWith(new HashMap<>(), Map.of());
        assertEquals(0d, r.todayOutputOf("NOPE"), 1e-9, "设备不存在应当返回 0");
    }

    @Test
    @DisplayName("契约⑤：跨天清零时刻（prodResetHour=8）⇒ 只算 8 点之后的那一天")
    void resetHourTruncatesEarlierRows() {
        // 构造一行「清零时刻之前」与一行「清零时刻之后」。
        // 清零时刻 8 ⇒ 今天从今天 8 点开始算 ⇒ 「8 点之前」那一行**不该**被计入。
        long now = System.currentTimeMillis();
        // ⚠️⚠️ **清零时刻必须动态取「当前本地小时 - 1」，不能写死 8**（2026-11-07 实测踩到）：
        //   写死 8 时，当**当前本地时间落在 [8:00, 9:00)**，
        //   `dayStart(now, 8)`（本地今天 8:00）**恰好等于「当前小时起点」**
        //   ⇒ 那一行会被生产代码判成「当前小时」⇒ 走的是「当前小时」分支而不是「已落库小时」分支
        //   ⇒ 测试断言的口径就悄悄变了（本地绿、CI 红；实测 CI 用 UTC 时区在 08:17 跑 ⇒ 红了）。
        //   ⇒ 修法：清零时刻取「当前本地小时 - 1」⇒ 起点**恒在当前小时之前** ⇒ 断言恒成立。
        int curLocalHour = currentLocalHour(now);
        int rh = (curLocalHour + 23) % 24;
        long dayStart = ProdDay.dayStart(now, rh);
        ProdHourly before = row("D1", dayStart - HOUR_MS, 100d);   // 起点之前 ⇒ 不该算
        ProdHourly after = row("D1", dayStart, 3d);                  // 起点（≠当前小时）⇒ 该算

        Map<String, List<ProdHourly>> rows = new HashMap<>();
        rows.put("D1", List.of(before, after));
        Map<String, DeviceEntity> devs = Map.of("D1", device("D1", rh));

        ProdRollup r = rollupWith(rows, devs);
        double got = r.todayOutputOf("D1");
        // 起点之前那行由 repo 的「按 start..now 过滤」保证不参与；
        // 这里验证 start 传对了：把 100 那行也放进去时，repo 会按 start 过滤掉它。
        assertEquals(3d, got, 1e-9,
                "清零时刻 " + rh + " ⇒ 只该算起点之后的行；起点之前那行（100）不该被计入");
    }

    /**
     * 当前时刻落在**本地时区**的哪个小时（0~23）。
     * <p>为什么要按本地时区算：生产代码的「统计日起点」用
     * {@code ProdDay.dayStart(ts, resetHour)}，它是**本地时区**口径
     * （{@code ZoneId.systemDefault()}）⇒ 测试必须用同一口径。
     */
    private static int currentLocalHour(long now) {
        long localMidnight = ProdDay.dayStart(now, 0);
        return (int) ((now - localMidnight) / HOUR_MS);
    }

    @Test
    @DisplayName("契约⑥：repo 被查询的窗口是 [dayStart(now,rh), now] —— 改造后不能改这个窗口")
    void queryWindowIsDayStartToNow() {
        long now = System.currentTimeMillis();
        long expectedStart = ProdDay.dayStart(now, 8);

        ProdHourlyRepository repo = mock(ProdHourlyRepository.class);
        final long[] captured = new long[2];
        when(repo.findByDeviceCodeAndHourStartBetween(anyString(), anyLong(), anyLong()))
                .thenAnswer(inv -> {
                    captured[0] = (Long) inv.getArgument(1);
                    captured[1] = (Long) inv.getArgument(2);
                    return List.of();
                });
        DeviceService deviceService = mock(DeviceService.class);
        when(deviceService.byCode("D1")).thenReturn(device("D1", 8));

        ProdRollup r = new ProdRollup(new SnapshotStore(), deviceService, repo,
                mock(ErpProdInboundService.class), mock(DevCounterStateRepository.class));
        r.todayOutputOf("D1");

        assertEquals(expectedStart, captured[0],
                "查询窗口起点必须是 dayStart(now, prodResetHour)，不能改成别的");
        assertTrueNear(now, captured[1], "查询窗口终点必须是 now");
    }

    private static void assertTrueNear(long expected, long actual, String msg) {
        long diff = Math.abs(expected - actual);
        assertEquals(true, diff < 2000, msg + "（实际差 " + diff + "ms）");
    }
}
