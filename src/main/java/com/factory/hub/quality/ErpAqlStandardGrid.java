package com.factory.hub.quality;

/**
 * GB/T 2828.1-2012 表 2-A 的原始网格（一般检验水平 II × 正常检验 × 一次抽样）。
 * <p>
 * <b>这个类只放"国标原样"，不做任何加工。</b>
 * 加工（沿箭头解析成最终方案）在 {@link ErpAqlSeeder} 里做，
 * 这样"标准长什么样"和"我们怎么用它"是两件事，改一边不会悄悄改掉另一边。
 * <p>
 * <b>为什么必须把箭头原样记下来</b>：国标表 2-A 里有 <b>27 个箭头格</b>（↑ / ↓），
 * 它们不是"还没填的空格"，而是<b>有含义的格子</b>。国标 10.3 规定：
 * <pre>
 * 在抽样方案表中，由该字码所在行向右，在样本量栏内读出样本量 n，
 * 再以样本量字码所在行和规定的接收质量限所在列相交处，读出接收数 Ac 和拒收数 Re。
 * 若在相交处是箭头，则沿着箭头方向读出第一个接收数 Ac 和拒收数 Re，
 * 然后由其接收数和拒收数所在行向左，在样本量栏内读出相应的样本量 n。
 * </pre>
 * 也就是说，箭头格要<b>借</b>相邻行的方案：<b>Ac/Re 和样本量 n 一起借</b>。
 * <p>
 * 漏掉这一步的后果：接收数往往碰巧一样（箭头多指向 Ac 相同的相邻行），
 * 所以<b>只看 Ac 完全看不出错</b>；但样本量会偏小一档甚至两档——
 * <b>抽样强度不足，接受概率被高估</b>，该判不合格的批次被放行。
 * <p>
 * <b>数据来源</b>：GB/T 2828.1-2012 标准原文扫描件（88 页），
 * 表 2-A 位于第 21-23 页（第 15-17 页是印刷页码），逐格放大核读并与行位置对齐校对。
 * 交叉验证：公开转载的三份对照表（AQL 2.5 全表、AQL 0.65/1.0/4.0/6.5 部分行）
 * 与本表的 <b>Ac 值 66/66 完全一致</b>。
 * <p>
 * M~R 六行（本轮补齐，用于一般检验水平 III 与特殊水平 S-4 的大批量）
 * 用同一套"红线对齐"方法读出，并做了<b>对角线自洽性验证</b>：
 * 同一 Ac 值应沿对角线重复（如 M@0.65 = 5/6 与 L@1.0 = 5/6 是同一方案），
 * 实测 16 行 × 6 列全部满足——这是读数正确的强证据。
 * <p>
 * <b>本表是"字码 × AQL"的方案表，与检验水平无关</b>：
 * 各检验水平共用本表，差别只在「批量 → 字码」的映射（见 {@link ErpAqlLevelMap}）。
 *
 * @see ErpAqlSeeder
 */
public final class ErpAqlStandardGrid {

    private ErpAqlStandardGrid() {
    }

    /**
     * 样本量字码 A~R（<b>跳过 I 与 O</b>，与国标一致：避免与 1、0 混淆）。
     * <p>
     * 字码表比"批量档表"长：同一批量在不同检验水平下对应不同字码
     * （见 {@link ErpAqlLevelMap}），所以字码是 16 行、批量档是 15 档。
     */
    public static final String[] LETTER = {
            "A", "B", "C", "D", "E", "F", "G", "H", "J", "K", "L", "M", "N", "P", "Q", "R"
    };

    /** 各字码行的样本量 n（国标表 1） */
    public static final int[] SAMPLE = {
            2, 3, 5, 8, 13, 20, 32, 50, 80, 125, 200, 315, 500, 800, 1250, 2000
    };

    /** 6 个 AQL 列（百分比数值） */
    public static final String[] AQL_TEXT = {"0.65", "1.0", "1.5", "2.5", "4.0", "6.5"};

