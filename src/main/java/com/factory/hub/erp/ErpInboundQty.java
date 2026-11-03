package com.factory.hub.erp;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 「设备产出 → 良品入库量」的口径换算（QC-SPEC §2.3 / §7 F1）。
 * <p>
 * <b>要解决的是什么问题</b>：{@code prod_hourly.output} 到底是"总产出（含不良）"
 * 还是"良品数"，取决于现场主计数器接在哪，代码无法判断，只能由设备配置
 * {@code device.counter_mode} 声明。两种口径的换算完全不同：
 * <ul>
 *   <li>{@code TOTAL}（默认）：主计数器含不良 → 入库量 = 总产出 − 不良数；</li>
 *   <li>{@code GOOD}：主计数器只算良品 → 入库量 = 总产出（<b>不能再减</b>，
 *       再减一次等于把不良算了两遍，库存凭空少一截）。</li>
 * </ul>
 * <p>
 * <b>为什么单独一个类</b>：这段算术是"不良品被当良品入库"这个 P0 缺陷的正中间，
 * 必须能脱离 Spring、脱离数据库被单测钉死（AC-08：产出 10 / 不良 2 → 入库必须 8）。
 * 埋在 {@code ErpProdInboundService} 的定时器方法里就只能靠跑一遍真实采集去验证。
 * <p>
 * <b>异常数据按 0 处理并留痕，绝不抛异常</b>：不良数大于总产出只可能是两个计数器
 * 采样时刻错位（不良是在下一批才登记的）。这时把入库量夹到 0、记一条告警，
 * 下一批产出会自然把差额补回来。抛异常会把这批产出永久卡在缓冲里，
 * 而"产出挡不住，账可以事后对"——这个项目里产量丢过一次就再也没找回来。
 */
public record ErpInboundQty(BigDecimal total, BigDecimal defect, BigDecimal good, boolean clamped) {

    /**
     * 按设备口径换算。
     *
     * @param counterMode {@code TOTAL} / {@code GOOD}；null 或未知值按 {@code TOTAL}
     *                    （与全系统"output 含不良"的历史假设保持一致）
     * @param total       这一批的计数增量
     * @param defect      这一批的不良数；null 按 0
     */
    public static ErpInboundQty resolve(String counterMode, BigDecimal total, BigDecimal defect) {
        BigDecimal t = scale(total);
        BigDecimal d = scale(defect);
        if (d.signum() < 0) {
            d = BigDecimal.ZERO;
        }
        // GOOD 口径：主计数器已经是良品数，不良只用于记录，不再从产出里扣
        boolean goodOnly = com.factory.hub.model.DeviceEntity.COUNTER_GOOD.equalsIgnoreCase(counterMode);
        if (goodOnly) {
            return new ErpInboundQty(t, d, t, false);
        }
        BigDecimal g = t.subtract(d);
        if (g.signum() < 0) {
            // 两个计数器采样时刻错位：不良先到、产出后到。
            // 夹到 0，等下一批产出把差额补回来（不抛异常，不阻塞产出登记）
            return new ErpInboundQty(t, d, BigDecimal.ZERO, true);
        }
        return new ErpInboundQty(t, d, g, false);
    }

    /** 有不良数吗（决定要不要在产出入库单的备注里说明） */
    public boolean hasDefect() {
        return defect.signum() > 0;
    }

    private static BigDecimal scale(BigDecimal v) {
        return (v == null ? BigDecimal.ZERO : v).setScale(4, RoundingMode.HALF_UP);
    }
}
