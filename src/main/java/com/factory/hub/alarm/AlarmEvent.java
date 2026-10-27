package com.factory.hub.alarm;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * 一条告警记录。从「条件满足」到「恢复正常」算一条，不是每秒一条。
 * <p>
 * 状态机：
 * <pre>
 *   (条件持续满足) → ACTIVE ──(人工确认)──→ ACKED ──(条件恢复)──→ CLEARED
 *                       └──────────────(条件恢复)──────────────────┘
 * </pre>
 * 为什么要有 ACKED：车间主任点了「我知道」之后，机器可能还没修好。
 * 这时候既不能算「未处理」（会一直被催），也不能算「已恢复」（骗自己）。
 * 确认动作记的是「人已经知道了」，跟设备好没好是两件事，所以分开记。
 * <p>
 * 为什么要落库：服务重启、客户重启工控机是常事。告警只放内存的话，
 * 一重启「3 号机从昨晚 11 点就停着」这条就没了，等于没报警。
 */
@Entity
@Table(name = "alarm_event", indexes = {
        @Index(name = "idx_alarm_state", columnList = "state,started_at"),
        @Index(name = "idx_alarm_key", columnList = "rule_id,device_code,state"),
        @Index(name = "idx_alarm_started", columnList = "started_at")
})
public class AlarmEvent {

    public static final String STATE_ACTIVE = "ACTIVE";
    public static final String STATE_ACKED = "ACKED";
    public static final String STATE_CLEARED = "CLEARED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "rule_id", length = 64)
    private String ruleId;

    @Column(name = "rule_name", length = 128)
    private String ruleName;

    @Column(name = "rule_type", length = 24)
    private String ruleType;

    @Column(name = "device_code", length = 64)
    private String deviceCode;

    @Column(name = "device_name", length = 128)
    private String deviceName;

    @Column(name = "location", length = 128)
    private String location;

    /** CRITICAL / MAJOR / MINOR */
    @Column(name = "level", length = 16)
    private String level = "MAJOR";

    /** ACTIVE / ACKED / CLEARED */
    @Column(name = "state", length = 16)
    private String state = STATE_ACTIVE;

    @Column(name = "message", length = 512)
    private String message;

    /**
     * 触发时的实际值，比如状态码 0、温度 82.5。
     * <p>
     * 列名不能直接用 value——它是 H2 的保留字，建表会直接语法错误。
     * 换成 metric_value，Java 那边仍叫 value，对外 JSON 也不变。
     */
    @Column(name = "metric_value")
    private Double value;

    @Column(name = "threshold")
    private Double threshold;

    @Column(name = "started_at")
    private long startedAt;

    @Column(name = "cleared_at")
    private long clearedAt;

    @Column(name = "ack_at")
    private long ackAt;

    @Column(name = "ack_by", length = 64)
    private String ackBy;

    /** 通知发了几次。用来排查「为什么我没收到」 */
    @Column(name = "notify_count")
    private int notifyCount;

    @Column(name = "last_notify_at")
    private long lastNotifyAt;

    /** 恢复原因：条件恢复 / 设备删除 / 规则停用 / 人工关闭 */
    @Column(name = "clear_reason", length = 64)
    private String clearReason;

    /** 触发时的规则快照。规则后来被改了，历史记录也能看懂当时是什么条件 */
    @Column(name = "rule_desc", length = 256)
    private String ruleDesc;

    public AlarmEvent() {
    }

    public AlarmEvent(AlarmRule rule, String deviceCode, String deviceName, String location,
                      double value, String message) {
        this.ruleId = rule.getId();
        this.ruleName = rule.getName();
        this.ruleType = rule.getType();
        this.level = rule.getLevel();
        this.deviceCode = deviceCode;
        this.deviceName = deviceName;
        this.location = location;
        this.value = value;
        this.threshold = rule.getThreshold();
        this.message = message;
        this.state = STATE_ACTIVE;
        this.startedAt = System.currentTimeMillis();
        this.ruleDesc = describe(rule);
    }

