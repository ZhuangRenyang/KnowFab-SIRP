package com.factory.hub.erp;

import java.math.BigDecimal;
import java.util.List;

/**
 * 质量退货闸门——<b>ERP 层与质量域之间的契约</b>（QC-SPEC §3.5 / AC-13 / AC-14）。
 *
 * <p><b>为什么接口放在 erp 包里</b>：依赖方向必须单向。
 * 质量域（{@code com.factory.hub.erp.quality.*}）依赖 ERP（{@code com.factory.hub.erp}），
 * 反过来就不行。所以 ERP 层只声明"我需要一个质量退货闸门"这个契约，
 * 由质量域提供实现，Spring 在运行时装配。ERP 代码里不会出现 {@code quality} 包的引用。
 *
 * <p><b>为什么退货必须按原因分流</b>：
 * 客户因质量问题退回来的货，和因"发错了/不要了"退回来的货，是两件不同的事。
 * 前者必须进禁发仓并生成不良记录（等着处置），后者正常回良品仓。
 * 不分流的后果是<b>最坏的那种</b>：质量退货按普通良品入库，
 * 重新发给下一个客户——<b>质量问题被彻底抹掉</b>，看板上也看不出来。
 * 而界面还显示着"退货原因：质量问题"——<b>界面说对了，账做错了</b>。
 */
public interface ErpQualityReturnGate {

    /**
     * 退货原因：质量问题。
     * <p>这条会强制入禁发仓并生成待处置的不良记录。
     */
    String REASON_QUALITY = "QUALITY";

    /**
     * 退货原因：其它（非质量原因）。
     * <p>正常回良品仓，不生成不良记录。
     */
    String REASON_OTHER = "OTHER";

    /**
     * 退货前的准备：决定这批货实际入哪个仓。
     *
     * @param returnReason 退货原因（{@link #REASON_QUALITY} / {@link #REASON_OTHER}），
     *                      <b>不能为空</b>——空则抛异常，不落默认值
     * @param requestedWhId 前端选的仓库（非质量原因时用它）
     * @return 实际入库的仓库 id。质量原因返回<b>禁发仓</b>的 id（不是前端选的那个）
     * @throws IllegalArgumentException 原因缺失/非法，或找不到禁发仓
     */
    Long resolveReturnWarehouse(String returnReason, Long requestedWhId);

    /**
     * 退货入库完成后补记不良记录（仅质量原因会走到这里）。
     * <p>必须在入库之后调用：不良记录要记入库单号（{@code srcBillNo}），
     * 之后处置"报废"才知道该去哪个仓扣库存。
     *
     * @param orderNo    原销售订单号
     * @param customerId 客户 id
     * @param whId       实际入库的仓库 id
     * @param billNo     入库单号
     * @param lines      退货明细
     * @param remark     备注
     * @param operator   操作人
     */
    void registerReturnDefects(String orderNo, Long customerId, Long whId, String billNo,
                                List<QualityReturnLine> lines, String remark, String operator);

    /**
     * 退货明细的一行。
     *
     * @param skuId 商品 id
     * @param qty   退货数量（件）
     */
    record QualityReturnLine(Long skuId, BigDecimal qty) {
    }
}
