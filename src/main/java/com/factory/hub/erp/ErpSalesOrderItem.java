package com.factory.hub.erp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/**
 * 销售订单明细行。{@code shippedQty} 是净发货（SO_OUT − SO_RETURN），由单据重算。
 * 未发货 = qty − shippedQty；可退 = shippedQty。同一张订单不允许重复商品。
 */
@Entity
@Table(name = "erp_sales_order_item", indexes = {
        @Index(name = "idx_erp_soi_order", columnList = "order_id"),
        @Index(name = "idx_erp_soi_sku", columnList = "sku_id")
})
public class ErpSalesOrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "line_no", nullable = false)
    private int lineNo = 1;

    @Column(name = "sku_id", nullable = false)
    private Long skuId;

    @Column(name = "qty", nullable = false, precision = 18, scale = 4)
    private BigDecimal qty;

    @Column(name = "shipped_qty", precision = 18, scale = 4)
    private BigDecimal shippedQty = BigDecimal.ZERO;

    @Column(name = "unit_price", precision = 18, scale = 4)
    private BigDecimal unitPrice;

    @Column(name = "amount", precision = 18, scale = 4)
    private BigDecimal amount = BigDecimal.ZERO;

    @Column(name = "remark", length = 255)
    private String remark;

    public Long getId() { return id; }
    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }
    public int getLineNo() { return lineNo; }
    public void setLineNo(int lineNo) { this.lineNo = lineNo; }
    public Long getSkuId() { return skuId; }
    public void setSkuId(Long skuId) { this.skuId = skuId; }
    public BigDecimal getQty() { return qty; }
    public void setQty(BigDecimal qty) { this.qty = qty; }
    public BigDecimal getShippedQty() { return shippedQty; }
    public void setShippedQty(BigDecimal shippedQty) { this.shippedQty = shippedQty; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public void setUnitPrice(BigDecimal unitPrice) { this.unitPrice = unitPrice; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
