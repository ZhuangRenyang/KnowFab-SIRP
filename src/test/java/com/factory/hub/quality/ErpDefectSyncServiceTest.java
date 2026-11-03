package com.factory.hub.quality;

import com.factory.hub.erp.ErpWorkOrderOutputRepository;
import com.factory.hub.erp.ErpWorkOrderRepository;
import com.factory.hub.model.DeviceEntity;
import com.factory.hub.report.ProdHourly;
import com.factory.hub.report.ProdHourlyRepository;
import com.factory.hub.service.DeviceService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 设备通道同步测试（AC-02 / AC-03 / AC-04 里的幂等部分）。
 *
 * <p><b>幂等是这块的命门</b>：同步是定时任务，重启、网络抖动、手工点"同步"都会再跑一遍。
 * 幂等靠 {@code source_key} 唯一键，不靠应用层"我记得我写过"——后者在进程重启后就失效了。
 *
 * <p>三个场景：
 * <ol>
 *   <li>同一 device+hour 同步 3 次 → 记录数与 qty 都不变；</li>
 *   <li>把 {@code prod_hourly.defect} 调大后重跑 → 总量等于最终值（未处置时覆盖、
 *       已处置时补记）；</li>
 *   <li>键的形态必须是 {@code DEV:设备:小时}，不能带工单号（同一小时换工单会翻倍）。</li>
 * </ol>
 */
class ErpDefectSyncServiceTest {

    private static final String DEV = "CNC-01";
    private static final long HOUR = 1_763_000_000_000L;   // 固定小时起点
    private static final long NOW = HOUR + 5 * 3600_000L;   // 5 小时后（已过封口期）

    /** 测试替身：内存里放小时行，缺陷记录存在 map 里 */
    private static final class Fx {
        ProdHourlyRepository prodRepo = mock(ProdHourlyRepository.class);
        ErpQcDefectRepository defectRepo = mock(ErpQcDefectRepository.class);
        ErpWorkOrderOutputRepository outputRepo = mock(ErpWorkOrderOutputRepository.class);
        ErpWorkOrderRepository workOrderRepo = mock(ErpWorkOrderRepository.class);
        DeviceService deviceService = mock(DeviceService.class);
        ErpDefectSyncService svc;
        final List<ProdHourly> hours = new ArrayList<>();
        final java.util.Map<String, ErpQcDefect> byKey = new java.util.LinkedHashMap<>();
    }

