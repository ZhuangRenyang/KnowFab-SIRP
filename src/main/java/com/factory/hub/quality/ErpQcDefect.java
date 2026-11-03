package com.factory.hub.quality;

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
 * 不良品记录——待处置的实物在这里排队。
 * <p>
 * <b>为什么 {@code sourceKey} 是唯一键</b>：设备通道的小时不良数会被定时任务反复同步，
 * 网络重试、进程重启、手动点"同步"都会再算一遍。靠"我记得我写过"这种应用层判断
 * 在重启后就失效了，只有数据库唯一键真正挡得住重复。
 * 键的形态是 {@code DEVICE:设备码:小时起点} / {@code INSPECTION:检验单号} / {@code MANUAL:设备码:日期},
 * 由同步服务生成，这里只负责约束。
 * <p>
 * <b>{@code inStock} 决定处置动不动库存</b>（这是本表最容易做错的一列）：
 * <ul>
 *   <li>{@code false}：这批不良从未入过账（设备采集的在制品、IQC 判定前的不合格品），
 *       报废<b>不动库存</b>，只改状态——去扣库存会把别人的货扣掉，然后账实不符；</li>
 *   <li>{@code true}：已经入过账（客户退货、库存抽检、FQC 之后才判的），
 *       报废<b>必须出库</b>（{@code SCRAP_OUT}），否则账面数量永远比现实多。</li>
 * </ul>
 * <p>
 * <b>本表不存处置方式</b>（刻意）：一批 8 件真实场景是"3 件报废 + 5 件返工"，
 * 存一个单值 {@code disposition} 只能二选一——填报废则 8 件全废（返工那 5 件白扔），
 * 填返工则报废那 3 件变成不存在的工时。处置方式在 {@link ErpQcDisposition} 明细里，
 * 一批可以有多条。本表只留两个汇总列：
 * <ul>
 *   <li>{@code disposedQty}：已处置件数（镜像列，见下）；</li>
 *   <li>{@code status}：PENDING / DISPOSED / CANCELLED 三态。</li>
 * </ul>
 * <b>没有 PARTIAL 这个状态</b>：8 件处置了 3 件仍然是 PENDING，
 * 靠 {@code disposedQty} 这个数表达进度（页面显示「已处置 3 · 待处置 5」）。
 * 多一个状态就多一批边界情况，尤其"处置全部作废后要从 PARTIAL 退回 PENDING"这种。
 * <p>
 * <b>{@code disposedQty} 是镜像列</b>，写法与 {@code erp_work_order.done_qty} 完全一致：
 * {@code = sum(处置明细.qty - 处置明细.fail_qty) where voided_at is null}，
 * 只由处置服务重算写入，任何绕过它直接写都会被审计抓出来（待处置数对不上）。
 * 为什么存而不每次聚合：列表 20 行 × 聚合查询不划算，且看板的"待处置件数"要能直接 sum。
 * <p>
 * <b>{@code status} 也是镜像列但只三态</b>，回跳是正确行为：6 件返工 1 件失败 →
 * 有效处置量 5 → 待处置回到 1 → 状态从 DISPOSED 退回 PENDING。这不是 bug，
 * 因为那 1 件确实又变成待处置了；界面靠 {@code statusHint} 说明原因，
 * 别让仓管以为数据错了。
 */
@Entity
@Table(name = "erp_qc_defect", indexes = {
        @Index(name = "uk_qc_defect_no", columnList = "defect_no", unique = true),
        @Index(name = "uk_qc_defect_source", columnList = "source_key", unique = true),
        @Index(name = "idx_qc_defect_found", columnList = "found_at"),
        @Index(name = "idx_qc_defect_code", columnList = "defect_code_id"),
        @Index(name = "idx_qc_defect_ins", columnList = "inspection_no"),
        @Index(name = "idx_qc_defect_device", columnList = "device_code"),
        @Index(name = "idx_qc_defect_status", columnList = "status")
})
public class ErpQcDefect {

    /** 设备采集（reject 点位小时量） */
    public static final String SRC_DEVICE = "DEVICE";
    /** 检验单判定产出 */
    public static final String SRC_INSPECTION = "INSPECTION";
    /** 人工补录 */
    public static final String SRC_MANUAL = "MANUAL";
    /** 客户退货（仅质量问题退货；订多/发错/取消的退货是良品，不生成不良记录） */
    public static final String SRC_RETURN = "RETURN";