    /**
     * 国标原始格子。行 = 批量档（字码），列 = AQL。
     * <p>
     * 数值格写成 {@code "Ac Re"}（如 {@code "0 1"}、{@code "10 11"}）；
     * 箭头格写成 {@code "↓"}（向下借）或 {@code "↑"}（向上借）。
     * <p>
     * <b>一个箭头格可能横跨多行</b>（如 AQL 0.65 列的 A~E 五行共用一根 ↓ 箭头），
     * 本表把它拆成每行一个格，值都写成同一个箭头——语义等价，
     * 因为"沿箭头方向找第一个数字格"的查找是逐行进行的。
     */
    public static final String[][] GRID = {
            //  0.65    1.0     1.5     2.5     4.0     6.5
            {"↓", "↓", "↓", "↓", "↓", "0 1"},        // A  2~8
            {"↓", "↓", "↓", "↓", "0 1", "↑"},        // B  9~15
            {"↓", "↓", "↓", "0 1", "↑", "↓"},        // C  16~25
            {"↓", "↓", "0 1", "↑", "↓", "1 2"},       // D  26~50
            {"↓", "0 1", "↑", "↓", "1 2", "2 3"},      // E  51~90
            {"0 1", "↑", "↓", "1 2", "2 3", "3 4"},    // F  91~150
            {"↑", "↓", "1 2", "2 3", "3 4", "5 6"},    // G  151~280
            {"↓", "1 2", "2 3", "3 4", "5 6", "7 8"},   // H  281~500
            {"1 2", "2 3", "3 4", "5 6", "7 8", "10 11"},  // J  501~1200
            {"2 3", "3 4", "5 6", "7 8", "10 11", "14 15"}, // K 1201~3200
            {"3 4", "5 6", "7 8", "10 11", "14 15", "21 22"}, // L 3201~10000
            // —— M~R（一般检验水平 III 与特殊水平 S-4 会用到 n>200 的字码）——
            {"5 6", "7 8", "10 11", "14 15", "21 22", "↑"},       // M 315
            {"7 8", "10 11", "14 15", "21 22", "↑", "↑"},        // N 500
            {"10 11", "14 15", "21 22", "↑", "↑", "↑"},          // P 800
            {"14 15", "21 22", "↑", "↑", "↑", "↑"},              // Q 1250
            {"21 22", "↑", "↑", "↑", "↑", "↑"},                  // R 2000
    };

    // ==================== 加严检验（表 2-B） ====================

    /**
     * 表 2-B 的字码：<b>比表 2-A 多一行 S（n=3150）</b>。
     * <p>
     * 为什么加严表要多一行：加严检验要「同样的接收数用更大的样本量」，
     * 在严 AQL + 大批量的角落，R 行（n=2000）还不够，于是延伸出 S 行。
     * <p>
     * <b>注意字码 S 不在国标表 1 里</b>（表 1 最大给到 R）——
     * 它是表 2-B 内部的延伸行，只有在箭头指向它时才会被借到。
     */
    public static final String[] LETTER_TIGHTENED = {
            "A", "B", "C", "D", "E", "F", "G", "H", "J", "K", "L", "M", "N", "P", "Q", "R", "S"
    };

    /** 表 2-B 各字码行的样本量 */
    public static final int[] SAMPLE_TIGHTENED = {
            2, 3, 5, 8, 13, 20, 32, 50, 80, 125, 200, 315, 500, 800, 1250, 2000, 3150
    };

    /**
     * 表 2-B 的原始格子（GB/T 2828.1-2012 第 22 页）。
     * <p>
     * 与表 2-A 的关系：<b>不是简单的矩阵平移</b>。读数看到的规律是
     * 「同样的 Ac 值出现在更大一号的字码行」（例：AQL 1.0 的 0/1 格，
     * 表 2-A 在 E 行 n=13，表 2-B 在 F 行 n=20），
     * 也就是<b>同样的接收数要抽更多件</b>——这就是加严。
     * <p>
     * 但个别格子并不遵守这个规律（如 J@6.5：表 2-A 是 10/11，表 2-B 是 8/9，
     * Ac 真的变小了），所以<b>必须逐格照抄，不能靠推导生成</b>。
     */
    public static final String[][] GRID_TIGHTENED = {
            //  0.65    1.0     1.5     2.5     4.0     6.5
            {"↓", "↓", "↓", "↓", "↓", "↓"},        // A  2
            {"↓", "↓", "↓", "↓", "↓", "0 1"},      // B  3
            {"↓", "↓", "↓", "↓", "0 1", "↓"},      // C  5
            {"↓", "↓", "↓", "0 1", "↓", "↓"},      // D  8
            {"↓", "↓", "0 1", "↓", "↓", "1 2"},     // E  13
            {"↓", "0 1", "↓", "↓", "1 2", "2 3"},    // F  20
            {"0 1", "↓", "↓", "1 2", "2 3", "3 4"},   // G  32
            {"↓", "↓", "1 2", "2 3", "3 4", "5 6"},   // H  50
            {"↓", "1 2", "2 3", "3 4", "5 6", "8 9"},  // J  80
            {"1 2", "2 3", "3 4", "5 6", "8 9", "12 13"},   // K  125
            {"2 3", "3 4", "5 6", "8 9", "12 13", "18 19"},  // L  200
            {"3 4", "5 6", "8 9", "12 13", "18 19", "↑"},     // M  315
            {"5 6", "8 9", "12 13", "18 19", "↑", "↑"},       // N  500
            {"8 9", "12 13", "18 19", "↑", "↑", "↑"},         // P  800
            {"12 13", "18 19", "↑", "↑", "↑", "↑"},           // Q  1250
            {"18 19", "↑", "↑", "↑", "↑", "↑"},               // R  2000
            {null, "↑", "↑", "↑", "↑", "↑"},                  // S  3150（本列无方案）
    };

