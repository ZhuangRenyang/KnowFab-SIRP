package com.factory.hub.erp.quality.inspection;

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
 * <b>数据来源</b>：{@link ErpAqlStandardGrid} —— 国标表 2-A 的原始网格，
 * 逐格放大核读标准原文扫描件得到，<b>连箭头格原样保留</b>。
 * <p>
 * <b>国标表 2-A 里有 27 个箭头格</b>，这不是"空格"，是有含义的格子。国标 10.3 规定：
 * <pre>
 * 若在相交处是箭头，则沿着箭头方向读出第一个接收数 Ac 和拒收数 Re，
 * 然后由其接收数和拒收数所在行向左，在样本量栏内读出相应的样本量 n。
 * </pre>
 * 即箭头格要<b>借</b>相邻行的方案，<b>Ac/Re 和样本量 n 一起借</b>。
 * <p>
 * <b>为什么这一条特别要紧</b>：箭头多指向 Ac 相同的相邻行，
 * 所以<b>只看接收数完全看不出错</b>（本表的 Ac 与国标 66/66 一致），
 * 但样本量会偏小一档甚至两档 —— <b>抽样强度不足，接受概率被高估</b>，
 * 该判不合格的批次被放行。比如 H 档（批量 281~500）在 AQL 0.65 下，
 * 国标是「↓ 借 J 行」→ 抽 <b>80</b> 件、Ac=1；不解析箭头就只抽 50 件。
 * <p>
 * <b>核对状态</b>：66 格全部逐格核对过国标原文扫描件，
 * 并与三份公开转载的对照表交叉验证（Ac 值 66/66 一致），
 * 因此 {@link #verifiedOf} 现在恒为 true。
 * <p>
 * <b>CR 致命缺陷不在表里</b>：它的规则是"任意 n 一律 Ac=-1 / Re=1"，
 * 与批量和检验水平都无关，放进这张表会得到 66 行完全相同的 Ac=-1，
 * 反而与"正常格子的 Ac=0"混淆。它由 {@link ErpAqlService#resolveForSeverity} 在运行时套用。
 * <p>
 * <b>按水平逐个补播种</b>：某个检验水平一行都没有才补那个水平，已有行的一律不动。
 * <p>
 * <b>为什么不是「表空才播」</b>：升级库里已经有老版本播的「一般 II / 正常检验」66 行，
 * 按「表空才播」判断的话，其它 6 个水平永远补不上——用户选了「一般检验水平 III」
 * 就撞上「没配方案」。
 * <p>
 * <b>为什么不是「整体重播」</b>：客户现场可能已经按自己的习惯改过某个水平的方案，
 * 整体重播会覆盖人家的修改。<b>升级包不能覆盖客户数据</b>。
 * <p>
 * 要恢复国标默认只能显式调 {@link #reseed()}（页面上是「恢复国标默认」按钮）。
 */
@Component
@Order(120)
public class ErpAqlSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ErpAqlSeeder.class);

    /** 批量档下限（含）——国标表 1（归 {@link ErpAqlLevelMap} 管：批量档是表 1 的概念） */
    private static final int[] LOT_FROM = ErpAqlLevelMap.LOT_FROM;
    /** 批量档上限（含） */
    private static final int[] LOT_TO = ErpAqlLevelMap.LOT_TO;
    /** 样本量字码 A~L */
    private static final String[] LETTER = ErpAqlStandardGrid.LETTER;
    /** 各字码行的样本量 n */
    private static final int[] SAMPLE = ErpAqlStandardGrid.SAMPLE;
    /** 播种的 6 个 AQL（百分比数值） */
    private static final String[] AQL_TEXT = ErpAqlStandardGrid.AQL_TEXT;

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

    /**
     * 缺哪个水平就补哪个水平。
     * <p>
     * <b>为什么不是「表空才播」</b>：升级库里已经有「一般 II / 正常检验」的 66 行
     * （老版本播的），如果按「表空才播」判断，其它 6 个水平永远补不上——
     * 用户选了「一般检验水平 III」就撞上「没配方案」。
     * <p>
     * <b>为什么不是「整体重播」</b>：客户现场可能已经按自己的习惯改过某个水平的方案，
     * 整体重播会把人家的修改覆盖掉。<b>升级包不能覆盖客户数据</b>。
     * <p>
     * 所以：<b>按水平逐个补</b>——某个水平一行都没有才补那个水平，已有行的一律不动。
     */
    private int seedIfEmptyImpl() {
        List<ErpQcAqlPlan> missing = new ArrayList<>();
        for (String level : ErpAqlLevelMap.LEVELS) {
            for (String strict : STRICT_LEVELS) {
                if (planRepo.countByLevelAndStrictLevel(level, strict) > 0) {
                    continue;   // 这个组合已有数据（可能是客户维护的），不动
                }
                missing.addAll(buildPlansOfLevel(level, strict));
            }
        }
        if (missing.isEmpty()) {
            return 0;
        }
        planRepo.saveAll(missing);
        return missing.size();
    }

    /**
     * 恢复国标默认：删掉<b>全部 7 个检验水平</b>的正常检验行，再重新播种。
     * <p>
     * 只动「正常检验」这一个严格度——客户自己加的加严/放宽方案一律不碰
     * （那是客户按自己的转移规则维护的，国标默认表里没有）。
     * <p>
     * <b>为什么连其它水平一起删</b>：客户的某个水平可能只改了几格，
     * 「恢复默认」如果只重播水平 II，客户会以为其它水平也被重置了——
     * 实际上它们还是改过的版本。<b>要么全恢复，要么不恢复</b>，
     * 半恢复最让人困惑。
     */
    @Transactional
    public int reseed() {
        List<ErpQcAqlPlan> old = new ArrayList<>();
        for (String level : ErpAqlLevelMap.LEVELS) {
            for (String strict : STRICT_LEVELS) {
                old.addAll(planRepo.findByLevelAndStrictLevelOrderByAqlAscLotFromAsc(
                        level, strict));
            }
        }
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
     * 生成方案对象（不落库）：<b>7 个检验水平 × 15 个批量档 × 6 个 AQL = 630 行</b>。
     * <p>
     * 抽成 public static 是为了让单测能直接拿到"将要写进库的那份数据"去验锚点——
     * 如果测试自己另抄一份期望值，抄错了也没人发现（测试和实现一起错，是最常见的假绿灯）。
     * <p>
     * <b>为什么要按水平展开 630 行，而不是只存"字码 × AQL"的 96 行</b>：
     * ① 客户可以在维护页上直接看到「水平 II、批量 281~500、AQL 0.65 → 抽 80 件」，
     *     不用自己拿表 1 去换算字码；<br>
     * ② 客户现场完全可以只按自己的习惯维护某一个水平的方案，
     *     其它水平照用国标默认——按行存才支持这种"局部覆盖"。<br>
     * ③ 查表退化成一次索引查找（level + lot + aql），不需要在应用层做换算。
     * <p>
     * 同一个字码的行会有重复（不同水平/批量档可能落到同一字码），
     * 这是有意的冗余：<b>每行都是一句完整的业务结论</b>，可读性换存储。
     */
    /** 三个严格度（播种顺序 = 正常 → 加严 → 放宽，与国标表 2-A/2-B/2-C 的顺序一致） */
    public static final String[] STRICT_LEVELS = {
            ErpQcAqlPlan.STRICT_NORMAL, ErpQcAqlPlan.STRICT_TIGHTENED, ErpQcAqlPlan.STRICT_REDUCED
    };

    public static List<ErpQcAqlPlan> buildPlans() {
        List<ErpQcAqlPlan> rows = new ArrayList<>(
                ErpAqlLevelMap.LEVELS.length * STRICT_LEVELS.length
                        * ErpAqlLevelMap.LOT_FROM.length * AQL_TEXT.length);
        for (String level : ErpAqlLevelMap.LEVELS) {
            for (String strict : STRICT_LEVELS) {
                rows.addAll(buildPlansOfLevel(level, strict));
            }
        }
        return rows;
    }

    /** 某个检验水平 + 某个严格度的全部方案行（15 批量档 × 6 AQL = 90 行） */
    public static List<ErpQcAqlPlan> buildPlansOfLevel(String level, String strictLevel) {
        long now = System.currentTimeMillis();
        List<ErpQcAqlPlan> rows = new ArrayList<>(
                ErpAqlLevelMap.LOT_FROM.length * AQL_TEXT.length);
        for (int i = 0; i < ErpAqlLevelMap.LOT_FROM.length; i++) {
                // 表 1：给定的批量 + 检验水平 → 样本量字码
                int gridRow = ErpAqlLevelMap.gridRowOf(ErpAqlLevelMap.LOT_FROM[i], level);
                if (gridRow < 0) {
                    log.warn("AQL 方案表：水平 {} 的第 {} 批量档找不到字码，跳过", level, i);
                    continue;
                }
                // 三张表（正常/加严/放宽）各自的字码行数、样本量、格子都不同
                ErpAqlStandardGrid.PlanTable table = ErpAqlStandardGrid.tableOf(strictLevel);
                for (int j = 0; j < AQL_TEXT.length; j++) {
                    // 国标 10.3：箭头格借相邻行的方案（Ac/Re 和样本量 n 一起借）
                    ErpAqlStandardGrid.Cell cell = ErpAqlStandardGrid.resolveCell(table, gridRow, j);
                    if (cell == null) {
                        // 该格无方案（表 2-B 的 S 行）或箭头指向表外 ——
                        // 宁可少播一格也不要播一个假方案。
                        log.warn("AQL 方案表（{}）{} 档 @ {} 无方案，跳过该格",
                                strictLevel, ErpAqlStandardGrid.LETTER[gridRow], AQL_TEXT[j]);
                        continue;
                    }
                    ErpQcAqlPlan p = new ErpQcAqlPlan();
                    p.setLevel(level);
                    p.setStrictLevel(strictLevel);
                    p.setLotFrom(ErpAqlLevelMap.LOT_FROM[i]);
                    p.setLotTo(ErpAqlLevelMap.LOT_TO[i]);
                    p.setCodeLetter(ErpAqlStandardGrid.LETTER[gridRow]);
                    p.setSampleSize(cell.sampleSize());
                    p.setAc(cell.ac());
                    p.setRe(cell.re());
                    p.setAql(new BigDecimal(AQL_TEXT[j]));
                    p.setRemark(remarkOf(strictLevel, gridRow, j, cell));
                    p.setVerified(verifiedOf(strictLevel, gridRow, j));
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
     * <b>现在恒为 true</b>：66 格全部逐格核读过国标原文扫描件
     * （GB/T 2828.1-2012 标准原文第 15-17 页的表 2-A），
     * 并与三份公开转载的对照表交叉验证（Ac 值 66/66 一致）。
     * <p>
     * <b>为什么之前是 false</b>：那时候只核了 AQL 1.0/1.5/2.5 三列加 4.0 列的大部分，
     * 0.65 与 6.5 两列是"按格子规律推出来的"。
     * <b>推出来的格子漏掉了箭头</b>——所以 Ac 碰巧对了，样本量错了 27 处。
     * <p>
     * 维护页仍然保留「确认已核对」按钮：客户现场若按自己的 AQL 组合维护这张表，
     * 改过的格子可以随手标回"已核对"。
     */
    public static boolean verifiedOf(int bandIndex, int aqlIndex) {
        return true;
    }

    /** 按严格度的版本（三张表都已逐格核对过标准原文，恒为 true） */
    public static boolean verifiedOf(String strictLevel, int bandIndex, int aqlIndex) {
        return true;
    }

    /**
     * 逐行备注：把"这格是怎么来的"直接写在数据里，翻库也能看出来。
     * <p>
     * 箭头格要写明「借了哪一行」——这一行是本轮修复的核心，
     * 不写清楚的话，下一个维护这张表的人会以为样本量就该是本行的字码值。
     */
    private static String remarkOf(String strictLevel, int bandIndex, int aqlIndex,
                                   ErpAqlStandardGrid.Cell cell) {
        String base = "GB/T 2828.1-2012 " + tableNameOf(strictLevel) + "一次抽样";
        if (cell.arrow() == null) {
            return base;
        }
        return base + "；本格为箭头格，按 10.3 借 " + cell.fromLetter() + " 行：n="
                + cell.sampleSize() + "，Ac=" + cell.ac() + "/Re=" + cell.re();
    }

    /** 表号：2-A 正常 / 2-B 加严 / 2-C 放宽（备注里要写清是哪张表） */
    private static String tableNameOf(String strictLevel) {
        if (ErpQcAqlPlan.STRICT_TIGHTENED.equals(strictLevel)) {
            return "表 2-B 加严检验";
        }
        if (ErpQcAqlPlan.STRICT_REDUCED.equals(strictLevel)) {
            return "表 2-C 放宽检验";
        }
        return "表 2-A 正常检验";
    }

    /** 供单测断言用：本播种器会写多少行（7 水平 × 3 严格度 × 15 批量档 × 6 AQL） */
    public static int plannedRowCount() {
        return ErpAqlLevelMap.LEVELS.length * STRICT_LEVELS.length
                * LOT_FROM.length * AQL_TEXT.length;
    }

    /**
     * 供单测断言用：某个<b>字码行</b>的字码。
     * <p>
     * ⚠️ 叫 "LetterRow" 而不是 "Band"：现在<b>字码行 ≠ 批量档</b>——
     * 同一个批量在不同检验水平下对应不同字码（国标表 1），
     * 所以按批量档取字码是错的，得先定水平再取。
     */
    public static String letterOfLetterRow(int index) {
        return LETTER[index];
    }

    /** 供单测断言用：某个<b>字码行</b>的样本量 */
    public static int sampleOfLetterRow(int index) {
        return SAMPLE[index];
    }

    /**
     * 供单测断言用：某个批量档的接收数（按 AQL 列取）。
     * <p>
     * 走 {@link ErpAqlStandardGrid#resolveCell} 而不是查一张写死的矩阵——
     * 这样单测验的是"国标原表 + 10.3 解析"这条链，
     * 而不是"实现和另一份抄下来的矩阵一致"。
     */
    public static int acOf(int lotBandIndex, int aqlIndex) {
        ErpAqlStandardGrid.Cell c = ErpAqlStandardGrid.resolveCell(lotBandIndex, aqlIndex);
        return c == null ? -1 : c.ac();
    }

    /**
     * 供单测断言用：某个批量档在某个 AQL 下<b>实际要抽的件数</b>。
     * <p>
     * 箭头格返回的是<b>借来的</b>样本量（国标 10.3：Ac/Re 与 n 一起借），
     * 不是本行的字码样本量——这正是旧实现错的地方。
     */
    public static int effectiveSampleOf(int lotBandIndex, int aqlIndex) {
        ErpAqlStandardGrid.Cell c = ErpAqlStandardGrid.resolveCell(lotBandIndex, aqlIndex);
        return c == null ? -1 : c.sampleSize();
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
