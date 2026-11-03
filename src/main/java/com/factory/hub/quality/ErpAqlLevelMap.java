package com.factory.hub.quality;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GB/T 2828.1-2012 表 1「样本量字码」——<b>各检验水平的批量 → 字码映射</b>。
 * <p>
 * <b>这个类是补齐全部检验水平的关键</b>。此前的实现只有「一般检验水平 II」，
 * 而且是靠一张写死的批量档表（11 档），其余 6 个水平只能报"没配方案"。
 * <p>
 * <b>为什么不用逐格录入方案</b>：国标 10.1 节原文：
 * <blockquote>
 * 检验水平对应着检验量。表 1 中给出了 3 个一般检验水平 I、II 和 III。
 * 除非另有规定，应使用水平 II。当要求鉴别力较低时可使用水平 I，
 * 当要求鉴别力较高时可使用水平 III。在表 1 中还给出了另外 4 个特殊检验水平
 * S-1、S-2、S-3 和 S-4……<br>
 * 样本量通过样本量字码确定。对于给定的批量和规定的检验水平使用表 1 检索适用的字码。
 * </blockquote>
 * 也就是说：<b>各检验水平共用同一套 Ac/Re 方案表（表 2-A）</b>，
 * 差别只在「批量 → 字码」这一步。所以补齐 7 个水平要补的是
 * <b>7 张 16 行的字码映射表</b>（本类），而不是 7 套 Ac/Re 方案。
 * <p>
 * <b>数据来源</b>：GB/T 2828.1-2012 标准原文扫描件（88 页）第 20 页的表 1，
 * 逐行核读。
 * <p>
 * <b>表的读法</b>：表 1 的行是批量范围，列是检验水平（S-1~S-4、I、II、III），
 * 交叉处是该水平下该批量范围对应的样本量字码。
 * <p>
 * <b>本类只放"国标原样"，不做任何加工。</b>
 * 加工（字码 → Ac/Re）在 {@link ErpAqlStandardGrid#resolveCell} 里做。
 *
 * @see ErpAqlStandardGrid
 * @see ErpAqlSeeder
 */
public final class ErpAqlLevelMap {

    private ErpAqlLevelMap() {
    }

    // ==================== 检验水平（与前端 QC_LEVELS 一致） ====================

    /** 特殊检验水平 S-1（鉴别力最低，样本量最小） */
    public static final String LEVEL_S1 = "SPECIAL_S1";
    /** 特殊检验水平 S-2 */
    public static final String LEVEL_S2 = "SPECIAL_S2";
    /** 特殊检验水平 S-3 */
    public static final String LEVEL_S3 = "SPECIAL_S3";
    /** 特殊检验水平 S-4 */
    public static final String LEVEL_S4 = "SPECIAL_S4";
    /** 一般检验水平 I（鉴别力较低） */
    public static final String LEVEL_I = "GENERAL_I";
    /** 一般检验水平 II（默认，绝大多数场景） */
    public static final String LEVEL_II = "GENERAL_II";
    /** 一般检验水平 III（鉴别力较高） */
    public static final String LEVEL_III = "GENERAL_III";

    /** 全部 7 个检验水平（顺序 = 表 1 的列顺序） */
    public static final String[] LEVELS = {
            LEVEL_S1, LEVEL_S2, LEVEL_S3, LEVEL_S4, LEVEL_I, LEVEL_II, LEVEL_III
    };

    // ==================== 批量档（表 1 的 16 行） ====================

    /** 批量档下限（含）——表 1 逐行读出（共 15 档） */
    public static final int[] LOT_FROM = {
            2, 9, 16, 26, 51, 91, 151, 281, 501, 1201, 3201, 10001, 35001, 150001, 500001
    };

    /** 批量档上限（含）；最后一档用哨兵值表示"500001 及以上" */
    public static final int[] LOT_TO = {
            8, 15, 25, 50, 90, 150, 280, 500, 1200, 3200, 10000, 35000, 150000, 500000,
            999999999
    };

    // ==================== 字码映射表（表 1 的 16 行 × 7 列） ====================

    /**
     * 表 1 的原始映射。行 = 批量档，列 = {@link #LEVELS}。
     * <p>
     * 值是样本量字码（{@code "A"}~{@code "R"}）。
     * <p>
     * ⚠️ 注意 {@code "I"} <b>不是</b>字码——国标的字码序列是
     * A B C D E F G H <b>J</b> K L M N <b>P</b> Q R（跳过 I 和 O，
     * 避免与 1、0 混淆）。
     */
    private static final String[][] MAP = {
            //  S-1  S-2  S-3  S-4   I   II  III
            {"A", "A", "A", "A", "A", "A", "B"},     // 2~8
            {"A", "A", "A", "A", "A", "B", "C"},     // 9~15
            {"A", "A", "B", "B", "B", "C", "D"},     // 16~25
            {"A", "B", "B", "C", "C", "D", "E"},     // 26~50
            {"B", "B", "C", "D", "C", "E", "F"},     // 51~90
            {"B", "B", "C", "D", "D", "F", "G"},     // 91~150
            {"B", "C", "D", "E", "D", "G", "H"},     // 151~280
            {"B", "C", "D", "E", "F", "H", "J"},     // 281~500
            {"C", "C", "E", "F", "G", "J", "K"},     // 501~1200
            {"C", "D", "E", "G", "H", "K", "L"},     // 1201~3200
            {"C", "D", "F", "G", "J", "L", "M"},     // 3201~10000
            {"C", "D", "F", "H", "K", "M", "N"},     // 10001~35000
            {"D", "E", "G", "J", "L", "N", "P"},     // 35001~150000
            {"D", "E", "G", "J", "M", "P", "Q"},     // 150001~500000
            {"D", "E", "H", "K", "N", "Q", "R"},     // 500001 及以上
    };

    /**
     * 查某个批量、某个检验水平对应的样本量字码。
     *
     * @param lotQty 批量
     * @param level  检验水平（{@link #LEVELS} 之一）
     * @return 字码；水平不认识时返回 null
     */
    public static String letterOf(long lotQty, String level) {
        int col = levelIndex(level);
        if (col < 0) {
            return null;
        }
        int row = bandIndexOf(lotQty);
        if (row < 0) {
            return null;
        }
        return MAP[row][col];
    }

    /**
     * 这个检验水平下，某个批量对应的字码在 {@link ErpAqlStandardGrid} 里的行下标。
     * <p>
     * 注意：<b>字码行下标 ≠ 批量档下标</b>——同一个批量在不同水平下对应不同字码，
     * 而 Ac/Re 方案是按<b>字码</b>查的（表 2-A 的行就是字码）。
     * 这是"同一批量、不同水平、抽样量不同"的根本原因。
     */
    public static int gridRowOf(long lotQty, String level) {
        String letter = letterOf(lotQty, level);
        if (letter == null) {
            return -1;
        }
        for (int i = 0; i < ErpAqlStandardGrid.LETTER.length; i++) {
            if (ErpAqlStandardGrid.LETTER[i].equals(letter)) {
                return i;
            }
        }
        return -1;
    }

    /** 检验水平在 {@link #LEVELS} 里的列下标；不认识返回 -1 */
    public static int levelIndex(String level) {
        for (int i = 0; i < LEVELS.length; i++) {
            if (LEVELS[i].equals(level)) {
                return i;
            }
        }
        return -1;
    }

    /** 批量落在哪个批量档；小于最小批量返回 -1 */
    public static int bandIndexOf(long lotQty) {
        for (int i = 0; i < LOT_FROM.length; i++) {
            if (lotQty >= LOT_FROM[i] && lotQty <= LOT_TO[i]) {
                return i;
            }
        }
        // 超出表 1 覆盖范围：并入最后一档（500001 及以上）
        if (lotQty >= LOT_FROM[LOT_FROM.length - 1]) {
            return LOT_FROM.length - 1;
        }
        return -1;
    }

    /**
     * 这个检验水平下有哪些批量档（每个水平覆盖全部 16 档，只是字码不同）。
     * <p>
     * 批量档本身与水平无关（表 1 的行），所以这个方法对所有水平都返回同样的 16 档。
     * <b>保留它是为了让调用方不必知道"批量档与水平无关"这件事</b>——
     * 以后如果国标出了按水平分档的表，改这一处就行。
     */
    public static Map<String, Object> bandsOf(String level) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("level", level);
        m.put("bandCount", LOT_FROM.length);
        return m;
    }
}
