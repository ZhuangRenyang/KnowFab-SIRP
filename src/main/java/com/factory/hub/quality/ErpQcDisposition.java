package com.factory.hub.quality;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;

/**
 * 处置明细——一批不良可以分多次、用不同方式处置。
 * <p>
 * <b>为什么是明细表而不是在不良记录上存一个 {@code disposition} 字段</b>：
 * 真实场景是"这批 8 件，3 件报废 + 5 件返工"。存单值只能二选一——
 * 填报废则 8 件全废（返工那 5 件白扔），填返工则那 3 件凭空变成不存在的工时。
 * 拆成两条互相 split 的记录更糟：会打穿设备通道的幂等键 {@code DEV:设备:小时}
 * （拆分后同步逻辑会把数量覆盖回去，合计凭空多 5 件），而且会级联成树、改写历史数字。
 * 明细表把"原始 8 件"钉住不动，两条明细各记 3 件和 5 件。
 * <p>
 * <b>只追加、只作废，永不改数量</b>：撤销 = 先冲销这次处置产生的库存单据，
 * 再给本行打 {@code voidedAt}，原因强制必填。<b>不改 {@code qty}、不删行</b>——
 * 质量记录被无声抹掉，比留一条被撤销的记录严重得多；审计要的就是"撤过几次、谁撤的、为什么"。
 * <p>
 * <b>{@code failQty} 是这个设计能闭合的关键</b>：6 件返工、复检 1 件失败 →
 * 本行记 {@code qty=6, failQty=1}，有效消费 5 件，可处置额度回到 1 →
 * 再追加一条 {@code SCRAP 1}。守恒：{@code (2+5)+1 = 8}。
 * 没有 {@code failQty} 的话那 1 件无处安放，只能作废整条 REWORK 再重建
 * {@code REWORK 5 + SCRAP 1}，而作废会把 {@code reworkOrderNo} 一起废掉、追溯断链。
 * <p>
 * <b>派生规则</b>：
 * <pre>
 *   有效处置量 = qty - failQty        （见 {@link #effectiveQty()}）
 *   已处置     = sum(有效处置量) where voidedAt is null
 *   待处置     = 不良单.qty - 已处置
 * </pre>
 * 注意 {@code failQty} 会让状态往回跳（DISPOSED → PENDING），这是正确行为：
 * 那 1 件确实又变回待处置了。
 * <p>
 * <b>三种处置动库存的方式完全不同</b>（见 Spec §3.4 矩阵）：
 * <ul>
 *   <li><b>报废 SCRAP</b>：已入账 → {@code SCRAP_OUT} 出库（qty 为负）；未入账 → 不动库存；</li>
 *   <li><b>返工 REWORK</b>：未入账 → 建 {@code REWORK} 工单，返工良品 {@code PROD_IN} 入库；
 *       已入账 → 先 {@code TRANSFER} 到返工仓。<b>已入账时系统不会自动把货从仓库领到车间</b>，
 *       需要先手工做一张其他出库，否则库存里永远躺着这批货（实际已在车间修完），月底盘亏；</li>
 *   <li><b>让步 CONCESSION</b>：未入账 → {@code OTHER_IN} 入禁发仓；
 *       已入账 → {@code TRANSFER} 原仓 → 禁发仓。
 *       不新增 {@code CONCESSION_IN} 类型：让价损失记在本行的 {@code concessionAmount} 上，
 *       发货风险由"禁发仓"这个仓维度硬拦（源仓是禁发仓且无客户同意即拒）。</li>
 * </ul>
 */
@Entity
@Table(name = "erp_qc_disposition", indexes = {
        @Index(name = "uk_qc_disp_seq", columnList = "defect_id,seq_no", unique = true),
        @Index(name = "idx_qc_disp_defect", columnList = "defect_id"),
        @Index(name = "idx_qc_disp_action", columnList = "action"),
        @Index(name = "idx_qc_disp_at", columnList = "disposed_at"),
        @Index(name = "idx_qc_disp_bill", columnList = "stock_bill_no"),
        @Index(name = "idx_qc_disp_fqc", columnList = "fqc_inspection_no")
})
public class ErpQcDisposition {

    /** 报废：损失摊入良品单位成本 */
    public static final String ACT_SCRAP = "SCRAP";
    /** 返工：建返工工单，返工后可回写失败件数 */
    public static final String ACT_REWORK = "REWORK";
    /** 让步接收：进禁发仓 + 让价损失 */
    public static final String ACT_CONCESSION = "CONCESSION";

