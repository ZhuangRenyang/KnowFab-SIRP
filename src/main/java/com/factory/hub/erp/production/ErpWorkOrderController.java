package com.factory.hub.erp.production;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
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

/**
 * 生产工单（DH-71）——设备与 ERP 联动的入口。
 * <p>
 * <b>权限</b>：读登录即可；建单/改单/下达/完工/取消沿用 {@code /api/erp/**} 的
 * ADMIN|MANAGER；<b>开工与领料额外放给 LEADER</b>（班组长在车间现场开工、领料，
 * 让他找经理点一下不现实，现场就会绕开系统记在纸上）。
 * 这条例外写在 SecurityConfig 里，不在这里逐个注解。
 * <p>
 * 失败一律 200 + {@code ok:false}：前端要把后端那句人话弹出来，
 * 例如「设备 CNC-01 正在做工单 WO20261103-0002，请先完工或取消那张单」。
 */
@RestController
@RequestMapping("/api/erp/work-orders")
public class ErpWorkOrderController {

    private final ErpWorkOrderService service;

    public ErpWorkOrderController(ErpWorkOrderService service) {
        this.service = service;
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) String status,
                                    @RequestParam(required = false) String kw,
                                    @RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "20") int size) {
        try {
            return service.list(status, kw, page, size);
        } catch (Exception e) {
            return fail(e);
        }
    }

    /** 概览四张卡：在产/待开工/草稿/已完工 */
    @GetMapping("/summary")
    public Map<String, Object> summary() {
        try {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.putAll(service.summary());
            return out;
        } catch (Exception e) {
            return fail(e);
        }
    }

    /** 缺料清单：未完工工单里需求 > 已领的材料 */
    @GetMapping("/shortage")
    public Map<String, Object> shortage() {
        try {
            return service.shortage();
        } catch (Exception e) {
            return fail(e);
        }
    }

    @GetMapping("/{orderNo}")
    public Map<String, Object> detail(@PathVariable String orderNo) {
        try {
            return service.detail(orderNo);
        } catch (Exception e) {
            return fail(e);
        }
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        try {
            return service.create(body, operator());
        } catch (Exception e) {
            return fail(e);
        }
    }

    @PutMapping("/{orderNo}")
    public Map<String, Object> update(@PathVariable String orderNo,
                                      @RequestBody Map<String, Object> body) {
        try {
            return service.update(orderNo, body, operator());
        } catch (Exception e) {
            return fail(e);
        }
    }

    /** 下达：按 BOM 展开用料快照 */
    @PostMapping("/{orderNo}/release")
    public Map<String, Object> release(@PathVariable String orderNo) {
        try {
            return service.release(orderNo, operator());
        } catch (Exception e) {
            return fail(e);
        }
    }

    /** 开工：绑定设备，产量开始记到这张工单 */
    @PostMapping("/{orderNo}/start")
    public Map<String, Object> start(@PathVariable String orderNo) {
        try {
            return service.start(orderNo, operator());
        } catch (Exception e) {
            return fail(e);
        }
    }

    @PostMapping("/{orderNo}/finish")
    public Map<String, Object> finish(@PathVariable String orderNo) {
        try {
            return service.finish(orderNo, operator());
        } catch (Exception e) {
            return fail(e);
        }
    }

    @PostMapping("/{orderNo}/cancel")
    public Map<String, Object> cancel(@PathVariable String orderNo,
                                      @RequestBody(required = false) Map<String, Object> body) {
        try {
            String reason = body == null ? null : str(body.get("reason"));
            return service.cancel(orderNo, operator(), reason);
        } catch (Exception e) {
            return fail(e);
        }
    }

    /**
     * 领料：{@code { warehouseId, lines: [{skuId, qty}] }}
     * <p>
     * 库存不足会整体失败并说清差多少（行锁校验），不会写出半张单。
     */
    @PostMapping("/{orderNo}/issue")
    public Map<String, Object> issue(@PathVariable String orderNo,
                                     @RequestBody Map<String, Object> body) {
        try {
            Long whId = longOf(body.get("warehouseId"));
            List<ErpWorkOrderService.IssueLine> lines = new ArrayList<>();
            if (body.get("lines") instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> m) {
                        lines.add(new ErpWorkOrderService.IssueLine(
                                longOf(m.get("skuId")), decOf(m.get("qty"))));
                    }
                }
            }
            return service.issue(orderNo, lines, whId, operator());
        } catch (Exception e) {
            return fail(e);
        }
    }

    /** 一键按缺口领全 */
    @PostMapping("/{orderNo}/auto-issue")
    public Map<String, Object> autoIssue(@PathVariable String orderNo,
                                         @RequestBody(required = false) Map<String, Object> body) {
        try {
            Long whId = body == null ? null : longOf(body.get("warehouseId"));
            return service.autoIssue(orderNo, whId, operator());
        } catch (Exception e) {
            return fail(e);
        }
    }

    /* ==================== 工具 ==================== */

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
        String s = String.valueOf(o).trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static BigDecimal decOf(Object o) {
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

    private static String str(Object o) {
        if (o == null) {
            return null;
        }
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }

    private static Map<String, Object> fail(Exception e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", false);
        m.put("message", e.getMessage() == null ? "操作失败" : e.getMessage());
        return m;
    }
}
