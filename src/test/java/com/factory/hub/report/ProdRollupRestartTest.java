package com.factory.hub.report;

import com.factory.hub.collect.SnapshotStore;
import com.factory.hub.erp.ErpProdInboundService;
import com.factory.hub.model.PointValue;
import com.factory.hub.service.DeviceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 重启丢产量（DH-81）。
 *
 * <p><b>这个缺陷的形态：数据静默消失。</b>
 * {@code ProdRollup} 的产量增量 = 本次计数器读数 - 上次读数，而"上次读数"原先只是内存字段
 * （初值 -1），判据是 {@code st.count >= 0 && ts > st.countTs}。服务一重启，第一次采样
 * 读到的是"新读数"而不是"增量"，判据不成立 → 那一段产量既不进
 * {@code prod_hourly}（产量报表），也不进 ERP 的 PROD_IN（入库单据）——
 * <b>两边同时少记，还不报错</b>。演示库那种"报表比实际少，但查不出少在哪"就是这么来的。
 *
 * <p>这里守四条，缺一条这个修法就不成立：
 * <ol>
 *   <li><b>T1 不丢</b>：重启后第一次采样必须补上跨重启的增量</li>
 *   <li><b>T2 不重复</b>：已经处理过的那一段，重启后不能再算一遍（水位同步写的意义就在这）</li>
 *   <li><b>T3 清零</b>：重启 + 计数器清零，按既有规则记 0 + resets，不能记负数</li>
 *   <li><b>T4 不阻断</b>：水位写库失败只 WARN，采集主链路照常走</li>
 * </ol>
 * 另加两条边界：T5 水位加载失败同样不能把启动带崩（项目铁律：迁移/恢复失败不阻断启动）；
 * T6 水位不能一直陈旧（否则重启后第一笔增量会被误标成 backfill）。
 */
class ProdRollupRestartTest {

    private static final String CODE = "CNC-01";
    private static final long HOUR_MS = 3_600_000L;
    /** 某个整点（对齐到小时，产量桶才落在预期的那一小时） */
    private static final long HOUR = 1_760_000_000_000L / HOUR_MS * HOUR_MS;

    /** 假的 dev_counter_state 表：key = 设备号 */
    private final Map<String, DevCounterState> db = new LinkedHashMap<>();

    private ProdHourlyRepository hourlyRepo;
    private ErpProdInboundService inbound;
    private DevCounterStateRepository counterRepo;
    private DeviceService deviceService;

    @BeforeEach
    void setUp() {
        db.clear();
        hourlyRepo = mock(ProdHourlyRepository.class);
        inbound = mock(ErpProdInboundService.class);
        deviceService = mock(DeviceService.class);
        counterRepo = mock(DevCounterStateRepository.class);

        // findAll 返回副本：真库里读出来的是 detached 对象，不是内存里那份
        when(counterRepo.findAll()).thenAnswer(inv -> {
            List<DevCounterState> out = new ArrayList<>();
            for (DevCounterState s : db.values()) {
                out.add(new DevCounterState(s.getDeviceCode(), s.getCountValue(),
                        s.getCountTs(), s.getUpdatedAt()));
            }
            return out;
        });
        when(counterRepo.saveAll(any())).thenAnswer(inv -> {
            Iterable<DevCounterState> rows = inv.getArgument(0);
            for (DevCounterState s : rows) {
                db.put(s.getDeviceCode(), copy(s));
            }
            return new ArrayList<>(db.values());
        });
        when(counterRepo.save(any())).thenAnswer(inv -> {
            DevCounterState s = inv.getArgument(0);
            db.put(s.getDeviceCode(), copy(s));
            return s;
        });
    }

    private static DevCounterState copy(DevCounterState s) {
        return new DevCounterState(s.getDeviceCode(), s.getCountValue(),
                s.getCountTs(), s.getUpdatedAt());
    }

    /** 一笔产量计数采样（role=count，别的角色都不给：这段逻辑只看计数器） */
    private static PointValue countPoint(double count, long ts) {
        return new PointValue(CODE, "count", "产量计数", count, "件",
                PointValue.GOOD, "count", ts);
    }

