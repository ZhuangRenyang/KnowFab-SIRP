package com.factory.hub.demand;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 断线电量摊平（DH-86）的算法边界。
 * <p>
 * <b>守的是这两个反向错误</b>：
 * <ul>
 *   <li><b>全塞进当前窗口</b>：离线 2 小时的电量记进 1 个 15 分钟窗口 → 峰值翻 8 倍，
 *       客户照着假峰值去申请扩容，白花几十万。</li>
 *   <li><b>整段丢弃</b>（本 Spec 之前的行为）：断网若恰逢当月用电高峰，那个峰值在需量里
 *       完全不存在 → 月最大需量低估 → 基本电费少算，而且<strong>超容告警不会响</strong>，
 *       客户被供电局加收时我们一句提醒都没有。</li>
 * </ul>
 * 摊平是第三条路：份额 = 电量 × 本窗口内重叠时长 / 总跨度，既不放大小，也不丢总量。
 * <p>
 * 另外钉死两条：<b>电量守恒</b>（份额之和必须等于原电量，否则月电量对不上账）、
 * <b>跨度上限</b>（离线 3 天摊到 288 个窗口填同一个平均值，那不是数据是伪造）。
 */
class DemandTrackerTest {

    private static final long W = 900_000L;              // 15 分钟
    private static final long MIN = 60_000L;

    /** 对齐到整 15 分钟的基准时刻（窗口边界，避免用例受「任意时刻」干扰） */
    private static long base() {
        long t = 1_700_000_000_000L;
        return t - (t % W);
    }

