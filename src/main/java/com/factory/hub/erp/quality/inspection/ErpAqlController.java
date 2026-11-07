package com.factory.hub.erp.quality.inspection;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.factory.hub.erp.ErpQcBody;

/**
 * AQL 抽样方案接口（QC-SPEC §4.2）。
 * <p>
 * <b>权限</b>：读 = 登录即可（建单要算样本量），写 = ADMIN/MANAGER（沿用 {@code /api/erp/**}）。
 * <p>
 * <b>失败一律 200 + {@code ok:false}</b>，但"缺方案"给独立的 {@code code}：
 * 检验员建单时查不到方案，页面不能只说"未维护"就卡死（货已在门口等着判），
 * 要给出两个出路（去补方案 / 临时手填样本量）。前端靠 {@code code = NO_AQL_PLAN} 区分
 * "方案缺失"和"参数填错"——后者只需要改输入框。
 */
@RestController
@RequestMapping("/api/erp/qc/aql")
public class ErpAqlController {

    private final ErpAqlService service;
    private final ErpAqlSeeder seeder;

    public ErpAqlController(ErpAqlService service, ErpAqlSeeder seeder) {
        this.service = service;
        this.seeder = seeder;
    }

    /**
     * 算样本量 n 与 Ac/Re。
     * 查不到方案时 {@code ok:false, code:NO_AQL_PLAN}，<b>绝不返回默认值</b>——
     * 返回一组假参数比报错严重得多，用户会拿着它去判定。
     */
    @GetMapping("/resolve")
    public Map<String, Object> resolve(@RequestParam(required = false) String level,
                                       @RequestParam(required = false) String strictLevel,
                                       @RequestParam BigDecimal lotQty,
                                       @RequestParam BigDecimal aql) {
        try {
            ErpAqlService.Resolved r = service.resolve(level, strictLevel, lotQty, aql);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", true);
            m.put("codeLetter", r.codeLetter());
            m.put("sampleSize", r.sampleSize());
            m.put("ac", r.ac());
            m.put("re", r.re());
            m.put("planId", r.planId());
            m.put("fullInspection", r.fullInspection());
            m.put("reasonCode", r.reasonCode());
            // Ac=-1 是零容忍，前端要显示成「0 件（零容忍）」，不能和普通的 0 长得一样
            m.put("zeroTolerance", r.ac() == ErpAqlService.AC_ZERO_TOLERANCE);
            return m;
        } catch (ErpAqlService.NoPlanException e) {
            return fail("NO_AQL_PLAN", e);
        } catch (Exception e) {
            return fail("BAD_REQUEST", e);
        }
    }

    /** 方案表。默认全给；onlyUnverified=true 只看还没和纸本核对过的格子 */
    /**
     * 方案表里实际有哪些「检验水平 + 严格度」组合。
     * <p>
     * <b>为什么要有这个接口</b>：抽样界面的两个下拉（检验水平 / 严格度）
     * 如果列全 GB/T 2828.1 的 8 个水平 × 3 个严格度，而方案表里只导入了
     * 一般 II / 正常检验 —— 用户选其它组合就必然撞上"没配方案"。
     * <b>界面在制造必然的失败</b>。
     * <p>
     * 由接口返回真实存在的组合，主数据补齐后前端自动跟着多出选项，
     * <b>不用改前端代码</b>。每个组合带方案数，界面可以显示"共 N 条"。
     */
    @GetMapping("/levels")
    public Map<String, Object> levels() {
        try {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("combos", service.availableCombos());
            return out;
        } catch (Exception e) {
            return ErpQcBody.fail(e);
        }
    }

    @GetMapping("/plans")
    public Map<String, Object> plans(@RequestParam(required = false) String level,
                                     @RequestParam(required = false) String strictLevel,
                                     @RequestParam(defaultValue = "false") boolean onlyUnverified) {
        try {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (ErpQcAqlPlan p : service.allPlans()) {
                if (level != null && !level.isBlank()
                        && !ErpAqlService.normalizeLevel(level).equals(p.getLevel())) {
                    continue;
                }
                if (strictLevel != null && !strictLevel.isBlank()
                        && !ErpAqlService.normalizeStrict(strictLevel).equals(p.getStrictLevel())) {
                    continue;
                }
                if (onlyUnverified && p.isVerified()) {
                    continue;
                }
                rows.add(toMap(p));
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("total", rows.size());
            out.put("unverifiedCount", service.unverifiedCount());
            out.put("rows", rows);
            return out;
        } catch (Exception e) {
            return fail("BAD_REQUEST", e);
        }
    }

    @PostMapping("/plans")
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        try {
            ErpQcAqlPlan p = service.savePlan(fromBody(new ErpQcAqlPlan(), body));
            return ok("已新增方案", p);
        } catch (Exception e) {
            return fail("BAD_REQUEST", e);
        }
    }