    /** 采集器重启后快照是空的，要重新喂一次真实读数 */
    private static SnapshotStore sample(double count, long ts) {
        SnapshotStore store = new SnapshotStore();
        store.update(CODE, Map.of("count", countPoint(count, ts)), 0L);
        return store;
    }

    private ProdRollup rollup(SnapshotStore store) {
        return new ProdRollup(store, deviceService, hourlyRepo, inbound, counterRepo);
    }

    /** 当前这一拍该小时的产量；NaN = 这一小时还没有任何采样 */
    private double outputOf(ProdRollup r) {
        return r.liveOutput(CODE, HOUR);
    }

    private double persistedCount() {
        DevCounterState s = db.get(CODE);
        return s == null ? Double.NaN : s.getCountValue();
    }

    /* ==================== T1 不丢 ==================== */

    @Test
    @DisplayName("T1 重启不丢：处理到 100 → 重启 → 采样 110，补上 delta=10（产量表 + ERP 入库都要有）")
    void restartDoesNotLoseDelta() {
        long t0 = HOUR + 5_000L;
        ProdRollup before = rollup(sample(100, t0));
        before.scan();
        assertEquals(100d, persistedCount(), 0.0001, "首次读数只当基准，但水位要落库");

        // ---- 重启：新实例（内存桶、states 全空），采集器重新采到 110 ----
        long t1 = t0 + 1_000L;
        ProdRollup after = rollup(sample(110, t1));
        after.restore();                     // 这一步就是修法的全部：把水位灌回来
        after.scan();

        assertEquals(10d, outputOf(after), 0.0001, "跨重启的 10 件必须补进 prod_hourly 该小时");
        verify(inbound).onProduced(eq(CODE), eq(10d), eq(t1), eq(false));
        verify(inbound, times(1)).onProduced(anyString(), anyDouble(), anyLong(), anyBoolean());
        assertEquals(110d, persistedCount(), 0.0001, "水位推进到 110");
    }

    /* ==================== T2 不重复 ==================== */

    @Test
    @DisplayName("T2 不重复：已处理到 110 再重启，采样还是 110 → delta=0，不重算")
    void restartDoesNotDoubleCount() {
        long t0 = HOUR + 5_000L;
        ProdRollup first = rollup(sample(100, t0));
        first.scan();
        first = rollup(sample(110, t0 + 1_000L));
        first.restore();
        first.scan();
        assertEquals(10d, outputOf(first), 0.0001);
        assertEquals(110d, persistedCount(), 0.0001);

        // ---- 重启后再采到同一个读数 110 ----
        ProdRollup second = rollup(sample(110, t0 + 2_000L));
        second.restore();
        second.scan();

        assertTrue(Double.isNaN(outputOf(second)),
                "重启后 delta 必须为 0，实际产出了 " + outputOf(second)
                        + "。这条断言守的是「水位同步写」这个设计决策，不只是个数字："
                        + "持久化一旦改成异步/攒批，重启后加载到的就是旧读数，"
                        + "已处理过的那一段会被重算一遍 ⇒ 重复计数。改成异步的人，先让这条变红再说。");
        verify(inbound, times(1)).onProduced(anyString(), anyDouble(), anyLong(), anyBoolean());
        assertEquals(110d, persistedCount(), 0.0001);
        assertTrue(db.get(CODE).getCountTs() >= t0 + 1_000L, "水位的时间戳不能往回退");
    }

    @Test
    @DisplayName("T6 水位不会一直陈旧：读数没变但 ts 已过 15 秒，仍然写一次（否则重启后首笔会被误标 backfill）")
    void staleWatermarkStillGetsFlushed() {
        long t0 = HOUR + 5_000L;
        db.put(CODE, new DevCounterState(CODE, 110d, t0, t0));
        ProdRollup r = rollup(sample(110, t0 + 16_000L));
        r.restore();
        r.scan();

        assertEquals(t0 + 16_000L, db.get(CODE).getCountTs(), "超过 15 秒的水位必须刷新");
        assertTrue(Double.isNaN(outputOf(r)), "读数没变就是没产量，刷新水位不能顺手记一笔");
        verify(inbound, never()).onProduced(anyString(), anyDouble(), anyLong(), anyBoolean());
    }

