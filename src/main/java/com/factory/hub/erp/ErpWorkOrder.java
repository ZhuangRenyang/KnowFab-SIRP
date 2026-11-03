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
 * 生产工单（DH-71）——设备与 ERP 之间真正的联动载体。
 * <p>
 * <b>为什么需要它</b>：原来设备只绑一个固定产品（{@code device.prod_sku_id}），
 * 换产品要改设备配置，而且「这批产量是哪个订单的」无从回答。工单把时间维度引进来了：
 * 一张工单 = 一台设备（或一条产线）+ 一个产品 + 一段时间 + 一个计划量。
 * 设备产出挂到工单上，工单再挂到销售订单上，交期和成本才有地方落。
 * <p>
 * <b>状态机</b>（五态，与采购/销售订单同一套命名，前端标签可复用）：
 * <pre>
 *   DRAFT ──release──→ RELEASED ──start──→ RUNNING ──finish──→ DONE
 *     │                   │                   │
 *     └──────cancel───────┴───────cancel──────┘        （DONE 之后不可取消）
 * </pre>
 * <ul>
 *   <li><b>DRAFT</b>：草稿，可随意改产品/数量/设备，<b>不展开 BOM</b>。此时改 BOM 没有副作用。</li>
 *   <li><b>RELEASED</b>：已下达。这一刻按 BOM 展开用料快照（{@link ErpWorkOrderMaterial}），
 *       之后 BOM 改动不影响这张工单。可以领料，但还不能开工。</li>
 *   <li><b>RUNNING</b>：生产中。设备产出自动挂到本工单；同一台设备<b>同时只能有一张 RUNNING 工单</b>
 *       （否则产出不知道该算给谁）。</li>
 *   <li><b>DONE</b>：已完工。产出与领料都可以为 0（客户可能有料无产、试产失败）。</li>
 *   <li><b>CANCELLED</b>：已取消。已领的料<b>不会自动退</b>——退料必须走 OTHER_IN 手工退，
 *       这样账面上「领了又退」和「从来没领过」是两件不同的事，能追。</li>
 * </ul>
 * <p>
 * <b>为什么不存产量字段的冗余</b>：{@code doneQty}/{@code defectQty} 是从产出流水
 * （{@link ErpWorkOrderOutput}）累加出来的镜像列，和销售订单的 shippedQty 一个规矩——
 * 只在 {@code produce()} 里增减，任何绕过它的直接写都会被审计抓出来（进度对不上）。
 */
@Entity
@Table(name = "erp_work_order", indexes = {
        @Index(name = "uk_wo_no", columnList = "order_no", unique = true),
        @Index(name = "idx_wo_status", columnList = "status"),
        @Index(name = "idx_wo_device", columnList = "device_code"),
        @Index(name = "idx_wo_product", columnList = "product_sku_id"),
        @Index(name = "idx_wo_created", columnList = "created_at")
})
public class ErpWorkOrder {

    public static final String ST_DRAFT = "DRAFT";
    public static final String ST_RELEASED = "RELEASED";
    public static final String ST_RUNNING = "RUNNING";
    public static final String ST_DONE = "DONE";
    public static final String ST_CANCELLED = "CANCELLED";

    public static final java.util.List<String> STATUSES = java.util.List.of(
            ST_DRAFT, ST_RELEASED, ST_RUNNING, ST_DONE, ST_CANCELLED);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 工单号：WO + yyyyMMdd + 序号，与库存单号同一套生成规则 */
    @Column(name = "order_no", nullable = false, length = 40)
    private String orderNo;

    /** 要生产的成品 SKU */
    @Column(name = "product_sku_id", nullable = false)
    private Long productSkuId;

    /** 产品编码/名称/单位快照：产品改名后工单上仍显示当时的名字 */
    @Column(name = "product_code", length = 64)
    private String productCode;
    @Column(name = "product_name", length = 128)
    private String productName;
    @Column(name = "product_unit", length = 16)
    private String productUnit;

    /** 计划产量 */
    @Column(name = "plan_qty", nullable = false, precision = 18, scale = 4)
    private BigDecimal planQty = BigDecimal.ZERO;

    /** 已产出（良品）——由产出流水累加的镜像列，只经 produce() 写 */
    @Column(name = "done_qty", nullable = false, precision = 18, scale = 4)
    private BigDecimal doneQty = BigDecimal.ZERO;

    /** 已产出中的不良品数量 */
    @Column(name = "defect_qty", nullable = false, precision = 18, scale = 4)
    private BigDecimal defectQty = BigDecimal.ZERO;

