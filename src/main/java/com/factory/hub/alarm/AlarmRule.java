package com.factory.hub.alarm;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * 一条告警规则 = 「什么条件下、持续多久、算几级报警、要不要发通知」。
 * <p>
 * 规则不是写死在代码里的。现场每台设备的脾气都不一样：
 * 有的机器待机 5 分钟就该报，有的换模具要停半小时；有的晚上必须开着，
 * 有的下班就关。这些只能由客户自己在网页上配，所以全部走数据。
 */
public class AlarmRule {

    /** 设备掉线：连续多久读不到数据 */
    public static final String TYPE_OFFLINE = "OFFLINE";
    /** 停机：状态点等于指定值（默认 0=停机）持续多久 */
    public static final String TYPE_STOP = "STOP";
    /** 报警位：role=alarm 的寄存器不为 0 */
    public static final String TYPE_FLAG = "FLAG";
    /** 数值越限：某个点的值超过阈值 */
    public static final String TYPE_THRESHOLD = "THRESHOLD";

    private String id;

    private String name;

    /** OFFLINE / STOP / FLAG / THRESHOLD */
    private String type = TYPE_STOP;

    /**
     * 适用设备编码，逗号分隔，空 = 全部设备。
     * 不同机器停机容忍时间不同，所以规则要能挑设备。
     */
    private String deviceCodes = "";

    /** FLAG / THRESHOLD 规则要指定看哪个点 */
    private String pointKey = "";

    /** 比较符：> >= < <= == != ，THRESHOLD 用 */
    private String op = ">";

    /** STOP 规则里表示「这个值算异常」，默认 0 停机；THRESHOLD 里表示阈值 */
    private Double threshold;

    /**
     * 条件要连续满足这么久才报，单位秒。
     * 这是最重要的一项：没有它就等着被误报淹没。
     * 机器换刀停 30 秒、通讯抖一下，都不该把车间主任半夜叫起来。
     */
    private int forSeconds = 300;

    /**
     * 恢复也要连续满足这么久才算好，单位秒。
     * 防止在阈值边缘反复跳变导致报警-恢复-报警刷屏。
     */
    private int recoverSeconds = 30;

    /** 恢复后多久内不重复报同一台，单位秒。防止修了又坏、坏了又修刷屏 */
    private int cooldownSeconds = 600;

    /** CRITICAL / MAJOR / MINOR */
    private String level = "MAJOR";

    private boolean enabled = false;

    /**
     * 生效时段，格式 {@code 08:00-20:00}，空 = 全天。
     * 支持跨零点，如 {@code 20:00-06:00}（夜班）。
     * <p>
     * 为什么必须有这个：工厂下班后机器本来就停着。不配时段直接按
     * 「停机超 10 分钟」报警，晚上八点以后全厂几十台一起报，第二天
     * 客户就会把这个功能关掉——那还不如不做。
     */
    private String schedule = "";

    /** 生效星期，格式 {@code 1,2,3,4,5,6}（1=周一），空 = 每天 */
    private String days = "";

    /** 自定义消息模板，支持 {device} {value} {threshold} {duration}，空则用默认文案 */
    private String message = "";

    /** 是否推送通知。关掉就只在页面列表里显示，不打扰人 */
    private boolean notify = true;

    /* ==================== 判断逻辑 ==================== */

