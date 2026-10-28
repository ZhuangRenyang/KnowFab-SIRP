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
 * 销售订单单头（ROADMAP DH-45b）。
 * <p>
 * 与采购订单是镜像结构（{@link ErpPurchaseOrder}），但语义不同：
 * <ul>
 *   <li>采购是"等货到"，销售是"把货发出去"——发货前必须先有库存（{@code SO_OUT} 会拦负数）</li>
 *   <li>采购看<strong>待到货</strong>，销售看<strong>待发货</strong>；逾期口径分别是"该到没到"和"该发没发"</li>
 *   <li>销售多一个"应收"概念：已发货金额 = 客户欠我们的钱（真正的收款单在 DH-54）</li>
 * </ul>
 * 订单本身不动库存，库存只由 {@code SO_OUT} / {@code SO_RETURN} 单据变动，
 * 进度由 {@link ErpSalesService#recalc} 按未作废单据重算，和采购同一套自愈思路。
 * <p>
 * 状态机：DRAFT → CONFIRMED → PARTIAL → DONE，未发货前可 CANCELLED。
 */
@Entity
@Table(name = "erp_sales_order", indexes = {
        @Index(name = "idx_erp_so_no", columnList = "order_no", unique = true),
        @Index(name = "idx_erp_so_customer", columnList = "customer_id"),
        @Index(name = "idx_erp_so_status", columnList = "status"),
        @Index(name = "idx_erp_so_date", columnList = "order_date")
})
public class ErpSalesOrder {

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_CONFIRMED = "CONFIRMED";
    public static final String STATUS_PARTIAL = "PARTIAL";
    public static final String STATUS_DONE = "DONE";
    public static final String STATUS_CANCELLED = "CANCELLED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 单号：XSDD + 日期 + 流水 */
    @Column(name = "order_no", nullable = false, length = 40, unique = true)
    private String orderNo;

    @Column(name = "customer_id")
    private Long customerId;

    /** 客户名称快照 */
    @Column(name = "customer_name", length = 128)
    private String customerName;

    @Column(name = "status", nullable = false, length = 16)
    private String status = STATUS_DRAFT;

    @Column(name = "order_date", nullable = false)
    private long orderDate = System.currentTimeMillis();

    /** 预计发货日期（可选，"逾期未发"按它算） */
    @Column(name = "deliver_date")
    private Long deliverDate;

    @Column(name = "total_qty", precision = 18, scale = 4)
    private BigDecimal totalQty = BigDecimal.ZERO;

    @Column(name = "total_amount", precision = 18, scale = 4)
    private BigDecimal totalAmount = BigDecimal.ZERO;

    /** 已净发货数量（出库 − 退货），由 recalc 重算 */
    @Column(name = "shipped_qty", precision = 18, scale = 4)
    private BigDecimal shippedQty = BigDecimal.ZERO;

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
    public Long getCustomerId() { return customerId; }
    public void setCustomerId(Long customerId) { this.customerId = customerId; }
    public String getCustomerName() { return customerName; }
    public void setCustomerName(String customerName) { this.customerName = customerName; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public long getOrderDate() { return orderDate; }
    public void setOrderDate(long orderDate) { this.orderDate = orderDate; }
    public Long getDeliverDate() { return deliverDate; }
    public void setDeliverDate(Long deliverDate) { this.deliverDate = deliverDate; }
    public BigDecimal getTotalQty() { return totalQty; }
    public void setTotalQty(BigDecimal totalQty) { this.totalQty = totalQty; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public void setTotalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; }
    public BigDecimal getShippedQty() { return shippedQty; }
    public void setShippedQty(BigDecimal shippedQty) { this.shippedQty = shippedQty; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }

    public boolean isEditable() { return STATUS_DRAFT.equals(status); }

    public boolean isShippable() {
        return STATUS_CONFIRMED.equals(status) || STATUS_PARTIAL.equals(status);
    }
}
