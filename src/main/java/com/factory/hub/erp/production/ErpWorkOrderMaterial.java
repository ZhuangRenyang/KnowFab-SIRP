package com.factory.hub.erp.production;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/**
 * 工单用料快照（DH-71）：工单下达那一刻，按 BOM 把用量算死。
 * <p>
 * <b>为什么是快照而不是每次查 BOM</b>：工单 10 号下达、BOM 15 号被改了用量，
 * 如果领料时再查 BOM，同一个工单前后算出来的需求量会变——已经领了 80、
 * 需求突然变成 90，账面永远说不清是"少领了"还是"BOM 被改了"。
 * 所以下达时把 {@code needQty} 固化在这里，之后 BOM 怎么改都不影响这张工单。
 * <p>
 * {@code issuedQty} 是已领量（由 MATERIAL_OUT 单据回写），
 * 与 {@code needQty} 的差额就是「还差多少料」——这是缺料预警的数据来源，
 * 也是 AI 能回答「哪张工单会因为缺料停线」的依据。
 * <p>
 * 注意：<b>允许超领</b>（issuedQty > needQty）。车间实际会有补料、试模浪费、
 * 料废重做，硬拦会让人绕过系统去手工出库，反而更乱。超领在工单详情里标红提示，
 * 由人决定要不要追。
 */
@Entity
@Table(name = "erp_work_order_material", indexes = {
        @Index(name = "uk_wom_wo_sku", columnList = "work_order_id,sku_id", unique = true),
        @Index(name = "idx_wom_wo", columnList = "work_order_id"),
        @Index(name = "idx_wom_sku", columnList = "sku_id")
})
public class ErpWorkOrderMaterial {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "work_order_id", nullable = false)
    private Long workOrderId;

    @Column(name = "sku_id", nullable = false)
    private Long skuId;

    /** 材料编码/名称/单位快照 */
    @Column(name = "sku_code", length = 64)
    private String skuCode;
    @Column(name = "sku_name", length = 128)
    private String skuName;
    @Column(name = "sku_unit", length = 16)
    private String skuUnit;

    /** 需求量（已含损耗）：planQty × bom.qty × (1 + lossRate/100) */
    @Column(name = "need_qty", nullable = false, precision = 18, scale = 4)
    private BigDecimal needQty = BigDecimal.ZERO;

    /** 已领量，由领料单据回写 */
    @Column(name = "issued_qty", nullable = false, precision = 18, scale = 4)
    private BigDecimal issuedQty = BigDecimal.ZERO;

    /** 计划从哪个仓领（可空 = 领料时再选） */
    @Column(name = "warehouse_id")
    private Long warehouseId;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getWorkOrderId() { return workOrderId; }
    public void setWorkOrderId(Long workOrderId) { this.workOrderId = workOrderId; }
    public Long getSkuId() { return skuId; }
    public void setSkuId(Long skuId) { this.skuId = skuId; }
    public String getSkuCode() { return skuCode; }
    public void setSkuCode(String skuCode) { this.skuCode = skuCode; }
    public String getSkuName() { return skuName; }
    public void setSkuName(String skuName) { this.skuName = skuName; }
    public String getSkuUnit() { return skuUnit; }
    public void setSkuUnit(String skuUnit) { this.skuUnit = skuUnit; }
    public BigDecimal getNeedQty() { return needQty; }
    public void setNeedQty(BigDecimal needQty) { this.needQty = needQty; }
    public BigDecimal getIssuedQty() { return issuedQty; }
    public void setIssuedQty(BigDecimal issuedQty) { this.issuedQty = issuedQty; }
    public Long getWarehouseId() { return warehouseId; }
    public void setWarehouseId(Long warehouseId) { this.warehouseId = warehouseId; }
}
