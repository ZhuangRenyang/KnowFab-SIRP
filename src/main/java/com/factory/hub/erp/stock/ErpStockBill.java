package com.factory.hub.erp.stock;

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
 * 出入库单据明细行（ROADMAP DH-43b/44）——<b>库存的唯一事实来源</b>。
 * <p>
 * 库存永远等于本表 qty 的正负累计，不另存冗余库存数：任何"库存对不上"的质疑，
 * 都可以把某个 SKU 的行按时间摊开重新加一遍验证。
 * <p>
 * 单据头在 {@link ErpStockBillHdr}（单号、往来单位、制单人、作废状态）；
 * 本表按 {@code header_id} 归属，一张单多行商品时共用同一个 bill_no。
 * 历史上生产入库是"单行即整单"、bill_no 唯一；升级后改为一单多行，
 * bill_no 的唯一索引由 migrations/1.1.0 改成普通索引（新装直接建普通索引）。
 * <p>
 * qty 正数=入库、负数=出库；调拨单是"源仓一行负数 + 目标仓一行正数"，全厂净额为零。
 * 作废的行打 voided_at 时间戳，库存聚合跳过它，但行本身永久保留可追溯。
 */
@Entity
@Table(name = "erp_stock_bill", indexes = {
        @Index(name = "idx_erp_bill_no", columnList = "bill_no"),
        @Index(name = "idx_erp_bill_hdr", columnList = "header_id"),
        @Index(name = "idx_erp_bill_sku", columnList = "sku_id"),
        @Index(name = "idx_erp_bill_created", columnList = "created_at")
})
public class ErpStockBill {

    /* ── 单据类型（bill_type）。前缀供单号生成用，见 ErpStockService ── */

    /** 生产入库：设备计数点每 +N 自动生成（系统单据） */
    public static final String TYPE_PROD_IN = "PROD_IN";
    /**
     * 生产领料：按工单从仓库领出原材料，负数出库（DH-70）。
     * <p>
     * <b>这一项缺了很久</b>：在此之前只有「设备产出 → 成品入库」，原料那一侧一分不动，
     * 客户买 100 吨钢做完 80 个零件卖掉，仓库里钢还是 100 吨。
     * 进销存「期初+入-出=期末」算术上永远对，但那是单边账。
     */
    public static final String TYPE_MATERIAL_OUT = "MATERIAL_OUT";
    /** 采购入库 */
    public static final String TYPE_PO_IN = "PO_IN";
    /** 采购退货：退回供应商，负数出库（DH-52） */
    public static final String TYPE_PO_RETURN = "PO_RETURN";
    /** 销售出库 */
    public static final String TYPE_SO_OUT = "SO_OUT";
    /** 销售退货：客户退回，正数入库（DH-45b） */
    public static final String TYPE_SO_RETURN = "SO_RETURN";
    /** 其他入库（盘盈调整、退料、赠品等） */
    public static final String TYPE_OTHER_IN = "OTHER_IN";
    /** 其他出库（领用、报废、盘亏调整等） */
    public static final String TYPE_OTHER_OUT = "OTHER_OUT";
    /**
     * 报废出库：不良品处置「报废」时产生，负数出库（QC-SPEC §2.1 裁决新增）。
     * <p>
     * <b>为什么必须单独一个类型，不能复用 {@code OTHER_OUT}</b>：
     * 质量成本报表要回答"这个月报废损失多少"，如果靠
     * {@code bill_type = 'OTHER_OUT' and remark like '%报废%'} 去匹配，
     * 客户自己手填的其他出库单只要备注带"报废"两个字就会被误计进质量成本。
     * 报表口径靠备注文本匹配，是迟早要出事的设计。
     * <p>
     * 新增出库类型的风险是"漏登记到出库白名单 → 方向反了、库存凭空涨且不报错"
     * （本项目踩过两次）。这个风险已由 {@code ErpStockService.isOutbound()} 收敛成
     * 单一事实来源，并由单测遍历所有 {@code TYPE_*} 常量兜底。
     * <p>
     * 它<b>不在</b> {@code MANUAL_UI_TYPES} 里：报废必须从不良品记录发起，
     * 不允许在库存页手搓一张报废单——否则不良单与出库单会分家，处置状态永远结不了案。
     */
    public static final String TYPE_SCRAP_OUT = "SCRAP_OUT";
    /** 调拨：源仓负数行 + 目标仓正数行，同一单号 */
    public static final String TYPE_TRANSFER = "TRANSFER";
    /** 盘点调整：只落有差异的行（盘盈正数、盘亏负数） */
    public static final String TYPE_STOCKTAKE = "STOCKTAKE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 单据头 id；历史生产入库单据回填前为 null（启动时自动补齐） */
    @Column(name = "header_id")
    private Long headerId;

