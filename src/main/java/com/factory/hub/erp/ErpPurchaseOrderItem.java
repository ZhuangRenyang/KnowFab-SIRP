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
 * 采购订单明细行。
 * <p>
 * {@code qty} 是订货数量，{@code receivedQty} 是<b>净入库</b>（PO_IN − PO_RETURN），
 * 由 {@link ErpPurchaseService#recalc} 从单据流水重算。未入库 = qty − receivedQty，
 * 可退数量 = receivedQty（退掉之后又能重新入库，符合"送错货退回再补发"的现场习惯）。
 * <p>
 * 同一张订单不允许重复商品：入库/退货按 SKU 对账，重复行会让"这次入的是哪一行"变成猜谜。
 */
@Entity
@Table(name = "erp_purchase_order_item", indexes = {
        @Index(name = "idx_erp_poi_order", columnList = "order_id"),
        @Index(name = "idx_erp_poi_sku", columnList = "sku_id")
})
public class ErpPurchaseOrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "line_no", nullable = false)
    private int lineNo = 1;

    @Column(name = "sku_id", nullable = false)
    private Long skuId;

    /** 订货数量 */
    @Column(name = "qty", nullable = false, precision = 18, scale = 4)
    private BigDecimal qty;

    /** 净入库数量（入库 − 退货），由单据重算 */
    @Column(name = "received_qty", precision = 18, scale = 4)
    private BigDecimal receivedQty = BigDecimal.ZERO;

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
    public BigDecimal getReceivedQty() { return receivedQty; }
    public void setReceivedQty(BigDecimal receivedQty) { this.receivedQty = receivedQty; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public void setUnitPrice(BigDecimal unitPrice) { this.unitPrice = unitPrice; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
