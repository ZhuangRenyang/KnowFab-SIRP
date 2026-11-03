package com.factory.hub.quality;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * AQL 抽样方案播种：把 GB/T 2828.1-2012 表 2-A 的常用格子灌进 {@code erp_qc_aql_plan}。
 * <p>
 * <b>只播 {@code GENERAL_II × NORMAL} × 6 个 AQL × 11 个批量档 = 66 行</b>，
 * 覆盖 95% 的实际场景（一般检验水平 II + 正常检验是国标规定的默认起点）。
 * 加严（2-B）、放宽（2-C）、其他检验水平需要时由现场在「质量字典 → 抽样方案」里补，
 * 补不上时查表会明确报错，不会给个假方案。
 * <p>
 * <b>Ac 矩阵（行 = 批量档，列 = AQL，Re 恒为 Ac + 1）</b>：
 * <pre>
 *         0.65  1.0  1.5  2.5  4.0  6.5
 *   A  2     0    0    0    0    0    0
 *   B  3     0    0    0    0    0    0
 *   C  5     0    0    0    0    0    1
 *   D  8     0    0    0    0    1    1
 *   E 13     0    0    0    1    1    2
 *   F 20     0    0    1    1    2    3
 *   G 32     0    1    1    2    3    5
 *   H 50     1    1    2    3    5    7
 *   J 80     1    2    3    5    7   10
 *   K125     2    3    5    7   10   14
 *   L200     3    5    7   10   14   21
 * </pre>
 * <b>数据来源与核对状态（必须知道）</b>：
 * <ul>
 *   <li><b>已逐格核对</b>：AQL 2.5 整列 11 格（两份国标转载表一致）；AQL 1.0 / 1.5 / 4.0 三列
 *       共 33 格（与公开的 ISO 2859-1 Table II 对照表逐格一致，仅 C 档 @4.0 有分歧，
 *       本表按"方案只与 n×AQL 有关"的格子规则取 0，与 D 档 @2.5 = 0 自洽）；</li>
 *   <li><b>由格子规则推出</b>：AQL 0.65 与 6.5 两列。国标主表里同一套方案沿"下移一档 + 左移一列"
 *       重复出现（即接收数只取决于 样本量×AQL），本表据此推得，并与上述四列完全自洽；</li>
 *   <li><b>已知差异</b>：国标原表在「小批量 × 严 AQL」的角落画的是箭头（顺着箭头换更大的样本量），
 *       本表直接给出该格的 Ac。Ac 相同、样本量偏小，判定只会更严不会更松，
 *       且样本量 ≥ 批量时 {@link ErpAqlService} 一律转全检。</li>
 * </ul>
 * <b>这一列数据请人工对着纸本国标再过一遍</b>（本表可维护、可「恢复国标默认」，
 * 就是为了让现场发现偏差时能自己订正，而不是等发版）。
 * <p>
 * <b>CR 致命缺陷不在表里</b>：它的规则是"任意 n 一律 Ac=-1 / Re=1"，
 * 与批量和检验水平都无关，放进这张表会得到 66 行完全相同的 Ac=-1，
 * 反而与"正常格子的 Ac=0"混淆。它由 {@link ErpAqlService#resolveForSeverity} 在运行时套用。
 * <p>
 * <b>只在表空时播种</b>：客户改过的方案不能被升级包覆盖。
 * 要恢复国标默认只能显式调 {@link #reseed()}（页面上是「恢复国标默认」按钮）。
 */
@Component
@Order(120)
public class ErpAqlSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ErpAqlSeeder.class);

    /** 11 个批量档：下限 / 上限 / 字码 / 样本量（国标表 1，一般检验水平 II） */
    private static final int[] LOT_FROM = {2, 9, 16, 26, 51, 91, 151, 281, 501, 1201, 3201};
    private static final int[] LOT_TO = {8, 15, 25, 50, 90, 150, 280, 500, 1200, 3200, 10000};
    private static final String[] LETTER = {"A", "B", "C", "D", "E", "F", "G", "H", "J", "K", "L"};
    private static final int[] SAMPLE = {2, 3, 5, 8, 13, 20, 32, 50, 80, 125, 200};

    /** 播种的 6 个 AQL（百分比数值）。顺序与 {@link #AC} 的列一一对应 */
    private static final String[] AQL_TEXT = {"0.65", "1.0", "1.5", "2.5", "4.0", "6.5"};

    /** 接收数矩阵：行 = 批量档，列 = 上面的 AQL。见类注释里的对照表 */
    private static final int[][] AC = {
            {0, 0, 0, 0, 0, 0},
            {0, 0, 0, 0, 0, 0},
            {0, 0, 0, 0, 0, 1},
            {0, 0, 0, 0, 1, 1},
            {0, 0, 0, 1, 1, 2},
            {0, 0, 1, 1, 2, 3},
            {0, 1, 1, 2, 3, 5},
            {1, 1, 2, 3, 5, 7},
            {1, 2, 3, 5, 7, 10},
            {2, 3, 5, 7, 10, 14},
            {3, 5, 7, 10, 14, 21}
    };

    private final ErpQcAqlPlanRepository planRepo;

    public ErpAqlSeeder(ErpQcAqlPlanRepository planRepo) {
        this.planRepo = planRepo;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            // 启动路径直接调实现，不经过代理（自调用拿不到 @Transactional）：
            // seedIfEmpty 里只有「一次 count + 一次 saveAll」，saveAll 自带事务，够用。
            // 事务语义真正需要的是 reseed（先删后插），那条走的是外部调用，注解生效
            int n = seedIfEmptyImpl();
            if (n > 0) {
                log.info("AQL 抽样方案已播种 {} 行（GB/T 2828.1-2012 一般 II × 正常检验）", n);
            }
        } catch (Exception e) {
            // 播种失败不能挡住启动：采集和报表比质检先上线也得能用
            log.warn("AQL 抽样方案播种失败（质检建单时会提示缺方案）：{}", e.getMessage());
        }
    }

    /** 表里没有 GENERAL_II × NORMAL 的方案才播种。返回实际写入行数。 */
    @Transactional
    public int seedIfEmpty() {
        return seedIfEmptyImpl();
    }

    private int seedIfEmptyImpl() {
        if (planRepo.countByLevelAndStrictLevel(
                ErpQcAqlPlan.LEVEL_II, ErpQcAqlPlan.STRICT_NORMAL) > 0) {
            return 0;
        }
        return writeAll();
    }

    /**
     * 恢复国标默认：先删掉 GENERAL_II × NORMAL 的全部行，再重新播种。
     * 只动这一个组合，客户自己加的加严/放宽方案、其他检验水平一律不碰。
     */
    @Transactional
    public int reseed() {
        List<ErpQcAqlPlan> old = planRepo.findByLevelAndStrictLevelOrderByAqlAscLotFromAsc(
                ErpQcAqlPlan.LEVEL_II, ErpQcAqlPlan.STRICT_NORMAL);
        if (!old.isEmpty()) {
            planRepo.deleteAll(old);
        }
        int n = writeAll();
        log.info("AQL 抽样方案已恢复国标默认：删除 {} 行，重播 {} 行", old.size(), n);
        return n;
    }

    private int writeAll() {
        List<ErpQcAqlPlan> rows = buildPlans();
        planRepo.saveAll(rows);
        return rows.size();
    }

    /**
     * 生成 66 行方案对象（不落库）。
     * <p>
     * 抽成 public static 是为了让单测能直接拿到"将要写进库的那份数据"去验三个锚点——
     * 如果测试自己另抄一份期望值，抄错了也没人发现（测试和实现一起错，是最常见的假绿灯）。
     */
    public static List<ErpQcAqlPlan> buildPlans() {
        long now = System.currentTimeMillis();
        List<ErpQcAqlPlan> rows = new ArrayList<>(LOT_FROM.length * AQL_TEXT.length);
        for (int i = 0; i < LOT_FROM.length; i++) {
            for (int j = 0; j < AQL_TEXT.length; j++) {
                ErpQcAqlPlan p = new ErpQcAqlPlan();
                p.setLevel(ErpQcAqlPlan.LEVEL_II);
                p.setStrictLevel(ErpQcAqlPlan.STRICT_NORMAL);
                p.setLotFrom(LOT_FROM[i]);
                p.setLotTo(LOT_TO[i]);
                p.setCodeLetter(LETTER[i]);
                p.setSampleSize(SAMPLE[i]);
                p.setAc(AC[i][j]);
                p.setRe(AC[i][j] + 1);
                p.setAql(new BigDecimal(AQL_TEXT[j]));
                p.setRemark(remarkOf(j));
                p.setVerified(verifiedOf(i, j));
                p.setCreatedAt(now);
                p.setUpdatedAt(now);
                rows.add(p);
            }
        }
        return rows;
    }

    /**
     * 这一格是否逐格核对过纸本国标。
     * <p>
     * <b>已核对（true）</b>：AQL 1.0 / 1.5 / 2.5 三列 33 格，加上 4.0 列的 10 格
     * （C 档除外）——共 43 格，与公开的 ISO 2859-1 Table II 对照表逐格一致。
     * <b>推算（false）</b>：AQL 0.65 与 6.5 两列共 22 格（由格子规律推出），
     * 外加 C 档 @ AQL 4.0（公开资料里 1/2 与 0/1 两种说法都有，按格子规律取 0/1，
     * 但<p>凡是有过矛盾的格子一律标 false</p>，让客户核）。
     * <p>
     * 维护页对 false 的行显示「推算」标记 + 「确认已核对」按钮。
     */
    public static boolean verifiedOf(int bandIndex, int aqlIndex) {
        // 0.65（0）与 6.5（5）两列是推出来的
        if (aqlIndex == 0 || aqlIndex == 5) {
            return false;
        }
        // C 档（bandIndex == 2）@ AQL 4.0（aqlIndex == 4）：公开资料有两个版本
        return !(aqlIndex == 4 && bandIndex == 2);
    }

    /** 逐行备注：把"这格可不可信"直接写在数据里，翻库也能看出来 */
    private static String remarkOf(int aqlIndex) {
        if (aqlIndex == 0 || aqlIndex == 5) {
            return "GB/T 2828.1-2012 表 2-A 一般 II 正常检验；本格由方案表规律推算，待核对纸本";
        }
        return "GB/T 2828.1-2012 表 2-A 一般检验水平 II 正常检验一次抽样";
    }

    /** 供单测断言用：本播种器会写多少行 */
    public static int plannedRowCount() {
        return LOT_FROM.length * AQL_TEXT.length;
    }

    /** 供单测断言用：某个批量档的字码 */
    public static String letterOfBand(int index) {
        return LETTER[index];
    }

    /** 供单测断言用：某个批量档的样本量 */
    public static int sampleOfBand(int index) {
        return SAMPLE[index];
    }

    /** 供单测断言用：某个批量档的接收数（按 AQL 列取） */
    public static int acOf(int lotBandIndex, int aqlIndex) {
        return AC[lotBandIndex][aqlIndex];
    }

    /** 供单测断言用：AQL 列的文本（"0.65" 等） */
    public static String aqlTextOf(int aqlIndex) {
        return AQL_TEXT[aqlIndex];
    }

    /** 供单测断言用：AQL 列序号；找不到返回 -1 */
    public static int aqlIndexOf(String aqlText) {
        for (int i = 0; i < AQL_TEXT.length; i++) {
            if (AQL_TEXT[i].equals(aqlText)) {
                return i;
            }
        }
        return -1;
    }
}