    @Column(name = "status", nullable = false, length = 16)
    private String status = ST_DRAFT;

    /** 产线编码（可空）。指定设备时这一项可以留空，两者都填则设备优先 */
    @Column(name = "line_code", length = 40)
    private String lineCode;

    /**
     * 执行设备编码（可空 = 未指定）。
     * <p>
     * 开工时如果为空且填了产线，取该产线的成品计数设备（与产量统计同一套规则），
     * 保证「产量算给谁」和「工单算给谁」是同一台机器。
     */
    @Column(name = "device_code", length = 64)
    private String deviceCode;

    /** 成品入库仓库；空 = 用默认仓 */
    @Column(name = "warehouse_id")
    private Long warehouseId;

    /**
     * 领料仓库（原料仓）；空 = 用默认仓。
     * <p>
     * 与 {@link #warehouseId}（成品仓）是两个概念：原料从原料仓出、成品入成品仓，
     * 混成一个字段的话，缺料看板会拿成品仓的库存去判断原料够不够，
     * 显示"现有 0"把车间吓一跳，实际原料仓堆着几百公斤。
     */
    @Column(name = "material_wh_id")
    private Long materialWhId;

    /** 关联销售订单号（可空）：工单与订单挂钩，交期才追得下去 */
    @Column(name = "src_order_no", length = 40)
    private String srcOrderNo;

    /** 是否在产出时按 BOM 自动反冲领料（1 = 自动扣料，0 = 只记产出，料由人工领） */
    @Column(name = "auto_backflush", nullable = false)
    private boolean autoBackflush = true;

    @Column(name = "plan_start")
    private Long planStart;
    @Column(name = "plan_end")
    private Long planEnd;
    @Column(name = "actual_start")
    private Long actualStart;
    @Column(name = "actual_end")
    private Long actualEnd;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "remark")
    private String remark;

    @Column(name = "created_by", length = 64)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private long createdAt = System.currentTimeMillis();

    @Column(name = "updated_at", nullable = false)
    private long updatedAt = System.currentTimeMillis();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
    public Long getProductSkuId() { return productSkuId; }
    public void setProductSkuId(Long productSkuId) { this.productSkuId = productSkuId; }
    public String getProductCode() { return productCode; }
    public void setProductCode(String productCode) { this.productCode = productCode; }
    public String getProductName() { return productName; }
    public void setProductName(String productName) { this.productName = productName; }
    public String getProductUnit() { return productUnit; }
    public void setProductUnit(String productUnit) { this.productUnit = productUnit; }
    public BigDecimal getPlanQty() { return planQty; }
    public void setPlanQty(BigDecimal planQty) { this.planQty = planQty; }
    public BigDecimal getDoneQty() { return doneQty; }
    public void setDoneQty(BigDecimal doneQty) { this.doneQty = doneQty; }
    public BigDecimal getDefectQty() { return defectQty; }
    public void setDefectQty(BigDecimal defectQty) { this.defectQty = defectQty; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getLineCode() { return lineCode; }
    public void setLineCode(String lineCode) { this.lineCode = lineCode; }
    public String getDeviceCode() { return deviceCode; }
    public void setDeviceCode(String deviceCode) { this.deviceCode = deviceCode; }
    public Long getWarehouseId() { return warehouseId; }
    public void setWarehouseId(Long warehouseId) { this.warehouseId = warehouseId; }
    public Long getMaterialWhId() { return materialWhId; }
    public void setMaterialWhId(Long materialWhId) { this.materialWhId = materialWhId; }
    public String getSrcOrderNo() { return srcOrderNo; }
    public void setSrcOrderNo(String srcOrderNo) { this.srcOrderNo = srcOrderNo; }
    public boolean isAutoBackflush() { return autoBackflush; }
    public void setAutoBackflush(boolean autoBackflush) { this.autoBackflush = autoBackflush; }
    public Long getPlanStart() { return planStart; }
    public void setPlanStart(Long planStart) { this.planStart = planStart; }
    public Long getPlanEnd() { return planEnd; }
    public void setPlanEnd(Long planEnd) { this.planEnd = planEnd; }
    public Long getActualStart() { return actualStart; }
    public void setActualStart(Long actualStart) { this.actualStart = actualStart; }
    public Long getActualEnd() { return actualEnd; }
    public void setActualEnd(Long actualEnd) { this.actualEnd = actualEnd; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }
}