    /** 待处置（含"处置了一部分"，进度看 disposedQty） */
    public static final String ST_PENDING = "PENDING";
    /** 已处置：待处置件数 == 0 */
    public static final String ST_DISPOSED = "DISPOSED";
    /** 已作废 */
    public static final String ST_CANCELLED = "CANCELLED";

    public static final java.util.List<String> STATUSES = java.util.List.of(
            ST_PENDING, ST_DISPOSED, ST_CANCELLED);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 不良单号：QC-BL + yyyyMMdd + 4 位流水 */
    @Column(name = "defect_no", nullable = false, length = 40)
    private String defectNo;

    /** 来源通道 DEVICE / INSPECTION / MANUAL */
    @Column(name = "source", nullable = false, length = 16)
    private String source = SRC_MANUAL;

    /** 幂等键（唯一）：同一来源的同一批只允许存在一条记录 */
    @Column(name = "source_key", nullable = false, length = 128)
    private String sourceKey;

    /** 来源检验单号（source = INSPECTION 时非空） */
    @Column(name = "inspection_no", length = 40)
    private String inspectionNo;

    @Column(name = "work_order_no", length = 40)
    private String workOrderNo;

    @Column(name = "device_code", length = 64)
    private String deviceCode;

    @Column(name = "sku_id")
    private Long skuId;

    @Column(name = "sku_code", length = 64)
    private String skuCode;

    @Column(name = "sku_name", length = 128)
    private String skuName;

    @Column(name = "lot_no", length = 40)
    private String lotNo;

    /* ---- 缺陷分类快照：分类改名后历史记录仍是当时的名字 ---- */

    @Column(name = "defect_code_id")
    private Long defectCodeId;

    @Column(name = "defect_code", length = 32)
    private String defectCode;

    @Column(name = "defect_name", length = 128)
    private String defectName;

    /** CR / MA / MI；未分类为 null（看板固定显示"未分类"卡片，不许静默吞掉） */
    @Column(name = "severity", length = 4)
    private String severity;

    /** 不良件数 */
    @Column(name = "qty", nullable = false, precision = 18, scale = 4)
    private BigDecimal qty = BigDecimal.ZERO;

    /** 是否已入账，决定处置动不动库存（见类注释） */
    @Column(name = "in_stock", nullable = false)
    private boolean inStock = false;

    /** 入账单据号：inStock=true 时必须能答出"这批货是哪张单进来的" */
    @Column(name = "src_bill_no", length = 40)
    private String srcBillNo;

    /** 实物所在仓：inStock=true 时报废/调拨动这个仓；未入账为空 */
    @Column(name = "warehouse_id")
    private Long warehouseId;

    /** 发现时间（业务时间，不是录入时间）：报表按它归期 */
    @Column(name = "found_at", nullable = false)
    private long foundAt = System.currentTimeMillis();

    @Column(name = "registered_by", length = 64)
    private String registeredBy;

    /**
     * 已处置件数（镜像列）。
     * <p>
     * {@code = sum(处置明细.qty - 处置明细.fail_qty) where voided_at is null}
     * ——减 {@code fail_qty} 是因为返工失败的件数退回了待处置，没被真正消费掉
     * （6 件返工 1 件失败 → 有效消费 5，可处置额度回到 1）。
     * 只由处置服务重算写入，别处不许直接改。
     */
    @Column(name = "disposed_qty", nullable = false, precision = 18, scale = 4)
    private BigDecimal disposedQty = BigDecimal.ZERO;

