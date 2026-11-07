package com.factory.hub.erp.analysis;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/**
 * 产品 BOM（DH-70）：做 1 件成品需要多少材料。
 * <p>
 * <b>为什么必须有这张表</b>：在此之前，系统只会「设备产出 → 成品入库」，
 * 原料那边一分不动。客户买 100 吨钢、做出 80 个零件卖掉，仓库里钢还是 100 吨——
 * 进销存报表「期初+入-出=期末」算术上永远对，但那是单边账，客户第一次对账就穿帮。
 * 有了 BOM，成品产出时才能把原料按用量扣下去，库存才形成「原料 → 成品」的闭环。
 * <p>
 * {@code qty} 是<b>单位用量</b>（做一件成品要几件材料），不是这一批的总量；
 * 总量由工单下达时按 {@code planQty × qty × (1 + lossRate/100)} 展开成快照，
 * 存进 {@link ErpWorkOrderMaterial}——<b>工单一旦下达，BOM 再改也不动已下达的工单</b>，
 * 否则同一个工单的用料会在中途变化，已领的量和需求的量永远对不上。
 * <p>
 * {@code lossRate} 是损耗百分比（0~100），不锈钢冲压这类有料头损耗的场景填 3~5。
 * 存百分比而不是倍数，是因为车间填的是「损耗 3%」，不是「乘 1.03」。
 */
@Entity
@Table(name = "erp_bom", indexes = {
        @Index(name = "uk_bom_product_material", columnList = "product_sku_id,material_sku_id", unique = true),
        @Index(name = "idx_bom_product", columnList = "product_sku_id"),
        @Index(name = "idx_bom_material", columnList = "material_sku_id")
})
public class ErpBom {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 成品/半成品 SKU */
    @Column(name = "product_sku_id", nullable = false)
    private Long productSkuId;

    /** 材料 SKU */
    @Column(name = "material_sku_id", nullable = false)
    private Long materialSkuId;

    /** 单位用量：做 1 件成品需要的材料数量（BOM 分母恒为 1，不引入「每 N 件」） */
    @Column(name = "qty", nullable = false, precision = 18, scale = 4)
    private BigDecimal qty;

    /** 损耗率百分比（0~100），0 表示不考虑损耗 */
    @Column(name = "loss_rate", nullable = false, precision = 9, scale = 4)
    private BigDecimal lossRate = BigDecimal.ZERO;

    @Column(name = "remark", length = 255)
    private String remark;

    @Column(name = "created_at", nullable = false)
    private long createdAt = System.currentTimeMillis();

    @Column(name = "updated_at", nullable = false)
    private long updatedAt = System.currentTimeMillis();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getProductSkuId() { return productSkuId; }
    public void setProductSkuId(Long productSkuId) { this.productSkuId = productSkuId; }
    public Long getMaterialSkuId() { return materialSkuId; }
    public void setMaterialSkuId(Long materialSkuId) { this.materialSkuId = materialSkuId; }
    public BigDecimal getQty() { return qty; }
    public void setQty(BigDecimal qty) { this.qty = qty; }
    public BigDecimal getLossRate() { return lossRate; }
    public void setLossRate(BigDecimal lossRate) { this.lossRate = lossRate; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }
}
