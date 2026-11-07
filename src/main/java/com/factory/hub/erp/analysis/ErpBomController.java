package com.factory.hub.erp.analysis;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
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
 * 物料清单（BOM）维护（DH-70）。
 * <p>
 * 权限沿用 {@code /api/erp/**}：读登录即可，写 ADMIN/MANAGER
 * （改 BOM 等于改成本口径，不能让所有人随便动）。
 * <p>
 * 失败一律 200 + {@code ok:false}，与全站约定一致——前端要把后端那句人话直接弹出来
 * （例如「材料不能是产品自己」，比 500 好读得多）。
 */
@RestController
@RequestMapping("/api/erp/bom")
public class ErpBomController {

    private final ErpBomService service;

    public ErpBomController(ErpBomService service) {
        this.service = service;
    }

    /** 某个产品的用料清单 + 单件材料成本 */
    @GetMapping
    public Map<String, Object> list(@RequestParam Long productSkuId) {
        try {
            return service.list(productSkuId);
        } catch (Exception e) {
            return fail(e);
        }
    }

    /** 产品列表（带"已配几种料"），BOM 管理页左侧用 */
    @GetMapping("/products")
    public List<Map<String, Object>> products() {
        try {
            return service.products();
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 这个材料被哪些产品用着——删商品前先查一下 */
    @GetMapping("/usage")
    public Map<String, Object> usage(@RequestParam Long materialSkuId) {
        try {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("rows", service.usage(materialSkuId));
            return out;
        } catch (Exception e) {
            return fail(e);
        }
    }

    /**
     * 整表保存：提交上来的就是这张 BOM 的最终样子，不在列表里的行会被删掉。
     * <p>
     * 请求体：{@code { lines: [{ materialSkuId, qty, lossRate, remark }] }}
     */
    @PostMapping
    public Map<String, Object> save(@RequestParam Long productSkuId,
                                    @RequestBody Map<String, Object> body) {
        try {
            List<ErpBomService.BomLine> lines = new ArrayList<>();
            Object raw = body.get("lines");
            if (raw instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> m) {
                        lines.add(new ErpBomService.BomLine(
                                longOf(m.get("materialSkuId")),
                                decOf(m.get("qty")),
                                decOf(m.get("lossRate")),
                                str(m.get("remark"))));
                    }
                }
            }
            return service.save(productSkuId, lines, operator());
        } catch (Exception e) {
            return fail(e);
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
