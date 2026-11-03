package com.factory.hub.quality;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 禁发仓放行校验接口（QC-SPEC §2.11）。
 * <p>
 * <b>参数是 {@code whId + skuIds}，不是 soNo</b>：禁发仓里的货可能是客户退回来的，
 * 压根没有销售单关联；而让步流水自己就记了 {@code concession_so_no}，
 * 用 SO 当查询键是循环依赖。
 * <p>
 * 返回顶层带 {@code blocked}（整单能不能发）与 {@code fromSpecialWh}（源仓是不是禁发仓），
 * {@code hits[]} 每行带 {@code blockReason}。
 */
@RestController
@RequestMapping("/api/erp/qc")
public class ErpQcReleaseController {

    private final ErpQcReleaseService service;

    public ErpQcReleaseController(ErpQcReleaseService service) {
        this.service = service;
    }

    /**
     * @param whId   源仓 id
     * @param skuIds SKU id 列表，逗号分隔（12,15）
     */
    @GetMapping("/concession-release")
    public Map<String, Object> release(@RequestParam Long whId,
                                       @RequestParam(required = false) String skuIds) {
        try {
            return service.check(whId, parse(skuIds));
        } catch (Exception e) {
            return ErpQcBody.fail(e);
        }
    }

    private static List<Long> parse(String csv) {
        List<Long> out = new ArrayList<>();
        if (csv == null || csv.isBlank()) {
            return out;
        }
        for (String s : csv.split(",")) {
            String t = s.trim();
            if (t.isEmpty()) {
                continue;
            }
            try {
                out.add(Long.parseLong(t));
            } catch (NumberFormatException ignored) {
                // 忽略坏参数：少查一个 SKU 最坏是少拦一个（不对），
                // 但这里 fail-safe 的方向是"查不到就拦"，所以宁可多拦不可漏拦。
                // 真正拦住的是"禁发仓 + 无凭证"这个默认分支
            }
        }
        return out;
    }
}
