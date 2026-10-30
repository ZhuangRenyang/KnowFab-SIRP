package com.factory.hub.web.dto;

import com.factory.hub.model.PointValue;

import java.util.List;

/**
 * 设备视图。前端拿这一个对象就能把卡片、详情、配置全画出来。
 */
public record DeviceView(
        Long id,
        String code,
        String name,
        String location,
        String protocol,
        Boolean enabled,
        String host,
        Integer port,
        String serialPort,
        Integer baudRate,
        Integer parity,
        Integer unitId,
        Integer pollIntervalMs,
        String servoBrand,
        String apiKey,
        boolean apiKeyEnabled,
        boolean online,
        Long lastOkTs,
        String lastError,
        long successCount,
        long failCount,
        long costMs,
        Long lineId,
        String lineName,
        int lineSeq,
        Integer dailyTarget,
        Double todayOutput,
        List<PointValue> points
) {
}
