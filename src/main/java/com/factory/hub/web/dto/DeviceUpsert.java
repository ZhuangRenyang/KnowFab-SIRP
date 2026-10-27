package com.factory.hub.web.dto;

import com.factory.hub.model.PointDef;

import java.util.List;

/**
 * 新增/修改设备的请求体。点表用数组传，后台自动转成 JSON 落库，
 * 前端不用自己拼字符串。
 */
public record DeviceUpsert(
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
        Integer timeoutMs,
        Integer offlineAfterFails,
        Boolean apiKeyEnabled,
        String servoBrand,
        Boolean enabled,
        String apiKey,
        List<PointDef> points
) {
}
