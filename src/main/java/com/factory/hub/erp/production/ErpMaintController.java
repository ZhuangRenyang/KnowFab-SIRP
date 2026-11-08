package com.factory.hub.erp.production;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.factory.hub.audit.AuditLog;
import com.factory.hub.audit.AuditService;

/**
 * 设备维修闭环接口。
 * <p>
 * 权限：建诊断单 / 开始维修 / 结案 / 取消都放给 <b>ADMIN|MANAGER|LEADER</b>——
 * 班组长在车间现场修机器，让他找管理员点一下不现实。
 * 这与「班组长本来就能确认告警」是同一条逻辑。
 * <p>
 * 每次写操作都记审计：<b>结案时填的「实际原因」是这套系统的经验资产</b>，
 * 谁在什么时候写了什么，必须能查。
 */
@RestController
@RequestMapping("/api/erp/maint")
public class ErpMaintController {

    private final ErpMaintService service;
    private final AuditService audit;

    public ErpMaintController(ErpMaintService service, AuditService audit) {
        this.service = service;
        this.audit = audit;
    }

    /**
     * 对一条报警做诊断（同步，可能等几秒）。
     * <p>
     * 同一条报警已有未结案的单 → 直接返回那张，不重复建。
     */
    @PostMapping("/analyze")
    public Map<String, Object> analyze(@RequestBody Map<String, Object> body) {
        Long alarmEventId = longOf(body == null ? null : body.get("alarmEventId"));
        Map<String, Object> out = service.analyze(alarmEventId, operator());
        Object order = out.get("order");
        String detail = "报警事件 #" + alarmEventId + " 诊断"
                + (order instanceof Map<?, ?> m
                    ? "，生成维修单 " + m.get("orderNo") + "（来源 " + m.get("aiSource") + "）"
                    : "");
        if (Boolean.TRUE.equals(out.get("ok"))) {
            audit.ok(AuditLog.CAT_SYSTEM, AuditLog.ACT_CREATE, "maint", "维修诊断", detail);
        } else {
            audit.fail(AuditLog.CAT_SYSTEM, AuditLog.ACT_CREATE, "maint", "维修诊断",
                    detail, String.valueOf(out.get("message")));
        }
        return out;
    }

    @PostMapping("/{orderNo}/start")
    public Map<String, Object> start(@PathVariable String orderNo) {
        Map<String, Object> out = service.start(orderNo, operator());
        auditIfOk(out, AuditLog.ACT_UPDATE, "开始维修", orderNo);
        return out;
    }

    /**
     * 结案并回填实际原因 —— 整个闭环最有价值的一步。
     */
    @PostMapping("/{orderNo}/resolve")
    public Map<String, Object> resolve(@PathVariable String orderNo,
                                       @RequestBody Map<String, Object> body) {
        Map<String, Object> out = service.resolve(orderNo, body, operator());
        if (Boolean.TRUE.equals(out.get("ok"))) {
            String cat = String.valueOf(body.get("causeCategory"));
            String cause = String.valueOf(body.get("rootCause"));
            String cut = cause.length() > 80 ? cause.substring(0, 80) + "…" : cause;
            audit.ok(AuditLog.CAT_SYSTEM, AuditLog.ACT_UPDATE, "maint", "维修结案",
                    orderNo + " 结案：分类 " + cat + "，原因「" + cut + "」");
        } else {
            audit.fail(AuditLog.CAT_SYSTEM, AuditLog.ACT_UPDATE, "maint", "维修结案",
                    orderNo, String.valueOf(out.get("message")));
        }
        return out;
    }

    @PostMapping("/{orderNo}/cancel")
    public Map<String, Object> cancel(@PathVariable String orderNo,
                                      @RequestBody Map<String, Object> body) {
        Map<String, Object> out = service.cancel(orderNo, body, operator());
        auditIfOk(out, AuditLog.ACT_UPDATE, "取消维修单", orderNo
                + (body == null ? "" : "：" + body.get("reason")));
        return out;
    }

