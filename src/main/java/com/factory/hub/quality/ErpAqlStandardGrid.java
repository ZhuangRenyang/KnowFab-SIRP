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
 * 表 2-A 位于第 15-17 页，逐格放大核读并与行位置对齐校对。
 * 交叉验证：公开转载的三份对照表（AQL 2.5 全表、AQL 0.65/1.0/4.0/6.5 部分行）
 * 与本表的 <b>Ac 值 66/66 完全一致</b>。
 *
 * @see ErpAqlSeeder
 */
public final class ErpAqlStandardGrid {

    private ErpAqlStandardGrid() {
    }

    /** 11 个批量档的下限（含）——GB/T 2828.1-2012 表 1，一般检验水平 II */
    public static final int[] LOT_FROM = {2, 9, 16, 26, 51, 91, 151, 281, 501, 1201, 3201};

    /** 11 个批量档的上限（含） */
    public static final int[] LOT_TO = {8, 15, 25, 50, 90, 150, 280, 500, 1200, 3200, 10000};

    /** 样本量字码 A~L（跳过 I，与国标一致：I 不用于抽样） */
    public static final String[] LETTER = {"A", "B", "C", "D", "E", "F", "G", "H", "J", "K", "L"};

    /** 各字码行的样本量 n（国标表 1） */
    public static final int[] SAMPLE = {2, 3, 5, 8, 13, 20, 32, 50, 80, 125, 200};

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
     * 解析一格：数值格直接取；箭头格按国标 10.3 沿箭头方向借第一个数字格。
     *
     * @param bandIndex 批量档下标 0~10
     * @param aqlIndex  AQL 列下标 0~5
     * @return 解析后的最终方案；箭头指向表外时返回 null
     */
    public static Cell resolveCell(int bandIndex, int aqlIndex) {
        String raw = GRID[bandIndex][aqlIndex];
        if (!ARROW_DOWN.equals(raw) && !ARROW_UP.equals(raw)) {
            String[] parts = raw.split("\\s+");
            int ac = Integer.parseInt(parts[0]);
            int re = Integer.parseInt(parts[1]);
            return new Cell(ac, re, SAMPLE[bandIndex], LETTER[bandIndex], null);
        }
        int step = ARROW_DOWN.equals(raw) ? 1 : -1;
        for (int i = bandIndex + step; i >= 0 && i < LETTER.length; i += step) {
            String t = GRID[i][aqlIndex];
            if (ARROW_DOWN.equals(t) || ARROW_UP.equals(t)) {
                continue;   // 连续箭头：继续沿同方向找
            }
            String[] parts = t.split("\\s+");
            int ac = Integer.parseInt(parts[0]);
            int re = Integer.parseInt(parts[1]);
            // 国标 10.3：Ac/Re 与样本量 n 一起借（从被借的那一行向左读 n）
            return new Cell(ac, re, SAMPLE[i], LETTER[i], raw);
        }
        return null;   // 箭头指向表外（GB/T 2828.1 表 2-A 里不会出现，防御性返回）
    }

    /** 这一格是不是箭头格 */
    public static boolean isArrow(int bandIndex, int aqlIndex) {
        String raw = GRID[bandIndex][aqlIndex];
        return ARROW_DOWN.equals(raw) || ARROW_UP.equals(raw);
    }
}
