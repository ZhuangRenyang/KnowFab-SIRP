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
}
