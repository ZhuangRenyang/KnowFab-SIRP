package com.factory.hub.report;

import com.factory.hub.alarm.AlarmEventRepository;
import com.factory.hub.manual.ManualEntry;
import com.factory.hub.manual.ManualEntryService;
import com.factory.hub.service.DeviceService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link ReportService} 的**口径契约**测试（2026-11-07 补）。
 * <p>
 * 为什么补：这个类 808 行、是全部报表（日 / 区间 / 月 / OEE）的唯一出口，
 * 但**此前 0 个测试**。它算错的后果不是报错，是**客户拿着错数字去对账**。
 * <p>
 * 这批测试钉的是**口径**，不是覆盖率：
 * <ul>
 *   <li>时间窗口用**本地时区**而不是 UTC 零点（中国 +8 差 8 小时，整天的数就错了）</li>
 *   <li>窗口是**半开区间** {@code [start, end)}，相邻两天必须首尾相接（不重不漏）</li>
 *   <li>舍入是 {@code Math.round} 四舍五入，不是银行家舍入</li>
 *   <li>补录并入总量时**同时单列**，两者不能混为一谈（DH-13）</li>
 *   <li>数据不足一分钟**不给稼动率**（返回 -1），而不是给 0%</li>
 * </ul>
 * <p>
 * ⚠️ 时区无关性：本测试**不断言窗口的毫秒长度**（有夏令时的时区在切换日是 23/25 小时），
 * 只断言「落在本地日历的哪一天、哪一刻」⇒ 在任何时区跑都成立。
 * 这与 {@code tools/verify-hour-independence.mjs} 是同一条纪律。
 */
class ReportServiceContractTest {

    private static final ZoneId ZONE = ZoneId.systemDefault();

    /** 只测纯计算的方法：依赖全部 mock 掉，报告里传 null 会在调用时才炸 */
    private ReportService svc() {
        return new ReportService(
                mock(com.factory.hub.report.ProdHourlyRepository.class),
                mock(ShiftStore.class),
                mock(AlarmEventRepository.class),
                mock(DeviceService.class),
                mock(ManualEntryService.class));
    }

    // ==================== 时间窗口口径 ====================

    @Test
    @DisplayName("dayWindow：起点是**本地时区**当天 00:00:00.000，不是 UTC 零点")
    void dayWindowStartsAtLocalMidnight() {
        LocalDate d = LocalDate.of(2026, 3, 1);
        long[] w = svc().dayWindow(d);

        var startLocal = Instant.ofEpochMilli(w[0]).atZone(ZONE);
        assertEquals(d, startLocal.toLocalDate(), "起点的本地日期必须是当天");
        assertEquals(LocalTime.MIDNIGHT, startLocal.toLocalTime(), "起点必须是本地 00:00");
        assertEquals(0, startLocal.getNano(), "起点毫秒必须归零");
    }

    @Test
    @DisplayName("dayWindow：终点是**次日**本地 00:00，即窗口是半开区间 [start, end)")
    void dayWindowEndsAtNextLocalMidnight() {
        LocalDate d = LocalDate.of(2026, 3, 1);
        long[] w = svc().dayWindow(d);

        var endLocal = Instant.ofEpochMilli(w[1]).atZone(ZONE);
        assertEquals(d.plusDays(1), endLocal.toLocalDate(), "终点必须是次日的本地 00:00（半开区间）");
        assertEquals(LocalTime.MIDNIGHT, endLocal.toLocalTime());
        assertTrue(w[1] > w[0], "终点必须大于起点");
    }

    @Test
    @DisplayName("dayWindow：相邻两天**首尾相接**（前一天的终点 == 后一天的起点，不重不漏）")
    void adjacentDaysAreContiguous() {
        LocalDate d = LocalDate.of(2026, 3, 1);
        long[] today = svc().dayWindow(d);
        long[] tomorrow = svc().dayWindow(d.plusDays(1));

        assertEquals(today[1], tomorrow[0],
                "前一天的终点必须等于后一天的起点 —— 否则区间报表会漏一小时或重复统计");
    }

    @Test
    @DisplayName("dayWindow：跨月 / 跨年边界正确（12-31 的次日是次年 1-1）")
    void dayWindowHandlesYearBoundary() {
        long[] w = svc().dayWindow(LocalDate.of(2026, 12, 31));

        assertEquals(LocalDate.of(2026, 12, 31), Instant.ofEpochMilli(w[0]).atZone(ZONE).toLocalDate());
        assertEquals(LocalDate.of(2027, 1, 1), Instant.ofEpochMilli(w[1]).atZone(ZONE).toLocalDate());
    }

    // ==================== 舍入口径 ====================

