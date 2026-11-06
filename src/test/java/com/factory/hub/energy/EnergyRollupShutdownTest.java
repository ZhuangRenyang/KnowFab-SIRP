package com.factory.hub.energy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.factory.hub.collect.SnapshotStore;
import com.factory.hub.demand.DemandTracker;
import com.factory.hub.model.PointValue;

/**
 * 优雅停机不能丢电量。
 * <p>
 * <b>守的是这个坑</b>：{@code EnergyRollup} 每 10 分钟才 checkpoint 一次。
 * 如果停机时不把内存里的当前小时写下去，那次 restart 就丢掉「上次 checkpoint 之后」
 * 采到的全部电量（最多 10 分钟）。
 * <p>
 * 为什么这条重要到要单独写测试：
 * <ul>
 *   <li><b>发版升级的必经路径就是 `systemctl restart`</b>。不是"偶尔发生"，是每次都发生。</li>
 *   <li>客户看到的是「重启一次，那个小时的电费少了一点」——<b>不报错、不告警</b>，
 *       只会在月度对账时觉得"系统算的跟电表对不上"。</li>
 *   <li>{@link DemandTracker} 早就有 {@code @PreDestroy}，这里一直没有。
 *       2026-11-06 做断网演练时实测丢了 4 分钟的补读电量，才发现这个不对称。</li>
 * </ul>
 * <p>
 * 注意边界：这只能覆盖<b>优雅停机</b>（SIGTERM → Spring 调 {@code @PreDestroy}）。
 * 硬杀 / 断电仍会丢最多 10 分钟，那是 checkpoint 周期的固有代价——别把这条测试
 * 当成"零丢失"的证明。
 */
class EnergyRollupShutdownTest {

    /** 一个「本小时已经采到 5 kWh、但还没到 10 分钟落库点」的现场 */
    private static EnergyRollup withPendingKwh(EnergyRepository repo,
                                               List<PointValue> feedOrder) {
        SnapshotStore store = mock(SnapshotStore.class);
        TariffStore tariff = mock(TariffStore.class);
        when(tariff.current()).thenReturn(new TariffPlan());   // 空时段 → 走 UNPRICED 分支
        when(repo.findByHourStart(anyLong())).thenReturn(new ArrayList<>());
        when(repo.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        EnergyRollup rollup = new EnergyRollup(store, tariff, repo, mock(DemandTracker.class));

        for (PointValue pv : feedOrder) {
            Map<String, Map<String, PointValue>> snap = new LinkedHashMap<>();
            snap.put("D1", Map.of("kwh", pv));
            when(store.entries()).thenReturn(snap.entrySet());
            rollup.scan();
        }
        return rollup;
    }

    private static PointValue kwhPoint(double kwh, long ts) {
        return new PointValue("D1", "kwh", "累计电量", kwh, "kWh",
                PointValue.GOOD, "energy", ts);
    }

    @Test
    @DisplayName("优雅停机把当前小时落库（否则每次 restart 丢最多 10 分钟电量）")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void shutdownPersistsCurrentHour() {
        EnergyRepository repo = mock(EnergyRepository.class);
        long now = System.currentTimeMillis();
        // 第一笔只立基线（delta=0），第二笔产生 5 kWh 的真增量
        EnergyRollup rollup = withPendingKwh(repo,
                List.of(kwhPoint(1000d, now), kwhPoint(1005d, now + 2000L)));

        rollup.onShutdown();

        ArgumentCaptor<Iterable> cap = ArgumentCaptor.forClass(Iterable.class);
        verify(repo, atLeastOnce()).saveAll(cap.capture());
        double saved = 0;
        int rows = 0;
        for (Object batch : cap.getAllValues()) {
            for (Object o : (Iterable) batch) {
                EnergyRecord r = (EnergyRecord) o;
                saved += r.getKwh();
                rows++;
            }
        }
        assertTrue(rows > 0, "停机时应当写至少一行，实际一行都没写 —— 这段电量就丢了");
        assertEquals(5d, saved, 1e-9, "落库的应该是那 5 kWh（本小时未落库的那部分）");
    }

    @Test
    @DisplayName("没有任何数据时停机不写库（避免无意义 IO）")
    void shutdownWithoutDataDoesNothing() {
        EnergyRepository repo = mock(EnergyRepository.class);
        SnapshotStore store = mock(SnapshotStore.class);
        TariffStore tariff = mock(TariffStore.class);
        when(tariff.current()).thenReturn(new TariffPlan());
        when(store.entries()).thenReturn(java.util.Set.of());

        EnergyRollup rollup = new EnergyRollup(store, tariff, repo, mock(DemandTracker.class));
        rollup.scan();
        rollup.onShutdown();

        // 一次都不该写
        verify(repo, org.mockito.Mockito.never()).saveAll(any());
    }

    /**
     * 上面两个测试是**直接调** {@code onShutdown()} 的，所以它们证明不了
     * 「Spring 停机时真的会调它」。如果注解被删掉，那两个测试照样绿——
     * 而修复就完全失效了（方法留着，没人调用）。
     * <p>
     * 这条用反射把注解本身钉住。删注解 → 立刻红。
     */
    @Test
    @DisplayName("onShutdown 必须带 @PreDestroy（否则 Spring 不会调它，等于没修）")
    void shutdownIsRegisteredAsPreDestroy() throws Exception {
        java.lang.reflect.Method m = EnergyRollup.class.getMethod("onShutdown");
        assertTrue(m.isAnnotationPresent(jakarta.annotation.PreDestroy.class),
                "onShutdown 缺少 @PreDestroy：方法写得再对，Spring 停机时也不会调它，"
                        + "每次 restart 照样丢最多 10 分钟电量");
    }
}
