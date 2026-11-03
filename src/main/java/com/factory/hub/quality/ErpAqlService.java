package com.factory.hub.quality;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AQL 抽样方案查询（GB/T 2828.1-2012 表 2-A）。
 * <p>
 * <b>这个类只做一件事</b>：把「批量 + 检验水平 + AQL」翻成「抽几件、收几件、拒几件」。
 * 判定算法不在这里（下一轮 {@code ErpQcJudge}），因为判定要能拿单头快照离线重算，
 * 而查表只发生在建单/提交那一刻。
 * <p>
 * <b>查不到就抛异常，绝不返回默认值。</b>
 * 抽样方案缺一行时给个"通用默认 Ac=1"看起来系统还能跑，但客户拿到的接收数与国标不符，
 * 判合格的一批货可能本该拒收。报错会有人来补那一行；静默给默认值，没人会去补。
 * 错误信息带齐四要素（水平/严格度/批量/AQL）并写明去哪补，现场不用回来问"缺哪一行"。
 * <p>
 * <b>两种退化情形按国标处理</b>：
 * <ol>
 *   <li>批量小于最小档（2 件）→ 全检（n = 批量，Ac = 0，Re = 1）；</li>
 *   <li>算出的样本量大于等于批量 → 全检（国标原文："样本量等于或超过批量时，执行 100% 检验"），
 *       Ac/Re 沿用方案表的值不变。</li>
 * </ol>
 */
@Service
public class ErpAqlService {

    /**
     * {@code Ac = -1} 表示<b>真正的零容忍</b>（CR 致命缺陷）：样品里发现 1 件就整批拒收。
     * <p>
     * 与 {@code Ac = 0} 必须区分开：{@code 0} 是"这一格允许一件都没有"的正常接收数，
     * 走 AQL 判定分支；{@code -1} 是"这一格不看 AQL"，走一票否决分支。
     * 混用会让致命缺陷退化成普通缺陷判定。
     */
    public static final int AC_ZERO_TOLERANCE = -1;
    /** 零容忍时的拒收数：发现 1 件即拒 */
    public static final int RE_ZERO_TOLERANCE = 1;

    /** 国标表 1 的最小批量档下限；比它还小的批量只能全检 */
    private static final int MIN_LOT = 2;

    /**
     * AQL 上限 10。超过它国标的计数口径就从「不合格品百分数（按件）」变成
     * 「每百单位不合格数（按项）」，与本系统的判定口径（只认件数）不同，必须拒绝。
     */
    private static final BigDecimal MAX_AQL = new BigDecimal("10");

    private final ErpQcAqlPlanRepository planRepo;
    private final com.factory.hub.audit.AuditService audit;

    public ErpAqlService(ErpQcAqlPlanRepository planRepo, com.factory.hub.audit.AuditService audit) {
        this.planRepo = planRepo;
        this.audit = audit;
    }

    /**
     * 方案表里实际有哪些「检验水平 + 严格度」组合，以及各自的方案条数。
     * <p>
     * <b>前端抽样下拉按这个来</b>：界面不能列出没有数据的选择项——
     * 用户选了必然撞上"没配方案"，那是界面在制造必然的失败。
     * <p>
     * 由接口返回真实存在的组合，主数据补齐后前端自动跟着多出选项，
     * <b>不用改前端代码</b>。
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> availableCombos() {
        List<Map<String, Object>> combos = new ArrayList<>();
        for (Object[] row : planRepo.listAvailableCombos()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("level", String.valueOf(row[0]));
            m.put("strictLevel", String.valueOf(row[1]));
            m.put("planCount", row[2] == null ? 0 : ((Number) row[2]).intValue());
            combos.add(m);
        }
        return combos;
    }

    /**
     * 抽样方案结果。
     *
     * @param codeLetter     样本量字码（批量小于最小档时为 null）
     * @param sampleSize     实际要抽的件数（全检时 = 批量）
     * @param ac             接收数；{@link #AC_ZERO_TOLERANCE} = 零容忍
     * @param re             拒收数；一次抽样恒为 ac + 1
     * @param planId         命中的方案行 id，供"这份方案来自哪一格"追溯
     * @param fullInspection 是否全检
     * @param note           给前端显示的一句人话（为什么全检），不需要解释时为 null
     */
    public record Resolved(String codeLetter, int sampleSize, int ac, int re,
                           Long planId, boolean fullInspection, String reasonCode) {
    }

