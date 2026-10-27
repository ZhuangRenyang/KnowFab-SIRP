package com.factory.hub.model;

/**
 * 统一数据结构。不管数据来自 Modbus、OPC UA 还是单片机，
 * 出来都是这一个对象。上层（存库、WebSocket、告警、对外 API）只认它。
 */
public record PointValue(
        String deviceCode,
        String pointKey,
        String name,
        Object value,
        String unit,
        String quality,
        String role,
        long ts
) {

    public static final String GOOD = "GOOD";
    public static final String BAD = "BAD";

    public static PointValue good(String deviceCode, PointDef def, Object value, long ts) {
        return new PointValue(deviceCode, def.getKey(), def.getName(),
                value, def.getUnit(), GOOD, def.getRole(), ts);
    }

    public static PointValue bad(String deviceCode, PointDef def, long ts) {
        return new PointValue(deviceCode, def.getKey(), def.getName(),
                null, def.getUnit(), BAD, def.getRole(), ts);
    }

    /** 只给 Java 内部用，不序列化到 JSON，免得污染对外接口 */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isGood() {
        return GOOD.equals(quality);
    }

    public Double asDouble() {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        return null;
    }

    /** 用于变更检测：值和质量都没变就不推送 */
    public boolean sameAs(PointValue other) {
        if (other == null) {
            return false;
        }
        if (!quality.equals(other.quality)) {
            return false;
        }
        if (value == null && other.value == null) {
            return true;
        }
        if (value == null || other.value == null) {
            return false;
        }
        return String.valueOf(value).equals(String.valueOf(other.value));
    }
}
