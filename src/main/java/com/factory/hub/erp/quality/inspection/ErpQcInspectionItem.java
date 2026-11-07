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
 * 检验单明细行（一个检验项一行）。
 * <p>
 * <b>为什么「项」和「件」必须分开记</b>：
 * 检验员看到的是"这件产品的尺寸超差、那件的外观划伤"，一行一条检验项记录的是
 * <b>某个缺陷在这一项上被判了几件不合格</b>（{@code defectQty}）。
 * 判定用的是单头的 {@code defectQty}（不合格<b>品</b>数，同一件不重复计数），
 * 明细行的 qty 只做汇总参考——把所有明细的 qty 加起来会大于等于单头的件数，
 * 因为同一件产品可能同时出现在两行里。这不是 bug，是两套口径。
 * <p>
 * 明细行整体替换（改草稿时提交的就是这张单的最终样子），所以行号 {@code lineNo}
 * 只是展示顺序，不承担业务含义；唯一键 {@code (inspection_id, line_no)} 保证不会出现
 * 同一张单里两个第 3 行。
 * <p>
 * 缺陷分类冗余 {@code defectCode / severity} 快照：分类主数据被改名或停用后，
 * 历史检验单上还是当时的名字与分级，对账时不会串。
 */
@Entity
@Table(name = "erp_qc_inspection_item", indexes = {
        @Index(name = "uk_qc_item", columnList = "inspection_id,line_no", unique = true),
        @Index(name = "idx_qc_item_code", columnList = "defect_code_id")
})
public class ErpQcInspectionItem {

    public static final String RESULT_OK = "OK";
    public static final String RESULT_NG = "NG";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属检验单 id（裸 Long + 索引，全库不建外键） */
    @Column(name = "inspection_id", nullable = false)
    private Long inspectionId;

    /** 行号，从 1 开始，仅表示展示顺序 */
    @Column(name = "line_no", nullable = false)
    private int lineNo = 1;

    /** 检验项目名（"外径""表面""导通电阻"） */
    @Column(name = "item_name", nullable = false, length = 128)
    private String itemName;

    /** 标准要求（"10±0.05""无划伤"），照图纸/检验指导书写 */
    @Column(name = "standard", length = 255)
    private String standard;

    /** 检验方法（卡尺/千分尺/目视/通止规），决定用什么量具，也影响复现性 */
    @Column(name = "method", length = 64)
    private String method;

    /** 缺陷分类 id；未分类为 null（帕累托里会落到"未分类"行，不能悄悄丢掉） */
    @Column(name = "defect_code_id")
    private Long defectCodeId;

    @Column(name = "defect_code", length = 32)
    private String defectCode;

    @Column(name = "defect_name", length = 128)
    private String defectName;

    /** 缺陷分级快照 CR / MA / MI */
    @Column(name = "severity", length = 4)
    private String severity;

    /** 实测值/实测描述："10.02""有 3 处划伤，最长 8mm" */
    @Column(name = "measured", length = 4000)
    private String measured;

    /** 本项结论 OK / NG */
    @Column(name = "result", length = 8)
    private String result = RESULT_OK;

    /** 本项判为不合格的<b>件</b>数（与单头的件数不是同一个口径，见类注释） */
    @Column(name = "defect_qty", nullable = false, precision = 18, scale = 4)
    private BigDecimal defectQty = BigDecimal.ZERO;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "remark")
    private String remark;

    @Column(name = "created_at", nullable = false)
    private long createdAt = System.currentTimeMillis();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getInspectionId() { return inspectionId; }
    public void setInspectionId(Long inspectionId) { this.inspectionId = inspectionId; }
    public int getLineNo() { return lineNo; }
    public void setLineNo(int lineNo) { this.lineNo = lineNo; }
    public String getItemName() { return itemName; }
    public void setItemName(String itemName) { this.itemName = itemName; }
    public String getStandard() { return standard; }
    public void setStandard(String standard) { this.standard = standard; }
    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }
    public Long getDefectCodeId() { return defectCodeId; }
    public void setDefectCodeId(Long defectCodeId) { this.defectCodeId = defectCodeId; }
    public String getDefectCode() { return defectCode; }
    public void setDefectCode(String defectCode) { this.defectCode = defectCode; }
    public String getDefectName() { return defectName; }
    public void setDefectName(String defectName) { this.defectName = defectName; }
    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }
    public String getMeasured() { return measured; }
    public void setMeasured(String measured) { this.measured = measured; }
    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }
    public BigDecimal getDefectQty() { return defectQty; }
    public void setDefectQty(BigDecimal defectQty) { this.defectQty = defectQty; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    public boolean isNg() {
        return RESULT_NG.equals(result);
    }
}
