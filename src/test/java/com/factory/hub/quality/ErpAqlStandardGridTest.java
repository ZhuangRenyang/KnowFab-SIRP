package com.factory.hub.quality;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 国标 10.3 箭头格解析的钉死测试。
 * <p>
 * <b>为什么这一组测试值钱</b>：箭头格解析错了一个字码，
 * 接收数可能碰巧不变（箭头多指向 Ac 相同的相邻行），但<b>样本量会差一档</b>——
 * 抽样强度不足，接受概率被高估，该判不合格的批次被放行。
 * <p>
 * 所以这里不只钉接收数，<b>更钉样本量</b>。
 */
class ErpAqlStandardGridTest {

    private static final int B_A = 0, B_B = 1, B_C = 2, B_D = 3, B_E = 4, B_F = 5,
            B_G = 6, B_H = 7, B_J = 8, B_K = 9, B_L = 10;
    private static final int Q_065 = 0, Q_10 = 1, Q_15 = 2, Q_25 = 3, Q_40 = 4, Q_65 = 5;

    @Test
    @DisplayName("国标原表有 27 个箭头格，各列 7/6/5/4/3/2 个（左上三角）")
    void arrowCellCount() {
        int total = 0;
        int[] perCol = new int[6];
        for (int band = 0; band < 11; band++) {
            for (int col = 0; col < 6; col++) {
                if (ErpAqlStandardGrid.isArrow(band, col)) {
                    total++;
                    perCol[col]++;
                }
            }
        }
        assertEquals(27, total, "箭头格总数");
        assertEquals(7, perCol[Q_065], "AQL 0.65 列的箭头格数");
        assertEquals(6, perCol[Q_10], "AQL 1.0 列的箭头格数");
        assertEquals(5, perCol[Q_15], "AQL 1.5 列的箭头格数");
        assertEquals(4, perCol[Q_25], "AQL 2.5 列的箭头格数");
        assertEquals(3, perCol[Q_40], "AQL 4.0 列的箭头格数");
        assertEquals(2, perCol[Q_65], "AQL 6.5 列的箭头格数");
    }

    @Test
    @DisplayName("箭头格要连样本量一起借（国标 10.3）——这是旧实现错的地方")
    void arrowBorrowsSampleSizeToo() {
        // H 档 @ AQL 0.65：国标是 ↓ 箭头，借 J 行 → n=80（不是 H 行自己的 50）
        ErpAqlStandardGrid.Cell c = ErpAqlStandardGrid.resolveCell(B_H, Q_065);
        assertNotNull(c);
        assertEquals(1, c.ac(), "Ac 取被借那行的");
        assertEquals(2, c.re());
        assertEquals(80, c.sampleSize(), "样本量必须一起借（80，不是 50）");
        assertEquals("J", c.fromLetter(), "借的是 J 行");
        assertEquals("↓", c.arrow());

        // A 档 @ AQL 0.65：↓ 箭头，借 F 行 → n=20（不是 A 行自己的 2）
        c = ErpAqlStandardGrid.resolveCell(B_A, Q_065);
        assertEquals(0, c.ac());
        assertEquals(20, c.sampleSize(), "A 档借 F 行的 n=20");
        assertEquals("F", c.fromLetter());
    }

    @Test
    @DisplayName("↑ 箭头向上借：D 档 @ AQL 2.5 借 C 行（n=5）")
    void upArrowBorrowsFromAbove() {
        ErpAqlStandardGrid.Cell c = ErpAqlStandardGrid.resolveCell(B_D, Q_25);
        assertNotNull(c);
        assertEquals(0, c.ac());
        assertEquals(5, c.sampleSize(), "D 档 @ 2.5 借 C 行的 n=5");
        assertEquals("C", c.fromLetter());
        assertEquals("↑", c.arrow());
    }

    @Test
    @DisplayName("连续箭头要一直找到第一个数字格（A~E 五行共用一根 ↓）")
    void consecutiveArrowsSkipToFirstNumericCell() {
        // AQL 0.65 列 A~E 全是 ↓，第一个数字格是 F 行
        for (int band = B_A; band <= B_E; band++) {
            ErpAqlStandardGrid.Cell c = ErpAqlStandardGrid.resolveCell(band, Q_065);
            assertEquals("F", c.fromLetter(), "第 " + band + " 档应借 F 行");
            assertEquals(20, c.sampleSize());
            assertEquals(0, c.ac());
        }
    }

    @Test
    @DisplayName("非箭头格直接读本行，样本量就是本行的字码样本量")
    void nonArrowCellReadsItsOwnRow() {
        // L 档 @ AQL 6.5：数字格 21 22，n=200
        ErpAqlStandardGrid.Cell c = ErpAqlStandardGrid.resolveCell(B_L, Q_65);
        assertEquals(21, c.ac());
        assertEquals(22, c.re());
        assertEquals(200, c.sampleSize());
        assertEquals("L", c.fromLetter());
        assertNull(c.arrow(), "非箭头格没有箭头");
    }

