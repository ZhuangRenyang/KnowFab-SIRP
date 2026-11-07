package com.factory.hub.erp;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * 生产统计日窗口（DH-41 顺手修掉的老问题）。
 * <p>
 * 老实现用 {@code Math.floorDiv(ts, 86400000)} 算"第几天"，那是 <b>UTC</b> 的天数：
 * 在东八区，这一天实际从本地 08:00 开始。于是"今日产量"漏掉了 0 点到 8 点的夜班产量
 * （两班倒客户的夜班正好落在这段），而且清零时刻越早漏得越多。
 * <p>
 * 正确做法：把时间<b>往前挪 resetHour 小时</b>再取本地日期，就是"统计日"；
 * 统计日的起点是那天的 resetHour 点。清零时刻 0 点时就等于自然日。
 */
public final class ProdDay {

    private static final ZoneId ZONE = ZoneId.systemDefault();

    private ProdDay() {
    }

    /** 该时刻所属统计日的起始毫秒（本地时区，含 resetHour 偏移） */
    public static long dayStart(long ts, int resetHour) {
        ZonedDateTime z = Instant.ofEpochMilli(ts).atZone(ZONE).minusHours(resetHour);
        return z.toLocalDate().atTime(resetHour, 0).atZone(ZONE).toInstant().toEpochMilli();
    }

    /** 该时刻所属统计日的 epochDay（可作为 Map 的 key） */
    public static long dayKey(long ts, int resetHour) {
        return Instant.ofEpochMilli(ts).atZone(ZONE).minusHours(resetHour).toLocalDate().toEpochDay();
    }

    /** epochDay 对应的统计日起点 */
    public static long startOfDayKey(long dayKey, int resetHour) {
        return LocalDate.ofEpochDay(dayKey).atTime(resetHour, 0).atZone(ZONE).toInstant().toEpochMilli();
    }

    /** 该时刻所属统计日的日期（算年/月用，跨清零时刻也正确） */
    public static LocalDate bizDate(long ts, int resetHour) {
        return Instant.ofEpochMilli(ts).atZone(ZONE).minusHours(resetHour).toLocalDate();
    }

    /**
     * 该时刻所属**本地整点**的起始毫秒（按时区对齐，不是 epoch 对齐）。
     * <p>
     * <b>为什么不能用 {@code ts - ts % 3600000}</b>：那是 <b>UTC</b> 的整点，
     * 在整小时偏移的时区（如中国 +8）恰好等于本地整点，所以一直没暴露；
     * 但在 <b>非整小时偏移</b>的时区（印度 +5:30、尼泊尔 +5:45、澳洲中部 +9:30）
     * 会比本地整点<b>偏早 30（或 45/30）分钟</b> ⇒ 小时报表的钟点标签错位，
     * 而且统计日边界（本地 00:00）与小时桶边界（本地 xx:30）不是同一套网格
     * ⇒ 跨日界那一小时整桶归到某一天。
     * <p>
     * 与 {@link #dayStart} 的关系：两者都按**本地时区**对齐，网格一致。
     */
    public static long hourStart(long ts) {
        return hourStart(ts, ZONE);
    }

    /**
     * 同上，**时区可注入**。
     * <p>
     * ⚠️ 这个重载是为测试存在的：{@link #hourStart(long)} 用系统时区，
     * 在有 DST 或整小时偏移的机器上，「epoch 对齐」和「本地对齐」结果相同
     * ⇒ 那种机器上**永远测不出**非整小时时区的错位
     * ⇒ 必须能指定时区（用 {@code Asia/Kolkata} +5:30 来证明）。
     */
    public static long hourStart(long ts, ZoneId zone) {
        return Instant.ofEpochMilli(ts).atZone(zone)
                .truncatedTo(java.time.temporal.ChronoUnit.HOURS)
                .toInstant().toEpochMilli();
    }
}