    /** 现在这个时刻，这条规则管不管事 */
    public boolean activeAt(long ts) {
        if (!enabled) {
            return false;
        }
        ZonedDateTime t = Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault());
        return inDays(t.getDayOfWeek()) && inSchedule(t.toLocalTime());
    }

    private boolean inDays(DayOfWeek dow) {
        if (days == null || days.isBlank()) {
            return true;
        }
        int d = dow.getValue();       // 1=周一 ... 7=周日
        for (String s : days.split(",")) {
            try {
                if (Integer.parseInt(s.trim()) == d) {
                    return true;
                }
            } catch (NumberFormatException ignored) {
                // 客户手改配置写错了，忽略这一项，别让整个规则失效
            }
        }
        return false;
    }

    private boolean inSchedule(LocalTime now) {
        if (schedule == null || schedule.isBlank()) {
            return true;
        }
        int dash = schedule.indexOf('-');
        if (dash <= 0) {
            return true;
        }
        LocalTime start = parseTime(schedule.substring(0, dash).trim());
        LocalTime end = parseTime(schedule.substring(dash + 1).trim());
        if (start == null || end == null) {
            return true;
        }
        if (!start.isAfter(end)) {
            // 普通时段，含头不含尾：08:00-20:00 表示 20:00 整已经下班
            return !now.isBefore(start) && now.isBefore(end);
        }
        // 跨零点，如 20:00-06:00
        return !now.isBefore(start) || now.isBefore(end);
    }

    private static LocalTime parseTime(String s) {
        try {
            String[] p = s.split(":");
            int h = Integer.parseInt(p[0]);
            int m = p.length > 1 ? Integer.parseInt(p[1]) : 0;
            if (h < 0 || h > 23 || m < 0 || m > 59) {
                return null;
            }
            return LocalTime.of(h, m);
        } catch (Exception e) {
            return null;
        }
    }

    /** 这条规则管不管这台设备 */
    public boolean covers(String deviceCode) {
        if (deviceCodes == null || deviceCodes.isBlank()) {
            return true;
        }
        for (String s : deviceCodes.split(",")) {
            String c = s.trim();
            if (c.isEmpty()) {
                continue;
            }
            if (c.equalsIgnoreCase(deviceCode)) {
                return true;
            }
            // 支持车间前缀，如 "CNC-*" 管所有加工中心。
            // 一个厂几十台同类设备时，逐台勾太折磨人。
            if (c.endsWith("*") && deviceCode.toLowerCase()
                    .startsWith(c.substring(0, c.length() - 1).toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    /** 配置体检。返回人话，前端直接显示 */
    public String validate() {
        if (name == null || name.isBlank()) {
            return "规则名称不能为空";
        }
        if (forSeconds < 0 || recoverSeconds < 0 || cooldownSeconds < 0) {
            return "秒数不能为负数";
        }
        if (TYPE_THRESHOLD.equals(type) && (pointKey == null || pointKey.isBlank())) {
            return "数值越限规则必须指定点位";
        }
        if (TYPE_THRESHOLD.equals(type) && threshold == null) {
            return "数值越限规则必须填阈值";
        }
        if (schedule != null && !schedule.isBlank()
                && (parseTime(schedule.split("-")[0].trim()) == null
                || schedule.indexOf('-') < 0
                || parseTime(schedule.substring(schedule.indexOf('-') + 1).trim()) == null)) {
            return "生效时段格式应为 08:00-20:00";
        }
        if (forSeconds < 30 && (TYPE_STOP.equals(type) || TYPE_OFFLINE.equals(type))) {
            return "建议持续时间不少于 30 秒，否则网络抖动就会报警";
        }
        return null;
    }

    public String typeName() {
        return switch (type == null ? "" : type) {
            case TYPE_OFFLINE -> "设备离线";
            case TYPE_STOP -> "设备停机";
            case TYPE_FLAG -> "报警信号";
            case TYPE_THRESHOLD -> "数值越限";
            default -> type;
        };
    }

    /* ==================== getter / setter ==================== */

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name == null ? typeName() : name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getDeviceCodes() {
        return deviceCodes == null ? "" : deviceCodes;
    }

    public void setDeviceCodes(String deviceCodes) {
        this.deviceCodes = deviceCodes;
    }

    public String getPointKey() {
        return pointKey == null ? "" : pointKey;
    }

    public void setPointKey(String pointKey) {
        this.pointKey = pointKey;
    }

    public String getOp() {
        return op == null ? ">" : op;
    }

    public void setOp(String op) {
        this.op = op;
    }

    public Double getThreshold() {
        return threshold;
    }

    public void setThreshold(Double threshold) {
        this.threshold = threshold;
    }

    public int getForSeconds() {
        return forSeconds;
    }

    public void setForSeconds(int forSeconds) {
        this.forSeconds = forSeconds;
    }

    public int getRecoverSeconds() {
        return recoverSeconds;
    }

    public void setRecoverSeconds(int recoverSeconds) {
        this.recoverSeconds = recoverSeconds;
    }

    public int getCooldownSeconds() {
        return cooldownSeconds;
    }

    public void setCooldownSeconds(int cooldownSeconds) {
        this.cooldownSeconds = cooldownSeconds;
    }

    public String getLevel() {
        return level == null ? "MAJOR" : level;
    }

    public void setLevel(String level) {
        this.level = level;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getSchedule() {
        return schedule == null ? "" : schedule;
    }

    public void setSchedule(String schedule) {
        this.schedule = schedule;
    }

    public String getDays() {
        return days == null ? "" : days;
    }

    public void setDays(String days) {
        this.days = days;
    }

    public String getMessage() {
        return message == null ? "" : message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public boolean isNotify() {
        return notify;
    }

    public void setNotify(boolean notify) {
        this.notify = notify;
    }
}
