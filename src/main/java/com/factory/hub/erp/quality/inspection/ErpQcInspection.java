package com.factory.hub.erp.quality.inspection;

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
 * 检验单头（IQC / FAI / IPQC / FQC）。
 * <p>
 * <b>本表最重要的设计是「抽样参数快照」</b>：{@code sampleLevel / strictLevel / aql /
 * codeLetter / sampleSize / ac / re} 在建单那一刻就从方案表抄进来，提交后永不回查方案表。
 * 理由是同一个 AQL 方案客户下个月改了一行，前天的检验单判定跟着变，
 * 那就等于"质量记录可以被追溯篡改"——审核时这一条足以否掉整套体系。
 * <p>
 * <b>判定只认 {@code defectQty}（不合格品数），不认 {@code defectCr/Ma/Mi}</b>：
 * 前者是"多少件不合格"，后者是"发现了多少个缺陷项"。一件产品同时有尺寸和外观两个缺陷，
 * 在 AQL 里仍然只算 <b>1 件</b>不合格品。按项数判会判反
 * （实测例子：10 项 → 5 件，n=80/Ac=5，按件数判合格，按项数判不合格）。
 * {@code defectCr/Ma/Mi} 只服务缺陷分析（帕累托、缺陷密度）。
 * <p>
 * <b>状态机</b>：
 * <pre>
 *   DRAFT ──submit──→ SUBMITTED ──judge──→ JUDGED
 *     │                   │
 *     └────cancel─────────┴──────cancel──────┘   （JUDGED 不可作废）
 * </pre>
 * 草稿可整表替换明细；提交锁定快照；判定后 {@code result} 落 PASS / FAIL / CONDITIONAL。
 * {@code CONDITIONAL}（让步接收）<b>只能人工选</b>，算法永远不产出——让不让步是商务决定，
 * 不是质量计算的结果（见 {@link ErpAqlService}）。
 */
@Entity
@Table(name = "erp_qc_inspection", indexes = {
        @Index(name = "uk_qc_ins_no", columnList = "inspection_no", unique = true),
        @Index(name = "idx_qc_ins_status", columnList = "inspection_type,status"),
        @Index(name = "idx_qc_ins_created", columnList = "created_at"),
        @Index(name = "idx_qc_ins_sku", columnList = "sku_id"),
        @Index(name = "idx_qc_ins_src", columnList = "src_bill_no")
})
public class ErpQcInspection {

    public static final String TYPE_IQC = "IQC";
    public static final String TYPE_FAI = "FAI";
    public static final String TYPE_IPQC = "IPQC";
    public static final String TYPE_FQC = "FQC";

    public static final java.util.List<String> TYPES = java.util.List.of(
            TYPE_IQC, TYPE_FAI, TYPE_IPQC, TYPE_FQC);

    public static final String ST_DRAFT = "DRAFT";
    public static final String ST_SUBMITTED = "SUBMITTED";
    public static final String ST_JUDGED = "JUDGED";
    public static final String ST_CANCELLED = "CANCELLED";

    public static final java.util.List<String> STATUSES = java.util.List.of(
            ST_DRAFT, ST_SUBMITTED, ST_JUDGED, ST_CANCELLED);

    public static final String RESULT_PASS = "PASS";
    public static final String RESULT_FAIL = "FAIL";
    /** 让步接收：只能人手动选，算法不产出 */
    public static final String RESULT_CONDITIONAL = "CONDITIONAL";

    /** 抽样参数来自 AQL 方案表 */
    public static final String SAMPLE_AQL = "AQL";
    /** 抽样参数由标准/类型固定（FAI n=1、IPQC 手填） */
    public static final String SAMPLE_FIXED = "FIXED";
    /** 主数据缺失的兜底：全检 + Ac=0/Re=1，必须单独统计 */
    public static final String SAMPLE_MANUAL = "MANUAL";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 检验单号：QC + yyyyMMdd + 4 位流水，与库存单号同一套生成规则 */
    @Column(name = "inspection_no", nullable = false, length = 40)
    private String inspectionNo;

    /** 检验类型 IQC / FAI / IPQC / FQC */
    @Column(name = "inspection_type", nullable = false, length = 16)
    private String inspectionType = TYPE_IQC;

    @Column(name = "status", nullable = false, length = 16)
    private String status = ST_DRAFT;

    /** 判定结论 PASS / FAIL / CONDITIONAL；未判定为 null */
    @Column(name = "result", length = 16)
    private String result;

