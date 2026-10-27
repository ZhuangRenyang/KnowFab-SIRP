package com.factory.hub.erp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;

/**
 * 商品主数据（ERP 基础资料三件套之一，ROADMAP DH-42）。
 * <p>
 * 参照 jshERP 的 material 模型自研重写（改名纪律：material → erp_sku），
 * 只留工厂场景需要的字段；批次/序列号是 ERPNext 的远期借鉴，当前明确不做。
 */
@Entity
@Table(name = "erp_sku", indexes = {
        @Index(name = "idx_erp_sku_code", columnList = "code", unique = true)
})
public class ErpSku {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, length = 64, unique = true)
    private String code;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Column(name = "category", length = 64)
    private String category;

    /** 计量单位：件 / kg / m 等 */
    @Column(name = "unit", length = 16)
    private String unit;

    @Column(name = "spec", length = 128)
    private String spec;

    @Column(name = "barcode", length = 64)
    private String barcode;

    @Column(name = "purchase_price", precision = 18, scale = 4)
    private BigDecimal purchasePrice;

    @Column(name = "sale_price", precision = 18, scale = 4)
    private BigDecimal salePrice;

    /** 库存上下限：给 DH-43 的库存预警用，先占位 */
    @Column(name = "min_stock", precision = 18, scale = 4)
    private BigDecimal minStock;

    @Column(name = "max_stock", precision = 18, scale = 4)
    private BigDecimal maxStock;

    /** 长文本必须 LONGVARCHAR：@Lob 在 MySQL 上是 tinytext（2026-10-27 的教训） */
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "remark")
    private String remark;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false)
    private long createdAt = System.currentTimeMillis();

    @Column(name = "updated_at", nullable = false)
    private long updatedAt = System.currentTimeMillis();

    @PreUpdate
    void touch() {
        updatedAt = System.currentTimeMillis();
    }

    public Long getId() { return id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getUnit() { return unit; }
    public void setUnit(String unit) { this.unit = unit; }
    public String getSpec() { return spec; }
    public void setSpec(String spec) { this.spec = spec; }
    public String getBarcode() { return barcode; }
    public void setBarcode(String barcode) { this.barcode = barcode; }
    public BigDecimal getPurchasePrice() { return purchasePrice; }
    public void setPurchasePrice(BigDecimal purchasePrice) { this.purchasePrice = purchasePrice; }
    public BigDecimal getSalePrice() { return salePrice; }
    public void setSalePrice(BigDecimal salePrice) { this.salePrice = salePrice; }
    public BigDecimal getMinStock() { return minStock; }
    public void setMinStock(BigDecimal minStock) { this.minStock = minStock; }
    public BigDecimal getMaxStock() { return maxStock; }
    public void setMaxStock(BigDecimal maxStock) { this.maxStock = maxStock; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public long getCreatedAt() { return createdAt; }
    public long getUpdatedAt() { return updatedAt; }
}
