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
 * 采购订单单头（ROADMAP DH-52）。
 * <p>
 * 参照 jshERP 的 purchase_order 自研重写（改名纪律：purchase → erp_purchase_order）。
 * 订单本身<b>不动库存</b>——它只是"跟供应商约好要买什么"。真正的库存变动发生在
 * 「从订单入库」时生成的 {@code PO_IN} 单据上（复用 DH-43b 的单头+明细行）。
 * <p>
 * 状态机（刻意只有五态，不做审批流，车间里没人走三级审批）：
 * <pre>
 *   DRAFT ──确认──→ CONFIRMED ──部分入库──→ PARTIAL ──入完──→ DONE
 *     │                 │                      │
 *     └──── 取消 ───────┴────── 取消 ──────────┘（仅未入库时允许）
 * </pre>
 * 进度（received_qty / returned_qty）不靠人工维护：每次入库/退货/作废单据后，
 * 由 {@link ErpPurchaseService#recalc} 按"未作废的 PO_IN − PO_RETURN"重算一遍，
 * 所以数据永远能自愈，不会出现"订单说入完了但库存里没有"的鬼故事。
 */
@Entity
@Table(name = "erp_purchase_order", indexes = {
        @Index(name = "idx_erp_po_no", columnList = "order_no", unique = true),
        @Index(name = "idx_erp_po_supplier", columnList = "supplier_id"),
        @Index(name = "idx_erp_po_status", columnList = "status"),
        @Index(name = "idx_erp_po_date", columnList = "order_date")
})
public class ErpPurchaseOrder {

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_CONFIRMED = "CONFIRMED";
    public static final String STATUS_PARTIAL = "PARTIAL";
    public static final String STATUS_DONE = "DONE";
    public static final String STATUS_CANCELLED = "CANCELLED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 单号：CGDD + 日期 + 流水，如 CGDD20261028-0001 */
    @Column(name = "order_no", nullable = false, length = 40, unique = true)
    private String orderNo;

    @Column(name = "supplier_id")
    private Long supplierId;

    /** 供应商名称快照：改过名的供应商，历史订单仍显示当时的名字 */
    @Column(name = "supplier_name", length = 128)
    private String supplierName;

    @Column(name = "status", nullable = false, length = 16)
    private String status = STATUS_DRAFT;

    /** 下单日期（当天 00:00 的毫秒） */
    @Column(name = "order_date", nullable = false)
    private long orderDate = System.currentTimeMillis();

    /** 预计到货日期（可选，用于"逾期未到"提示） */
    @Column(name = "expect_date")
    private Long expectDate;

    @Column(name = "total_qty", precision = 18, scale = 4)
    private BigDecimal totalQty = BigDecimal.ZERO;

    @Column(name = "total_amount", precision = 18, scale = 4)
    private BigDecimal totalAmount = BigDecimal.ZERO;

    /** 已净入库数量（入库 − 退货），由 recalc 重算，不手工维护 */
    @Column(name = "received_qty", precision = 18, scale = 4)
    private BigDecimal receivedQty = BigDecimal.ZERO;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "remark")
    private String remark;

    @Column(name = "operator", length = 64)
    private String operator;

    @Column(name = "created_at", nullable = false)
    private long createdAt = System.currentTimeMillis();

    @Column(name = "updated_at", nullable = false)
    private long updatedAt = System.currentTimeMillis();

    public Long getId() { return id; }
    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
    public Long getSupplierId() { return supplierId; }
    public void setSupplierId(Long supplierId) { this.supplierId = supplierId; }
    public String getSupplierName() { return supplierName; }
    public void setSupplierName(String supplierName) { this.supplierName = supplierName; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public long getOrderDate() { return orderDate; }
    public void setOrderDate(long orderDate) { this.orderDate = orderDate; }
    public Long getExpectDate() { return expectDate; }
    public void setExpectDate(Long expectDate) { this.expectDate = expectDate; }
    public BigDecimal getTotalQty() { return totalQty; }
    public void setTotalQty(BigDecimal totalQty) { this.totalQty = totalQty; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public void setTotalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; }
    public BigDecimal getReceivedQty() { return receivedQty; }
    public void setReceivedQty(BigDecimal receivedQty) { this.receivedQty = receivedQty; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }

    /** 还能不能改行（只有草稿能改） */
    public boolean isEditable() { return STATUS_DRAFT.equals(status); }

    /** 还收不收得货（确认后、未完成前） */
    public boolean isReceivable() {
        return STATUS_CONFIRMED.equals(status) || STATUS_PARTIAL.equals(status);
    }
}
