package com.factory.hub.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 串口点表对拍的**二分定位**逻辑。
 * <p>
 * <b>为什么用假设备测</b>：真跑一次对拍要开串口、接 485 设备，
 * 而「读不通时怎么把空洞定位出来」这段恰恰是最容易写错的地方
 * （递归终止、边界、命中与未命中计数）。用 {@code RangeReader} 抽出来就能全跑一遍。
 * <p>
 * <b>假设备的规则</b>：区间里**只要有一个地址不存在**，整段读就失败
 * （这正是 Modbus 的行为——设备对超出范围的地址返回异常码，整帧作废）。
 */
class SerialProberProbeTest {

    /**
     * 造一个假设备：{@code existing} 里的地址能读到，其余读不通。
     *
     * @param calls 实际发起的读取次数（二分效率就看它）
     */
    private static SerialProber.RangeReader fake(Set<Integer> existing, AtomicInteger calls) {
        return (fc, from, to) -> {
            calls.incrementAndGet();
            for (int a = from; a <= to; a++) {
                if (!existing.contains(a)) {
                    // 整段作废：只要有一个地址读不通，这次读就失败
                    throw new IOException("Illegal Data Address: " + a);
                }
            }
            Map<String, Object> m = new LinkedHashMap<>();
            for (int a = from; a <= to; a++) {
                m.put("reg_" + a, 100 + a);
            }
            return m;
        };
    }

    private static Set<Integer> range(int from, int to) {
        Set<Integer> s = new HashSet<>();
        for (int i = from; i <= to; i++) {
            s.add(i);
        }
        return s;
    }

    private List<SerialProber.Hit> run(Set<Integer> existing, int from, int to, int[] callsOut) {
        List<SerialProber.Hit> hits = new ArrayList<>();
        int[] stats = new int[]{0, 0};
        AtomicInteger calls = new AtomicInteger();
        try {
            new SerialProber().probeRange(fake(existing, calls), 3, from, to, hits, stats,
                    System.currentTimeMillis());
        } catch (Exception e) {
            throw new AssertionError("不该中断", e);
        }
        callsOut[0] = calls.get();
        assertEquals(stats[0], hits.size(), "命中计数与命中明细条数必须一致");
        return hits;
    }

    @Test
    @DisplayName("地址连续存在：一次读就命中全部（二分不该触发）")
    void allPresentOneRead() {
        int[] calls = new int[1];
        List<SerialProber.Hit> hits = run(range(0, 31), 0, 31, calls);
        assertEquals(32, hits.size(), "32 个地址都要命中");
        assertEquals(1, calls[0], "整段存在时只该读 1 次（读多了是浪费现场时间）");
    }

    @Test
    @DisplayName("中间有空洞：二分能精确定位，命中与未命中数正确")
    void holeInMiddle() {
        Set<Integer> exist = range(0, 31);
        exist.remove(16);          // 中间挖一个洞
        exist.remove(17);
        int[] calls = new int[1];
        List<SerialProber.Hit> hits = run(exist, 0, 31, calls);
        assertEquals(30, hits.size(), "除掉 2 个不存在的地址，其余 30 个都该命中");
        for (SerialProber.Hit h : hits) {
            assertTrue(h.address() != 16 && h.address() != 17, "空洞地址不该被算命中");
        }
        assertTrue(calls[0] < 32, "二分应该比逐地址读少，实际读了 " + calls[0] + " 次");
    }

    @Test
    @DisplayName("空洞在两端")
    void holesAtEdges() {
        Set<Integer> exist = range(0, 15);
        int[] calls = new int[1];
        List<SerialProber.Hit> hits = run(exist, 0, 31, calls);
        assertEquals(16, hits.size(), "只有前 16 个地址存在");
        for (SerialProber.Hit h : hits) {
            assertTrue(h.address() <= 15, "16 之后的地址都不存在");
        }
    }

    @Test
    @DisplayName("一个都不存在：全 miss，不该崩也不该误报命中")
    void nonePresent() {
        int[] calls = new int[1];
        List<SerialProber.Hit> hits = run(new HashSet<>(), 0, 31, calls);
        assertEquals(0, hits.size(), "没有任何地址读得通");
    }

    @Test
    @DisplayName("只有一个地址存在")
    void singleAddress() {
        Set<Integer> exist = new HashSet<>();
        exist.add(7);
        int[] calls = new int[1];
        List<SerialProber.Hit> hits = run(exist, 0, 15, calls);
        assertEquals(1, hits.size());
        assertEquals(7, hits.get(0).address());
    }

    @Test
    @DisplayName("单地址区间：读不通就是 miss，不会无限递归")
    void singleAddressRangeFails() {
        int[] calls = new int[1];
        List<SerialProber.Hit> hits = run(new HashSet<>(), 5, 5, calls);
        assertEquals(0, hits.size());
        assertEquals(1, calls[0], "单地址只该读 1 次");
    }
}