    private static DemandWindowRepository newRepo() {
        DemandWindowRepository repo = mock(DemandWindowRepository.class);
        // 默认：库里没有任何已落的窗口（全新安装的样子）
        when(repo.findByWindowStart(anyLong())).thenReturn(new ArrayList<>());
        when(repo.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        when(repo.deleteOlderThan(anyLong())).thenReturn(0);
        return repo;
    }

    /** 走一次 checkpoint（= flush 落库），把落库的窗口按 windowStart 收起来 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Map<Long, DemandWindow> flushAndCollect(DemandTracker tracker,
                                                           DemandWindowRepository repo) {
        tracker.checkpoint();
        ArgumentCaptor<Iterable> cap = ArgumentCaptor.forClass(Iterable.class);
        // atLeast(0)：有些用例的预期结果就是「一次都不写库」（跨度过大被丢弃），
        // 用 atLeastOnce 会让这些用例以 Mockito 错误而不是断言失败收场
        verify(repo, atLeast(0)).saveAll(cap.capture());
        Map<Long, DemandWindow> out = new HashMap<>();
        for (Object batch : cap.getAllValues()) {
            for (Object o : (Iterable) batch) {
                DemandWindow w = (DemandWindow) o;
                out.put(w.getWindowStart(), w);
            }
        }
        return out;
    }

    @Test
    @DisplayName("正常采集：电量全部记入读数时刻所在窗口，不标推算")
    void normalFeedGoesToCurrentWindow() {
        DemandWindowRepository repo = newRepo();
        DemandTracker tracker = new DemandTracker(repo);
        long b = base();

        tracker.feed("M1", b, b + 10 * MIN, 12.5, false);
        Map<Long, DemandWindow> w = flushAndCollect(tracker, repo);

        assertEquals(1, w.size(), "正常采集只该落一个窗口");
        assertEquals(12.5, w.get(b).getKwh(), 1e-9);
        assertEquals(50.0, w.get(b).getDemandKw(), 1e-9, "需量 = kwh × 4");
        assertFalse(w.get(b).isEstimated(), "实测数据不能被标成推算");
    }

    @Test
    @DisplayName("摊平：40 分钟 40 kWh → 15/15/10，总和守恒")
    void backfillSpreadsByOverlap() {
        DemandWindowRepository repo = newRepo();
        DemandTracker tracker = new DemandTracker(repo);
        long b = base();

        // 从窗口起点开始，跨了 3 个窗口（15 + 15 + 10 分钟）
        tracker.feed("M1", b, b + 40 * MIN, 40d, true);
        Map<Long, DemandWindow> w = flushAndCollect(tracker, repo);

        assertEquals(3, w.size(), "40 分钟跨 3 个窗口");
        assertEquals(15d, w.get(b).getKwh(), 1e-6);
        assertEquals(15d, w.get(b + W).getKwh(), 1e-6);
        assertEquals(10d, w.get(b + 2 * W).getKwh(), 1e-6);

        double sum = w.values().stream().mapToDouble(DemandWindow::getKwh).sum();
        assertEquals(40d, sum, 1e-6, "各窗口份额之和必须等于原电量（电量守恒）");
        assertTrue(w.values().stream().allMatch(DemandWindow::isEstimated),
                "摊平的窗口都要标推算");
    }

    @Test
    @DisplayName("摊平：起点不在窗口边界时按重叠时长分（10/10）")
    void backfillFromMidWindow() {
        DemandWindowRepository repo = newRepo();
        DemandTracker tracker = new DemandTracker(repo);
        long b = base();

        // 从第 5 分钟到第 25 分钟，20 分钟、20 kWh → 两个窗口各 10 分钟
        tracker.feed("M1", b + 5 * MIN, b + 25 * MIN, 20d, true);
        Map<Long, DemandWindow> w = flushAndCollect(tracker, repo);

        assertEquals(2, w.size());
        assertEquals(10d, w.get(b).getKwh(), 1e-6);
        assertEquals(10d, w.get(b + W).getKwh(), 1e-6);
    }

    @Test
    @DisplayName("跨度超过 4 小时：不摊平、不产生任何窗口（摊了就是伪造）")
    void backfillBeyondLimitIsDropped() {
        DemandWindowRepository repo = newRepo();
        DemandTracker tracker = new DemandTracker(repo);
        long b = base();

        tracker.feed("M1", b, b + 5 * 3_600_000L, 300d, true);

        assertEquals(0, tracker.liveWindowCount(), "超限的段不该在内存里留下任何窗口");
        assertTrue(flushAndCollect(tracker, repo).isEmpty(), "也不该落库");
    }

    @Test
    @DisplayName("跨度刚好 4 小时：仍然摊（边界包含）")
    void backfillAtLimitStillSpreads() {
        DemandWindowRepository repo = newRepo();
        DemandTracker tracker = new DemandTracker(repo);
        long b = base();

        tracker.feed("M1", b, b + 4 * 3_600_000L, 160d, true);
        Map<Long, DemandWindow> w = flushAndCollect(tracker, repo);

        assertEquals(16, w.size(), "4 小时 = 16 个窗口");
        double sum = w.values().stream().mapToDouble(DemandWindow::getKwh).sum();
        assertEquals(160d, sum, 1e-6);
    }

    @Test
    @DisplayName("缺起点（fromTs=0）：丢弃，不瞎猜归属")
    void backfillWithoutFromTsIsDropped() {
        DemandWindowRepository repo = newRepo();
        DemandTracker tracker = new DemandTracker(repo);

        tracker.feed("M1", 0L, base() + MIN, 5d, true);

        assertEquals(0, tracker.liveWindowCount(), "没有起点就摊不了，宁可丢也不猜");
    }

    @Test
    @DisplayName("同一窗口已有实测值：摊平累加不覆盖，且补上推算标记")
    void backfillAddsOnTopOfMeasured() {
        DemandWindowRepository repo = newRepo();
        DemandTracker tracker = new DemandTracker(repo);
        long b = base();

        tracker.feed("M1", b, b + MIN, 10d, false);        // 实测 10
        tracker.feed("M1", b, b + 10 * MIN, 5d, true);     // 同窗口再摊 5
        Map<Long, DemandWindow> w = flushAndCollect(tracker, repo);

        assertEquals(1, w.size());
        assertEquals(15d, w.get(b).getKwh(), 1e-6, "10 + 5，不能是 5");
        assertTrue(w.get(b).isEstimated(), "这个窗口混了推算值，必须标出来");
    }

    @Test
    @DisplayName("重启后摊平：先继承库里已落的量，不能把已有电量冲掉")
    void backfillInheritsPersistedBaseline() {
        DemandWindowRepository repo = newRepo();
        long b = base();
        // 库里这一窗口已经有 10 kWh（重启前落的）
        List<DemandWindow> existing = new ArrayList<>();
        existing.add(new DemandWindow("M1", b, 10d));
        when(repo.findByWindowStart(b)).thenReturn(existing);

        DemandTracker tracker = new DemandTracker(repo);
        tracker.feed("M1", b, b + 10 * MIN, 5d, true);
        Map<Long, DemandWindow> w = flushAndCollect(tracker, repo);

        assertEquals(15d, w.get(b).getKwh(), 1e-6,
                "重启后从 10 继续累加；如果被 5 覆盖，月峰值就凭空少了一半");
    }

    @Test
    @DisplayName("跨窗口摊平时每个窗口都继承自己的基线（不是只第一个）")
    void everyWindowInheritsItsOwnBaseline() {
        DemandWindowRepository repo = newRepo();
        long b = base();
        when(repo.findByWindowStart(b)).thenReturn(
                new ArrayList<>(List.of(new DemandWindow("M1", b, 100d))));
        when(repo.findByWindowStart(b + W)).thenReturn(
                new ArrayList<>(List.of(new DemandWindow("M1", b + W, 200d))));

        DemandTracker tracker = new DemandTracker(repo);
        tracker.feed("M1", b, b + 30 * MIN, 30d, true);
        Map<Long, DemandWindow> w = flushAndCollect(tracker, repo);

        assertEquals(115d, w.get(b).getKwh(), 1e-6, "100 + 15");
        assertEquals(215d, w.get(b + W).getKwh(), 1e-6, "200 + 15");
    }

    @Test
    @DisplayName("零/负电量与空设备号：直接忽略，不建窗口")
    void zeroOrNullIgnored() {
        DemandWindowRepository repo = newRepo();
        DemandTracker tracker = new DemandTracker(repo);

        tracker.feed(null, base(), base() + MIN, 10d, false);
        tracker.feed("M1", base(), base() + MIN, 0d, false);
        tracker.feed("M1", base(), base() + MIN, -5d, false);

        assertEquals(0, tracker.liveWindowCount());
    }
}
