package com.factory.hub.erp.quality.inspection;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 判定算法测试（QC-SPEC §5 / AC-04）——<b>全分支覆盖</b>。
 *
 * <p>判定结论是质量记录里最不能出错的一个字段：把不合格判成合格，货就发出去了。
 * 所以这里逐条盯住六个分支和每个边界（{@code = Ac} 判合格、{@code = Re} 判不合格），
 * 并且<b>故意不写"差不多"的断言</b>——每个断言都带上面向现场的数字。
 *
 * <p>另外两条是防回归的护栏：
 * <ul>
 *   <li>算法<b>永远不产出 CONDITIONAL</b>（让步是商务决定，不是质量计算的结果）；</li>
 *   <li>{@code fullInspection} <b>不改结论</b>。国标里全检只是 n = 批量，Ac/Re 照旧。
 *       加这条断言是为了防止以后有人"顺手"加一条"全检一律不合格"的规则。</li>
 * </ul>
 */
class ErpInspectionJudgeTest {

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    /** 抽检输入（不带致命缺陷） */
    private static ErpInspectionJudge.Input in(String defectQty, int ac, int re) {
        return new ErpInspectionJudge.Input(BigDecimal.ZERO, bd(defectQty), ac, re, false);
    }

    /* ==================== 分支 1：CR 一票否决 ==================== */

    @Test
    @DisplayName("分支1：致命缺陷 > 0 → 整批拒收，哪怕不合格品数远小于 Ac")
    void criticalVetoesEverything() {
        // 抽 80 件、Ac=10，只发现 1 件不合格品，但其中含 1 项致命缺陷
        ErpInspectionJudge.Result r = ErpInspectionJudge.judge(new ErpInspectionJudge.Input(
                bd("1"), bd("1"), 10, 11, false));
        assertEquals(ErpInspectionJudge.Verdict.FAIL, r.verdict());
        assertEquals(ErpInspectionJudge.WHY_CR_FOUND, r.why());
        assertTrue(r.text().contains("一票否决"), r.text());
    }

    @Test
    @DisplayName("分支1：致命缺陷判定优先于零容忍分支（Ac=-1 时同样先报 CR）")
    void criticalWinsOverZeroTolerance() {
        ErpInspectionJudge.Result r = ErpInspectionJudge.judge(new ErpInspectionJudge.Input(
                bd("2"), bd("1"), -1, 1, false));
        assertEquals(ErpInspectionJudge.WHY_CR_FOUND, r.why());
    }

    /* ==================== 分支 2：零容忍 Ac = -1 ==================== */

    @Test
    @DisplayName("分支2：Ac=-1 且 0 件不合格 → 合格（零容忍被满足，不能落进「不自洽」）")
    void zeroToleranceSatisfied() {
        ErpInspectionJudge.Result r = ErpInspectionJudge.judge(in("0", -1, 1));
        assertEquals(ErpInspectionJudge.Verdict.PASS, r.verdict());
        assertEquals(ErpInspectionJudge.WHY_NO_DEFECT, r.why());
    }

    @Test
    @DisplayName("分支2：Ac=-1 且 1 件不合格 → 拒收（与普通 Ac=0 的原因码分开）")
    void zeroToleranceViolated() {
        ErpInspectionJudge.Result r = ErpInspectionJudge.judge(in("1", -1, 1));
        assertEquals(ErpInspectionJudge.Verdict.FAIL, r.verdict());
        assertEquals(ErpInspectionJudge.WHY_ZERO_TOLERANCE, r.why());
        assertTrue(r.text().contains("零容忍"), r.text());
    }

    /* ==================== 分支 3 / 4：Ac 与 Re 边界 ==================== */

    @Test
    @DisplayName("分支3：不合格品数 = Ac → 合格（边界含等号，这是最常被写错的一格）")
    void equalsAcPasses() {
        // 锚点格子的真实场景：n=80、AQL 1.0 → Ac=2、Re=3
        ErpInspectionJudge.Result r = ErpInspectionJudge.judge(in("2", 2, 3));
        assertEquals(ErpInspectionJudge.Verdict.PASS, r.verdict());
        assertEquals(ErpInspectionJudge.WHY_WITHIN_AC, r.why());
        assertTrue(r.text().contains("2"), r.text());
    }

    @Test
    @DisplayName("分支4：不合格品数 = Re → 不合格（边界含等号）")
    void equalsReFails() {
        ErpInspectionJudge.Result r = ErpInspectionJudge.judge(in("3", 2, 3));
        assertEquals(ErpInspectionJudge.Verdict.FAIL, r.verdict());
        assertEquals(ErpInspectionJudge.WHY_OVER_RE, r.why());
    }

    @Test
    @DisplayName("分支4：远超 Re 也判不合格，且说明里写上真实件数")
    void farOverReFails() {
        ErpInspectionJudge.Result r = ErpInspectionJudge.judge(in("17", 2, 3));
        assertEquals(ErpInspectionJudge.Verdict.FAIL, r.verdict());
        assertTrue(r.text().contains("17"), r.text());
    }