    /* ---- 对象快照：产品改名后检验单上仍显示当时的名字 ---- */

    @Column(name = "sku_id", nullable = false)
    private Long skuId;

    @Column(name = "sku_code", length = 64)
    private String skuCode;

    @Column(name = "sku_name", length = 128)
    private String skuName;

    @Column(name = "sku_spec", length = 128)
    private String skuSpec;

    @Column(name = "sku_unit", length = 16)
    private String skuUnit;

    /** 供应商（IQC 用）；其他类型为空 */
    @Column(name = "partner_id")
    private Long partnerId;

    @Column(name = "partner_name", length = 128)
    private String partnerName;

    /** 批号：自由文本，不做批次库存（本期明确范围外），只用于报表分组与追溯说明 */
    @Column(name = "lot_no", length = 40)
    private String lotNo;

    /** 送检批量 N（抽样方案按它查档） */
    @Column(name = "lot_qty", nullable = false, precision = 18, scale = 4)
    private BigDecimal lotQty = BigDecimal.ZERO;

    /** 来源单据号（采购单 / 工单 / 销售单），追"这批货是哪来的" */
    @Column(name = "src_bill_no", length = 40)
    private String srcBillNo;

    /** 现场设备（IPQC/FQC 用） */
    @Column(name = "device_code", length = 64)
    private String deviceCode;

    /* ---- 抽样参数快照：提交后永久冻结，判定只读这里 ---- */

    @Column(name = "sample_level", length = 16)
    private String sampleLevel;

    @Column(name = "strict_level", length = 16)
    private String strictLevel;

    @Column(name = "aql", precision = 10, scale = 4)
    private BigDecimal aql;

    @Column(name = "code_letter", length = 2)
    private String codeLetter;

    /** 抽样数 n */
    @Column(name = "sample_size")
    private Integer sampleSize;

    /** 接收数。-1 = 零容忍（CR 触发） */
    @Column(name = "ac")
    private Integer ac;

    @Column(name = "re")
    private Integer re;

    /** 是否全检（批量不够抽样数，或 FAI/IPQC 这种必须全数的类型） */
    @Column(name = "full_inspection", nullable = false)
    private boolean fullInspection = false;

    /**
     * 抽样参数的来源，三值：
     * <ul>
     *   <li>{@link #SAMPLE_AQL}：正常查方案表（大多数情况）；</li>
     *   <li>{@link #SAMPLE_FIXED}：标准本身规定的固定样本量（FAI 的 n=1、IPQC 手填 n），
     *       与主数据无关；</li>
     *   <li>{@link #SAMPLE_MANUAL}：<b>主数据缺失时的兜底</b>——查不到方案又不能卡住检验员
     *       （货已经在门口等着判），只能按全检 + Ac=0/Re=1 走，并把来源标出来。</li>
     * </ul>
     * <b>FIXED 和 MANUAL 必须能分开统计</b>：前者是标准要求，后者是"质量字典没维护到位"的信号。
     * 混在一起，看板上永远看不出主数据缺了多少档，那批临时全检就会永久留下来。
     */
    @Column(name = "sample_source", nullable = false, length = 8)
    private String sampleSource = SAMPLE_AQL;

    /* ---- 检验结果 ---- */

    /** 不合格<b>品</b>数：判定唯一依据 */
    @Column(name = "defect_qty", nullable = false, precision = 18, scale = 4)
    private BigDecimal defectQty = BigDecimal.ZERO;

    /** 致命缺陷<b>项</b>数：> 0 即整批拒收，只用于分析 */
    @Column(name = "defect_cr", nullable = false)
    private int defectCr;

    /** 严重缺陷<b>项</b>数 */
    @Column(name = "defect_ma", nullable = false)
    private int defectMa;

    /** 轻微缺陷<b>项</b>数 */
    @Column(name = "defect_mi", nullable = false)
    private int defectMi;

    @Column(name = "inspector", length = 64)
    private String inspector;

    @Column(name = "inspected_at")
    private Long inspectedAt;

    @Column(name = "judged_by", length = 64)
    private String judgedBy;

    @Column(name = "judged_at")
    private Long judgedAt;

