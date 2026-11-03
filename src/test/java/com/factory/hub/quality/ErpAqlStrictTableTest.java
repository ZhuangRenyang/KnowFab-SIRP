package com.factory.hub.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * GB/T 2828.1-2012 表 2-B（加严检验）与表 2-C（放宽检验）的专项测试。
 * <p>
 * <b>为什么三张表要分开测</b>：正常 / 加严 / 放宽**共用同一套箭头解析规则**，
 * 但**数据完全不同**——行数不同（表 2-B 多一行 S）、样本量不同（表 2-C 降两档）、
 * Ac 值也不同。任何"按规律推导另一张表"的做法都会出错，
 * 所以这里钉住一批**只有直接照抄才会对上**的格子。
 * <p>
 * <b>期望值来源</b>：GB/T 2828.1-2012 标准原文扫描件
 * （表 2-B 在第 22 页、表 2-C 在第 23 页），逐格放大核读。
 */
class ErpAqlStrictTableTest {

    private static final int Q_065 = 0;
    private static final int Q_10 = 1;
    private static final int Q_15 = 2;
    private static final int Q_25 = 3;
    private static final int Q_40 = 4;
    private static final int Q_65 = 5;

    // 字码行下标（A~R 共 16 行，表 2-B 多一行 S）
    private static final int B_M = 11;
    private static final int B_N = 12;
    private static final int B_R = 15;
    private static final int B_S = 16;

    @Test
    @DisplayName("三张表的行数：正常 16 行 A~R / 加严 17 行 A~S / 放宽 16 行 A~R")
    void rowCounts() {
        assertEquals(16, ErpAqlStandardGrid.NORMAL.rows());
        assertEquals(17, ErpAqlStandardGrid.TIGHTENED.rows(),
                "表 2-B 比表 2-A 多一行 S（n=3150）");
        assertEquals(16, ErpAqlStandardGrid.REDUCED.rows());
        assertEquals("S", ErpAqlStandardGrid.LETTER_TIGHTENED[16]);
        assertEquals(3150, ErpAqlStandardGrid.SAMPLE_TIGHTENED[16]);
    }

    @Test
    @DisplayName("表 2-C 的样本量整体降两档：A/B/C 都是 2 件，R 只有 800")
    void reducedSamplesAreTwoStepsLower() {
        // 放宽检验的"放宽"就体现在样本量上。
        // 如果这里与表 2-A 一样，那放宽个寂寞——白挂一个状态。
        assertEquals(2, ErpAqlStandardGrid.SAMPLE_REDUCED[0], "表 2-C 的 A 行 = 2 件");
        assertEquals(2, ErpAqlStandardGrid.SAMPLE_REDUCED[1], "表 2-C 的 B 行也是 2 件");
        assertEquals(2, ErpAqlStandardGrid.SAMPLE_REDUCED[2], "表 2-C 的 C 行也是 2 件");
        assertEquals(800, ErpAqlStandardGrid.SAMPLE_REDUCED[15], "表 2-C 的 R 行 = 800 件");
        // 而表 2-A 是 2 / 3 / 5 ... 2000
        assertEquals(3, ErpAqlStandardGrid.SAMPLE[1]);
        assertEquals(5, ErpAqlStandardGrid.SAMPLE[2]);
        assertEquals(2000, ErpAqlStandardGrid.SAMPLE[15]);
    }

    @Test
    @DisplayName("同一格在三张表里可以完全不同：M@AQL1.5 是 7/8 vs 8/9 vs 6/7")
    void sameCellDiffersAcrossTables() {
        // 这一格是"不能靠推导"的铁证：
        //   表 2-A（正常）M@1.5 = 10 11；
        //   表 2-B（加严）M@1.5 = 8 9；
        //   表 2-C（放宽）M@1.5 = 6 7。
        // 三张表三组数字，任何线性换算都凑不出来。
        ErpAqlStandardGrid.Cell n = ErpAqlStandardGrid.resolveCell(
                ErpAqlStandardGrid.NORMAL, B_M, Q_15);
        ErpAqlStandardGrid.Cell t = ErpAqlStandardGrid.resolveCell(
                ErpAqlStandardGrid.TIGHTENED, B_M, Q_15);
        ErpAqlStandardGrid.Cell r = ErpAqlStandardGrid.resolveCell(
                ErpAqlStandardGrid.REDUCED, B_M, Q_15);
        assertNotNull(n);
        assertNotNull(t);
        assertNotNull(r);
        assertEquals(10, n.ac(), "表 2-A 的 M@1.5 是 10/11");
        assertEquals(8, t.ac(), "表 2-B 的 M@1.5 是 8/9");
        assertEquals(6, r.ac(), "表 2-C 的 M@1.5 是 6/7");
        // 样本量也跟着各自表走：M 行在表 2-A/2-B 都是 315，表 2-C 是 125
        assertEquals(315, n.sampleSize());
        assertEquals(315, t.sampleSize());
        assertEquals(125, r.sampleSize(), "表 2-C 的 M 行样本量降两档到 125");
    }