    public static final java.util.List<String> ACTIONS = java.util.List.of(
            ACT_SCRAP, ACT_REWORK, ACT_CONCESSION);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属不良记录 id */
    @Column(name = "defect_id", nullable = false)
    private Long defectId;

    /** 第几次处置，从 1 开始。与 defect_id 组成唯一键；撤销过的行仍占着序号（不回收） */
    @Column(name = "seq_no", nullable = false)
    private int seqNo = 1;

    /** 处置方式 SCRAP / REWORK / CONCESSION */
    @Column(name = "action", nullable = false, length = 16)
    private String action;

    /** 本次处置件数（正数）。累计有效处置量不能超过不良单的 qty */
    @Column(name = "qty", nullable = false, precision = 18, scale = 4)
    private BigDecimal qty = BigDecimal.ZERO;

    /**
     * 本次返工失败退回的件数（只有 REWORK 用，其余恒为 0）。
     * <p>
     * 约束：{@code 0 <= failQty <= qty}。它把返工失败的件数退回待处置，
     * 是"不删行、不改数量"能表达"6 件返工里 1 件没修好"的唯一办法。
     */
    @Column(name = "fail_qty", nullable = false, precision = 18, scale = 4)
    private BigDecimal failQty = BigDecimal.ZERO;

    /** 返工失败原因（"同轴度仍超差""客户退回二次维修"），复检不合格时必填 */
    @Column(name = "fail_reason", length = 255)
    private String failReason;

    /** 本次处置产生的库存单据号（报废出库单/调拨单/其他入库单）；不动库存时为空 */
    @Column(name = "stock_bill_no", length = 40)
    private String stockBillNo;

    /** 返工工单号（REWORK 且已建单时非空） */
    @Column(name = "rework_order_no", length = 40)
    private String reworkOrderNo;

    /** 返工后的复检单号（FQC）；空 = 还没复检，界面显示"待复检" */
    @Column(name = "fqc_inspection_no", length = 40)
    private String fqcInspectionNo;

    /* ---- 让步接收专用 ---- */

    @Column(name = "concession_partner_id")
    private Long concessionPartnerId;

    /** 让价关联的销售订单号：追溯"这批让步品最后卖给谁" */
    @Column(name = "concession_so_no", length = 40)
    private String concessionSoNo;

    /** 内部批准人（经理签字，未必等于操作人）。<b>这不等于客户同意</b>，见下 */
    @Column(name = "concession_approver", length = 64)
    private String concessionApprover;

    /**
     * 客户同意凭证号（邮件号/盖章单号）。
     * <p>
     * <b>空 = 未取得客户同意</b>，源仓是禁发仓时这次出库会被硬拦。
     * {@code concessionApprover} 只能证明"我们内部有人批了"，证明不了"客户同意了"，
     * 拿它去挡发货是把内部决定当成客户承诺。出库判定只认这一列。
     */
    @Column(name = "concession_ack_bill_no", length = 64)
    private String concessionAckBillNo;

    /** 登记客户同意的时间 */
    @Column(name = "concession_ack_at")
    private Long concessionAckAt;

    /** 登记客户同意的人（内部操作人，不是客户方） */
    @Column(name = "concession_ack_by", length = 64)
    private String concessionAckBy;

    /** 本次让价金额（损失），未让步为 0 */
    @Column(name = "concession_amount", precision = 18, scale = 4)
    private BigDecimal concessionAmount;

    /** 本次处置的直接损失（报废的材料+工时成本），成本报表按它汇总 */
    @Column(name = "cost_amount", precision = 18, scale = 4)
    private BigDecimal costAmount;

    /**
     * 成本只算了一部分（缺 BOM 单价/缺工时费率等）。
     * <p>
     * 有这一列，成本报表才能把"算不出来"和"真的零成本"分开——
     * 少了它，缺数据的处置会被当成零损失，质量成本月报永远偏低且看不出来。
     */
    @Column(name = "cost_partial", nullable = false)
    private boolean costPartial = false;

    @Column(name = "operator", nullable = false, length = 64)
    private String operator;

