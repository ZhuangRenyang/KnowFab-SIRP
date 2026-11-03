package com.factory.hub.quality;

import java.math.BigDecimal;

/**
 * 检验单判定算法（QC-SPEC §5）——<b>纯函数，没有依赖，不查库</b>。
 * <p>
 * <b>为什么必须是纯函数</b>：判定结论是质量记录里最不能出错的一个字段。它要能被
 * 单测逐条盯住（每个分支、每个边界），也要能在事后拿单头快照离线重算一遍
 * （"这张单当时为什么判合格"必须答得出来）。挂在 service 里、依赖数据库和当前配置，
 * 这两件事都做不到。
 * <p>
 * <b>判定只认 {@code defectQty}（不合格品件数）</b>：
 * 一件产品同时有尺寸和外观两个缺陷，在 AQL 里仍然只算 <b>1 件</b>不合格品。
 * 按缺陷项数判会判反——实测例子：10 项 = 5 件，n=80/Ac=5，
 * 按件数判合格（5 ≤ 5），按项数判不合格（10 > 5）。
 * {@code defectCr/Ma/Mi} 只用于 CR 一票否决和缺陷分析（帕累托），<b>不参与 AQL 比较</b>。
 * <p>
 * <b>判定顺序</b>（顺序本身就是规则，不能换）：
 * <pre>
 *   1. 致命缺陷 &gt; 0                        → FAIL   （一票否决，不看 AQL）
 *   2. Ac = -1（零容忍）                    → 有 1 件就 FAIL，0 件 PASS
 *   3. 不合格品数 ≤ Ac                      → PASS
 *   4. 不合格品数 ≥ Re                      → FAIL
 *   5. 其余（Ac &lt; 数 &lt; Re，一次抽样里不可能）→ 抛异常，不猜
 * </pre>
 * <p>
 * <b>算法永远不产出 CONDITIONAL（让步接收）</b>：让不让步是商务决定，不是质量计算的结果。
 * 让它由算法产出，等于把经理该背的责任挪给一段代码。
 */
public final class ErpInspectionJudge {

    /** 判定结论。只有两种——CONDITIONAL 由人手动选，算法不产出 */
    public enum Verdict {
        PASS, FAIL
    }

    /* 判定原因码：写进检验单的判定说明，也供前端做文案映射 */
    /** 发现致命缺陷，一票否决 */
    public static final String WHY_CR_FOUND = "CR_FOUND";
    /** 零容忍方案（Ac = -1）下发现不合格品 */
    public static final String WHY_ZERO_TOLERANCE = "ZERO_TOLERANCE";
    /** 接收数为 0 的严格方案下发现不合格品 */
    public static final String WHY_AC_ZERO_EXCEEDED = "AC_ZERO_EXCEEDED";
    /** 不合格品数在接收数以内 */
    public static final String WHY_WITHIN_AC = "WITHIN_AC";
    /** 零容忍方案且一件不合格品都没有 */
    public static final String WHY_NO_DEFECT = "NO_DEFECT";
    /** 不合格品数达到拒收数 */
    public static final String WHY_OVER_RE = "OVER_RE";

    private ErpInspectionJudge() {
    }

    /**
     * 判定输入。
     *
     * @param defectCr       致命缺陷<b>项</b>数；null 按 0。&gt; 0 即整批拒收
     * @param defectQty      不合格<b>品</b>数（判定唯一依据），不能为空
     * @param ac             接收数；{@code -1} = 零容忍
     * @param re             拒收数；一次抽样里应恒为 {@code ac + 1}
     * @param fullInspection 是否全检。<b>不影响结论</b>：全检只是 n = 批量，
     *                       Ac/Re 照旧（国标原文"样本量等于或超过批量时执行 100% 检验"），
     *                       它只用于生成给人看的判定说明。加这个参数是为了让界面能写
     *                       "全检 20 件，1 件不合格，允许 0 件"，不是为了改判
     */
    public record Input(BigDecimal defectCr, BigDecimal defectQty,
                        int ac, int re, boolean fullInspection) {
    }

    /**
     * 判定结果。
     *
     * @param verdict 结论
     * @param why     原因码（上面那组常量）
     * @param text    一句人话，直接存进检验单的判定说明
     */
    public record Result(Verdict verdict, String why, String text) {
        public boolean passed() {
            return verdict == Verdict.PASS;
        }
    }

