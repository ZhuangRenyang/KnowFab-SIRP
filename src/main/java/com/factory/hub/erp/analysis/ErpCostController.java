package com.factory.hub.erp.analysis;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 成本核算（DH-74）。
 * <p>
 * 权限沿用 {@code /api/erp/**}：读登录即可，写 ADMIN|MANAGER（这里全是只读接口）。
 * <p>
 * <b>成本数据要不要给销售看</b>：不给。销售只有 `/api/erp/sales-query/**` 一个入口，
 * 那个服务明确只返回售价、不含成本（`ErpSalesQueryService` 的注释写着这件事）。
 * 这条边界在 SecurityConfig 与 AI 工具权限里各守一次，别在这边破例。
 * <p>
 * 日期参数是 {@code yyyy-MM-dd}，含义是<b>完工日期</b>——钱是完工时才结清的，
 * 按下单日归期会把还没做完的单算进成本，单位成本必然被低估。
 */
@RestController
@RequestMapping("/api/erp/cost")
public class ErpCostController {

    private static final ZoneId ZONE = ZoneId.systemDefault();
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final ErpCostService service;

    public ErpCostController(ErpCostService service) {
        this.service = service;
    }

    /** 成本概览：本期料/工/费合计、占比、单位成本。默认本月。 */
    @GetMapping("/overview")
    public Map<String, Object> overview(@RequestParam(required = false) String from,
                                        @RequestParam(required = false) String to) {
        try {
            long[] w = window(from, to);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("from", DAY.format(java.time.Instant.ofEpochMilli(w[0]).atZone(ZONE)));
            out.put("to", DAY.format(java.time.Instant.ofEpochMilli(w[1] - 1).atZone(ZONE)));
            out.putAll(service.overview(w[0], w[1]));
            return out;
        } catch (Exception e) {
            return fail(e);
        }
    }

    /** 产品成本：按产品汇总已完工工单的成本与毛利 */
    @GetMapping("/products")
    public Map<String, Object> products(@RequestParam(required = false) String from,
                                        @RequestParam(required = false) String to) {
        try {
            long[] w = window(from, to);
            return service.productCosts(w[0], w[1]);
        } catch (Exception e) {
            return fail(e);
        }
    }

    /** 单张工单的成本明细 */
    @GetMapping("/orders/{orderNo}")
    public Map<String, Object> order(@PathVariable String orderNo) {
        try {
            return service.orderCost(orderNo);
        } catch (Exception e) {
            return fail(e);
        }
    }

    /* ==================== 工具 ==================== */

    /**
     * 区间默认本月。{@code to} 是"含当天"的，转成半开区间时 +1 天——
     * 两边都含会让月末那天被相邻两个月各算一次，报表会凭空多一天。
     */
    private static long[] window(String from, String to) {
        LocalDate today = LocalDate.now(ZONE);
        LocalDate f = parse(from, today.withDayOfMonth(1));
        LocalDate t = parse(to, today);
        if (t.isBefore(f)) {
            throw new IllegalArgumentException("结束日期不能早于开始日期");
        }
        long fromTs = f.atStartOfDay(ZONE).toInstant().toEpochMilli();
        long toTs = t.plusDays(1).atStartOfDay(ZONE).toInstant().toEpochMilli();
        return new long[]{fromTs, toTs};
    }

    private static LocalDate parse(String s, LocalDate def) {
        if (s == null || s.isBlank()) {
            return def;
        }
        try {
            return LocalDate.parse(s.trim(), DAY);
        } catch (Exception e) {
            throw new IllegalArgumentException("日期格式应为 yyyy-MM-dd：" + s);
        }
    }

    private static Map<String, Object> fail(Exception e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", false);
        m.put("message", e.getMessage() == null ? "查询失败" : e.getMessage());
        return m;
    }
}
