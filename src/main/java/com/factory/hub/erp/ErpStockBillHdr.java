package com.factory.hub.erp;

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
 * 出入库单据单头（ROADMAP DH-43b）。
 * <p>
 * 单据结构照 jshERP 的 depot_head / depot_item 拆成"单头 + 明细行"，改名纪律下叫
 * {@code erp_stock_bill_hdr} / {@code erp_stock_bill}（明细行沿用老表名，生产入库的
 * 344 张历史单据不用搬家）。一张单可以有多行商品，调拨单则固定两行（源仓出、目标仓入）。
 * <p>
 * <b>为什么合计数量/金额直接存单头：</b>单据列表要分页，若每次都把明细聚合再排序分页，
 * 数据量一大就要全表扫描；单头冗余 total_qty / total_amount / line_count，列表查询只碰单头。
 * 明细行仍是库存的唯一事实来源——库存 = 明细行 qty 累计，单头永远不参与算库存。
 * <p>
 * <b>作废而不是删除：</b>仓库单据删了就没法追溯"这批货谁在什么时候放进来的"。
 * 作废只打 voided_at 时间戳，明细行同步打戳，库存聚合跳过作废行，单据仍可查询。
 */
@Entity
@Table(name = "erp_stock_bill_hdr", indexes = {
        @Index(name = "idx_erp_hdr_no", columnList = "bill_no", unique = true),
        @Index(name = "idx_erp_hdr_type", columnList = "bill_type"),
        @Index(name = "idx_erp_hdr_created", columnList = "created_at")
})
public class ErpStockBillHdr {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 单号：前缀 + 日期 + 流水，如 CGRK20261028-0001 */
    @Column(name = "bill_no", nullable = false, length = 40, unique = true)
    private String billNo;

    @Column(name = "bill_type", nullable = false, length = 16)
    private String billType;

    /** 往来单位（采购入库=供应商，销售出库=客户），其他单据为空 */
    @Column(name = "partner_id")
    private Long partnerId;

    /** 往来单位名称快照：单位改名后历史单据要保留当时名字 */
    @Column(name = "partner_name", length = 128)
    private String partnerName;

    /** 调拨单的源仓；其他单据为空 */
    @Column(name = "src_wh_id")
    private Long srcWhId;

    /** 目标仓（调拨的收货仓 / 其他单据的出入库仓）：列表页直接显示，不必回查明细 */
    @Column(name = "dst_wh_id")
    private Long dstWhId;

    /** 生产入库的来源设备编码（手工单据为空） */
    @Column(name = "device_code", length = 64)
    private String deviceCode;

    /**
     * 来源采购订单号（DH-52）：从订单入库/退货生成的单据带上它，
     * 订单进度就靠"按订单号汇总未作废单据"重算，不靠人工维护。
     */
    @Column(name = "src_order_no", length = 40)
    private String srcOrderNo;

    /** 制单人（生产入库联动写 system） */
    @Column(name = "operator", length = 64)
    private String operator;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "remark")
    private String remark;

    @Column(name = "total_qty", precision = 18, scale = 4)
    private BigDecimal totalQty;

    @Column(name = "total_amount", precision = 18, scale = 4)
    private BigDecimal totalAmount;

    @Column(name = "line_count")
    private int lineCount;

    @Column(name = "created_at", nullable = false)
    private long createdAt = System.currentTimeMillis();

    /** 作废时间戳；null = 有效。明细行同步打戳，库存聚合按它过滤 */
    @Column(name = "voided_at")
    private Long voidedAt;

    @Column(name = "voided_by", length = 64)
    private String voidedBy;

    public Long getId() { return id; }
    public String getBillNo() { return billNo; }
    public void setBillNo(String billNo) { this.billNo = billNo; }
    public String getBillType() { return billType; }
    public void setBillType(String billType) { this.billType = billType; }
    public Long getPartnerId() { return partnerId; }
    public void setPartnerId(Long partnerId) { this.partnerId = partnerId; }
    public String getPartnerName() { return partnerName; }
    public void setPartnerName(String partnerName) { this.partnerName = partnerName; }
    public Long getSrcWhId() { return srcWhId; }
    public void setSrcWhId(Long srcWhId) { this.srcWhId = srcWhId; }
    public Long getDstWhId() { return dstWhId; }
    public void setDstWhId(Long dstWhId) { this.dstWhId = dstWhId; }
    public String getDeviceCode() { return deviceCode; }
    public void setDeviceCode(String deviceCode) { this.deviceCode = deviceCode; }
    public String getSrcOrderNo() { return srcOrderNo; }
    public void setSrcOrderNo(String srcOrderNo) { this.srcOrderNo = srcOrderNo; }
    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public BigDecimal getTotalQty() { return totalQty; }
    public void setTotalQty(BigDecimal totalQty) { this.totalQty = totalQty; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public void setTotalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; }
    public int getLineCount() { return lineCount; }
    public void setLineCount(int lineCount) { this.lineCount = lineCount; }
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
    public Long getVoidedAt() { return voidedAt; }
    public void setVoidedAt(Long voidedAt) { this.voidedAt = voidedAt; }
    public String getVoidedBy() { return voidedBy; }
    public void setVoidedBy(String voidedBy) { this.voidedBy = voidedBy; }

    public boolean isVoided() { return voidedAt != null; }
}