    @Test
    @DisplayName("round1：四舍五入到 1 位小数（用 Math.round，不是银行家舍入）")
    void round1RoundsHalfUp() {
        assertEquals(1.3, ReportService.round1(1.25), 1e-9, "1.25 → 1.3（四舍五入，不是 1.2）");
        assertEquals(1.2, ReportService.round1(1.24), 1e-9);
        assertEquals(1.3, ReportService.round1(1.253), 1e-9);
        assertEquals(0.0, ReportService.round1(0.0), 1e-9);
    }

    @Test
    @DisplayName("round1：负值向零方向取整（Math.round(-12.5) = -12，与正数不对称）")
    void round1NegativeValues() {
        // ⚠️ 这不是笔误：Java 的 Math.round 是 floor(x + 0.5) ⇒ -1.25 → -1.2 而不是 -1.3
        //    钉住它是为了让「改了舍入实现」这件事被看见，而不是悄悄改掉报表数字
        assertEquals(-1.2, ReportService.round1(-1.25), 1e-9);
        assertEquals(-1.3, ReportService.round1(-1.26), 1e-9);
    }

    // ==================== 补录并入口径（DH-13）====================

    @Test
    @DisplayName("daily：补录**并入**总量，同时**单列** manualOutput（两者不能混为一谈）")
    void manualOutputIsAddedAndAlsoListedSeparately() {
        var repo = mock(com.factory.hub.report.ProdHourlyRepository.class);
        var shiftStore = mock(ShiftStore.class);
        var manualService = mock(ManualEntryService.class);

        ShiftPlan plan = new ShiftPlan();
        plan.setShifts(List.of());
        when(shiftStore.current()).thenReturn(plan);

        ProdHourly collected = new ProdHourly("DEV-1", "一号机", 0L);
        collected.setOutput(100d);
        when(repo.findInWindow(anyLong(), anyLong())).thenReturn(List.of(collected));

        ManualEntry manual = new ManualEntry();
        // ⚠️ day 必须设：manualRows() 里 e.getDay().toString() 会解引用
        //    （真实数据由 activeOfDay(date) 查出来，day 必然非空；这里是在造数据）
        manual.setDay(LocalDate.of(2026, 3, 1));
        manual.setDeviceCode("DEV-2");
        manual.setOutput(30d);
        when(manualService.activeOfDay(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(manual));

        ReportService service = new ReportService(repo, shiftStore,
                mock(AlarmEventRepository.class), mock(DeviceService.class), manualService);

        Map<String, Object> out = service.daily(LocalDate.of(2026, 3, 1));
        @SuppressWarnings("unchecked")
        Map<String, Object> totals = (Map<String, Object>) out.get("totals");

        assertNotNull(totals);
        assertEquals(130.0, num(totals.get("output")), 1e-9,
                "总量必须是 采集 100 + 补录 30 = 130（补录要算进产量）");
        assertEquals(30.0, num(totals.get("manualOutput")), 1e-9,
                "同时必须单列「其中补录 30」—— 不能和采集混成一个数（DH-13）");
    }

    @Test
    @DisplayName("daily：数据不足一分钟**不给稼动率**（返回 -1，不是 0%）")
    void availabilityIsMinusOneWhenLessThanOneMinute() {
        var repo = mock(com.factory.hub.report.ProdHourlyRepository.class);
        var shiftStore = mock(ShiftStore.class);
        var manualService = mock(ManualEntryService.class);

        ShiftPlan plan = new ShiftPlan();
        plan.setShifts(List.of());
        when(shiftStore.current()).thenReturn(plan);

        // 只跑了 30 秒 ⇒ counted = 30 < 60
        ProdHourly p = new ProdHourly("DEV-1", "一号机", 0L);
        p.setRunSec(30d);
        when(repo.findInWindow(anyLong(), anyLong())).thenReturn(List.of(p));
        when(manualService.activeOfDay(org.mockito.ArgumentMatchers.any())).thenReturn(new ArrayList<>());

        ReportService service = new ReportService(repo, shiftStore,
                mock(AlarmEventRepository.class), mock(DeviceService.class), manualService);

        Map<String, Object> out = service.daily(LocalDate.of(2026, 3, 1));
        @SuppressWarnings("unchecked")
        Map<String, Object> totals = (Map<String, Object>) out.get("totals");

        // ⚠️ 源码原话：「数据不足一分钟不给稼动率，否则刚接上的设备会显示 0%，
        //    看着像坏了」⇒ 这里必须钉 -1。若有人把它「优化」成 0，
        //    客户会以为设备停了（0%），而实际只是刚接上还没数据。
        assertEquals(-1.0, num(totals.get("availability")), 1e-9,
                "counted < 60 秒时稼动率必须是 -1（数据不足），不能是 0（看起来像设备坏了）");
    }

    private static double num(Object o) {
        return o instanceof Number n ? n.doubleValue() : Double.NaN;
    }
}