    /* ==================== T3 清零 ==================== */

    @Test
    @DisplayName("T3 重启 + 清零：水位 100，重启后读数 50 → 记 0 + resets++，绝不记负数")
    void restartWithCounterResetRecordsZero() {
        long t0 = HOUR + 5_000L;
        ProdRollup before = rollup(sample(100, t0));
        before.scan();

        ProdRollup after = rollup(sample(50, t0 + 1_000L));
        after.restore();
        after.scan();

        double out = outputOf(after);
        assertTrue(Double.isNaN(out) || out == 0d, "清零不能记成负产量，实际 " + out);
        assertEquals(1L, after.resetsOf(CODE), "清零次数要 +1（客户靠这个数看换表/换班频率）");
        verify(inbound, never()).onProduced(anyString(), anyDouble(), anyLong(), anyBoolean());
        assertEquals(50d, persistedCount(), 0.0001, "水位跟着新读数走，下一次从这里算");
    }

    /* ==================== T4 持久化失败不影响采集 ==================== */

    @Test
    @DisplayName("T4 水位写库失败：scan 不抛，产量照记；内存水位继续推进，下一拍不重算也不丢")
    void persistFailureDoesNotBlockCollection() {
        long t0 = HOUR + 5_000L;
        db.put(CODE, new DevCounterState(CODE, 100d, t0, t0));   // 上次落库的水位

        SnapshotStore store = new SnapshotStore();
        ProdRollup r = rollup(store);
        r.restore();
        // 整批 + 逐台重试都失败：模拟数据库抖动。
        // 用 doThrow 而不是 when(...).thenThrow：when 会把 saveXxx 真调一遍，
        // 而 setUp 里注册的 Answer 会拿 null 参数去跑（那是桩调用，不是真调用）。
        doThrow(new RuntimeException("db down")).when(counterRepo).saveAll(any());
        doThrow(new RuntimeException("db down")).when(counterRepo).save(any());

        store.update(CODE, Map.of("count", countPoint(110, t0 + 1_000L)), 0L);
        assertDoesNotThrow(r::scan, "采集主链路不能被水位写库挡住");
        assertEquals(10d, outputOf(r), 0.0001, "写库失败不影响本拍产量");
        verify(inbound).onProduced(eq(CODE), eq(10d), eq(t0 + 1_000L), eq(false));

        // 下一拍：库里的水位还是 100（写失败），但内存里的已经推到 110 → 只算新增的 10，不重算
        store.update(CODE, Map.of("count", countPoint(120, t0 + 2_000L)), 0L);
        assertDoesNotThrow(r::scan, "写库失败不能把下一拍也带崩");
        assertEquals(20d, outputOf(r), 0.0001, "10 + 10，既没因为写失败重复，也没丢");
        verify(inbound, times(2)).onProduced(anyString(), anyDouble(), anyLong(), anyBoolean());
        assertEquals(100d, persistedCount(), 0.0001,
                "库里的水位确实没写进去（这就是 WARN 里要打设备号+读数的原因：方便事后核对）");
    }

    /* ==================== T5 加载失败不阻断启动 ==================== */

    @Test
    @DisplayName("T5 水位加载失败：restore 只 WARN 不抛，启动照走，采集照采（按首次读数重新起算）")
    void restoreFailureDoesNotBlockStartup() {
        when(counterRepo.findAll()).thenThrow(new RuntimeException("table doesn't exist"));

        ProdRollup r = rollup(sample(100, HOUR + 5_000L));
        assertDoesNotThrow(r::restore, "恢复失败绝不能把服务带崩");
        assertDoesNotThrow(r::scan, "采集也不能因为恢复失败停摆");
        assertTrue(Double.isNaN(outputOf(r)), "没有水位就只记基准，不能瞎推增量");
        verify(inbound, never()).onProduced(anyString(), anyDouble(), anyLong(), anyBoolean());
    }
}
