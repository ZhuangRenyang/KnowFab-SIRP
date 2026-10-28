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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 财务管理（ROADMAP DH-54）：应收 / 应付 / 收付款 / 往来对账 / 账龄。
 * <p>
 * 读=登录即可（老板、销售都要看欠款），写=ADMIN/MANAGER（沿用 /api/erp/** 方法级收口）。
 * 失败一律 200 + ok:false + 人话。
 */
@RestController
@RequestMapping("/api/erp/finance")
public class ErpFinanceController {

    private final ErpFinanceService service;

    public ErpFinanceController(ErpFinanceService service) {
        this.service = service;
    }

    @GetMapping("/summary")
    public Map<String, Object> summary() {
        return service.summary();
    }

    @GetMapping("/receivables")
    public Map<String, Object> receivables(@RequestParam(required = false) Long partnerId,
                                           @RequestParam(defaultValue = "false") boolean onlyOpen,
                                           @RequestParam(required = false) String kw,
                                           @RequestParam(defaultValue = "1") int page,
                                           @RequestParam(defaultValue = "20") int size) {
        return service.receivables(partnerId, onlyOpen, kw, page, size);
    }

    @GetMapping("/payables")
    public Map<String, Object> payables(@RequestParam(required = false) Long partnerId,
                                        @RequestParam(defaultValue = "false") boolean onlyOpen,
                                        @RequestParam(required = false) String kw,
                                        @RequestParam(defaultValue = "1") int page,
                                        @RequestParam(defaultValue = "20") int size) {
        return service.payables(partnerId, onlyOpen, kw, page, size);
    }

    @GetMapping("/payments")
    public Map<String, Object> payments(@RequestParam(required = false) String direction,
                                        @RequestParam(required = false) Long partnerId,
                                        @RequestParam(required = false) Long from,
                                        @RequestParam(required = false) Long to,
                                        @RequestParam(required = false) String kw,
                                        @RequestParam(defaultValue = "false") boolean withVoided,
                                        @RequestParam(defaultValue = "1") int page,
                                        @RequestParam(defaultValue = "20") int size) {
        return service.payments(direction, partnerId, from, to, kw, withVoided, page, size);
    }

    /** 往来对账：type=CUSTOMER 看客户应收，SUPPLIER 看供应商应付 */
    @GetMapping("/partners")
    public List<Map<String, Object>> partners(@RequestParam(defaultValue = "CUSTOMER") String type) {
        return service.partners(type);
    }

    /**
     * 新建收付款。body:
     * <pre>
     * { "direction":"IN"|"OUT", "partnerId":1, "orderNo":"XSDD...",  // orderNo 可空 = 预收/预付
     *   "amount":1000, "method":"银行转账", "payDate":"2026-10-28", "remark":"..." }
     * </pre>
     */
    @PostMapping("/payments")
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        try {
            return service.createPayment(body, operator());
        } catch (IllegalArgumentException e) {
            return fail(e.getMessage());
        }
    }

    @PostMapping("/payments/{payNo}/void")
    public Map<String, Object> voidPayment(@PathVariable String payNo) {
        try {
            service.voidPayment(payNo, operator());
            return ok("收付款单已作废：" + payNo);
        } catch (IllegalArgumentException e) {
            return fail(e.getMessage());
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
