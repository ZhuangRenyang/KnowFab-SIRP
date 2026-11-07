package com.factory.hub.erp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * {@link ProdDay#hourStart} 的**时区口径**契约测试（2026-11-07 补）。
 * <p>
 * 为什么补：`ProdRollup` 里原来有 **8 处** `ts - ts % 3600000`（epoch 对齐），
 * 而「今天起点」走 {@link ProdDay#dayStart}（本地时区）⇒ 两套网格。
 * <p>
 * <b>为什么一直没暴露</b>：中国是 +8（整小时偏移），epoch 对齐恰好等于本地整点。
 * 但在<b>非整小时</b>偏移的时区会错位：
 * <ul>
 *   <li>印度 +5:30、澳洲中部 +9:30 ⇒ 错位 30 分钟</li>
 *   <li>尼泊尔 +5:45 ⇒ 错位 45 分钟</li>
 * </ul>
 * 后果：小时报表的钟点标签错位；统计日边界（本地 00:00）与小时桶边界（本地 xx:30）
 * 不是同一套网格 ⇒ 跨日界那一小时整桶归到某一天。
 * <p>
 * ⚠️ 本测试**不依赖运行机器的时区**：全部用显式传入的 {@link ZoneId} 断言，
 * 所以在 +8 的开发机上也能验证 +5:30 的行为（这正是加
 * {@code hourStart(ts, zone)} 重载的唯一理由）。
 */
class ProdDayHourStartTest {

    /** 本地时间 → 该时区下的 epoch 毫秒 */
    private static long at(ZoneId zone, int y, int mo, int d, int h, int mi) {
        return ZonedDateTime.of(LocalDateTime.of(y, mo, d, h, mi), zone).toInstant().toEpochMilli();
    }

    private static LocalDateTime localOf(long ts, ZoneId zone) {
        return Instant.ofEpochMilli(ts).atZone(zone).toLocalDateTime();
    }

    /** 老算法：epoch 对齐（**被测代码已不再使用**，只用来证明差别真实存在） */
    private static long epochAligned(long ts) {
        return ts - (ts % 3_600_000L);
    }

    @Test
    @DisplayName("+5:30（印度）：本地 10:20 的小时起点必须是本地 10:00，不是 9:30")
    void halfHourOffsetZone() {
        ZoneId kolkata = ZoneId.of("Asia/Kolkata");
        long ts = at(kolkata, 2026, 3, 1, 10, 20);

        long start = ProdDay.hourStart(ts, kolkata);

        assertEquals(LocalDateTime.of(2026, 3, 1, 10, 0), localOf(start, kolkata),
                "小时起点必须是本地 10:00");

        // ⭐ 这条是**本测试的核心**：证明「epoch 对齐」在这个时区确实会错
        assertEquals(LocalDateTime.of(2026, 3, 1, 9, 30), localOf(epochAligned(ts), kolkata),
                "（对照）老算法在这个时区会给出本地 9:30 —— 差距就是真实存在的 bug");
        assertNotEquals(epochAligned(ts), start,
                "若两者相等，说明这个时区是整小时偏移、测不出问题（本用例用 +5:30 不会发生）");
    }

    @Test
    @DisplayName("+5:45（尼泊尔）：错位是 45 分钟")
    void quarterHourOffsetZone() {
        ZoneId kathmandu = ZoneId.of("Asia/Kathmandu");
        long ts = at(kathmandu, 2026, 3, 1, 10, 20);

        long start = ProdDay.hourStart(ts, kathmandu);

        assertEquals(LocalDateTime.of(2026, 3, 1, 10, 0), localOf(start, kathmandu));
        assertEquals(LocalDateTime.of(2026, 3, 1, 9, 45), localOf(epochAligned(ts), kathmandu),
                "（对照）老算法错位 45 分钟：本地 10:20 = UTC 04:35，对齐到 UTC 04:00 = 本地 09:45");
    }

    @Test
    @DisplayName("+9:30（澳洲中部）：同样错位 30 分钟")
    void anotherHalfHourZone() {
        ZoneId darwin = ZoneId.of("Australia/Darwin");
        long ts = at(darwin, 2026, 3, 1, 10, 20);

        assertEquals(LocalDateTime.of(2026, 3, 1, 10, 0),
                localOf(ProdDay.hourStart(ts, darwin), darwin));
        assertEquals(LocalDateTime.of(2026, 3, 1, 9, 30),
                localOf(epochAligned(ts), darwin));
    }

    @Test
    @DisplayName("+8（中国）：整小时偏移 ⇒ 新旧算法相同（这就是为什么一直没暴露）")
    void wholeHourZoneLooksIdentical() {
        ZoneId shanghai = ZoneId.of("Asia/Shanghai");
        long ts = at(shanghai, 2026, 3, 1, 10, 20);

        assertEquals(epochAligned(ts), ProdDay.hourStart(ts, shanghai),
                "整小时偏移时两种算法结果相同 ⇒ 在有这个 bug 的机器上「看起来一切正常」");
    }

    @Test
    @DisplayName("小时起点幂等：对已对齐的值再取一次不变（liveOutput 内部会再归一化）")
    void hourStartIsIdempotent() {
        ZoneId kolkata = ZoneId.of("Asia/Kolkata");
        long ts = at(kolkata, 2026, 3, 1, 10, 20);

        long once = ProdDay.hourStart(ts, kolkata);
        long twice = ProdDay.hourStart(once, kolkata);

        assertEquals(once, twice, "对已对齐的值再取小时起点必须不变（幂等）");
    }

    @Test
    @DisplayName("⭐ 日界必须落在小时网格上：统计日起点 == 该起点的小时起点")
    void dayBoundaryAlignsWithHourGrid() {
        // 这是「两套网格必须一致」的直接表达：
        //   dayStart 用本地时区算日本该从 00:00（或 resetHour 点）开始，
        //   小时桶也必须以同一个点为界；否则跨日界那一小时会被整桶归到某一天。
        for (String zid : new String[]{"Asia/Shanghai", "Asia/Kolkata", "Asia/Kathmandu", "Australia/Darwin", "UTC"}) {
            ZoneId zone = ZoneId.of(zid);
            long ts = at(zone, 2026, 3, 1, 23, 40);

            // 系统时区版本在这里没法逐个验（它固定用系统时区），
            // 所以直接验「日界点本身是小时网格上的点」——用同一时区算出来再比。
            long dayStart = ZonedDateTime.of(
                    Instant.ofEpochMilli(ts).atZone(zone).minusHours(0).toLocalDate().atTime(0, 0), zone)
                    .toInstant().toEpochMilli();

            assertEquals(dayStart, ProdDay.hourStart(dayStart, zone),
                    "时区 " + zid + "：统计日起点必须是小时网格上的点（否则跨日界那小时归属会错）");
        }
    }

    @Test
    @DisplayName("无参版本与系统时区版本一致（口径没有分叉）")
    void defaultUsesSystemZone() {
        long ts = 1_772_400_000_000L;   // 固定时刻，不依赖当前时间
        assertEquals(ProdDay.hourStart(ts, ZoneId.systemDefault()), ProdDay.hourStart(ts),
                "hourStart(ts) 必须等于 hourStart(ts, 系统时区) —— 不允许有两套口径");
    }

    @Test
    @DisplayName("DST 切换日也不会算错（用有夏令时的时区验证）")
    void daylightSavingTransition() {
        ZoneId ny = ZoneId.of("America/New_York");
        // 2026-03-08 是美东 DST 开始日（02:00 → 03:00）
        long ts = at(ny, 2026, 3, 8, 5, 30);

        long start = ProdDay.hourStart(ts, ny);

        assertEquals(LocalDateTime.of(2026, 3, 8, 5, 0), localOf(start, ny),
                "DST 切换日的普通小时也必须对齐到本地整点");
    }
}
