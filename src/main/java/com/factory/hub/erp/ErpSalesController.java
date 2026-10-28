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
 * 销售管理（ROADMAP DH-45b）。权限与采购一致：读=登录，写=ADMIN/MANAGER。
 * <p>
 * 发货的"库存不足"由库存服务抛人话（"现有 70，本次需要 100"），这里原样透传。
 */
@RestController
@RequestMapping("/api/erp/sales")
public class ErpSalesController {

    private final ErpSalesService service;

    public ErpSalesController(ErpSalesService service) {
        this.service = service;
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return service.stats();
    }

    @GetMapping("/orders")
    public Map<String, Object> orders(@RequestParam(required = false) String status,
                                      @RequestParam(required = false) Long customerId,
                                      @RequestParam(required = false) Long from,
                                      @RequestParam(required = false) Long to,
                                      @RequestParam(required = false) String kw,
                                      @RequestParam(defaultValue = "1") int page,
                                      @RequestParam(defaultValue = "20") int size) {
        return service.list(status, customerId, from, to, kw, page, size);
    }

    @GetMapping("/orders/{orderNo}")
    public Map<String, Object> detail(@PathVariable String orderNo) {
        try {
            return service.detail(orderNo);
        } catch (IllegalArgumentException e) {
            return fail(e.getMessage());
        }
    }

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
            return ok("订单已确认，可以发货了");
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
     * 发货。body: { warehouseId, remark, lines:[{itemId 或 skuId, qty, unitPrice?}] }
     * 数量按未发货量校验；库存按目标仓可用量校验（不足直接拒绝）。
     */
    @PostMapping("/orders/{orderNo}/ship")
    public Map<String, Object> ship(@PathVariable String orderNo, @RequestBody Map<String, Object> body) {
        try {
            return service.ship(orderNo, asLong(body.get("warehouseId")), body.get("lines"),
                    str(body.get("remark")), operator());
        } catch (IllegalArgumentException e) {
            return fail(e.getMessage());
        }
    }

    /** 客户退货：数量按净发货校验，生成 SO_RETURN（正数入库） */
    @PostMapping("/orders/{orderNo}/return")
    public Map<String, Object> returnFromCustomer(@PathVariable String orderNo,
                                                  @RequestBody Map<String, Object> body) {
        try {
            return service.returnFromCustomer(orderNo, asLong(body.get("warehouseId")),
                    body.get("lines"), str(body.get("remark")), operator());
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
