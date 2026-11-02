package com.factory.hub.manual;

import java.time.LocalDate;

/**
 * 补录请求。日期范围支持一次补多天，服务层按天平均拆开存。
 */
public record ManualEntryRequest(
        String deviceCode,
        LocalDate fromDay,
        LocalDate toDay,
        Double kwh,
        Double output,
        String reason
) {
}