    @PutMapping("/plans/{id}")
    public Map<String, Object> update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        try {
            ErpQcAqlPlan in = new ErpQcAqlPlan();
            in.setId(id);
            ErpQcAqlPlan p = service.savePlan(fromBody(in, body));
            return ok("已保存方案", p);
        } catch (Exception e) {
            return fail("BAD_REQUEST", e);
        }
    }

    @DeleteMapping("/plans/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        try {
            service.deletePlan(id);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", true);
            m.put("message", "已删除方案行");
            return m;
        } catch (Exception e) {
            return fail("BAD_REQUEST", e);
        }
    }

    /** 确认这一格已与纸本国标核对过（把 verified 置 true，写审计） */
    @PostMapping("/plans/{id}/verify")
    public Map<String, Object> verify(@PathVariable Long id) {
        try {
            return ok("已标记为已核对", service.verifyPlan(id));
        } catch (Exception e) {
            return fail("BAD_REQUEST", e);
        }
    }

    /** 恢复国标默认：只重播 GENERAL_II × NORMAL，客户自己加的加严/放宽方案不碰 */
    @PostMapping("/reseed")
    public Map<String, Object> reseed() {
        try {
            int n = seeder.reseed();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", true);
            m.put("message", "已恢复国标默认，重播 " + n + " 行（其中 "
                    + service.unverifiedCount() + " 行是推算的，请人工核对纸本后确认）");
            m.put("rows", n);
            return m;
        } catch (Exception e) {
            return fail("BAD_REQUEST", e);
        }
    }

    /* ==================== 内部 ==================== */

    private static Map<String, Object> toMap(ErpQcAqlPlan p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("level", p.getLevel());
        m.put("strictLevel", p.getStrictLevel());
        m.put("lotFrom", p.getLotFrom());
        m.put("lotTo", p.getLotTo());
        m.put("codeLetter", p.getCodeLetter());
        m.put("sampleSize", p.getSampleSize());
        m.put("ac", p.getAc());
        m.put("re", p.getRe());
        m.put("aql", p.getAql());
        // false = 由格子规律推算、还没对纸本核过；页面显示「推算」标记 + 确认按钮
        m.put("verified", p.isVerified());
        // 核对留痕：谁核的、什么时候核的（审计要能追到人）
        m.put("verifiedBy", p.getVerifiedBy());
        m.put("verifiedAt", p.getVerifiedAt());
        m.put("zeroTolerance", p.isZeroTolerance());
        m.put("remark", p.getRemark());
        return m;
    }

    private static ErpQcAqlPlan fromBody(ErpQcAqlPlan p, Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        if (b.get("level") != null) {
            p.setLevel(str(b.get("level")));
        }
        if (b.get("strictLevel") != null) {
            p.setStrictLevel(str(b.get("strictLevel")));
        }
        if (b.get("lotFrom") != null) {
            p.setLotFrom(integer(b.get("lotFrom")));
        }
        if (b.get("lotTo") != null) {
            p.setLotTo(integer(b.get("lotTo")));
        }
        if (b.get("codeLetter") != null) {
            p.setCodeLetter(str(b.get("codeLetter")));
        }
        if (b.get("sampleSize") != null) {
            p.setSampleSize(integer(b.get("sampleSize")));
        }
        if (b.get("ac") != null) {
            p.setAc(integer(b.get("ac")));
        }
        if (b.get("aql") != null) {
            p.setAql(dec(b.get("aql")));
        }
        if (b.get("remark") != null) {
            p.setRemark(str(b.get("remark")));
        }
        // 人工新增/修改的行视为"人已经看过"，直接 verified=true；
        // 推算标记只属于播种进来的那批
        if (b.get("verified") != null) {
            p.setVerified(Boolean.parseBoolean(String.valueOf(b.get("verified"))));
        } else if (p.getId() == null) {
            p.setVerified(true);
        }
        return p;
    }

    private static Map<String, Object> ok(String message, ErpQcAqlPlan p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("message", message);
        if (p != null) {
            m.put("data", toMap(p));
        }
        return m;
    }

    private static Map<String, Object> fail(String code, Exception e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", false);
        m.put("code", code);
        m.put("message", e.getMessage() == null ? "操作失败" : e.getMessage());
        return m;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static int integer(Object o) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static BigDecimal dec(Object o) {
        if (o instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : new BigDecimal(s);
    }
}
