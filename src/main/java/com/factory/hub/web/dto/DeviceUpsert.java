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
        List<PointDef> points,
        /* 生产入库联动（DH-44） */
        Long prodSkuId,
        Long prodWarehouseId,
        Integer prodResetHour,
        Boolean prodAutoStockin,
        Integer dailyTarget,
        /* DH-15：理想节拍（秒/件），OEE 性能因子用 */
        Double idealCycleSec,
        /* 主计数器口径 TOTAL / GOOD：决定入库量要不要减不良数，配错会多算或少算库存 */
        String counterMode,
        /* 是否总表（进线计量点）：参与需量与基本电费的全厂口径 */
        Boolean isMeter,
        /*
         * 本机源地址（可选）：指定这台设备用本机的哪个 IP 出去。
         * 留空 = 交给系统路由表自动选（绝大多数情况这样就对）。
         * ⚠️ 只对 Modbus 生效；OPC UA 填了会明确报错（底层 milo 不支持）。
         * ⚠️ 多网段不需要填这个字段 —— 给本机加 IP 别名即可（tools/net/plc-on）。
         */
        String sourceAddress
) {
}
