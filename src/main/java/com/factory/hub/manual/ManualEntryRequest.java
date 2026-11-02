package com.factory.hub.manual;

import java.time.LocalDate;

/**
 * 补录请求。日期范围支持一次补多天，服务层按天平均拆开存。
 *
 * @param defect 补录的不良品数（件，DH-15）。<b>null = 没录</b>，0 = 录了「零不良」。
 *               这两者必须分得开：没录说明数据缺失（良率算不出来），
 *               录了 0 说明确实是零不良。混为一谈会让良率假成 100%。
 */
public record ManualEntryRequest(
        String deviceCode,
        LocalDate fromDay,
        LocalDate toDay,
        Double kwh,
        Double output,
        Double defect,
        String reason
) {
}
