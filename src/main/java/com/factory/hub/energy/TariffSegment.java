package com.factory.hub.energy;

/**
 * 分时电价里的一个时段，例如「尖峰 10:00-12:00 1.35 元/度」。
 * <p>
 * 时段可以跨零点（低谷 23:00-07:00），这是现场必须支持的——
 * 大部分地区的低谷电价都是从头天晚上到第二天早上。
 */
public class TariffSegment {

    /** 显示名，客户可以自己改，如「尖峰」「高峰」「平段」「低谷」 */
    private String name = "平段";

    /**
     * 类型，决定前端配色和统计口径。固定四种：
     * TIP=尖峰 / PEAK=高峰 / FLAT=平段 / VALLEY=低谷
     * 有的地区还有深谷，直接归到 VALLEY 即可。
     */
    private String type = "FLAT";

    /** 起止时间，24 小时制 HH:mm，含头不含尾。end 小于 start 表示跨零点 */
    private String start = "08:00";
    private String end = "11:00";

    /** 电价，元/度（kWh） */
    private double price = 0.7d;

    public TariffSegment() {
    }

    public TariffSegment(String name, String type, String start, String end, double price) {
        this.name = name;
        this.type = type;
        this.start = start;
        this.end = end;
        this.price = price;
    }

    public String getName() {
        return name == null || name.isBlank() ? type : name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getType() {
        return type == null ? "FLAT" : type.toUpperCase();
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getStart() {
        return start;
    }

    public void setStart(String start) {
        this.start = start;
    }

    public String getEnd() {
        return end;
    }

    public void setEnd(String end) {
        this.end = end;
    }

    public double getPrice() {
        return price;
    }

    public void setPrice(double price) {
        this.price = price;
    }

    /** 起始分钟数，0=00:00，1439=23:59 */
    public int startMinute() {
        return parseMinute(start);
    }

    public int endMinute() {
        return parseMinute(end);
    }

    /**
     * 这一分钟（0-1439）是否落在本时段内。
     * 含头不含尾：08:00-11:00 包括 08:00 到 10:59，不包括 11:00。
     */
    public boolean contains(int minuteOfDay) {
        int s = startMinute();
        int e = endMinute();
        if (s == e) {
            return true;                 // 起止相同视为全天
        }
        if (s < e) {
            return minuteOfDay >= s && minuteOfDay < e;
        }
        return minuteOfDay >= s || minuteOfDay < e;   // 跨零点
    }

    /** "8:00" / "08:00" / "08:00:00" 都能吃 */
    private int parseMinute(String hhmm) {
        if (hhmm == null || hhmm.isBlank()) {
            return 0;
        }
        String[] parts = hhmm.trim().split(":");
        try {
            int h = Integer.parseInt(parts[0].trim());
            int m = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : 0;
            return Math.max(0, Math.min(1439, h * 60 + m));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 用于前端展示，如 "23:00 — 次日 07:00" */
    public String label() {
        return startMinute() > endMinute()
                ? start + " — 次日 " + end
                : start + " — " + end;
    }

    public TariffSegment copy() {
        return new TariffSegment(name, type, start, end, price);
    }
}