    // ==================== 放宽检验（表 2-C） ====================

    /** 表 2-C 的字码：与表 2-A 相同（A~R 16 行） */
    public static final String[] LETTER_REDUCED = LETTER;

    /**
     * 表 2-C 各字码行的样本量——<b>与表 2-A 不同</b>。
     * <p>
     * 放宽检验的「放宽」就体现在这里：<b>样本量整体降两档</b>
     * （表 2-C 的 A/B/C 都是 2 件，而表 2-A 是 2/3/5；表 2-C 的 R 是 800，表 2-A 是 2000）。
     * <p>
     * ⚠️ 所以<b>不能拿表 2-A 的 SAMPLE 去查表 2-C 的格子</b>——
     * 那样「放宽」就退化成「正常」，白放宽了。
     */
    public static final int[] SAMPLE_REDUCED = {
            2, 2, 2, 3, 5, 8, 13, 20, 32, 50, 80, 125, 200, 315, 500, 800
    };

    /**
     * 表 2-C 的原始格子（GB/T 2828.1-2012 第 23 页）。
     * <p>
     * 与表 2-A / 2-B 都不同：Ac 值更小、箭头分布也不同。
     * 例：M 行 @ AQL 1.5 —— 表 2-A 是 7/8、表 2-B 是 8/9、表 2-C 是 6/7。
     * <b>三张表必须各自独立照抄，任何"按规律推导"都会出错。</b>
     */
    public static final String[][] GRID_REDUCED = {
            //  0.65    1.0     1.5     2.5     4.0     6.5
            {"↓", "↓", "↓", "↓", "↓", "0 1"},        // A  2
            {"↓", "↓", "↓", "↓", "0 1", "↑"},        // B  2
            {"↓", "↓", "↓", "0 1", "↑", "↓"},        // C  2
            {"↓", "↓", "0 1", "↑", "↓", "↓"},        // D  3
            {"↓", "0 1", "↑", "↓", "↓", "1 2"},       // E  5
            {"0 1", "↑", "↓", "↓", "1 2", "2 3"},      // F  8
            {"↑", "↓", "↓", "1 2", "2 3", "3 4"},      // G  13
            {"↓", "↓", "1 2", "2 3", "3 4", "5 6"},    // H  20
            {"↓", "1 2", "2 3", "3 4", "5 6", "6 7"},   // J  32
            {"1 2", "2 3", "3 4", "5 6", "6 7", "8 9"},  // K  50
            {"2 3", "3 4", "5 6", "6 7", "8 9", "10 11"},  // L  80
            {"3 4", "5 6", "6 7", "8 9", "10 11", "↑"},     // M  125
            {"5 6", "6 7", "8 9", "10 11", "↑", "↑"},       // N  200
            {"6 7", "8 9", "10 11", "↑", "↑", "↑"},         // P  315
            {"8 9", "10 11", "↑", "↑", "↑", "↑"},           // Q  500
            {"10 11", "↑", "↑", "↑", "↑", "↑"},             // R  800
    };

    /** 箭头代号：向下借 */
    public static final String ARROW_DOWN = "↓";

    /** 箭头代号：向上借 */
    public static final String ARROW_UP = "↑";

    /**
     * 沿箭头解析后得到的一格最终方案。
     *
     * @param ac         接收数
     * @param re         拒收数（一次抽样恒为 Ac + 1）
     * @param sampleSize 实际要抽的件数（箭头格 = 借来的那一行的 n）
     * @param fromLetter 方案实际来自哪个字码行（箭头格 = 被借的那一行；本格直接读数 = 本身）
     * @param arrow      本格是不是箭头格；不是则为 null
     */
    public record Cell(int ac, int re, int sampleSize, String fromLetter, String arrow) {
    }

    /**
     * 一张完整的抽样方案表（字码行 + 样本量 + 格子）。
     * <p>
     * <b>为什么三张表要各自带 LETTER 与 SAMPLE</b>：
     * 表 2-B 比表 2-A 多一行 S（n=3150）；
     * 表 2-C 的字码虽然还是 A~R，但<b>样本量整体降两档</b>
     * （A/B/C 都是 2 件，R 只有 800）。
     * 如果三张表共用一套 LETTER/SAMPLE，放宽检验会退化成正常检验——
     * <b>放宽了个寂寞</b>。
     */
    public record PlanTable(String strictLevel, String[] letters, int[] samples, String[][] grid) {
        public int rows() {
            return letters.length;
        }
    }

