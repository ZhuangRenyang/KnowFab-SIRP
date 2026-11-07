package com.factory.hub.erp.quality.inspection;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/**
 * AQL 抽样方案（GB/T 2828.1-2012 表 2-A，正常检验一次抽样）。
 * <p>
 * <b>为什么把国标做成数据表而不是代码里的常量</b>：
 * ① 客户验厂时常被要求按自己的 AQL 组合执行（电子 1.0/2.5/4.0、服装 2.5/4.0），
 *    常量改一次要发版；
 * ② 表里有些格子是"箭头"（该格没有方案，要顺着箭头换样本量），不同版本的标准在
 *    边角处细节不同，现场需要能自己修一行；
 * ③ 误判的代价是把一批货判反。数据表至少能被人一眼看到并订正。
 * <p>
 * <b>一行 = 一个格子</b>：{@code (检验水平, 严格度, 批量下限, 批量上限, AQL)} → 字码/样本量/Ac/Re。
 * <p>
 * <b>{@code lot_to} 用 999999999 而不是 NULL</b>：MySQL 的 UNIQUE 索引允许多个 NULL，
 * 用 NULL 表示"无上限"会让唯一键对它失效，重复播种就变成重复行，
 * 而"空表才播种"的判断一旦被并发或人工干预破掉，就会安静地出现两套互相矛盾的方案。
 * <p>
 * <b>{@code ac = -1} 是真正的零容忍</b>（CR 致命缺陷），{@code ac = 0} 是"一格都不许有"
 * 的正常接收数。两者不能混：{@code -1} 走的是"发现即拒"的一票否决分支，
 * {@code 0} 走的是 AQL 判定分支。见 {@link ErpAqlService}。
 */
@Entity
@Table(name = "erp_qc_aql_plan", indexes = {
        @Index(name = "uk_qc_aql", columnList = "level,strict_level,lot_from,aql", unique = true),
        @Index(name = "idx_qc_aql_lookup", columnList = "level,strict_level,aql")
})
public class ErpQcAqlPlan {

    /** 特殊检验水平 S-1（鉴别力最低，样本量最小） */
    public static final String LEVEL_S1 = "SPECIAL_S1";
    /** 特殊检验水平 S-2 */
    public static final String LEVEL_S2 = "SPECIAL_S2";
    /** 特殊检验水平 S-3 */
    public static final String LEVEL_S3 = "SPECIAL_S3";
    /** 特殊检验水平 S-4 */
    public static final String LEVEL_S4 = "SPECIAL_S4";
    /** 一般检验水平 I */
    public static final String LEVEL_I = "GENERAL_I";
    /** 一般检验水平 II（默认，绝大多数场景） */
    public static final String LEVEL_II = "GENERAL_II";
    /** 一般检验水平 III */
    public static final String LEVEL_III = "GENERAL_III";

    /** 正常检验 */
    public static final String STRICT_NORMAL = "NORMAL";
    /** 加严检验（表 2-B），本期只留字段不实现自动转移 */
    public static final String STRICT_TIGHTENED = "TIGHTENED";
    /** 放宽检验（表 2-C） */
    public static final String STRICT_REDUCED = "REDUCED";

    /** 批量上限的哨兵值：比任何真实批量都大，避免 NULL 破坏唯一键 */
    public static final int LOT_TO_NO_LIMIT = 999999999;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 检验水平 GENERAL_I / GENERAL_II / GENERAL_III */
    @Column(name = "level", nullable = false, length = 16)
    private String level = LEVEL_II;

    /** 严格度 NORMAL / TIGHTENED / REDUCED */
    @Column(name = "strict_level", nullable = false, length = 16)
    private String strictLevel = STRICT_NORMAL;

    /** 批量下限（含） */
    @Column(name = "lot_from", nullable = false)
    private int lotFrom;

    /** 批量上限（含）。{@link #LOT_TO_NO_LIMIT} = 无上限 */
    @Column(name = "lot_to", nullable = false)
    private int lotTo;

    /** 样本量字码 A~R */
    @Column(name = "code_letter", nullable = false, length = 2)
    private String codeLetter;