    /**
     * 领用备件：生成维修领料单（MAINT_OUT）并真实扣库存。
     * <p>
     * body: {@code { warehouseId, lines: [{skuId, qty}] }}。
     * 库存不足会整单失败并说清差多少（行锁校验），不会写出半张单。
     */
    @PostMapping("/{orderNo}/parts")
    public Map<String, Object> issueParts(@PathVariable String orderNo,
                                          @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        Long whId = null;
        if (b.get("warehouseId") != null) {
            try {
                whId = Long.valueOf(String.valueOf(b.get("warehouseId")).trim());
            } catch (NumberFormatException e) {
                Map<String, Object> bad = new LinkedHashMap<>();
                bad.put("ok", false);
                bad.put("message", "仓库编号不是数字");
                return bad;
            }
        }
        List<ErpMaintService.PartLine> lines = new ArrayList<>();
        if (b.get("lines") instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    Object sku = m.get("skuId");
                    Object qty = m.get("qty");
                    lines.add(new ErpMaintService.PartLine(
                            sku == null ? null : Long.valueOf(String.valueOf(sku).trim()),
                            qty == null ? null : new BigDecimal(String.valueOf(qty).trim())));
                }
            }
        }
        Map<String, Object> out = service.issueParts(orderNo, whId, lines, operator());
        auditIfOk(out, AuditLog.ACT_UPDATE, "维修领料", orderNo
                + (out.get("billNo") == null ? "" : "，出库单 " + out.get("billNo")));
        return out;
    }

    /** 这张维修单领过的备件明细与成本（成本口径，作废单不计） */
    @GetMapping("/{orderNo}/parts")
    public Map<String, Object> parts(@PathVariable String orderNo) {
        return service.parts(orderNo);
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) String status,
                                    @RequestParam(required = false) String kw,
                                    @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "20") int size) {
        return service.list(status, kw, page, size);
    }

    /** 经验统计：哪类故障最多 / 哪台机最爱坏 / 哪个码最常见 */
    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return service.stats();
    }

    /** 原因分类的取值（前端下拉直接用，避免前后端各写一份） */
    @GetMapping("/categories")
    public Map<String, Object> categories() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        var list = new java.util.ArrayList<Map<String, Object>>();
        for (String c : ErpMaintOrder.CAUSE_CATEGORIES) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("value", c);
            x.put("text", ErpMaintService.causeText(c));
            list.add(x);
        }
        m.put("categories", list);
        m.put("statuses", java.util.List.of(
                Map.of("value", ErpMaintOrder.ST_ANALYZED,
                        "text", ErpMaintService.statusText(ErpMaintOrder.ST_ANALYZED)),
                Map.of("value", ErpMaintOrder.ST_IN_PROGRESS,
                        "text", ErpMaintService.statusText(ErpMaintOrder.ST_IN_PROGRESS)),
                Map.of("value", ErpMaintOrder.ST_RESOLVED,
                        "text", ErpMaintService.statusText(ErpMaintOrder.ST_RESOLVED)),
                Map.of("value", ErpMaintOrder.ST_CANCELLED,
                        "text", ErpMaintService.statusText(ErpMaintOrder.ST_CANCELLED))));
        return m;
    }

    /**
     * 指派负责人（DH-85）。
     * <p>
     * 权限在 SecurityConfig 里显式写成 ADMIN|MANAGER|LEADER，且**必须排在 `/api/erp/**` 通配之前**
     * —— 前端藏按钮不算权限控制（项目铁律）。
     * <p>
     * body.assignee 传空字符串 = 取消指派。
     */
    @PostMapping("/{orderNo}/assign")
    public Map<String, Object> assign(@PathVariable String orderNo,
                                      @RequestBody(required = false) Map<String, Object> body) {
        String assignee = body == null || body.get("assignee") == null
                ? "" : String.valueOf(body.get("assignee")).trim();
        Map<String, Object> out = service.assign(orderNo, assignee, operator());
        auditIfOk(out, AuditLog.ACT_UPDATE, "指派维修工单",
                orderNo + " → " + (assignee.isEmpty() ? "(取消指派)" : assignee));
        return out;
    }

    /** 我的待办：每个人看自己的（登录即可，没有跨用户查看的口子） */
    @GetMapping("/my")
    public List<Map<String, Object>> my() {
        return service.my(operator());
    }

    /** 可选人员（派单下拉框用）：只列能接单的角色，不下发密码等敏感字段 */
    @GetMapping("/assignable")
    public List<Map<String, Object>> assignable() {
        return service.assignable();
    }

    private void auditIfOk(Map<String, Object> out, String action, String title, String detail) {
        if (Boolean.TRUE.equals(out.get("ok"))) {
            audit.ok(AuditLog.CAT_SYSTEM, action, "maint", title, detail);
        } else {
            audit.fail(AuditLog.CAT_SYSTEM, action, "maint", title, detail,
                    String.valueOf(out.get("message")));
        }
    }

    private static String operator() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null || a.getName() == null || a.getName().isBlank()
                || "anonymousUser".equals(a.getName())) {
            return "system";
        }
        return a.getName();
    }

    private static Long longOf(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