    /** 单据号：与单头一致，一张单的多行共用 */
    @Column(name = "bill_no", nullable = false, length = 40)
    private String billNo;

    /** 行号：从 1 开始，调拨单源仓/目标仓各占一行 */
    @Column(name = "line_no", nullable = false)
    private int lineNo = 1;

    /** 单据类型：冗余在行上，库存聚合与类型筛选不必回表单头 */
    @Column(name = "bill_type", nullable = false, length = 16)
    private String billType;

    /** 来源设备（生产入库才有；采购/销售单为空） */
    @Column(name = "device_code", length = 64)
    private String deviceCode;

    /**
     * 来源采购订单号：冗余在行上，订单进度聚合（sum by order）不用回表单头。
     * 单头也有一份（列表显示用），以单头为准，这里跟着写同一个值。
     */
    @Column(name = "src_order_no", length = 40)
    private String srcOrderNo;

    @Column(name = "sku_id", nullable = false)
    private Long skuId;

    @Column(name = "warehouse_id", nullable = false)
    private Long warehouseId;

    /** 正数 = 入库，负数 = 出库（调拨的源仓行、销售的出库行都是负数） */
    @Column(name = "qty", nullable = false, precision = 18, scale = 4)
    private BigDecimal qty;

    @Column(name = "unit_price", precision = 18, scale = 4)
    private BigDecimal unitPrice;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "remark")
    private String remark;

    @Column(name = "created_at", nullable = false)
    private long createdAt = System.currentTimeMillis();

    /**
     * 单据业务日期（补录用）。null = 按 {@link #createdAt} 算。
     * <p>
     * 为什么必须有这一列：月底补录 25 号的领料单，录入时间是 30 号，
     * 如果报表按录入时间统计，这个月的出库就会凭空多出来、上个月少一笔。
     * 报表统一取 {@code coalesce(biz_date, created_at)}，录入时间继续只做审计。
     */
    @Column(name = "biz_date")
    private Long bizDate;

    /** 作废时间戳；null = 有效。历史行升级后为 null，库存聚合照常算进去 */
    @Column(name = "voided_at")
    private Long voidedAt;

    @Column(name = "voided_by", length = 64)
    private String voidedBy;

    public Long getId() { return id; }
    public Long getHeaderId() { return headerId; }
    public void setHeaderId(Long headerId) { this.headerId = headerId; }
    public String getBillNo() { return billNo; }
    public void setBillNo(String billNo) { this.billNo = billNo; }
    public int getLineNo() { return lineNo; }
    public void setLineNo(int lineNo) { this.lineNo = lineNo; }
    public String getBillType() { return billType; }
    public void setBillType(String billType) { this.billType = billType; }
    public String getDeviceCode() { return deviceCode; }
    public void setDeviceCode(String deviceCode) { this.deviceCode = deviceCode; }
    public String getSrcOrderNo() { return srcOrderNo; }
    public void setSrcOrderNo(String srcOrderNo) { this.srcOrderNo = srcOrderNo; }
    public Long getSkuId() { return skuId; }
    public void setSkuId(Long skuId) { this.skuId = skuId; }
    public Long getWarehouseId() { return warehouseId; }
    public void setWarehouseId(Long warehouseId) { this.warehouseId = warehouseId; }
    public BigDecimal getQty() { return qty; }
    public void setQty(BigDecimal qty) { this.qty = qty; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public void setUnitPrice(BigDecimal unitPrice) { this.unitPrice = unitPrice; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
    public Long getBizDate() { return bizDate; }
    public void setBizDate(Long bizDate) { this.bizDate = bizDate; }
    public Long getVoidedAt() { return voidedAt; }
    public void setVoidedAt(Long voidedAt) { this.voidedAt = voidedAt; }
    public String getVoidedBy() { return voidedBy; }
    public void setVoidedBy(String voidedBy) { this.voidedBy = voidedBy; }
}