    /** 原因代号：批量小于最小抽样档 → 全检 */
    public static final String REASON_LOT_TOO_SMALL = "LOT_TOO_SMALL";
    /** 原因代号：方案样本量不小于批量 → 全检（国标：样本量等于或超过批量时执行 100% 检验） */
    public static final String REASON_SAMPLE_GE_LOT = "SAMPLE_GE_LOT";
    /** 原因代号：CR 致命缺陷 → 零容忍（Ac=-1），不走抽样表 */
    public static final String REASON_ZERO_TOLERANCE = "ZERO_TOLERANCE";

    /**
     * 查不到方案时抛这个（而不是普通 IllegalArgumentException），
     * 好让接口层能给它一个独立的错误码：前端要靠它区分
     * "方案缺一行（给两个出路：去补方案 / 临时手填样本量）" 和 "参数填错了"。
     * <p>
     * 它仍是 {@link IllegalArgumentException} 的子类，其他调用方不用改。
     */
    public static final class NoPlanException extends IllegalArgumentException {
        public NoPlanException(String message) {
            super(message);
        }
    }

    /* ==================== 查表 ==================== */

    /** 默认水平（一般 II / 正常检验）的便捷重载 */
    public Resolved resolve(BigDecimal lotQty, BigDecimal aql) {
        return resolve(ErpQcAqlPlan.LEVEL_II, ErpQcAqlPlan.STRICT_NORMAL, lotQty, aql);
    }

    /** 查表。查不到抛 {@link IllegalArgumentException}，消息直接给现场看。 */
    @Transactional(readOnly = true)
    public Resolved resolve(String level, String strictLevel, BigDecimal lotQty, BigDecimal aql) {
        String lv = normalizeLevel(level);
        String st = normalizeStrict(strictLevel);
        int lot = requirePositiveLot(lotQty);
        BigDecimal a = requireAql(aql);

        // ① 批量比最小档还小：没有抽样方案可用，只能全检
        if (lot < MIN_LOT) {
            return new Resolved(null, lot, 0, 1, null, true, REASON_LOT_TOO_SMALL);
        }

        ErpQcAqlPlan plan = planRepo.findPlan(lv, st, lot, a)
                .orElseThrow(() -> new NoPlanException(missingMessage(lv, st, lot, a)));

        // ② 样本量 >= 批量：国标要求 100% 检验，Ac/Re 不变
        if (plan.getSampleSize() >= lot) {
            return new Resolved(plan.getCodeLetter(), lot, plan.getAc(), plan.getRe(),
                    plan.getId(), true, REASON_SAMPLE_GE_LOT);
        }
        return new Resolved(plan.getCodeLetter(), plan.getSampleSize(), plan.getAc(), plan.getRe(),
                plan.getId(), false, null);
    }

    /**
     * 按缺陷分级查表：{@code CR} 一律换成零容忍（Ac=-1 / Re=1），其余走 AQL。
     * <p>
     * CR 的样本量仍取方案表的 n（"抽多少件"跟批量和检验水平有关，跟致命缺陷无关），
     * 只把接收数换成 -1 —— 一件都不许有。这就是"致命缺陷一票否决"在数据上的表达。
     */
    @Transactional(readOnly = true)
    public Resolved resolveForSeverity(String level, String strictLevel, BigDecimal lotQty,
                                       BigDecimal aql, String severity) {
        Resolved base = resolve(level, strictLevel, lotQty, aql);
        if (!ErpQcDefectCode.SEV_CR.equals(severity)) {
            return base;
        }
        return new Resolved(base.codeLetter(), base.sampleSize(),
                AC_ZERO_TOLERANCE, RE_ZERO_TOLERANCE, base.planId(), base.fullInspection(),
                REASON_ZERO_TOLERANCE);
    }

    /* ==================== 方案表维护 ==================== */

    @Transactional(readOnly = true)
    public List<ErpQcAqlPlan> allPlans() {
        return planRepo.findAllOrdered();
    }

