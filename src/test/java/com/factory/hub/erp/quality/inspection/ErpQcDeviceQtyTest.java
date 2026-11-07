package com.factory.hub.erp.quality.inspection;

import com.factory.hub.report.ProdHourly;
import com.factory.hub.report.ProdHourlyRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 设备采集数的时间窗边界测试。
 *
 * <p><b>守的是这个 bug</b>：原来按「小时<b>起点</b>落在时间窗内」筛。
 * 工单 12:35 开工、13:10 完工，那 12:00 那一小时整行被漏掉 ——
 * 而它 12:35–13:00 的 25 分钟产出<b>确实属于这张工单</b>。
 *
 * <p><b>后果</b>：每张工单的设备数都偏小 → gap 偏大 →
 * <b>每张工单都误报"两边对不上"</b>，对账功能形同虚设。
 * 而这类错误不报错、不影响任何测试，只是数字不对。
 */
class ErpQcDeviceQtyTest {

    private static final long HOUR = 3_600_000L;
    private static final String DEV = "CNC-01";

    private ProdHourlyRepository repoWith(long hourStart, double defect) {
        ProdHourlyRepository repo = mock(ProdHourlyRepository.class);
        List<ProdHourly> rows = new ArrayList<>();
        ProdHourly p = new ProdHourly(DEV, "一号机", hourStart);
        p.setDefect(defect);
        rows.add(p);
        when(repo.findByDeviceCodeAndHourStartBetween(anyString(), anyLong(), anyLong()))
                .thenAnswer(inv -> {
                    long from = inv.getArgument(1);
                    long to = inv.getArgument(2);
                    List<ProdHourly> hit = new ArrayList<>();
                    for (ProdHourly x : rows) {
                        if (x.getHourStart() >= from && x.getHourStart() <= to) {
                            hit.add(x);
                        }
                    }
                    return hit;
                });
        return repo;
    }

    @Test
    @DisplayName("开工那一小时要算进去：12:35 开工，12:00 那一小时属于这张工单")
    void startHourIsIncluded() {
        long hour12 = 1_793_700_000_000L;      // 12:00 整点
        long start = hour12 + 35 * 60_000L;    // 12:35 开工
        long end = start + 35 * 60_000L;       // 13:10 完工

        BigDecimal qty = ErpQcDeviceQty.of(repoWith(hour12, 3), DEV, start, end);

        assertEquals(0, new BigDecimal("3").compareTo(qty),
                "开工那一小时（12:00）必须算进去，否则每张工单的设备数都偏小、每张都误报冲突");
    }

    @Test
    @DisplayName("完全在窗外的小时不算")
    void outsideWindowIsExcluded() {
        long hour12 = 1_793_700_000_000L;
        long hour10 = hour12 - 2 * HOUR;

        // 时间窗在 12:00 那一小时之后
        long start = hour12 + 35 * 60_000L;
        long end = start + 35 * 60_000L;

        BigDecimal qty = ErpQcDeviceQty.of(repoWith(hour10, 9), DEV, start, end);
        assertEquals(0, BigDecimal.ZERO.compareTo(qty), "窗外的小时不能算进来");
    }

    @Test
    @DisplayName("跨整点的完整小时都算")
    void fullHoursAreIncluded() {
        long hour12 = 1_793_700_000_000L;
        long start = hour12;                   // 整点开工
        long end = hour12 + 3 * HOUR;          // 三个整点后完工

        BigDecimal qty = ErpQcDeviceQty.of(repoWith(hour12, 4), DEV, start, end);
        assertEquals(0, new BigDecimal("4").compareTo(qty));
    }

    @Test
    @DisplayName("没有设备或时间窗倒置 → 0，不报错")
    void nullDeviceOrInvertedWindow() {
        ProdHourlyRepository repo = repoWith(1_793_700_000_000L, 3);
        assertEquals(0, BigDecimal.ZERO.compareTo(
                ErpQcDeviceQty.of(repo, null, 0, 100)));
        assertEquals(0, BigDecimal.ZERO.compareTo(
                ErpQcDeviceQty.of(repo, DEV, 100, 50)));
    }
}
