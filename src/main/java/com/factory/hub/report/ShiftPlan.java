package com.factory.hub.report;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * 班次方案。三班倒、两班倒、常白班都靠这个描述。
 * <p>
 * 只存「几点到几点」，不存产量——产量永远从小时表现算。
 * 这样客户改班次，历史报表还能按新班次重算，不会因为改配置把历史废掉。
 */
public class ShiftPlan {

    private String name = "三班倒";

    private List<Shift> shifts = new ArrayList<>();

    public static class Shift {
        private String id = "";
        private String name = "";
        /** HH:mm */
        private String start = "08:00";
        /** HH:mm。允许小于 start，表示跨零点 */
        private String end = "16:00";

        public Shift() {
        }

        public Shift(String id, String name, String start, String end) {
            this.id = id;
            this.name = name;
            this.start = start;
            this.end = end;
        }

        public int startMin() {
            return toMin(start);
        }

        public int endMin() {
            return toMin(end);
        }

        /** 这个班次是不是跨零点（夜班 20:00-08:00 就是） */
        public boolean crossMidnight() {
            return endMin() <= startMin();
        }

        public String getLabel() {
            return name + " " + start + "-" + end;
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
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
    }

    /** 默认三班倒。客户现场九成是这种，改起来也简单 */
    public static ShiftPlan defaults() {
        ShiftPlan p = new ShiftPlan();
        p.name = "三班倒";
        p.shifts.add(new Shift("A", "早班", "08:00", "16:00"));
        p.shifts.add(new Shift("B", "中班", "16:00", "00:00"));
        p.shifts.add(new Shift("C", "夜班", "00:00", "08:00"));
        return p;
    }

    /**
     * 某个班次在指定日期那一次的具体时间段。
     * <p>
     * 跨零点的班次（20:00-08:00）算在「开班那天」：
     * 10 月 26 日夜班是 26 日 20:00 到 27 日 08:00，产量记在 26 日。
     * 车间就是这么记账的——晚上八点开班，第二天早上八点交班，
     * 这一班算 26 号的产量。
     */
    public long[] windowOf(Shift s, LocalDate date) {
        ZoneId zone = ZoneId.systemDefault();
        LocalDateTime startAt = date.atStartOfDay().plusMinutes(s.startMin());
        LocalDateTime endAt = s.crossMidnight()
                ? date.plusDays(1).atStartOfDay().plusMinutes(s.endMin())
                : date.atStartOfDay().plusMinutes(s.endMin());
        return new long[]{
                startAt.atZone(zone).toInstant().toEpochMilli(),
                endAt.atZone(zone).toInstant().toEpochMilli()
        };
    }

    /** 体检。返回人话，前端直接显示 */
    public List<String> validate() {
        List<String> out = new ArrayList<>();
        if (shifts == null || shifts.isEmpty()) {
            out.add("还没有配置班次，报表算不出每一班的产量");
            return out;
        }
        for (Shift s : shifts) {
            if (toMin(s.getStart()) < 0 || toMin(s.getEnd()) < 0) {
                out.add("班次「" + s.getName() + "」的时间格式不对，应该是 08:00 这种");
            }
        }
        // 时段重叠会让同一小时被算进两个班，产量凭空翻倍
        for (int i = 0; i < shifts.size(); i++) {
            for (int j = i + 1; j < shifts.size(); j++) {
                if (overlap(shifts.get(i), shifts.get(j))) {
                    out.add("班次「" + shifts.get(i).getName() + "」和「"
                            + shifts.get(j).getName() + "」的时间有重叠，产量会重复计算");
                }
            }
        }
        return out;
    }

    private boolean overlap(Shift a, Shift b) {
        for (int[] x : intervalsOf(a)) {
            for (int[] y : intervalsOf(b)) {
                if (x[0] < y[1] && y[0] < x[1]) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 把一个班次拆成一天内的若干区间 [起, 止)。跨零点的班次拆成两段。
     * 不拆的话 20:00-08:00 会变成「起点比终点大」的怪数，重叠检测直接失效。
     */
    private List<int[]> intervalsOf(Shift s) {
        List<int[]> out = new ArrayList<>();
        int start = s.startMin();
        int end = s.endMin();
        if (start < 0 || end < 0) {
            return out;
        }
        if (s.crossMidnight()) {
            out.add(new int[]{start, 1440});
            if (end > 0) {
                out.add(new int[]{0, end});
            }
        } else if (end > start) {
            out.add(new int[]{start, end});
        }
        return out;
    }

    static int toMin(String hhmm) {
        try {
            LocalTime t = LocalTime.parse(hhmm.trim());
            return t.getHour() * 60 + t.getMinute();
        } catch (Exception e) {
            return -1;
        }
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public List<Shift> getShifts() {
        return shifts;
    }

    public void setShifts(List<Shift> shifts) {
        this.shifts = shifts;
    }
}