    /**
     * 方案行保存（新增或改）。校验区间不重叠，并强制 {@code re == ac + 1}——
     * 一次抽样里这两个数不是自由变量，让用户手填只会填错。
     */
    @Transactional
    public ErpQcAqlPlan savePlan(ErpQcAqlPlan in) {
        if (in == null) {
            throw new IllegalArgumentException("方案内容为空");
        }
        String lv = normalizeLevel(in.getLevel());
        String st = normalizeStrict(in.getStrictLevel());
        if (in.getLotFrom() <= 0) {
            throw new IllegalArgumentException("批量下限必须大于 0");
        }
        if (in.getLotTo() < in.getLotFrom()) {
            throw new IllegalArgumentException("批量上限不能小于下限");
        }
        if (in.getSampleSize() <= 0) {
            throw new IllegalArgumentException("样本量必须大于 0");
        }
        if (in.getAc() < AC_ZERO_TOLERANCE) {
            throw new IllegalArgumentException("接收数不能小于 -1（-1 表示零容忍）");
        }
        BigDecimal a = requireAql(in.getAql());

        in.setLevel(lv);
        in.setStrictLevel(st);
        in.setAql(a);
        in.setRe(in.getAc() + 1);
        in.setUpdatedAt(System.currentTimeMillis());

        // 同一格不允许两行：区间重叠也算重复，否则查表会随机命中其中一行
        for (ErpQcAqlPlan p : planRepo.findByLevelAndStrictLevelOrderByAqlAscLotFromAsc(lv, st)) {
            if (in.getId() != null && in.getId().equals(p.getId())) {
                continue;
            }
            if (p.getAql().compareTo(a) != 0) {
                continue;
            }
            if (in.getLotFrom() <= p.getLotTo() && p.getLotFrom() <= in.getLotTo()) {
                throw new IllegalArgumentException("与已有方案重叠：批量 "
                        + p.getLotFrom() + "~" + p.getLotTo() + "、AQL " + a.toPlainString()
                        + "（请先修改或删除那一行）");
            }
        }
        if (in.getId() == null) {
            in.setCreatedAt(System.currentTimeMillis());
        }
        return planRepo.save(in);
    }

    @Transactional
    public void deletePlan(Long id) {
        if (!planRepo.existsById(id)) {
            throw new IllegalArgumentException("方案行不存在，可能已被删除");
        }
        planRepo.deleteById(id);
    }