    @Test
    @DisplayName("接收数与三份公开对照表一致（ISO 2859-1 Table II 的转载表）")
    void acceptanceNumbersMatchPublishedTables() {
        // AQL 2.5 整列（公开对照表：2~8 A 0/1 … 3201~10000 L 10/11）
        int[] ac25 = {0, 0, 0, 0, 1, 1, 2, 3, 5, 7, 10};
        for (int i = 0; i < ac25.length; i++) {
            assertEquals(ac25[i], ErpAqlStandardGrid.resolveCell(i, Q_25).ac(),
                    "AQL 2.5 第 " + i + " 档");
        }
        // AQL 4.0 的 H/J/K/L 四行（公开对照表：5/6、7/8、10/11、14/15）
        assertEquals(5, ErpAqlStandardGrid.resolveCell(B_H, Q_40).ac());
        assertEquals(7, ErpAqlStandardGrid.resolveCell(B_J, Q_40).ac());
        assertEquals(10, ErpAqlStandardGrid.resolveCell(B_K, Q_40).ac());
        assertEquals(14, ErpAqlStandardGrid.resolveCell(B_L, Q_40).ac());
        // AQL 0.65 的 J/K/L（公开对照表：1/2、2/3、3/4）
        assertEquals(1, ErpAqlStandardGrid.resolveCell(B_J, Q_065).ac());
        assertEquals(2, ErpAqlStandardGrid.resolveCell(B_K, Q_065).ac());
        assertEquals(3, ErpAqlStandardGrid.resolveCell(B_L, Q_065).ac());
    }

    @Test
    @DisplayName("样本量必须等于「被借那一行」的样本量（国标 10.3：向左读那一行）")
    void sampleSizeComesFromBorrowedRow() {
        // ⚠️ 不要假设箭头只会借"更大"的行：B 档 @ AQL 6.5 是 ↑ 箭头，
        // 借 A 行 → n=2，比 B 行自己的 3 还小。国标就这么规定的（↑ 往小借）。
        // 所以正确的自检是「样本量 == 被借行的样本量」，不是「≥ 本行的」。
        for (int band = 0; band < 11; band++) {
            for (int col = 0; col < 6; col++) {
                ErpAqlStandardGrid.Cell c = ErpAqlStandardGrid.resolveCell(band, col);
                assertNotNull(c, "格（" + band + "," + col + "）不该解析失败");
                int fromIdx = -1;
                for (int i = 0; i < ErpAqlStandardGrid.LETTER.length; i++) {
                    if (ErpAqlStandardGrid.LETTER[i].equals(c.fromLetter())) {
                        fromIdx = i;
                        break;
                    }
                }
                assertTrue(fromIdx >= 0, "来源字码不对：" + c.fromLetter());
                assertEquals(ErpAqlStandardGrid.SAMPLE[fromIdx], c.sampleSize(),
                        "格（" + ErpAqlStandardGrid.LETTER[band] + " @ "
                                + ErpAqlStandardGrid.AQL_TEXT[col] + "）的样本量应取 "
                                + c.fromLetter() + " 行的 " + ErpAqlStandardGrid.SAMPLE[fromIdx]);
            }
        }
        // 专门钉住那个"往小借"的格子：B 档 @ AQL 6.5 = ↑ → A 行 → n=2
        ErpAqlStandardGrid.Cell c = ErpAqlStandardGrid.resolveCell(B_B, Q_65);
        assertEquals(2, c.sampleSize(), "B 档 @ 6.5 向上借 A 行，n=2");
        assertEquals("A", c.fromLetter());
    }

    @Test
    @DisplayName("播种出的 66 行：样本量与解析结果一致，备注写明来源")
    void seededRowsMatchResolution() {
        java.util.List<ErpQcAqlPlan> rows = ErpAqlSeeder.buildPlans();
        assertEquals(66, rows.size());
        for (ErpQcAqlPlan p : rows) {
            int band = -1;
            for (int i = 0; i < ErpAqlStandardGrid.LOT_FROM.length; i++) {
                if (p.getLotFrom() == ErpAqlStandardGrid.LOT_FROM[i]) {
                    band = i;
                    break;
                }
            }
            assertTrue(band >= 0, "批量档对不上：" + p.getLotFrom());
            int col = -1;
            for (int i = 0; i < ErpAqlStandardGrid.AQL_TEXT.length; i++) {
                if (p.getAql().compareTo(new java.math.BigDecimal(ErpAqlStandardGrid.AQL_TEXT[i])) == 0) {
                    col = i;
                    break;
                }
            }
            assertTrue(col >= 0, "AQL 对不上：" + p.getAql());
            ErpAqlStandardGrid.Cell c = ErpAqlStandardGrid.resolveCell(band, col);
            assertEquals(c.sampleSize(), p.getSampleSize(), "样本量不一致");
            assertEquals(c.ac(), p.getAc(), "接收数不一致");
            assertEquals(c.re(), p.getRe(), "拒收数不一致");
        }
    }
}