    /** 把规则翻成一句人话存下来，日后规则改了也能看懂当时判的是什么 */
    private static String describe(AlarmRule r) {
        return switch (r.getType()) {
            case AlarmRule.TYPE_OFFLINE -> "离线超过 " + r.getForSeconds() + " 秒";
            case AlarmRule.TYPE_STOP -> "状态值 = " + (r.getThreshold() == null ? "0" : r.getThreshold())
                    + " 持续 " + r.getForSeconds() + " 秒";
            case AlarmRule.TYPE_FLAG -> (r.getPointKey().isBlank() ? "任一报警位" : r.getPointKey() + " = 1");
            case AlarmRule.TYPE_THRESHOLD -> r.getPointKey() + " " + r.getOp() + " " + r.getThreshold()
                    + " 持续 " + r.getForSeconds() + " 秒";
            default -> r.getType();
        };
    }

    /** 是否还没恢复（不管人确认过没有） */
    public boolean isOpen() {
        return STATE_ACTIVE.equals(state) || STATE_ACKED.equals(state);
    }

    /**
     * 持续时长，未恢复就按当前时间算。
     * <p>
     * 方法名不是 getXxx，Jackson 默认不会序列化它，前端拿到 undefined 会显示成 NaN。
     * 这里显式标注一下，内部调用也能用这个短名字。
     */
    @com.fasterxml.jackson.annotation.JsonProperty("durationMs")
    public long durationMs() {
        long end = clearedAt > 0 ? clearedAt : System.currentTimeMillis();
        return Math.max(0L, end - startedAt);
    }

    /* ==================== getter / setter ==================== */

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getRuleId() {
        return ruleId;
    }

    public void setRuleId(String ruleId) {
        this.ruleId = ruleId;
    }

    public String getRuleName() {
        return ruleName;
    }

    public void setRuleName(String ruleName) {
        this.ruleName = ruleName;
    }

    public String getRuleType() {
        return ruleType;
    }

    public void setRuleType(String ruleType) {
        this.ruleType = ruleType;
    }

    public String getDeviceCode() {
        return deviceCode;
    }

    public void setDeviceCode(String deviceCode) {
        this.deviceCode = deviceCode;
    }

    public String getDeviceName() {
        return deviceName;
    }

    public void setDeviceName(String deviceName) {
        this.deviceName = deviceName;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public String getLevel() {
        return level;
    }

    public void setLevel(String level) {
        this.level = level;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public Double getValue() {
        return value;
    }

    public void setValue(Double value) {
        this.value = value;
    }

    public Double getThreshold() {
        return threshold;
    }

    public void setThreshold(Double threshold) {
        this.threshold = threshold;
    }

    public long getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(long startedAt) {
        this.startedAt = startedAt;
    }

    public long getClearedAt() {
        return clearedAt;
    }

    public void setClearedAt(long clearedAt) {
        this.clearedAt = clearedAt;
    }

    public long getAckAt() {
        return ackAt;
    }

    public void setAckAt(long ackAt) {
        this.ackAt = ackAt;
    }

    public String getAckBy() {
        return ackBy;
    }

    public void setAckBy(String ackBy) {
        this.ackBy = ackBy;
    }

    public int getNotifyCount() {
        return notifyCount;
    }

    public void setNotifyCount(int notifyCount) {
        this.notifyCount = notifyCount;
    }

    public long getLastNotifyAt() {
        return lastNotifyAt;
    }

    public void setLastNotifyAt(long lastNotifyAt) {
        this.lastNotifyAt = lastNotifyAt;
    }

    public String getClearReason() {
        return clearReason;
    }

    public void setClearReason(String clearReason) {
        this.clearReason = clearReason;
    }

    public String getRuleDesc() {
        return ruleDesc;
    }

    public void setRuleDesc(String ruleDesc) {
        this.ruleDesc = ruleDesc;
    }
}
