package com.factory.hub.erp.quality.inspection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 国标表 1（样本量字码）的专项测试。
 * <p>
 * <b>为什么这张表要单独测</b>：补齐 7 个检验水平靠的就是它——
 * 各水平共用表 2-A 的 Ac/Re 方案，差别只在「批量 → 字码」这一步。
 * 这张表错了，抽样量就错了，而且是<b>静默地错</b>（查表照样有结果）。
 * <p>
 * <b>期望值从哪来</b>：GB/T 2828.1-2012 标准原文扫描件第 20 页的表 1，
 * 逐行核读。这里抄的是原表，不是从实现反推的。
 */
class ErpAqlLevelMapTest {

    @Test
    @DisplayName("表 1 的行数与列数：15 个批量档 × 7 个检验水平")
    void tableShape() {
        assertEquals(15, ErpAqlLevelMap.LOT_FROM.length, "表 1 是 15 行批量档");
        assertEquals(15, ErpAqlLevelMap.LOT_TO.length);
        assertEquals(7, ErpAqlLevelMap.LEVELS.length, "表 1 是 7 个检验水平列");
    }

    @Test
    @DisplayName("一般检验水平 II 的字码：与国标表 1 第 II 列逐行一致")
    void generalIIColumnMatchesStandard() {
        // 国标表 1，一般检验水平 II 列，15 行批量档
        String[] expect = {
                "A", "B", "C", "D", "E", "F", "G", "H", "J", "K", "L", "M", "N", "P", "Q", "R"
        };
        // 上表是 16 个字码；表 1 的 II 列实际是 15 行：
        //   2~8 A / 9~15 B / 16~25 C / 26~50 D / 51~90 E / 91~150 F / 151~280 G
        //   281~500 H / 501~1200 J / 1201~3200 K / 3201~10000 L / 10001~35000 M
        //   35001~150000 N / 150001~500000 P / 500001 及以上 Q
        String[] expectII = {
                "A", "B", "C", "D", "E", "F", "G", "H", "J", "K", "L", "M", "N", "P", "Q"
        };
        assertEquals(15, expectII.length);
        for (int i = 0; i < expectII.length; i++) {
            long lot = ErpAqlLevelMap.LOT_FROM[i];
            assertEquals(expectII[i], ErpAqlLevelMap.letterOf(lot, ErpAqlLevelMap.LEVEL_II),
                    "水平 II、批量 " + lot + " 的字码不对");
        }
        // 最后一档（500001 及以上）用哨兵上限，落在同一档
        assertEquals("Q", ErpAqlLevelMap.letterOf(500001, ErpAqlLevelMap.LEVEL_II));
        assertEquals("Q", ErpAqlLevelMap.letterOf(9_999_999, ErpAqlLevelMap.LEVEL_II));
    }

    @Test
    @DisplayName("一般检验水平 III：批量 2~8 要抽 B 字码（比水平 II 大）")
    void generalIIIColumn() {
        // 国标表 1 的 III 列：2~8→B、9~15→C、16~25→D … 500001 及以上→R
        assertEquals("B", ErpAqlLevelMap.letterOf(2, ErpAqlLevelMap.LEVEL_III));
        assertEquals("C", ErpAqlLevelMap.letterOf(9, ErpAqlLevelMap.LEVEL_III));
        assertEquals("D", ErpAqlLevelMap.letterOf(16, ErpAqlLevelMap.LEVEL_III));
        assertEquals("R", ErpAqlLevelMap.letterOf(500001, ErpAqlLevelMap.LEVEL_III));
    }

    @Test
    @DisplayName("特殊检验水平 S-1：批量越大字码不变（S-1 的抽样量最小）")
    void specialS1Column() {
        // 国标表 1 的 S-1 列：2~8→A … 500001 及以上→D
        assertEquals("A", ErpAqlLevelMap.letterOf(2, ErpAqlLevelMap.LEVEL_S1));
        assertEquals("A", ErpAqlLevelMap.letterOf(16, ErpAqlLevelMap.LEVEL_S1));
        assertEquals("B", ErpAqlLevelMap.letterOf(51, ErpAqlLevelMap.LEVEL_S1));
        assertEquals("C", ErpAqlLevelMap.letterOf(501, ErpAqlLevelMap.LEVEL_S1));
        // 10 001~35 000 档的 S-1 列是 C（D 在 35 001~150 000 档）
        assertEquals("C", ErpAqlLevelMap.letterOf(10001, ErpAqlLevelMap.LEVEL_S1));
        assertEquals("D", ErpAqlLevelMap.letterOf(35001, ErpAqlLevelMap.LEVEL_S1));
    }

