package com.factory.hub.web.dto;

/**
 * 设备完整配置，只给「编辑」用。
 * 单独一个接口，是因为列表接口要保持轻量：
 * 500 台设备要是每台都带上点表 JSON，列表就是 1MB 以上，前端每次刷新都卡。
 */
public record DeviceConfig(
        Long id,
        String code,
        String name,
        String location,
        String protocol,
        String host,
        Integer port,
        String serialPort,
        Integer baudRate,
        Integer dataBits,
        Integer stopBits,
        Integer parity,
        Integer unitId,
        Integer pollIntervalMs,
        String servoBrand,
        Integer timeoutMs,
        Integer offlineAfterFails,
        Boolean apiKeyEnabled,
        Boolean enabled,
        String apiKey,
        String pointsJson,
        Long prodSkuId,
        Long prodWarehouseId,
        Integer prodResetHour,
        Boolean prodAutoStockin
) {
}