    @Test
    @DisplayName("表 2-B 的 S 行：AQL 0.65 列无方案（不编一个假的值）")
    void tightenedSRowHasNoPlanAt065() {
        // 国标表 2-B 的 S 行在 0.65 列是空白 —— 没有方案。
        // 这里返回 null 而不是编一个 Ac，调用方会跳过该格不播种。
        assertNull(ErpAqlStandardGrid.resolveCell(
                        ErpAqlStandardGrid.TIGHTENED, B_S, Q_065),
                "S 行 @ AQL0.65 没有方案，必须返回 null");
        // 但同一行的 6.5 列是 ↑（借上面），能解析出结果
        ErpAqlStandardGrid.Cell c = ErpAqlStandardGrid.resolveCell(
                ErpAqlStandardGrid.TIGHTENED, B_S, Q_65);
        assertNotNull(c, "S 行 @ AQL6.5 是箭头，应当能借到方案");
        assertEquals("↑", c.arrow());
    }

    @Test
    @DisplayName("表 2-B 的箭头也要借样本量：R@AQL1.0 的 ↑ 指向 Q 行的 18/19")
    void tightenedArrowBorrowsSample() {
        ErpAqlStandardGrid.Cell c = ErpAqlStandardGrid.resolveCell(
                ErpAqlStandardGrid.TIGHTENED, B_R, Q_10);
        assertNotNull(c);
        assertEquals("↑", c.arrow());
        assertEquals(18, c.ac());
        assertEquals(19, c.re());
        assertEquals("Q", c.fromLetter(), "R 的 ↑ 借 Q 行");
        assertEquals(1250, c.sampleSize(), "样本量要取 Q 行的 1250，不是 R 行的 2000");
    }

    @Test
    @DisplayName("加严不等于「样本量一定变大」：也可能是样本量不变、Ac 变小")
    void tightenedMayBeSmallerAcInsteadOfBiggerSample() {
        // 批量 800、水平 II、AQL 1.0：
        //   表 2-A 的 J 行 @1.0 = 2/3（n=80）
        //   表 2-B 的 J 行 @1.0 = 1/2（n=80）
        // 样本量一样，Ac 从 2 降到 1 —— 这就是加严。
        // ⚠️ 如果断言"加严样本量必须更大"，会把对的实现判成错的（我第一版就写错了）。
        ErpAqlStandardGrid.Cell n = ErpAqlStandardGrid.resolveCell(
                ErpAqlStandardGrid.NORMAL, 8, Q_10);      // J 行
        ErpAqlStandardGrid.Cell t = ErpAqlStandardGrid.resolveCell(
                ErpAqlStandardGrid.TIGHTENED, 8, Q_10);
        assertNotNull(n);
        assertNotNull(t);
        assertEquals(80, n.sampleSize());
        assertEquals(80, t.sampleSize(), "这一档加严没有加大样本量");
        assertEquals(2, n.ac());
        assertEquals(1, t.ac(), "加严体现在接收数从 2 降到 1");
    }

    @Test
    @DisplayName("放宽不等于「Ac 一定变大」：表 2-C 的 Ac 也可能更小")
    void reducedAcIsNotAlwaysBigger() {
        // 批量 800、水平 II、AQL 1.0：
        //   表 2-C 的 J 行 @1.0 = 1/2，J 行在表 2-C 的样本量是 32（不是 80）。
        // 也就是"放宽"主要靠**少抽**：样本量从 80 降到 32，Ac 反而更小。
        ErpAqlStandardGrid.Cell r = ErpAqlStandardGrid.resolveCell(
                ErpAqlStandardGrid.REDUCED, 8, Q_10);
        assertNotNull(r);
        assertEquals(32, r.sampleSize(), "表 2-C 的 J 行样本量 = 32");
        assertEquals(1, r.ac(), "表 2-C 的 J@1.0 是 1/2");
    }