    /**
     * 确认某一行已与纸本国标核对（{@code verified} 置 true，记核对人与时间，并写审计）。
     * <p>
     * 播种表里 AQL 0.65 / 6.5 两列共 22 格是"由格子规律推算"的，页面标着「推算」。
     * 这个动作就是让人核完纸本后来点一下把它转正——<b>审计里记下是谁在什么时候认了这格</b>，
     * 因为从此以后这一格会被当成国标直接用于判定。
     */
    @Transactional
    public ErpQcAqlPlan verifyPlan(Long id) {
        ErpQcAqlPlan p = planRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("方案行不存在，可能已被删除：" + id));
        if (p.isVerified()) {
            return p;
        }
        p.setVerified(true);
        p.setVerifiedBy(currentOperator());
        p.setVerifiedAt(System.currentTimeMillis());
        p.setUpdatedAt(p.getVerifiedAt());
        p = planRepo.save(p);
        audit.ok(com.factory.hub.audit.AuditLog.CAT_QUALITY, com.factory.hub.audit.AuditLog.ACT_UPDATE,
                String.valueOf(id),
                p.getLevel() + "/" + p.getStrictLevel() + " 批量 " + p.getLotFrom() + "~" + p.getLotTo()
                        + " AQL " + p.getAql().stripTrailingZeros().toPlainString(),
                "确认抽样方案已与纸本 GB/T 2828.1 核对（核对人 " + p.getVerifiedBy() + "）");
        return p;
    }

    /** 当前操作人：核对人要落到 {@code verified_by} 上，审计追得到人 */
    private static String currentOperator() {
        try {
            org.springframework.security.core.Authentication a =
                    org.springframework.security.core.context.SecurityContextHolder
                            .getContext().getAuthentication();
            if (a != null && a.getName() != null && !a.getName().isBlank()
                    && !"anonymousUser".equals(a.getName())) {
                return a.getName();
            }
        } catch (Exception ignored) {
            // 没有安全上下文（定时任务/单测）时不影响主流程
        }
        return "system";
    }

    /** 还有多少格没核对过（页面顶部提示"共 N 格待核对"） */
    @Transactional(readOnly = true)
    public long unverifiedCount() {
        return planRepo.findAllOrdered().stream().filter(p -> !p.isVerified()).count();
    }

    /* ==================== 内部 ==================== */

    /**
     * 水平归一化：接受 {@code GENERAL_II} 与现场的 {@code II} / {@code 2} 两种写法。
     * 空值按默认（一般 II）——绝大多数场景就是 II。
     */
    public static String normalizeLevel(String raw) {
        if (raw == null || raw.isBlank()) {
            return ErpQcAqlPlan.LEVEL_II;
        }
        String v = raw.trim().toUpperCase().replace("-", "_").replace(" ", "_");
        return switch (v) {
            // 特殊检验水平 S-1~S-4（国标表 1 的前四列，鉴别力从低到高）
            case "S1", "S_1", "SPECIAL_S1" -> ErpQcAqlPlan.LEVEL_S1;
            case "S2", "S_2", "SPECIAL_S2" -> ErpQcAqlPlan.LEVEL_S2;
            case "S3", "S_3", "SPECIAL_S3" -> ErpQcAqlPlan.LEVEL_S3;
            case "S4", "S_4", "SPECIAL_S4" -> ErpQcAqlPlan.LEVEL_S4;
            case "I", "1", "GENERAL_1", "GENERAL_I" -> ErpQcAqlPlan.LEVEL_I;
            case "II", "2", "GENERAL_2", "GENERAL_II" -> ErpQcAqlPlan.LEVEL_II;
            case "III", "3", "GENERAL_3", "GENERAL_III" -> ErpQcAqlPlan.LEVEL_III;
            default -> throw new IllegalArgumentException(
                    "不支持的检验水平：" + raw + "（可用 "
                            + String.join(" / ", ErpAqlLevelMap.LEVELS) + "）");
        };
    }

    /** 严格度归一化；空值 = 正常检验（国标规定的初始状态） */
    public static String normalizeStrict(String raw) {
        if (raw == null || raw.isBlank()) {
            return ErpQcAqlPlan.STRICT_NORMAL;
        }
        String v = raw.trim().toUpperCase();
        return switch (v) {
            case "NORMAL", "N" -> ErpQcAqlPlan.STRICT_NORMAL;
            case "TIGHTENED", "T" -> ErpQcAqlPlan.STRICT_TIGHTENED;
            case "REDUCED", "R" -> ErpQcAqlPlan.STRICT_REDUCED;
            default -> throw new IllegalArgumentException(
                    "不支持的严格度：" + raw + "（可用 NORMAL / TIGHTENED / REDUCED）");
        };
    }

    private static int requirePositiveLot(BigDecimal lotQty) {
        if (lotQty == null) {
            throw new IllegalArgumentException("送检批量不能为空");
        }
        BigDecimal v = lotQty.setScale(0, RoundingMode.DOWN);
        if (v.signum() <= 0) {
            throw new IllegalArgumentException("送检批量必须大于 0（当前 " + lotQty.toPlainString() + "）");
        }
        return v.min(BigDecimal.valueOf(Integer.MAX_VALUE)).intValue();
    }

    private static BigDecimal requireAql(BigDecimal aql) {
        if (aql == null) {
            throw new IllegalArgumentException("AQL 不能为空（常用 0.65 / 1.0 / 1.5 / 2.5 / 4.0 / 6.5）");
        }
        if (aql.signum() <= 0) {
            throw new IllegalArgumentException("AQL 必须大于 0（当前 " + aql.toPlainString() + "）");
        }
        // AQL > 10 必须拒绝，不做静默兼容：GB/T 2828.1 的计数口径由 AQL 值决定——
        // AQL ≤ 10 用「不合格品百分数」（按件判），AQL > 10 要换成「每百单位不合格数」（按项判）。
        // 同一批货两种口径能算出相反结论，而本系统判定只认件数（defect_qty），
        // 收下一个 >10 的 AQL 等于让它按错的口径判。
        if (aql.compareTo(MAX_AQL) > 0) {
            throw new IllegalArgumentException("AQL 不能大于 " + MAX_AQL.toPlainString()
                    + "（当前 " + aql.stripTrailingZeros().toPlainString() + "）："
                    + "GB/T 2828.1 里 AQL ≤ 10 用不合格品百分数（按件判），AQL > 10 要换成"
                    + "每百单位不合格数（按项判），两种口径能算出相反结论。"
                    + "本系统按件判，请选 ≤ 10 的 AQL（常用 0.65 / 1.0 / 1.5 / 2.5 / 4.0 / 6.5）");
        }
        // 统一成 scale=4，保证与方案表里存的值能精确相等（查表用的是等值比较）
        return aql.setScale(4, RoundingMode.HALF_UP);
    }

    private static String missingMessage(String level, String strict, int lot, BigDecimal aql) {
        String strictText = switch (strict) {
            case ErpQcAqlPlan.STRICT_TIGHTENED -> "加严检验";
            case ErpQcAqlPlan.STRICT_REDUCED -> "放宽检验";
            default -> "正常检验";
        };
        String levelText = switch (level) {
            case ErpQcAqlPlan.LEVEL_I -> "一般检验水平 I";
            case ErpQcAqlPlan.LEVEL_III -> "一般检验水平 III";
            default -> "一般检验水平 II";
        };
        return "未维护抽样方案：" + levelText + "、" + strictText + "、批量 " + lot
                + "、AQL " + aql.stripTrailingZeros().toPlainString()
                + "。请到「质检 → 质量字典 → 抽样方案」补一行（或点「恢复国标默认」）";
    }
}