    @Test
    @DisplayName("一般检验水平 I ≤ II ≤ III：水平越高抽样量越大")
    void higherGeneralLevelMeansLargerSample() {
        // 三个**一般**检验水平是严格递增的：水平越高 → 字码越大 → 样本量越大 → 鉴别力越高。
        // 如果某个批量下出现「水平越高抽样量反而变小」，说明表抄错了。
        for (int i = 0; i < ErpAqlLevelMap.LOT_FROM.length; i++) {
            long lot = ErpAqlLevelMap.LOT_FROM[i];
            int prev = -1;
            for (String level : new String[]{
                    ErpAqlLevelMap.LEVEL_I, ErpAqlLevelMap.LEVEL_II,
                    ErpAqlLevelMap.LEVEL_III}) {
                int n = sampleOf(lot, level);
                assertTrue(n >= prev, "批量 " + lot + " 的水平 " + level
                        + " 抽样量 " + n + " 比上一个水平还小（" + prev + "）");
                prev = n;
            }
        }
    }

    @Test
    @DisplayName("特殊水平与一般水平之间不保证单调（国标表 1 本来就是这样）")
    void specialLevelsAreNotMonotonicAgainstGeneral() {
        // ⚠️ 这条是**国标事实**，不是 bug：S-1~S-4 与 I/II/III 是两套并行的鉴别力档，
        // 跨套不保证单调。例：51~90 档，S-4=D（n=8）而一般 I=C（n=5）——S-4 更大。
        // 我最初写断言时以为「S-4 ≤ I」，结果被国标原文打脸。
        // **写自检断言前先查标准原文**，别按「看起来应该怎样」写。
        assertEquals("D", ErpAqlLevelMap.letterOf(51, ErpAqlLevelMap.LEVEL_S4));
        assertEquals("C", ErpAqlLevelMap.letterOf(51, ErpAqlLevelMap.LEVEL_I));
        assertTrue(sampleOf(51, ErpAqlLevelMap.LEVEL_S4) > sampleOf(51, ErpAqlLevelMap.LEVEL_I),
                "51 档的 S-4 抽样量确实比一般 I 大（国标原文如此）");
        // 但 S-1 一定是最小的那档（它的样本量不小于任何水平）
        for (int i = 0; i < ErpAqlLevelMap.LOT_FROM.length; i++) {
            long lot = ErpAqlLevelMap.LOT_FROM[i];
            int s1 = sampleOf(lot, ErpAqlLevelMap.LEVEL_S1);
            for (String level : ErpAqlLevelMap.LEVELS) {
                assertTrue(sampleOf(lot, level) >= s1,
                        "批量 " + lot + " 的 " + level + " 抽样量比 S-1 还小");
            }
        }
    }

    @Test
    @DisplayName("S-1~S-4 四个特殊水平互不相同（不能抄成一样）")
    void specialLevelsDiffer() {
        // 国标表 1 的 S-1~S-4 四列在多数批量上不同：
        // 26~50 档 → S-1=A / S-2=B / S-3=B / S-4=C
        assertEquals("A", ErpAqlLevelMap.letterOf(26, ErpAqlLevelMap.LEVEL_S1));
        assertEquals("B", ErpAqlLevelMap.letterOf(26, ErpAqlLevelMap.LEVEL_S2));
        assertEquals("B", ErpAqlLevelMap.letterOf(26, ErpAqlLevelMap.LEVEL_S3));
        assertEquals("C", ErpAqlLevelMap.letterOf(26, ErpAqlLevelMap.LEVEL_S4));
        // 91~150 档 → S-1=B / S-2=B / S-3=C / S-4=D
        assertEquals("B", ErpAqlLevelMap.letterOf(91, ErpAqlLevelMap.LEVEL_S1));
        assertEquals("B", ErpAqlLevelMap.letterOf(91, ErpAqlLevelMap.LEVEL_S2));
        assertEquals("C", ErpAqlLevelMap.letterOf(91, ErpAqlLevelMap.LEVEL_S3));
        assertEquals("D", ErpAqlLevelMap.letterOf(91, ErpAqlLevelMap.LEVEL_S4));
    }