    /** 样本量 n */
    @Column(name = "sample_size", nullable = false)
    private int sampleSize;

    /** 接收数 Ac。-1 = 零容忍（CR），0 = 一件都不许有 */
    @Column(name = "ac", nullable = false)
    private int ac;

    /** 拒收数 Re。一次抽样里恒等于 Ac + 1（Ac=-1 时 Re=1） */
    @Column(name = "re", nullable = false)
    private int re;

    /** 这一行的 AQL（百分比数值，1.0 表示 1.0%） */
    @Column(name = "aql", nullable = false, precision = 10, scale = 4)
    private BigDecimal aql;

    /** 备注：说明这一行来自标准哪张表，或现场调整的理由 */
    @Column(name = "remark", length = 255)
    private String remark;

    /**
     * 这一格是否<b>逐格核对过纸本 GB/T 2828.1 表 2-A</b>。
     * <p>
     * {@code false} = 由方案表的格子规律（同一套方案沿"下移一档 + 左移一列"重复出现，
     * 即接收数只取决于 样本量×AQL）推出来的，还<b>没有</b>对着纸本核过。
     * <p>
     * <b>为什么要有这一列</b>：推出来的格子和核对过的格子，在页面上长得一模一样，
     * 但可信度不同。默默用 = 客户以为是国标，其实有二十几格是猜的；
     * 摘掉不用 = 小批量 × 严 AQL 的场景直接报错不可用。
     * 标出来，客户能自己核，核完一键确认——这是"诚实优于看起来完整"。
     * <p>
     * 维护页对 {@code false} 的行显示「推算」标记；点「确认已核对」把它置 true。
     * 凡是公开资料里出现过互相矛盾的格子，一律先标 false，不自作聪明选一个。
     */
    @Column(name = "verified", nullable = false)
    private boolean verified = true;

    /**
     * 谁确认核对过（核对人）。null = 还没人确认。
     * <p>
     * <b>必须有这一列</b>：审计的价值是"能追到人"。只记一个 {@code verified=true} 的话，
     * 半年后没人说得清这一格是标准本来就对、还是有人拍过板——而这一格直接决定一批货判合格还是拒收。
     */
    @Column(name = "verified_by", length = 64)
    private String verifiedBy;

    /** 确认核对的时间（epoch ms） */
    @Column(name = "verified_at")
    private Long verifiedAt;

    @Column(name = "created_at", nullable = false)
    private long createdAt = System.currentTimeMillis();

    @Column(name = "updated_at", nullable = false)
    private long updatedAt = System.currentTimeMillis();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getLevel() { return level; }
    public void setLevel(String level) { this.level = level; }
    public String getStrictLevel() { return strictLevel; }
    public void setStrictLevel(String strictLevel) { this.strictLevel = strictLevel; }
    public int getLotFrom() { return lotFrom; }
    public void setLotFrom(int lotFrom) { this.lotFrom = lotFrom; }
    public int getLotTo() { return lotTo; }
    public void setLotTo(int lotTo) { this.lotTo = lotTo; }
    public String getCodeLetter() { return codeLetter; }
    public void setCodeLetter(String codeLetter) { this.codeLetter = codeLetter; }
    public int getSampleSize() { return sampleSize; }
    public void setSampleSize(int sampleSize) { this.sampleSize = sampleSize; }
    public int getAc() { return ac; }
    public void setAc(int ac) { this.ac = ac; }
    public int getRe() { return re; }
    public void setRe(int re) { this.re = re; }
    public BigDecimal getAql() { return aql; }
    public void setAql(BigDecimal aql) { this.aql = aql; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public boolean isVerified() { return verified; }
    public void setVerified(boolean verified) { this.verified = verified; }
    public String getVerifiedBy() { return verifiedBy; }
    public void setVerifiedBy(String verifiedBy) { this.verifiedBy = verifiedBy; }
    public Long getVerifiedAt() { return verifiedAt; }
    public void setVerifiedAt(Long verifiedAt) { this.verifiedAt = verifiedAt; }
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }

    /** 零容忍方案（CR） */
    public boolean isZeroTolerance() {
        return ac < 0;
    }
}