    /** 处置状态 PENDING / DISPOSED / CANCELLED（三态，没有 PARTIAL，见类注释） */
    @Column(name = "status", nullable = false, length = 16)
    private String status = ST_PENDING;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "remark")
    private String remark;

    @Column(name = "created_at", nullable = false)
    private long createdAt = System.currentTimeMillis();

    @Column(name = "updated_at", nullable = false)
    private long updatedAt = System.currentTimeMillis();

    /** 作废时间；null = 有效。作废的不良单不再参与待处置统计 */
    @Column(name = "voided_at")
    private Long voidedAt;

    @Column(name = "voided_by", length = 64)
    private String voidedBy;

    @Column(name = "void_reason", length = 255)
    private String voidReason;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getDefectNo() { return defectNo; }
    public void setDefectNo(String defectNo) { this.defectNo = defectNo; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getSourceKey() { return sourceKey; }
    public void setSourceKey(String sourceKey) { this.sourceKey = sourceKey; }
    public String getInspectionNo() { return inspectionNo; }
    public void setInspectionNo(String inspectionNo) { this.inspectionNo = inspectionNo; }
    public String getWorkOrderNo() { return workOrderNo; }
    public void setWorkOrderNo(String workOrderNo) { this.workOrderNo = workOrderNo; }
    public String getDeviceCode() { return deviceCode; }
    public void setDeviceCode(String deviceCode) { this.deviceCode = deviceCode; }
    public Long getSkuId() { return skuId; }
    public void setSkuId(Long skuId) { this.skuId = skuId; }
    public String getSkuCode() { return skuCode; }
    public void setSkuCode(String skuCode) { this.skuCode = skuCode; }
    public String getSkuName() { return skuName; }
    public void setSkuName(String skuName) { this.skuName = skuName; }
    public String getLotNo() { return lotNo; }
    public void setLotNo(String lotNo) { this.lotNo = lotNo; }
    public Long getDefectCodeId() { return defectCodeId; }
    public void setDefectCodeId(Long defectCodeId) { this.defectCodeId = defectCodeId; }
    public String getDefectCode() { return defectCode; }
    public void setDefectCode(String defectCode) { this.defectCode = defectCode; }
    public String getDefectName() { return defectName; }
    public void setDefectName(String defectName) { this.defectName = defectName; }
    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }
    public BigDecimal getQty() { return qty; }
    public void setQty(BigDecimal qty) { this.qty = qty; }
    public boolean isInStock() { return inStock; }
    public void setInStock(boolean inStock) { this.inStock = inStock; }
    public String getSrcBillNo() { return srcBillNo; }
    public void setSrcBillNo(String srcBillNo) { this.srcBillNo = srcBillNo; }
    public Long getWarehouseId() { return warehouseId; }
    public void setWarehouseId(Long warehouseId) { this.warehouseId = warehouseId; }
    public long getFoundAt() { return foundAt; }
    public void setFoundAt(long foundAt) { this.foundAt = foundAt; }
    public String getRegisteredBy() { return registeredBy; }
    public void setRegisteredBy(String registeredBy) { this.registeredBy = registeredBy; }
    public BigDecimal getDisposedQty() { return disposedQty; }
    public void setDisposedQty(BigDecimal disposedQty) { this.disposedQty = disposedQty; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }
    public Long getVoidedAt() { return voidedAt; }
    public void setVoidedAt(Long voidedAt) { this.voidedAt = voidedAt; }
    public String getVoidedBy() { return voidedBy; }
    public void setVoidedBy(String voidedBy) { this.voidedBy = voidedBy; }
    public String getVoidReason() { return voidReason; }
    public void setVoidReason(String voidReason) { this.voidReason = voidReason; }

    public boolean isVoided() {
        return voidedAt != null;
    }

    /**
     * 待处置件数 = 不良件数 − 已处置件数。
     * <p>
     * 看板/列表里的「待处置」<b>必须按件算</b>，不能按条算：8 件的单处置 3 件后
     * 如果按条统计会显示"待处置 8 件"，车间会一直以为积压很多。
     */
    public BigDecimal pendingQty() {
        BigDecimal q = qty == null ? BigDecimal.ZERO : qty;
        BigDecimal d = disposedQty == null ? BigDecimal.ZERO : disposedQty;
        BigDecimal left = q.subtract(d);
        return left.signum() < 0 ? BigDecimal.ZERO : left;
    }

    /** 还能不能继续处置（作废的、已处置完的都不行） */
    public boolean isDisposable() {
        return !isVoided() && !ST_CANCELLED.equals(status) && pendingQty().signum() > 0;
    }

    /** 已入账但答不出来源单号：数据不自洽，处置前必须拦下来（见 Spec §9） */
    public boolean isSourceMissing() {
        return inStock && (srcBillNo == null || srcBillNo.isBlank());
    }
}
