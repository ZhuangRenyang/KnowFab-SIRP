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
        /* DH-15：理想节拍（秒/件） */
        Double idealCycleSec,
        /* 主计数器口径 TOTAL / GOOD：决定入库量要不要减不良数 */
        String counterMode,
        /*
         * 生产入库联动（DH-44）的四项配置。
         * <p>
         * 为什么列表视图也要带这几项：它们决定「这台设备的产出会不会自动变成入库单」，
         * 是排查「产量涨了但没入库」时的第一现场。以前只有编辑表单（DeviceConfig）返回，
         * 想确认必须逐台点开表单——现场没法一眼看出是哪台没配。
         * 只读展示，改动仍走 DeviceUpsert（写入口不变）。
         */
        Long prodSkuId,
        Long prodWhId,
        Integer prodResetHour,
        boolean prodAutoStockin,
        Double todayOutput,
        List<PointValue> points
) {
}