    @Test
    @DisplayName("三张表都满足 Re = Ac + 1（解析后）")
    void reAlwaysAcPlusOne() {
        for (ErpAqlStandardGrid.PlanTable t : new ErpAqlStandardGrid.PlanTable[]{
                ErpAqlStandardGrid.NORMAL, ErpAqlStandardGrid.TIGHTENED,
                ErpAqlStandardGrid.REDUCED}) {
            for (int i = 0; i < t.rows(); i++) {
                for (int j = 0; j < ErpAqlStandardGrid.AQL_TEXT.length; j++) {
                    ErpAqlStandardGrid.Cell c = ErpAqlStandardGrid.resolveCell(t, i, j);
                    if (c == null) {
                        continue;   // 无方案格（表 2-B 的 S@0.65）
                    }
                    assertEquals(c.ac() + 1, c.re(),
                            t.strictLevel() + " 的 " + t.letters()[i] + " @ "
                                    + ErpAqlStandardGrid.AQL_TEXT[j] + " 的 Re 不等于 Ac+1");
                }
            }
        }
    }

    @Test
    @DisplayName("样本量必须等于「被借那一行」的样本量（三张表都是）")
    void borrowedSampleComesFromBorrowedRow() {
        for (ErpAqlStandardGrid.PlanTable t : new ErpAqlStandardGrid.PlanTable[]{
                ErpAqlStandardGrid.NORMAL, ErpAqlStandardGrid.TIGHTENED,
                ErpAqlStandardGrid.REDUCED}) {
            for (int i = 0; i < t.rows(); i++) {
                for (int j = 0; j < ErpAqlStandardGrid.AQL_TEXT.length; j++) {
                    ErpAqlStandardGrid.Cell c = ErpAqlStandardGrid.resolveCell(t, i, j);
                    if (c == null) {
                        continue;
                    }
                    int fromIdx = -1;
                    for (int k = 0; k < t.letters().length; k++) {
                        if (t.letters()[k].equals(c.fromLetter())) {
                            fromIdx = k;
                            break;
                        }
                    }
                    assertTrue(fromIdx >= 0,
                            t.strictLevel() + " 的来源字码不存在：" + c.fromLetter());
                    assertEquals(t.samples()[fromIdx], c.sampleSize(),
                            t.strictLevel() + " 的 " + t.letters()[i] + " @ "
                                    + ErpAqlStandardGrid.AQL_TEXT[j]
                                    + " 样本量应取 " + c.fromLetter() + " 行的");
                }
            }
        }
    }

    @Test
    @DisplayName("表 2-B 的 0.65 列：G 行开始有数字（H 之后是 ↓，不是一路箭头到底）")
    void tightened065ColumnShape() {
        // 表 2-B 的 0.65 列：A~F 是 ↓、G = 0/1、H = ↓、J = 1/2、K = 2/3 …
        assertEquals(0, ErpAqlStandardGrid.resolveCell(
                ErpAqlStandardGrid.TIGHTENED, 6, Q_065).ac(), "G@0.65 = 0/1");
        assertEquals(1, ErpAqlStandardGrid.resolveCell(
                ErpAqlStandardGrid.TIGHTENED, 8, Q_065).ac(), "J@0.65 借 K 行的 1/2");
        assertEquals(1, ErpAqlStandardGrid.resolveCell(
                ErpAqlStandardGrid.TIGHTENED, 9, Q_065).ac(), "K@0.65 = 1/2");
        assertEquals(2, ErpAqlStandardGrid.resolveCell(
                ErpAqlStandardGrid.TIGHTENED, 10, Q_065).ac(), "L@0.65 = 2/3");
        assertEquals(18, ErpAqlStandardGrid.resolveCell(
                ErpAqlStandardGrid.TIGHTENED, B_R, Q_065).ac(), "R@0.65 = 18/19");
    }
}