    /** 正常检验（表 2-A） */
    public static final PlanTable NORMAL =
            new PlanTable("NORMAL", LETTER, SAMPLE, GRID);

    /** 加严检验（表 2-B），17 行 A~S */
    public static final PlanTable TIGHTENED =
            new PlanTable("TIGHTENED", LETTER_TIGHTENED, SAMPLE_TIGHTENED, GRID_TIGHTENED);

    /** 放宽检验（表 2-C），16 行 A~R，样本量降两档 */
    public static final PlanTable REDUCED =
            new PlanTable("REDUCED", LETTER_REDUCED, SAMPLE_REDUCED, GRID_REDUCED);

    /**
     * 按严格度取表。
     * <p>
     * 不认识的严格度退回正常检验——<b>但不能静默</b>：
     * 调用方（{@link ErpAqlService#normalizeStrict}）已经先做过归一化并会对
     * 非法值抛异常，走到这里说明是内部代码传了脏值。
     */
    public static PlanTable tableOf(String strictLevel) {
        if (TIGHTENED.strictLevel().equals(strictLevel)) {
            return TIGHTENED;
        }
        if (REDUCED.strictLevel().equals(strictLevel)) {
            return REDUCED;
        }
        return NORMAL;
    }

    /**
     * 解析一格（默认正常检验，保留给旧调用方）。
     *
     * @see #resolveCell(PlanTable, int, int)
     */
    public static Cell resolveCell(int bandIndex, int aqlIndex) {
        return resolveCell(NORMAL, bandIndex, aqlIndex);
    }

    /**
     * 解析一格：数值格直接取；箭头格按国标 10.3 沿箭头方向借第一个数字格。
     * <p>
     * <b>三张表共用这一套解析规则</b>——国标 10.3 讲的是「抽样方案表」的通则，
     * 表 2-A/2-B/2-C 的页脚都印着同样的箭头图例。
     * 差别只在数据（哪格是箭头、箭头朝哪），不在算法。
     *
     * @param table     哪张表（正常/加严/放宽）
     * @param bandIndex 字码行下标
     * @param aqlIndex  AQL 列下标 0~5
     * @return 解析后的最终方案；该格无方案（如 S@0.65）或箭头指向表外时返回 null
     */
    public static Cell resolveCell(PlanTable table, int bandIndex, int aqlIndex) {
        String[] letters = table.letters();
        int[] samples = table.samples();
        String[][] grid = table.grid();
        if (bandIndex < 0 || bandIndex >= grid.length) {
            return null;
        }
        String raw = grid[bandIndex][aqlIndex];
        if (raw == null) {
            return null;   // 该格无方案（表 2-B 的 S@AQL0.65）
        }
        if (!ARROW_DOWN.equals(raw) && !ARROW_UP.equals(raw)) {
            String[] parts = raw.split("\\s+");
            int ac = Integer.parseInt(parts[0]);
            int re = Integer.parseInt(parts[1]);
            return new Cell(ac, re, samples[bandIndex], letters[bandIndex], null);
        }
        int step = ARROW_DOWN.equals(raw) ? 1 : -1;
        for (int i = bandIndex + step; i >= 0 && i < grid.length; i += step) {
            String t = grid[i][aqlIndex];
            if (t == null) {
                continue;   // 无方案的格子不能当"数字格"借，继续找
            }
            if (ARROW_DOWN.equals(t) || ARROW_UP.equals(t)) {
                continue;   // 连续箭头：继续沿同方向找
            }
            String[] parts = t.split("\\s+");
            int ac = Integer.parseInt(parts[0]);
            int re = Integer.parseInt(parts[1]);
            // 国标 10.3：Ac/Re 与样本量 n 一起借（从被借的那一行向左读 n）
            return new Cell(ac, re, samples[i], letters[i], raw);
        }
        return null;   // 箭头指向表外（GB/T 2828.1 的三张表里不会出现，防御性返回）
    }

    /** 这一格是不是箭头格（默认正常检验） */
    public static boolean isArrow(int bandIndex, int aqlIndex) {
        return isArrow(NORMAL, bandIndex, aqlIndex);
    }

    /** 这一格是不是箭头格 */
    public static boolean isArrow(PlanTable table, int bandIndex, int aqlIndex) {
        String raw = table.grid()[bandIndex][aqlIndex];
        return ARROW_DOWN.equals(raw) || ARROW_UP.equals(raw);
    }
}