    @Column(name = "disposed_at", nullable = false)
    private long disposedAt = System.currentTimeMillis();

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "remark")
    private String remark;

    /** 撤销时间；null = 有效。撤销 = 冲销单据 + 打时间戳，不删行、不改数量 */
    @Column(name = "voided_at")
    private Long voidedAt;

    @Column(name = "voided_by", length = 64)
    private String voidedBy;

    /** 撤销原因：强制必填，审计要的就是这句人话 */
    @Column(name = "void_reason", length = 255)
    private String voidReason;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getDefectId() { return defectId; }
    public void setDefectId(Long defectId) { this.defectId = defectId; }
    public int getSeqNo() { return seqNo; }
    public void setSeqNo(int seqNo) { this.seqNo = seqNo; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public BigDecimal getQty() { return qty; }
    public void setQty(BigDecimal qty) { this.qty = qty; }
    public BigDecimal getFailQty() { return failQty; }
    public void setFailQty(BigDecimal failQty) { this.failQty = failQty; }
    public String getFailReason() { return failReason; }
    public void setFailReason(String failReason) { this.failReason = failReason; }
    public String getStockBillNo() { return stockBillNo; }
    public void setStockBillNo(String stockBillNo) { this.stockBillNo = stockBillNo; }
    public String getReworkOrderNo() { return reworkOrderNo; }
    public void setReworkOrderNo(String reworkOrderNo) { this.reworkOrderNo = reworkOrderNo; }
    public String getFqcInspectionNo() { return fqcInspectionNo; }
    public void setFqcInspectionNo(String fqcInspectionNo) { this.fqcInspectionNo = fqcInspectionNo; }
    public Long getConcessionPartnerId() { return concessionPartnerId; }
    public void setConcessionPartnerId(Long concessionPartnerId) { this.concessionPartnerId = concessionPartnerId; }
    public String getConcessionSoNo() { return concessionSoNo; }
    public void setConcessionSoNo(String concessionSoNo) { this.concessionSoNo = concessionSoNo; }
    public String getConcessionApprover() { return concessionApprover; }
    public void setConcessionApprover(String concessionApprover) { this.concessionApprover = concessionApprover; }
    public String getConcessionAckBillNo() { return concessionAckBillNo; }
    public void setConcessionAckBillNo(String concessionAckBillNo) { this.concessionAckBillNo = concessionAckBillNo; }
    public Long getConcessionAckAt() { return concessionAckAt; }
    public void setConcessionAckAt(Long concessionAckAt) { this.concessionAckAt = concessionAckAt; }
    public String getConcessionAckBy() { return concessionAckBy; }
    public void setConcessionAckBy(String concessionAckBy) { this.concessionAckBy = concessionAckBy; }
    public BigDecimal getConcessionAmount() { return concessionAmount; }
    public void setConcessionAmount(BigDecimal concessionAmount) { this.concessionAmount = concessionAmount; }
    public BigDecimal getCostAmount() { return costAmount; }
    public void setCostAmount(BigDecimal costAmount) { this.costAmount = costAmount; }
    public boolean isCostPartial() { return costPartial; }
    public void setCostPartial(boolean costPartial) { this.costPartial = costPartial; }
    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }
    public long getDisposedAt() { return disposedAt; }
    public void setDisposedAt(long disposedAt) { this.disposedAt = disposedAt; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public Long getVoidedAt() { return voidedAt; }
    public void setVoidedAt(Long voidedAt) { this.voidedAt = voidedAt; }
    public String getVoidedBy() { return voidedBy; }
    public void setVoidedBy(String voidedBy) { this.voidedBy = voidedBy; }
    public String getVoidReason() { return voidReason; }
    public void setVoidReason(String voidReason) { this.voidReason = voidReason; }

    public boolean isVoided() {
        return voidedAt != null;
    }

    /**
     * 有效消费件数 = 本次处置件数 − 返工失败退回件数。
     * 已处置总量就是所有未撤销行的这个数之和。
     */
    public BigDecimal effectiveQty() {
        BigDecimal q = qty == null ? BigDecimal.ZERO : qty;
        BigDecimal f = failQty == null ? BigDecimal.ZERO : failQty;
        BigDecimal v = q.subtract(f);
        return v.signum() < 0 ? BigDecimal.ZERO : v;
    }

    /** 返工还没复检（界面显示"待复检"，dispose 队列要能筛出来） */
    public boolean isReworkWaitingFqc() {
        return ACT_REWORK.equals(action) && (fqcInspectionNo == null || fqcInspectionNo.isBlank());
    }

    /** 返工复检不合格（failQty > 0，那部分退回待处置） */
    public boolean hasReworkFail() {
        return ACT_REWORK.equals(action) && failQty != null && failQty.signum() > 0;
    }
}
