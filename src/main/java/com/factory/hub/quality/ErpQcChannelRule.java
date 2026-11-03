package com.factory.hub.quality;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>有效通道口径的唯一实现</b>（QC-SPEC §2.5.1）。
 *
 * <p><b>为什么要有这个类</b>：同一批货可能有两个来源——设备计数器采的、人工补录的、
 * 检验判定出的、客户退货的。它们描述的是同一件事的两次记录，
 * <b>统计时相加就是重复计数</b>。而这种错误不报错：数字偏大、页面正常、
 * 良率看着还挺合理，月底盘账才发现。
 *
 * <p>本类之前，各处统计各写一句 {@code sum(qty)}（仓库里 6 条 SQL + 服务里 2 处遍历），
 * 每处都要自己判断"要不要排除设备通道"——漏一处就是一个新的重复计数。
 * <b>现在口径只有这一处</b>：看板、待处置、帕累托、未分类全部走这里。
 *
 * <p><b>规则（一条决策树）</b>，对每个工单分组单独判断：
 * <ol>
 *   <li>先排除被定责标记为 {@code DROP} 的行——那是被判"不成立"的通道；</li>
 *   <li>若组内有任何 {@code KEEP} 行 → <b>只算 KEEP 行</b>：定责已经明确指定了哪个通道说了算；</li>
 *   <li>否则（全都未定责）→ 若组内有设备通道的行 → <b>只算设备通道</b>：
 *       设备配了采集点且有数据时，人工补录只是对照，<b>不叠加</b>（§2.5.1 通则 2）；</li>
 *   <li>否则（没有设备数据）→ 算全部人工记录。</li>
 * </ol>
 *
 * <p><b>不变式（单测必须钉死）</b>：定责是<b>标记</b>不是改数，
 * 所以「同一次定责重复执行，合计数不变」。
 * 如果改数实现（{@code DEVICE := qcQty}），合计会越校越大（3+2 → 5+2=7 → 7+2=9）。
 *
 * <p><b>已处置的行怎么办</b>：定责只标记<b>未处置</b>的行；已处置的（已报废/已让步）
 * 是既成事实，不能因为定责就当作没发生过。但它们<b>不计入有效通道合计</b>——
 * 有效通道回答的是"这批货现在还剩多少问题"，而已发生的损失由
 * 报废/让步的独立统计给出（看板的 {@code scrapQty} / {@code concessionQty}）。
 */
public final class ErpQcChannelRule {

    private ErpQcChannelRule() {
    }

    /**
     * 单条记录是否计入统计。
     * 只有被定责标记为 {@code DROP} 的才排除；作废的行一律不算（那是废单）。
     */
    public static boolean counted(ErpQcDefect d) {
        return d != null && !d.isVoided() && d.countedInStats();
    }

    /**
     * 某个工单分组的有效通道合计。
     *
     * @param group 同一个工单的不良记录（可含设备通道、人工通道、检验判定）
     */
    public static BigDecimal effectiveQtyOfGroup(List<ErpQcDefect> group) {
        if (group == null || group.isEmpty()) {
            return BigDecimal.ZERO;
        }
        List<ErpQcDefect> alive = new ArrayList<>(group.size());
        for (ErpQcDefect d : group) {
            if (counted(d)) {
                alive.add(d);
            }
        }
        if (alive.isEmpty()) {
            return BigDecimal.ZERO;
        }
        // 定责过：有 KEEP 行 → 只算 KEEP 行
        boolean hasKeep = false;
        boolean hasDevice = false;
        for (ErpQcDefect d : alive) {
            if (ErpQcDefect.RECON_KEEP.equals(d.getReconState())) {
                hasKeep = true;
            }
            if (ErpQcDefect.SRC_DEVICE.equals(d.getSource())) {
                hasDevice = true;
            }
        }
        if (hasKeep) {
            BigDecimal sum = BigDecimal.ZERO;
            for (ErpQcDefect d : alive) {
                if (ErpQcDefect.RECON_KEEP.equals(d.getReconState())) {
                    sum = sum.add(nz(d.getQty()));
                }
            }
            return scale(sum);
        }
        // 未定责：设备通道优先，人工不叠加
        if (hasDevice) {
            BigDecimal sum = BigDecimal.ZERO;
            for (ErpQcDefect d : alive) {
                if (ErpQcDefect.SRC_DEVICE.equals(d.getSource())) {
                    sum = sum.add(nz(d.getQty()));
                }
            }
            return scale(sum);
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (ErpQcDefect d : alive) {
            sum = sum.add(nz(d.getQty()));
        }
        return scale(sum);
    }

    /**
     * 一批记录（可能跨多个工单）的有效通道合计。
     * <p>按工单分组后逐组套用规则——同一工单的两个通道不能相加，
     * 不同工单之间互不相关（各自有自己的设备与人工）。
     */
    public static BigDecimal effectiveQty(List<ErpQcDefect> rows) {
        if (rows == null || rows.isEmpty()) {
            return BigDecimal.ZERO;
        }
        Map<String, List<ErpQcDefect>> groups = new LinkedHashMap<>();
        for (ErpQcDefect d : rows) {
            groups.computeIfAbsent(groupKey(d), k -> new ArrayList<>()).add(d);
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (List<ErpQcDefect> g : groups.values()) {
            sum = sum.add(effectiveQtyOfGroup(g));
        }
        return scale(sum);
    }

    /**
     * 分组键：同一张工单的记录算一组。
     * <p>没有工单号的记录各自算一组——它们互不相关（人工补录可能没填工单，
     * 但那不代表它们描述同一批货）。
     */
    public static String groupKey(ErpQcDefect d) {
        String wo = d.getWorkOrderNo();
        if (wo != null && !wo.isBlank()) {
            return "WO:" + wo;
        }
        return "ROW:" + (d.getId() == null ? "?" : d.getId());
    }

    private static BigDecimal scale(BigDecimal v) {
        return v.setScale(4, RoundingMode.HALF_UP);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
