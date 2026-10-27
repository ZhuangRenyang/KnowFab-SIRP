package com.factory.hub.erp;

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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 采购管理（ROADMAP DH-52）。
 * <p>
 * 权限沿用全站收口：GET 登录即可（销售/班组长也能看采购到货情况），
 * POST/PUT 有 ADMIN/MANAGER（SecurityConfig 对 {@code /api/erp/**} 按方法收口）。
 * <p>
 * 失败一律 200 + {@code ok:false} + 人话（"「六角螺栓 M8」还能入库 40，本次填了 50"），
 * 前端直接 ElMessage.error 显示，不需要再翻译错误码。
 */
@RestController
@RequestMapping("/api/erp/purchase")
public class ErpPurchaseController {

    private final ErpPurchaseService service;

    public ErpPurchaseController(ErpPurchaseService service) {
        this.service = service;
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return service.stats();
    }

    @GetMapping("/orders")
    public Map<String, Object> orders(@RequestParam(required = false) String status,
                                      @RequestParam(required = false) Long supplierId,
                                      @RequestParam(required = false) Long from,
                                      @RequestParam(required = false) Long to,
                                      @RequestParam(required = false) String kw,
                                      @RequestParam(defaultValue = "1") int page,
                                      @RequestParam(defaultValue = "20") int size) {
        return service.list(status, supplierId, from, to, kw, page, size);
    }

    @GetMapping("/orders/{orderNo}")
    public Map<String, Object> detail(@PathVariable String orderNo) {
        try {
            return service.detail(orderNo);
        } catch (IllegalArgumentException e) {
            return fail(e.getMessage());
        }
    }

    /** 待收货订单（首页"该催哪些货"） */
    @GetMapping("/open")
    public java.util.List<Map<String, Object>> open(@RequestParam(defaultValue = "10") int limit) {
        return service.openOrders(limit);
    }

    @PostMapping("/orders")
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        try {
            return service.create(body, operator());
        } catch (IllegalArgumentException e) {
            return fail(e.getMessage());
        }
    }

    @PutMapping("/orders/{orderNo}")
    public Map<String, Object> update(@PathVariable String orderNo, @RequestBody Map<String, Object> body) {
        try {
            service.update(orderNo, body, operator());
            return ok("已保存");
        } catch (IllegalArgumentException e) {
            return fail(e.getMessage());
        }
    }

    @PostMapping("/orders/{orderNo}/confirm")
    public Map<String, Object> confirm(@PathVariable String orderNo) {
        try {
            service.confirm(orderNo, operator());
            return ok("订单已确认，可以收货了");
        } catch (IllegalArgumentException e) {
            return fail(e.getMessage());
        }
    }

    @PostMapping("/orders/{orderNo}/cancel")
    public Map<String, Object> cancel(@PathVariable String orderNo) {
        try {
            service.cancel(orderNo, operator());
            return ok("订单已取消");
        } catch (IllegalArgumentException e) {
            return fail(e.getMessage());
        }
    }

    /**
     * 从订单入库。body: { warehouseId, remark, lines:[{itemId 或 skuId, qty, unitPrice?}] }
     * 数量按"未入库量"校验；生成 PO_IN 单据并回写订单进度。
     */
    @PostMapping("/orders/{orderNo}/receive")
    public Map<String, Object> receive(@PathVariable String orderNo, @RequestBody Map<String, Object> body) {
        try {
            return service.receive(orderNo, asLong(body.get("warehouseId")), body.get("lines"),
                    str(body.get("remark")), operator());
        } catch (IllegalArgumentException e) {
            return fail(e.getMessage());
        }
    }

    /** 退货给供应商：body 同 receive，数量按净入库校验，生成 PO_RETURN（负数出库） */
    @PostMapping("/orders/{orderNo}/return")
    public Map<String, Object> returnToSupplier(@PathVariable String orderNo,
                                                @RequestBody Map<String, Object> body) {
        try {
            return service.returnToSupplier(orderNo, asLong(body.get("warehouseId")), body.get("lines"),
                    str(body.get("remark")), operator());
        } catch (IllegalArgumentException e) {
            return fail(e.getMessage());
        }
    }

    /* ==================== 工具 ==================== */

    private static Long asLong(Object o) {
        if (o == null) {
            return null;
        }
        String s = String.valueOf(o).trim();
        if (s.isEmpty() || "null".equalsIgnoreCase(s)) {
            return null;
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private static String operator() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null || a.getName() == null || a.getName().isBlank()
                || "anonymousUser".equals(a.getName())) {
            return "system";
        }
        return a.getName();
    }

    private static Map<String, Object> ok(String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("message", msg);
        return m;
    }

    private static Map<String, Object> fail(String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", false);
        m.put("message", msg);
        return m;
    }
}
