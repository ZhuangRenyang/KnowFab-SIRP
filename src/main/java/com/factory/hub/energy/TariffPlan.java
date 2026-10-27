package com.factory.hub.energy;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * 一套分时电价方案。整个厂用一套，需要多个方案（比如分厂区）时再加维度。
 * <p>
 * 默认值是示例，各省分时电价差很多，交付时必须按客户当地供电局的
 * 《分时电价执行细则》改这四段的价格和时段。
 */
public class TariffPlan {

    private String name = "默认峰谷电价";

    /** 币种，目前只有人民币，留着给出口项目 */
    private String currency = "CNY";

    private List<TariffSegment> segments = new ArrayList<>();

    /**
     * 示例方案，覆盖完整 24 小时。价格是 2024 年前后华东一般工商业的
     * 大致水平，只用于让系统跑起来看得见数，不代表任何具体地区。
     */
    public static TariffPlan defaults() {
        TariffPlan p = new TariffPlan();
        p.setName("默认峰谷电价（示例，请按当地供电局文件修改）");
        List<TariffSegment> list = new ArrayList<>();
        list.add(new TariffSegment("低谷", "VALLEY", "23:00", "07:00", 0.35d));
        list.add(new TariffSegment("平段", "FLAT", "07:00", "08:00", 0.68d));
        list.add(new TariffSegment("高峰", "PEAK", "08:00", "10:00", 1.05d));
        list.add(new TariffSegment("尖峰", "TIP", "10:00", "12:00", 1.32d));
        list.add(new TariffSegment("平段", "FLAT", "12:00", "17:00", 0.68d));
        list.add(new TariffSegment("高峰", "PEAK", "17:00", "23:00", 1.05d));
        p.setSegments(list);
        return p;
    }

    /**
     * 某个时刻属于哪个时段。找不到就返回 null——
     * 说明客户把时段配漏了，这时候宁可不计价，也不要编一个电价出来。
     */
    public TariffSegment segmentAt(long epochMs) {
        LocalDateTime ldt = LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault());
        int minute = ldt.getHour() * 60 + ldt.getMinute();
        for (TariffSegment s : segments) {
            if (s.contains(minute)) {
                return s;
            }
        }
        return null;
    }

    /** 四个类型固定顺序，前端图例和堆叠条都用这个顺序 */
    public static final String[] TYPES = {"TIP", "PEAK", "FLAT", "VALLEY"};

    public static String typeName(String type) {
        return switch (type == null ? "FLAT" : type.toUpperCase()) {
            case "TIP" -> "尖峰";
            case "PEAK" -> "高峰";
            case "VALLEY" -> "低谷";
            default -> "平段";
        };
    }

    /**
     * 配置体检。不阻止保存（客户可能确实有非整点、非 24 小时覆盖的特殊政策），
     * 但要把问题说清楚，否则电费算出来对不上客户会以为是软件错了。
     */
    public List<String> validate() {
        List<String> warn = new ArrayList<>();
        if (segments == null || segments.isEmpty()) {
            warn.add("没有任何时段，电费将无法计算");
            return warn;
        }

        int[] hit = new int[1440];
        for (TariffSegment s : segments) {
            for (int m = 0; m < 1440; m++) {
                if (s.contains(m)) {
                    hit[m]++;
                }
            }
            if (s.getPrice() < 0) {
                warn.add("时段「" + s.getName() + "」电价为负数");
            }
        }

        int bad = 0;
        StringBuilder missing = new StringBuilder();
        for (int m = 0; m < 1440; m++) {
            if (hit[m] == 0) {
                bad++;
                if (missing.length() < 60) {
                    missing.append(String.format("%02d:%02d ", m / 60, m % 60));
                }
            }
        }
        if (bad > 0) {
            warn.add("有 " + bad + " 分钟没有对应时段（从 " + missing.toString().trim()
                    + " 开始…），这些时间产生的电量不会计价");
        }

        int overlap = 0;
        for (int m = 0; m < 1440; m++) {
            if (hit[m] > 1) {
                overlap++;
            }
        }
        if (overlap > 0) {
            warn.add("有 " + overlap + " 分钟被多个时段同时覆盖，会取第一个匹配到的时段，请检查是否配重");
        }
        return warn;
    }

    /** 复制一份，避免调用方改到内部 list */
    public TariffPlan copy() {
        TariffPlan p = new TariffPlan();
        p.setName(name);
        p.setCurrency(currency);
        List<TariffSegment> list = new ArrayList<>();
        if (segments != null) {
            for (TariffSegment s : segments) {
                list.add(s.copy());
            }
        }
        p.setSegments(list);
        return p;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public List<TariffSegment> getSegments() {
        if (segments == null) {
            segments = new ArrayList<>();
        }
        return segments;
    }

    public void setSegments(List<TariffSegment> segments) {
        this.segments = segments;
    }
}