    /** 判定说明：判 FAIL 时写清依据（哪一条超了 Ac），判 PASS 时可空 */
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "remark")
    private String remark;

    @Column(name = "created_by", length = 64)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private long createdAt = System.currentTimeMillis();

    @Column(name = "updated_at", nullable = false)
    private long updatedAt = System.currentTimeMillis();

    /** 作废时间；null = 有效。已判定的单不允许作废（结论一旦产生就要留档） */
    @Column(name = "voided_at")
    private Long voidedAt;

    @Column(name = "voided_by", length = 64)
    private String voidedBy;

    @Column(name = "void_reason", length = 255)
    private String voidReason;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getInspectionNo() { return inspectionNo; }
    public void setInspectionNo(String inspectionNo) { this.inspectionNo = inspectionNo; }
    public String getInspectionType() { return inspectionType; }
    public void setInspectionType(String inspectionType) { this.inspectionType = inspectionType; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }
    public Long getSkuId() { return skuId; }
    public void setSkuId(Long skuId) { this.skuId = skuId; }
    public String getSkuCode() { return skuCode; }
    public void setSkuCode(String skuCode) { this.skuCode = skuCode; }
    public String getSkuName() { return skuName; }
    public void setSkuName(String skuName) { this.skuName = skuName; }
    public String getSkuSpec() { return skuSpec; }
    public void setSkuSpec(String skuSpec) { this.skuSpec = skuSpec; }
    public String getSkuUnit() { return skuUnit; }
    public void setSkuUnit(String skuUnit) { this.skuUnit = skuUnit; }
    public Long getPartnerId() { return partnerId; }
    public void setPartnerId(Long partnerId) { this.partnerId = partnerId; }
    public String getPartnerName() { return partnerName; }
    public void setPartnerName(String partnerName) { this.partnerName = partnerName; }
    public String getLotNo() { return lotNo; }
    public void setLotNo(String lotNo) { this.lotNo = lotNo; }
    public BigDecimal getLotQty() { return lotQty; }
    public void setLotQty(BigDecimal lotQty) { this.lotQty = lotQty; }
    public String getSrcBillNo() { return srcBillNo; }
    public void setSrcBillNo(String srcBillNo) { this.srcBillNo = srcBillNo; }
    public String getDeviceCode() { return deviceCode; }
    public void setDeviceCode(String deviceCode) { this.deviceCode = deviceCode; }
    public String getSampleLevel() { return sampleLevel; }
    public void setSampleLevel(String sampleLevel) { this.sampleLevel = sampleLevel; }
    public String getStrictLevel() { return strictLevel; }
    public void setStrictLevel(String strictLevel) { this.strictLevel = strictLevel; }
    public BigDecimal getAql() { return aql; }
    public void setAql(BigDecimal aql) { this.aql = aql; }
    public String getCodeLetter() { return codeLetter; }
    public void setCodeLetter(String codeLetter) { this.codeLetter = codeLetter; }
    public Integer getSampleSize() { return sampleSize; }
    public void setSampleSize(Integer sampleSize) { this.sampleSize = sampleSize; }
    public Integer getAc() { return ac; }
    public void setAc(Integer ac) { this.ac = ac; }
    public Integer getRe() { return re; }
    public void setRe(Integer re) { this.re = re; }
    public boolean isFullInspection() { return fullInspection; }
    public void setFullInspection(boolean fullInspection) { this.fullInspection = fullInspection; }
    public String getSampleSource() { return sampleSource; }
    public void setSampleSource(String sampleSource) { this.sampleSource = sampleSource; }
    public BigDecimal getDefectQty() { return defectQty; }
    public void setDefectQty(BigDecimal defectQty) { this.defectQty = defectQty; }
    public int getDefectCr() { return defectCr; }
    public void setDefectCr(int defectCr) { this.defectCr = defectCr; }
    public int getDefectMa() { return defectMa; }
    public void setDefectMa(int defectMa) { this.defectMa = defectMa; }
    public int getDefectMi() { return defectMi; }
    public void setDefectMi(int defectMi) { this.defectMi = defectMi; }
    public String getInspector() { return inspector; }
    public void setInspector(String inspector) { this.inspector = inspector; }
    public Long getInspectedAt() { return inspectedAt; }
    public void setInspectedAt(Long inspectedAt) { this.inspectedAt = inspectedAt; }
    public String getJudgedBy() { return judgedBy; }
    public void setJudgedBy(String judgedBy) { this.judgedBy = judgedBy; }
    public Long getJudgedAt() { return judgedAt; }
    public void setJudgedAt(Long judgedAt) { this.judgedAt = judgedAt; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
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

    /** 草稿才能改（提交后快照冻结） */
    public boolean isEditable() {
        return ST_DRAFT.equals(status) && !isVoided();
    }
}
