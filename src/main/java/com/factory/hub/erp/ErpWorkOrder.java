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
        @Index(name = "idx_wo_type", columnList = "order_type"),
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

    /** 正常生产工单 */
    public static final String TYPE_PROD = "PROD";
    /** 返工工单：不良品处置「返工」产生，指向原工单 */
    public static final String TYPE_REWORK = "REWORK";

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

    /**
     * 工单类型 {@link #TYPE_PROD} / {@link #TYPE_REWORK}，默认正常生产。
     * <p>
     * <b>为什么必须是独立一列而不是靠"有没有 src_work_order_no"判断</b>：
     * 返工单在没有原工单时也可能存在（客户寄回来一批不良品，找不到当年的工单号），
     * 这时它就是返工单但没有来源；反过来，手工建单时也可能填了来源单号做备注。
     * 用"有没有来源"推类型，两种边界都会判错，而判错的后果是成本统计把返工算进正常生产。
     */
    @Column(name = "order_type", nullable = false, length = 16)
    private String orderType = TYPE_PROD;

    /**
     * 返工单指向的原工单号（可空）。
     * <p>
     * 让它可空是刻意的：客户退回的不良品往往找不到原工单（跨年、跨系统导入），
     * 强制必填会让现场随便编一个单号填进去——那比留空更糟，因为假的追溯链比断的更危险。
     */
    @Column(name = "src_work_order_no", length = 40)
    private String srcWorkOrderNo;

    /**
     * 返工单指向的不良单号（可空）：追溯"这批返工是哪张不良单发起的"。
     * <p>
     * 为什么单独一列而不用 {@code src_order_no}：{@code src_order_no} 的语义是"来源销售订单"，
     * 填一个不良单号进去会让报表把返工单的来源算成销售订单（数字对不上还不报错）。
     * 两件事各占一列，比一列混着用强。
     */
    @Column(name = "src_defect_no", length = 40)
    private String srcDefectNo;

    /** 是否在产出时按 BOM 自动反冲领料（1 = 自动扣料，0 = 只记产出，料由人工领） */
    @Column(name = "auto_backflush", nullable = false)
    private boolean autoBackflush = true;

    /**
     * 计划开始时间（毫秒时间戳）。<b>NULL = 未排程</b>（不是 0）——
     * 存 0 会让工单出现在甘特图的最左边，看着像"排了五十年"。
     */
    @Column(name = "plan_start")
    private Long planStart;

    /**
     * 计划结束时间（毫秒时间戳）。与 {@link #planStart} 构成**半开区间**
     * {@code [planStart, planEnd)}：前一张的结束 == 后一张的开始**不算冲突**
     * （交接班/换型时刻是正常衔接）。
     * <p>排程口径见 {@link com.factory.hub.erp.ErpScheduleService} 的类注释。
     */
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
    public String getOrderType() { return orderType; }
    public void setOrderType(String orderType) { this.orderType = orderType; }
    public String getSrcWorkOrderNo() { return srcWorkOrderNo; }
    public void setSrcWorkOrderNo(String srcWorkOrderNo) { this.srcWorkOrderNo = srcWorkOrderNo; }
    public String getSrcDefectNo() { return srcDefectNo; }
    public void setSrcDefectNo(String srcDefectNo) { this.srcDefectNo = srcDefectNo; }

    /**
     * 是否返工单。
     * <p>
     * <b>判空要稳</b>：升级上来的老行 order_type 由 1.7.0 迁移脚本回填成 PROD，
     * 但万一某个环境脚本没跑到（例如迁移被关掉），null 也必须当 PROD 处理，
     * 不能在这里抛 NPE——工单列表一崩，整个 ERP 都进不去。
     */
    public boolean isRework() {
        return TYPE_REWORK.equals(orderType);
    }
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