    /**
     * 判定。数据不自洽时抛异常，绝不猜一个结论。
     *
     * @throws IllegalArgumentException 不合格品数为空、Ac/Re 配置非法
     * @throws IllegalStateException    数据落进 Ac 与 Re 之间（一次抽样里不可能出现）
     */
    public static Result judge(Input in) {
        if (in == null) {
            throw new IllegalArgumentException("判定输入为空");
        }
        if (in.defectQty() == null) {
            throw new IllegalArgumentException("不合格品数不能为空——它是判定的唯一依据，缺了不能猜");
        }
        if (in.ac() < -1) {
            throw new IllegalArgumentException("接收数不能小于 -1（-1 表示零容忍），当前 " + in.ac());
        }
        if (in.re() <= in.ac()) {
            throw new IllegalArgumentException("拒收数必须大于接收数，当前 Ac=" + in.ac() + "、Re=" + in.re());
        }

        BigDecimal qty = in.defectQty().stripTrailingZeros();
        BigDecimal cr = in.defectCr() == null ? BigDecimal.ZERO : in.defectCr();

        // ① 致命缺陷一票否决：不走 AQL，哪怕 Ac = 10、只有 1 件 CR，也整批拒收
        if (cr.signum() > 0) {
            return new Result(Verdict.FAIL, WHY_CR_FOUND,
                    "发现致命缺陷 " + plain(cr) + " 项，一票否决，整批拒收（不适用 AQL 接收数）");
        }

        // ② 零容忍方案（Ac = -1）：一件都不许有。
        //    注意这里要单独处理"0 件"——若往下走到 ③，0 <= -1 是 false，
        //    再走到 ④ 又会 0 >= 1 false，最后落进"不自洽"抛异常，那是错的
        if (in.ac() == ErpAqlService.AC_ZERO_TOLERANCE) {
            if (qty.signum() > 0) {
                return new Result(Verdict.FAIL, WHY_ZERO_TOLERANCE,
                        "零容忍方案（接收数 " + in.ac() + "）下发现不合格品 " + plain(qty) + " 件，整批拒收");
            }
            return new Result(Verdict.PASS, WHY_NO_DEFECT, "零容忍方案，未发现不合格品，判定合格");
        }

        // ③ 在接收数以内 → 合格（含"Ac=0 且 0 件不合格"这个最常见的严格方案）
        if (qty.compareTo(BigDecimal.valueOf(in.ac())) <= 0) {
            return new Result(Verdict.PASS, WHY_WITHIN_AC,
                    label(in) + "不合格品 " + plain(qty) + " 件 ≤ 接收数 " + in.ac() + "，判定合格");
        }

        // ④ 达到拒收数 → 不合格
        if (qty.compareTo(BigDecimal.valueOf(in.re())) >= 0) {
            // Ac=0 的严格方案单独给个原因码：这类方案"一件都不许有"，
            // 说明里点出来，避免现场以为 Ac=0 和"未配置"是一回事
            String why = in.ac() == 0 ? WHY_AC_ZERO_EXCEEDED : WHY_OVER_RE;
            return new Result(Verdict.FAIL, why,
                    label(in) + "不合格品 " + plain(qty) + " 件 ≥ 拒收数 " + in.re()
                            + "（接收数 " + in.ac() + "），判定不合格");
        }

        // ⑤ 落进 (Ac, Re) 之间：一次抽样里 Re = Ac + 1，整数件数不可能落在这里。
        //    能走到这儿说明数据不自洽（件数带了小数、或 Re 与 Ac 不是差 1）
        throw new IllegalStateException("判定数据不自洽：不合格品 " + plain(qty)
                + " 件既不超过接收数 " + in.ac() + "、也没达到拒收数 " + in.re()
                + "，无法判定。请核对缺陷件数与方案的 Ac/Re（一次抽样里 Re 应等于 Ac + 1）");
    }

    /** 判定说明的前缀：全检和抽检结论一样，但现场看这句话时要能分清这张单是怎么检的 */
    private static String label(Input in) {
        return in.fullInspection() ? "全检：" : "抽检：";
    }

    /** 去掉多余的 0：8.0000 显示成 8 */
    private static String plain(BigDecimal v) {
        BigDecimal s = v.stripTrailingZeros();
        return s.scale() <= 0 ? s.toBigInteger().toString() : s.toPlainString();
    }
}