    private Fx fx(double defect) {
        Fx f = new Fx();
        ProdHourly row = new ProdHourly(DEV, "一号机", HOUR);
        row.setDefect(defect);
        f.hours.add(row);
        // 仓储查询是 "hourStart >= from and hourStart < to"，这里要照做，
        // 否则"封口 1 小时内的小时不处理"这条规则测不出来（mock 无条件返回就等于没过滤）
        when(f.prodRepo.findInWindow(anyLong(), anyLong())).thenAnswer(inv -> {
            long from = inv.getArgument(0);
            long to = inv.getArgument(1);
            return f.hours.stream()
                    .filter(h -> h.getHourStart() >= from && h.getHourStart() < to)
                    .toList();
        });
        when(f.outputRepo.findByDeviceCodeAndCreatedAtBetweenOrderByCreatedAtAsc(
                anyString(), anyLong(), anyLong())).thenReturn(List.of());
        DeviceEntity dev = new DeviceEntity();
        dev.setCode(DEV);
        dev.setProdSkuId(77L);
        when(f.deviceService.byCode(DEV)).thenReturn(dev);
        when(f.defectRepo.maxNoByPrefix(anyString())).thenReturn(null);
        // 唯一键模拟：save 时按 source_key 覆盖
        when(f.defectRepo.save(any())).thenAnswer(inv -> {
            ErpQcDefect d = inv.getArgument(0);
            if (d.getId() == null) {
                d.setId((long) f.byKey.size() + 1);
            }
            f.byKey.put(d.getSourceKey(), d);
            return d;
        });
        when(f.defectRepo.findBySourceKey(anyString())).thenAnswer(
                inv -> Optional.ofNullable(f.byKey.get(inv.getArgument(0))));
        // 同一"基键"下的全部记录：基础行 + 所有 :#n 补记行。
        // 差额基准必须是这个和（QA 静态验收 B2）——不 stub 它的话，
        // Mockito 默认返回空集合，实现会走进"首次同步"分支，测试测不到真实路径。
        when(f.defectRepo.findBySourceKeyGroup(anyString())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            List<ErpQcDefect> group = new ArrayList<>();
            for (ErpQcDefect d : f.byKey.values()) {
                String sk = d.getSourceKey();
                if (sk.equals(key) || sk.startsWith(key + ":#")) {
                    group.add(d);
                }
            }
            return group;
        });
        f.svc = new ErpDefectSyncService(f.prodRepo, f.defectRepo, f.outputRepo,
                f.workOrderRepo, f.deviceService);
        return f;
    }

    @Test
    @DisplayName("同一 device+hour 同步 3 次：只产生一条记录，数量不变")
    void idempotentAcrossRuns() {
        Fx f = fx(3);
        f.svc.sync(NOW);
        f.svc.sync(NOW);
        f.svc.sync(NOW);
        assertEquals(1, f.byKey.size(), "同步 3 次只能有 1 条记录");
        ErpQcDefect d = f.byKey.values().iterator().next();
        assertEquals(0, d.getQty().compareTo(new BigDecimal("3")));
    }

    @Test
    @DisplayName("幂等键必须是 DEV:设备:小时（不带工单号、不带检验单、不按天）")
    void sourceKeyShape() {
        Fx f = fx(2);
        f.svc.sync(NOW);
        assertEquals(1, f.byKey.size());
        String key = f.byKey.keySet().iterator().next();
        assertTrue(key.startsWith(ErpDefectSyncService.KEY_PREFIX + DEV + ":"), key);
        assertTrue(key.endsWith(":" + HOUR), "键里必须带小时起点：" + key);
        assertNotEquals(key.contains("WO"), true, "键里不能带工单号（同一小时换工单会翻倍）：" + key);
    }

    @Test
    @DisplayName("未处置时把 prod_hourly.defect 调大 → 覆盖 qty，总量等于最终值")
    void pendingRecordIsOverwritten() {
        Fx f = fx(3);
        f.svc.sync(NOW);
        f.hours.get(0).setDefect(6);
        f.svc.sync(NOW);
        assertEquals(1, f.byKey.size(), "还是同一条记录");
        ErpQcDefect d = f.byKey.values().iterator().next();
        assertEquals(0, d.getQty().compareTo(new BigDecimal("6")));
    }

    @Test
    @DisplayName("已处置后不良数变大 → 不改已处置的那条，另开一条补记行（总量等于最终值）")
    void disposedRecordGetsMemoRow() {
        Fx f = fx(3);
        f.svc.sync(NOW);
        ErpQcDefect d = f.byKey.values().iterator().next();
        d.setStatus(ErpQcDefect.ST_DISPOSED);
        d.setDisposedQty(new BigDecimal("3"));

        f.hours.get(0).setDefect(5);
        f.svc.sync(NOW);

        assertEquals(2, f.byKey.size(), "已处置的那条不能改，要另开补记行");
        BigDecimal total = BigDecimal.ZERO;
        for (ErpQcDefect x : f.byKey.values()) {
            total = total.add(x.getQty());
        }
        assertEquals(0, total.compareTo(new BigDecimal("5")), "总量必须等于最终值 5");
        String memoKey = f.byKey.keySet().stream().filter(k -> k.contains(":#")).findFirst().orElse("");
        assertTrue(memoKey.endsWith(":#1"), "补记行的键要带 :#n 后缀：" + memoKey);
    }

    @Test
    @DisplayName("已处置后不良数变大 → 重跑 3 次，记录数不再涨（补记行不能无限增殖）")
    void disposedRecordDoesNotMultiplyOnRepeatedSync() {
        Fx f = fx(3);
        f.svc.sync(NOW);
        ErpQcDefect d = f.byKey.values().iterator().next();
        d.setStatus(ErpQcDefect.ST_DISPOSED);
        d.setDisposedQty(new BigDecimal("3"));

        f.hours.get(0).setDefect(5);
        f.svc.sync(NOW);
        int afterFirst = f.byKey.size();
        f.svc.sync(NOW);
        f.svc.sync(NOW);

        assertEquals(afterFirst, f.byKey.size(),
                "同步是定时任务，每 5 分钟跑一次；补记行不能每轮多一条（QA 静态验收 B2）");
        BigDecimal total = BigDecimal.ZERO;
        for (ErpQcDefect x : f.byKey.values()) {
            total = total.add(x.getQty());
        }
        assertEquals(0, total.compareTo(new BigDecimal("5")), "总量必须等于最终值 5");
    }

    @Test
    @DisplayName("连续两次增量（3→5→8）：总量等于最后一次的值，不翻倍")
    void twoConsecutiveIncrements() {
        Fx f = fx(3);
        f.svc.sync(NOW);
        ErpQcDefect d = f.byKey.values().iterator().next();
        d.setStatus(ErpQcDefect.ST_DISPOSED);
        d.setDisposedQty(new BigDecimal("3"));

        f.hours.get(0).setDefect(5);
        f.svc.sync(NOW);
        f.hours.get(0).setDefect(8);
        f.svc.sync(NOW);

        BigDecimal total = BigDecimal.ZERO;
        for (ErpQcDefect x : f.byKey.values()) {
            total = total.add(x.getQty());
        }
        assertEquals(0, total.compareTo(new BigDecimal("8")), "总量必须等于最终值 8，不能是 5+5 或翻倍");
    }

    @Test
    @DisplayName("设备通道的记录：缺陷分类为空（要人分诊）、in_stock=false（在制品没入账）")
    void deviceRecordIsUntriagedAndNotInStock() {
        Fx f = fx(4);
        f.svc.sync(NOW);
        ErpQcDefect d = f.byKey.values().iterator().next();
        assertNull(d.getDefectCodeId(), "设备只知道坏了几件，不知道为什么坏");
        assertNull(d.getSeverity());
        assertEquals(false, d.isInStock(), "设备采到的不良是在制品，没入过账");
        assertEquals(ErpQcDefect.ST_PENDING, d.getStatus());
        assertEquals(ErpQcDefect.SRC_DEVICE, d.getSource());
        assertEquals(0, d.getDisposedQty().signum());
    }

    @Test
    @DisplayName("封口 1 小时内的小时不处理（断线补记还没落完）")
    void recentHourIsSkipped() {
        Fx f = fx(5);
        // NOW 只比 HOUR 晚 30 分钟 → 还没到封口期
        f.svc.sync(HOUR + 30 * 60_000L);
        assertEquals(0, f.byKey.size(), "刚封口就同步会漏掉断线补记的部分");
    }

    @Test
    @DisplayName("不良数为 0 的小时不产生记录")
    void zeroDefectCreatesNothing() {
        Fx f = fx(0);
        f.svc.sync(NOW);
        assertEquals(0, f.byKey.size());
    }
}
