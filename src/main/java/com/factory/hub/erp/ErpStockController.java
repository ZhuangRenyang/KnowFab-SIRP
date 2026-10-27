package com.factory.hub.erp;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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
 * 出入库单据与库存（ROADMAP DH-43b）。
 * <p>
 * 权限：读 = 登录即可（车间班组长要查库存）；写 = ADMIN/MANAGER
 * （SecurityConfig 对 {@code POST/PUT/DELETE /api/erp/**} 统一收口，这里不再逐个注解）。
 * <p>
 * 失败一律 200 + {@code ok:false}，与全站约定一致：前端 http.ts 只在 401 时跳登录，
 * 业务校验失败要让页面弹出后端那句人话（例如"库存不足：六角螺栓 M8 现有 10，本次需要 20"）。
 */
@RestController
@RequestMapping("/api/erp/stock")
public class ErpStockController {

    private final ErpStockService service;

    public ErpStockController(ErpStockService service) {
        this.service = service;
    }

    /* ==================== 看板 / 主数据辅助 ==================== */

    /** 单据类型下拉 + 可手工建的类型（前端不必再维护一份中文名） */
    @GetMapping("/types")
    public Map<String, Object> types() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("all", ErpStockService.typeOptions());
        out.put("manual", ErpStockService.MANUAL_TYPES);
        return out;
    }

    @GetMapping("/summary")
    public Map<String, Object> summary() {
        return service.summary();
    }

    @GetMapping("/trend")
    public List<Map<String, Object>> trend(@RequestParam(defaultValue = "30") int days) {
        return service.trend(days);
    }

    /* ==================== 库存 ==================== */

    @GetMapping("/balances")
    public List<Map<String, Object>> balances(@RequestParam(required = false) Long warehouseId,
                                              @RequestParam(required = false) String q) {
        return service.balances(warehouseId, q);
    }

    @GetMapping("/alerts")
    public List<Map<String, Object>> alerts() {
        return service.alerts();
    }

    @GetMapping("/flow")
    public List<Map<String, Object>> flow(@RequestParam Long skuId,
                                          @RequestParam Long warehouseId,
                                          @RequestParam(defaultValue = "60") int limit) {
        return service.flow(skuId, warehouseId, limit);
    }

    /* ==================== 单据 ==================== */

    @GetMapping("/bills")
    public Map<String, Object> bills(@RequestParam(required = false) String type,
                                     @RequestParam(required = false) Long from,
                                     @RequestParam(required = false) Long to,
                                     @RequestParam(defaultValue = "false") boolean withProd,
                                     @RequestParam(required = false) String kw,
                                     @RequestParam(defaultValue = "1") int page,
                                     @RequestParam(defaultValue = "20") int size) {
        return service.bills(type, from, to, withProd, kw, page, size);
    }

    @GetMapping("/bills/{billNo}")
    public Map<String, Object> billDetail(@PathVariable String billNo) {
        try {
            return service.billDetail(billNo);
        } catch (IllegalArgumentException e) {
            return fail(e.getMessage());
        }
    }

    /**
     * 建单。body:
     * <pre>
     * { "type":"PO_IN", "warehouseId":1, "srcWarehouseId":null,
     *   "partnerId":2, "remark":"...",
     *   "lines":[{"skuId":1,"qty":100,"unitPrice":1.2}] }
     * </pre>
     * 数量填正数，方向由 type 决定（出库类自动取负）。
     */
    @PostMapping("/bills")
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        try {
            String type = str(body.get("type"));
            List<ErpStockService.LineReq> lines = parseLines(body.get("lines"));
            ErpStockService.Created c = service.createManual(
                    type,
                    asLong(body.get("partnerId")),
                    asLong(body.get("warehouseId")),
                    asLong(body.get("srcWarehouseId")),
                    str(body.get("remark")),
                    operator(),
                    lines);
            Map<String, Object> m = ok("单据已生成：" + c.billNo());
            m.put("billNo", c.billNo());
            m.put("lineCount", c.lineCount());
            return m;
        } catch (IllegalArgumentException e) {
            return fail(e.getMessage());
        }
    }

    /**
     * 盘点。body: { "warehouseId":1, "remark":"...", "lines":[{"skuId":1,"qty":实盘数}] }
     * 只落有差异的行；全部一致则返回 ok:false 提示"无差异"，不生成空单。
     */
    @PostMapping("/stocktake")
    public Map<String, Object> stocktake(@RequestBody Map<String, Object> body) {
        try {
            ErpStockService.Created c = service.createStocktake(
                    asLong(body.get("warehouseId")),
                    operator(),
                    str(body.get("remark")),
                    parseLines(body.get("lines")));
            if (c == null) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("ok", true);
                m.put("noDiff", true);
                m.put("message", "账实一致，没有差异，未生成盘点单");
                return m;
            }
            Map<String, Object> m = ok("盘点单已生成：" + c.billNo());
            m.put("billNo", c.billNo());
            m.put("lineCount", c.lineCount());
            return m;
        } catch (IllegalArgumentException e) {
            return fail(e.getMessage());
        }
    }

    /** 作废整单（不删数据；作废后库存不能为负） */
    @PostMapping("/bills/{billNo}/void")
    public Map<String, Object> voidBill(@PathVariable String billNo) {
        try {
            service.voidBill(billNo, operator());
            return ok("单据已作废：" + billNo);
        } catch (IllegalArgumentException e) {
            return fail(e.getMessage());
        }
    }

    /* ==================== 工具 ==================== */

    private static List<ErpStockService.LineReq> parseLines(Object raw) {
        List<ErpStockService.LineReq> out = new ArrayList<>();
        if (!(raw instanceof List<?> list)) {
            return out;
        }
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) {
                continue;
            }
            Long skuId = asLong(m.get("skuId"));
            if (skuId == null) {
                continue;
            }
            BigDecimal qty = dec(m.get("qty"));
            BigDecimal price = dec(m.get("unitPrice"));
            out.add(new ErpStockService.LineReq(skuId, qty, price));
        }
        return out;
    }

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

    private static BigDecimal dec(Object o) {
        if (o == null) {
            return null;
        }
        String s = String.valueOf(o).trim();
        if (s.isEmpty() || "null".equalsIgnoreCase(s)) {
            return null;
        }
        try {
            return new BigDecimal(s);
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