    @Test
    @DisplayName("批量落在档内任意位置都取到同一字码")
    void lotInsideBandMapsToSameLetter() {
        // 281~500 这一档，四档之内都该是同一个字码
        String expect = ErpAqlLevelMap.letterOf(281, ErpAqlLevelMap.LEVEL_II);
        for (long lot : new long[]{281, 300, 400, 499, 500}) {
            assertEquals(expect, ErpAqlLevelMap.letterOf(lot, ErpAqlLevelMap.LEVEL_II),
                    "批量 " + lot + " 的字码不对");
        }
        // 跨档要变
        assertEquals("J", ErpAqlLevelMap.letterOf(501, ErpAqlLevelMap.LEVEL_II));
    }

    @Test
    @DisplayName("gridRowOf 返回的是字码行下标，不是批量档下标")
    void gridRowIsLetterRowNotBand() {
        // 同一个批量，不同水平 → 不同字码行
        int rowII = ErpAqlLevelMap.gridRowOf(2, ErpAqlLevelMap.LEVEL_II);
        int rowIII = ErpAqlLevelMap.gridRowOf(2, ErpAqlLevelMap.LEVEL_III);
        assertTrue(rowIII > rowII,
                "水平 III 的字码行应比水平 II 大（鉴别力更高 → 样本量更大）");
        assertEquals("A", ErpAqlStandardGrid.LETTER[rowII]);
        assertEquals("B", ErpAqlStandardGrid.LETTER[rowIII]);
    }

    @Test
    @DisplayName("不认识的水平返回 null（不静默当成水平 II）")
    void unknownLevelReturnsNull() {
        assertEquals(null, ErpAqlLevelMap.letterOf(100, "GENERAL_IV"));
        assertEquals(null, ErpAqlLevelMap.letterOf(100, "随便写的"));
        assertEquals(-1, ErpAqlLevelMap.gridRowOf(100, "GENERAL_IV"));
    }

    @Test
    @DisplayName("批量小于 2 没有方案（返回 null，由查表逻辑转全检）")
    void lotTooSmallHasNoLetter() {
        assertEquals(-1, ErpAqlLevelMap.bandIndexOf(1));
        assertEquals(null, ErpAqlLevelMap.letterOf(1, ErpAqlLevelMap.LEVEL_II));
    }

    @Test
    @DisplayName("7 个水平全部能查表，且字码都在 A~R 范围内")
    void allLevelsResolveToValidLetters() {
        Set<String> allLetters = new HashSet<>();
        for (String level : ErpAqlLevelMap.LEVELS) {
            for (int i = 0; i < ErpAqlLevelMap.LOT_FROM.length; i++) {
                String letter = ErpAqlLevelMap.letterOf(ErpAqlLevelMap.LOT_FROM[i], level);
                assertNotNull(letter, "水平 " + level + " 批量档 " + i + " 没有字码");
                allLetters.add(letter);
            }
        }
        // 表 1 用到的字码集合（A~R 跳过 I 和 O）
        assertTrue(allLetters.contains("A") && allLetters.contains("R"),
                "字码集合应覆盖 A 与 R：" + allLetters);
        assertTrue(!allLetters.contains("I"), "国标字码跳过 I（避免与 1 混淆）");
        assertTrue(!allLetters.contains("O"), "国标字码跳过 O（避免与 0 混淆）");
    }

    /** 该批量 + 水平下的样本量（借字码行查） */
    private int sampleOf(long lot, String level) {
        int row = ErpAqlLevelMap.gridRowOf(lot, level);
        assertTrue(row >= 0, "查不到字码行：" + level + " / " + lot);
        return ErpAqlStandardGrid.SAMPLE[row];
    }
}
