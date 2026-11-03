package com.factory.hub.quality;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 缺陷分类（质量字典）维护接口。
 * <p>
 * <b>权限</b>：读 = 登录即可（检验员建单要选分类），写 = ADMIN / MANAGER
 * （沿用 {@code /api/erp/**} 的既有规则，改字典等于改判定依据，不能让所有人动）。
 * <p>
 * <b>失败一律 200 + {@code ok:false}</b>，与全站约定一致：前端要把后端那句人话直接弹出来。
 * "已被 12 条不良记录引用，请改为停用"这句话弹给现场，比一个 409 有用得多。
 */
@RestController
@RequestMapping("/api/erp/qc/defect-codes")
public class ErpQcDefectCodeController {

    private final ErpQcDefectCodeService service;

    public ErpQcDefectCodeController(ErpQcDefectCodeService service) {
        this.service = service;
    }

    /** 列表：{@code q} 关键字 / {@code severity} 分级 / {@code enabled} 启用状态 */
    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) String q,
                                    @RequestParam(required = false) String severity,
                                    @RequestParam(required = false) Boolean enabled) {
        try {
            List<Map<String, Object>> rows = service.list(q, severity, enabled);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("total", rows.size());
            out.put("rows", rows);
            return out;
        } catch (Exception e) {
            return fail(e);
        }
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        try {
            return service.create(toReq(body));
        } catch (Exception e) {
            return fail(e);
        }
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        try {
            return service.update(id, toReq(body));
        } catch (Exception e) {
            return fail(e);
        }
    }

    /** 删除：被引用过会拒绝并说明引用数，引导改停用 */
    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        try {
            return service.delete(id);
        } catch (Exception e) {
            return fail(e);
        }
    }

    /* ==================== 内部 ==================== */

    private ErpQcDefectCodeService.Req toReq(Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        return new ErpQcDefectCodeService.Req(
                str(b.get("code")),
                str(b.get("name")),
                str(b.get("severity")),
                str(b.get("category")),
                dec(b.get("defaultAql")),
                str(b.get("remark")),
                integer(b.get("sortNo")),
                bool(b.get("enabled")));
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static Integer integer(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Boolean bool(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Boolean b) {
            return b;
        }
        String s = String.valueOf(o).trim();
        if (s.isEmpty()) {
            return null;
        }
        return "true".equalsIgnoreCase(s) || "1".equals(s);
    }

    private static BigDecimal dec(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        String s = String.valueOf(o).trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Map<String, Object> fail(Exception e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", false);
        m.put("message", e.getMessage() == null ? "操作失败" : e.getMessage());
        return m;
    }
}
