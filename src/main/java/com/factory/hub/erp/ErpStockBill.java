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
 * 出入库单据（ROADMAP DH-43b/44）。
 * <p>
 * 当前只有 <b>PROD_IN 生产入库</b>一种：设备计数点每 +N，联动服务自动生成一张单
 * （"每产一个入库一个"，汇总周期 10 秒）。采购入库/销售出库/调拨/盘点（DH-43b/52/45b）
 * 会复用这张表，用 bill_type 区分；库存视图永远从流水聚合推导，不存冗余库存数。
 */
@Entity
@Table(name = "erp_stock_bill", indexes = {
        @Index(name = "idx_erp_bill_no", columnList = "bill_no", unique = true),
        @Index(name = "idx_erp_bill_sku", columnList = "sku_id"),
        @Index(name = "idx_erp_bill_created", columnList = "created_at")
})
public class ErpStockBill {

    public static final String TYPE_PROD_IN = "PROD_IN";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 单号：SC + 时间戳，全局唯一 */
    @Column(name = "bill_no", nullable = false, length = 40, unique = true)
    private String billNo;

    /** 单据类型：PROD_IN 生产入库；后续 PO_IN 采购入库 / SO_OUT 销售出库 等 */
    @Column(name = "bill_type", nullable = false, length = 16)
    private String billType;

    /** 来源设备（生产入库才有；采购/销售单为空） */
    @Column(name = "device_code", length = 64)
    private String deviceCode;

    @Column(name = "sku_id", nullable = false)
    private Long skuId;

    @Column(name = "warehouse_id", nullable = false)
    private Long warehouseId;

    /** 正数 = 入库，负数 = 出库（退货/盘亏会生成负数行） */
    @Column(name = "qty", nullable = false, precision = 18, scale = 4)
    private BigDecimal qty;

    @Column(name = "unit_price", precision = 18, scale = 4)
    private BigDecimal unitPrice;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "remark")
    private String remark;

    @Column(name = "created_at", nullable = false)
    private long createdAt = System.currentTimeMillis();

    public Long getId() { return id; }
    public String getBillNo() { return billNo; }
    public void setBillNo(String billNo) { this.billNo = billNo; }
    public String getBillType() { return billType; }
    public void setBillType(String billType) { this.billType = billType; }
    public String getDeviceCode() { return deviceCode; }
    public void setDeviceCode(String deviceCode) { this.deviceCode = deviceCode; }
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
}