    @Test
    @DisplayName("Ac=0 的严格方案：0 件合格、1 件不合格，原因码单独区分")
    void acZeroPlan() {
        assertEquals(ErpInspectionJudge.Verdict.PASS,
                ErpInspectionJudge.judge(in("0", 0, 1)).verdict());
        ErpInspectionJudge.Result fail = ErpInspectionJudge.judge(in("1", 0, 1));
        assertEquals(ErpInspectionJudge.Verdict.FAIL, fail.verdict());
        assertEquals(ErpInspectionJudge.WHY_AC_ZERO_EXCEEDED, fail.why(),
                "Ac=0 与 Ac=-1 是两回事：前者是「一件都不许有」的正常方案，后者是零容忍");
    }

    /* ==================== 分支 5：数据不自洽 → 抛异常 ==================== */

    @Test
    @DisplayName("分支5：件数落进 (Ac, Re) 之间 → 抛异常，不猜结论")
    void inconsistentDataThrows() {
        // 不合格品数带了小数（件数不该是小数），落在 Ac=2 与 Re=3 之间
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> ErpInspectionJudge.judge(in("2.5", 2, 3)));
        assertTrue(ex.getMessage().contains("不自洽"), ex.getMessage());

        // Re 与 Ac 不符合"差 1"的约定时同样会落进这个区间
        assertThrows(IllegalStateException.class,
                () -> ErpInspectionJudge.judge(in("5", 2, 9)));
    }

    @Test
    @DisplayName("输入非法直接拒绝：不合格品数为空、Ac<-1、Re<=Ac")
    void badInputsRejected() {
        assertThrows(IllegalArgumentException.class, () -> ErpInspectionJudge.judge(
                new ErpInspectionJudge.Input(BigDecimal.ZERO, null, 2, 3, false)));
        assertThrows(IllegalArgumentException.class,
                () -> ErpInspectionJudge.judge(in("0", -2, 1)));
        assertThrows(IllegalArgumentException.class,
                () -> ErpInspectionJudge.judge(in("0", 2, 2)));
    }

    /* ==================== 两条护栏 ==================== */

    @Test
    @DisplayName("算法永远不产出 CONDITIONAL：让步是商务决定，不是质量计算结果")
    void neverProducesConditional() {
        assertEquals(2, ErpInspectionJudge.Verdict.values().length,
                "Verdict 只能有 PASS / FAIL；CONDITIONAL 由人在判定页手动选");
        for (String why : new String[]{ErpInspectionJudge.WHY_CR_FOUND,
                ErpInspectionJudge.WHY_ZERO_TOLERANCE, ErpInspectionJudge.WHY_AC_ZERO_EXCEEDED,
                ErpInspectionJudge.WHY_WITHIN_AC, ErpInspectionJudge.WHY_NO_DEFECT,
                ErpInspectionJudge.WHY_OVER_RE}) {
            assertFalse(why.contains("CONDITIONAL"));
        }
    }

    @Test
    @DisplayName("fullInspection 不改变结论，只改变判定说明的措辞")
    void fullInspectionDoesNotChangeVerdict() {
        ErpInspectionJudge.Result sampled = ErpInspectionJudge.judge(
                new ErpInspectionJudge.Input(BigDecimal.ZERO, bd("1"), 2, 3, false));
        ErpInspectionJudge.Result full = ErpInspectionJudge.judge(
                new ErpInspectionJudge.Input(BigDecimal.ZERO, bd("1"), 2, 3, true));
        assertEquals(sampled.verdict(), full.verdict(),
                "全检只是 n = 批量，Ac/Re 照旧；不能因为全检就改判");
        assertTrue(sampled.text().startsWith("抽检"), sampled.text());
        assertTrue(full.text().startsWith("全检"), full.text());
    }

    @Test
    @DisplayName("缺陷项数按件数判：10 项 = 5 件，n=80/Ac=5 时合格（按项数判会错判不合格）")
    void judgesByPieceNotByItem() {
        // Spec §3.3.1 的实测例子：一件产品可能同时有尺寸和外观两个缺陷
        ErpInspectionJudge.Result r = ErpInspectionJudge.judge(new ErpInspectionJudge.Input(
                BigDecimal.ZERO, bd("5"),    // 不合格“品”5 件
                5, 6, false));               // 10 个缺陷“项”不参与比较
        assertEquals(ErpInspectionJudge.Verdict.PASS, r.verdict(),
                "5 件 ≤ Ac=5，判合格。如果拿 10 个缺陷项去比就会错判不合格——结论反转");
    }

    @Test
    @DisplayName("小数批量按小数比较：0.0001 件也算有不良（不能被四舍五入吃掉）")
    void tinyDecimalDefectCounts() {
        ErpInspectionJudge.Result r = ErpInspectionJudge.judge(in("0.0001", -1, 1));
        assertEquals(ErpInspectionJudge.Verdict.FAIL, r.verdict(),
                "零容忍下哪怕 0.0001 件也要拒收，不能因为看起来像 0 就放过");
    }
}
