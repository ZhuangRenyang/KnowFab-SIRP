package com.factory.hub.erp.quality.inspection;

import com.factory.hub.report.ProdHourly;
import com.factory.hub.report.ProdHourlyRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 设备采集数的唯一算法（同一设备在某个时间窗内采到的不良件数）。
 *
 * <p><b>为什么单独一个类</b>：对账条（{@link ErpQcReconService}）和人工补录基线
 * （{@link ErpQcDefectQueryService#deviceBaseline}）都要这个数。
 * 两处各写一份的后果不是代码丑，是<b>两个数对不上</b>——
 * 用户看着补录弹窗说"设备采了 3 件"，对账条却说"设备采了 0 件"，
 * 然后他会认为系统算错了，去哪都绕不出来。
 *
 * <p><b>边界口径（这里最容易错）</b>：小时行与工单时间窗<b>有交集</b>就算，
 * 不能按「小时起点落在窗内」筛。
 * <p>
 * 工单几乎都是任意时刻开工的：12:35 开工、13:10 完工，那么 12:00 那一小时
 * <b>也在这张工单的时间窗里</b>（12:35–13:00 那 25 分钟属于它）。
 * 只按小时起点筛会把这个小时整行漏掉 ——
 * <b>等于每张工单的设备数都偏小，于是每张工单都误报"两边对不上"</b>，
 * 对账功能形同虚设。
 * <p>
 * 查库范围要放宽一小时（{@code from - 1h}），否则交集判断在边界上会漏。
 */
public final class ErpQcDeviceQty {

    private static final long HOUR_MS = 3600_000L;

    private ErpQcDeviceQty() {
    }

    /**
     * 该设备在 {@code [from, to]} 时间内采到的不良件数。
     *
     * @param from 起始时间戳（含）
     * @param to   结束时间戳（含）
     */
    public static BigDecimal of(ProdHourlyRepository prodRepo, String deviceCode,
                                long from, long to) {
        if (deviceCode == null || to < from) {
            return BigDecimal.ZERO;
        }
        BigDecimal sum = BigDecimal.ZERO;
        // 放宽一小时查出来，再按"有交集"筛
        for (ProdHourly p : prodRepo.findByDeviceCodeAndHourStartBetween(
                deviceCode, from - HOUR_MS, to)) {
            long hourEnd = p.getHourStart() + HOUR_MS;
            if (hourEnd > from && p.getHourStart() < to) {
                sum = sum.add(BigDecimal.valueOf(p.getDefect()));
            }
        }
        return sum.setScale(4, RoundingMode.HALF_UP);
    }
}
