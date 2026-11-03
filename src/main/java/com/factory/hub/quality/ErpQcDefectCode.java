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

/**
 * 缺陷分类主数据（质量字典）——「不良品管理」的最小公约数。
 * <p>
 * <b>为什么必须先有它</b>：没有缺陷分类，不良记录只能写一段自由文本，
 * 帕累托图就永远生成不出来（"划伤""刮伤""表面划伤"会变成三行），
 * 而帕累托是质量改善唯一真正有用的排序工具。所以不良记录一律引用本表的分类，
 * 自由文本只放在 {@code remark} 里做补充说明。
 * <p>
 * <b>三级分级（{@code severity}）不是标签，是判定依据</b>：
 * <ul>
 *   <li>{@code CR} 致命 —— 任何一件都整批拒收，<b>不走 AQL</b>（见 {@link ErpAqlService#AC_ZERO_TOLERANCE}）；</li>
 *   <li>{@code MA} 严重 —— 主要功能失效，按约定的 AQL 判；</li>
 *   <li>{@code MI} 轻微 —— 外观瑕疵，AQL 通常更松。</li>
 * </ul>
 * 分级填错的后果是判定结论直接反过来（把 CR 填成 MI，一批该退的货会判合格），
 * 所以列表页把 severity 摆在第一列，编辑时也强制必填。
 * <p>
 * <b>被引用过就只能停用、不能删</b>：不良记录、检验项都按 id 引用它，
 * 删掉分类会让历史记录变成"未分类"，历史报表当场失真。
 * 删除校验在 {@code ErpQcDefectCodeService.delete()} 里，用引用计数拦。
 */
@Entity
@Table(name = "erp_qc_defect_code", indexes = {
        @Index(name = "uk_qc_dc_code", columnList = "code", unique = true),
        @Index(name = "idx_qc_dc_severity", columnList = "severity"),
        @Index(name = "idx_qc_dc_enabled", columnList = "enabled")
})
public class ErpQcDefectCode {

    /** 致命：一件即整批拒收，AQL 对它不适用 */
    public static final String SEV_CR = "CR";
    /** 严重 */
    public static final String SEV_MA = "MA";
    /** 轻微 */
    public static final String SEV_MI = "MI";

    public static final java.util.List<String> SEVERITIES = java.util.List.of(SEV_CR, SEV_MA, SEV_MI);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 缺陷代码：现场填写用，短、能手写（XC01 / CR-LEAK …），全表唯一 */
    @Column(name = "code", nullable = false, length = 32)
    private String code;

    /** 中文名（"表面划伤"），帕累托图上的那一行 */
    @Column(name = "name", nullable = false, length = 128)
    private String name;

    /** 分级 CR / MA / MI，判定用，见类注释 */
    @Column(name = "severity", nullable = false, length = 4)
    private String severity = SEV_MA;

    /** 缺陷大类（外观/尺寸/功能/材料），字典页按它分组显示 */
    @Column(name = "category", length = 64)
    private String category;

    /**
     * 默认 AQL（百分比，如 1.0 表示 1.0）。
     * <p>
     * 只是建检验单时的预填值，不是"标准答案"——同一类缺陷对不同客户可以有不同 AQL，
     * 真正生效的是检验单头上那份快照。null = 未给出默认值。
     */
    @Column(name = "default_aql", precision = 10, scale = 4)
    private java.math.BigDecimal defaultAql;

    /** 排查要点/典型现象。检验员按这句判断"算不算这一条"，比只写一个名字有用得多 */
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "remark")
    private String remark;

    /** 排序号：列表和下拉框里按它升序，让常用缺陷排前面 */
    @Column(name = "sort_no", nullable = false)
    private int sortNo = 0;

    /** 启用标记。停用后不再出现在新建时的下拉里，但历史记录照常显示 */
    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false)
    private long createdAt = System.currentTimeMillis();

    @Column(name = "updated_at", nullable = false)
    private long updatedAt = System.currentTimeMillis();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public java.math.BigDecimal getDefaultAql() { return defaultAql; }
    public void setDefaultAql(java.math.BigDecimal defaultAql) { this.defaultAql = defaultAql; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public int getSortNo() { return sortNo; }
    public void setSortNo(int sortNo) { this.sortNo = sortNo; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }

    /** 致命缺陷：判定时一票否决，不看 AQL */
    public boolean isCritical() {
        return SEV_CR.equals(severity);
    }
}
