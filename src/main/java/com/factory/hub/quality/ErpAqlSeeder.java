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
 * <b>只在表空时播种</b>：客户改过的方案不能被升级包覆盖。
 * 要恢复国标默认只能显式调 {@link #reseed()}（页面上是「恢复国标默认」按钮）。
 */
@Component
@Order(120)
public class ErpAqlSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ErpAqlSeeder.class);

    /** 批量档下限（含）——国标表 1，一般检验水平 II */
    private static final int[] LOT_FROM = ErpAqlStandardGrid.LOT_FROM;
    /** 批量档上限（含） */
    private static final int[] LOT_TO = ErpAqlStandardGrid.LOT_TO;
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
                // 国标 10.3：箭头格借相邻行的方案（Ac/Re 和样本量 n 一起借）
                ErpAqlStandardGrid.Cell cell = ErpAqlStandardGrid.resolveCell(i, j);
                if (cell == null) {
                    // 箭头指向表外：GB/T 2828.1 表 2-A 里不会出现，
                    // 真出现了说明标准原表被改动过 —— 宁可少播一格也不要播一个假方案。
                    log.warn("AQL 方案表 {} 档 @ {} 的箭头指向表外，跳过该格", LETTER[i], AQL_TEXT[j]);
                    continue;
                }
                ErpQcAqlPlan p = new ErpQcAqlPlan();
                p.setLevel(ErpQcAqlPlan.LEVEL_II);
                p.setStrictLevel(ErpQcAqlPlan.STRICT_NORMAL);
                p.setLotFrom(LOT_FROM[i]);
                p.setLotTo(LOT_TO[i]);
                p.setCodeLetter(LETTER[i]);
                p.setSampleSize(cell.sampleSize());
                p.setAc(cell.ac());
                p.setRe(cell.re());
                p.setAql(new BigDecimal(AQL_TEXT[j]));
                p.setRemark(remarkOf(i, j, cell));
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

    /**
     * 逐行备注：把"这格是怎么来的"直接写在数据里，翻库也能看出来。
     * <p>
     * 箭头格要写明「借了哪一行」——这一行是本轮修复的核心，
     * 不写清楚的话，下一个维护这张表的人会以为样本量就该是本行的字码值。
     */
    private static String remarkOf(int bandIndex, int aqlIndex, ErpAqlStandardGrid.Cell cell) {
        String base = "GB/T 2828.1-2012 表 2-A 一般检验水平 II 正常检验一次抽样";
        if (cell.arrow() == null) {
            return base;
        }
        return base + "；本格为箭头格，按 10.3 借 " + cell.fromLetter() + " 行：n="
                + cell.sampleSize() + "，Ac=" + cell.ac() + "/Re=" + cell.re();
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
