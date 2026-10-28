package com.factory.hub.erp;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

/**
 * 经营报表接口（DH-57）。
 * <p>
 * 日期参数统一用 {@code yyyy-MM-dd} 的 {@code from} / {@code to}（两端都含），
 * 内部换成 {@code [from 00:00, to+1 00:00)} 的毫秒区间——
 * 区间右开是为了"截止到今天"这种筛选不会漏掉今天白天的单据。
 * 不传就默认本月 1 号到今天，符合厂长打开报表的第一眼预期。
 */
@RestController
@RequestMapping("/api/erp/reports")
public class ErpReportController {

    private static final ZoneId ZONE = ZoneId.systemDefault();

    private final ErpReportService service;

    public ErpReportController(ErpReportService service) {
        this.service = service;
    }

    @GetMapping("/overview")
    public Map<String, Object> overview(@RequestParam(required = false) String from,
                                        @RequestParam(required = false) String to) {
        long[] r = range(from, to);
        return service.overview(r[0], r[1]);
    }

    @GetMapping("/stock")
    public Map<String, Object> stock(@RequestParam(required = false) String from,
                                     @RequestParam(required = false) String to,
                                     @RequestParam(required = false) Long warehouseId) {
        long[] r = range(from, to);
        return service.stock(r[0], r[1], warehouseId);
    }

    @GetMapping("/flows")
    public Map<String, Object> flows(@RequestParam(required = false) String from,
                                     @RequestParam(required = false) String to,
                                     @RequestParam(required = false) Long warehouseId) {
        long[] r = range(from, to);
        return service.flows(r[0], r[1], warehouseId);
    }

    @GetMapping("/trade")
    public Map<String, Object> trade(@RequestParam(defaultValue = "purchase") String side,
                                     @RequestParam(required = false) String from,
                                     @RequestParam(required = false) String to) {
        long[] r = range(from, to);
        return service.trade(side, r[0], r[1]);
    }

    /**
     * 导出 xlsx。失败返回 JSON 而不是 0 字节文件——
     * 客户拿到一个打不开的空表，比看到"导出失败：原因"更糟。
     */
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@RequestParam(defaultValue = "overview") String type,
                                         @RequestParam(required = false) String from,
                                         @RequestParam(required = false) String to,
                                         @RequestParam(required = false) Long warehouseId,
                                         @RequestParam(defaultValue = "purchase") String side) {
        long[] r = range(from, to);
        String label;
        byte[] body;
        try {
            switch (type) {
                case "stock" -> {
                    Map<String, Object> d = service.stock(r[0], r[1], warehouseId);
                    body = ErpReportExcel.stock(d);
                    label = "库存状况-" + d.get("warehouseName");
                }
                case "flows" -> {
                    body = ErpReportExcel.flows(service.flows(r[0], r[1], warehouseId));
                    label = "出入库统计";
                }
                case "trade" -> {
                    Map<String, Object> d = service.trade(side, r[0], r[1]);
                    body = ErpReportExcel.trade(d);
                    label = d.get("sideLabel") + "执行";
                }
                default -> {
                    body = ErpReportExcel.overview(service.overview(r[0], r[1]));
                    label = "经营概况";
                }
            }
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(("{\"ok\":false,\"message\":\"导出失败：" + safe(e.getMessage()) + "\"}")
                            .getBytes(StandardCharsets.UTF_8));
        }

        String range = dayLabel(r[0]) + "_" + dayLabel(r[1] - 1);
        String name = label + "-" + range + ".xlsx";
        String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"erp-report.xlsx\"; filename*=UTF-8''" + encoded)
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(body);
    }

    /* ==================== 内部 ==================== */

    /** 返回 [fromTs, toTsExclusive] */
    private static long[] range(String from, String to) {
        LocalDate today = LocalDate.now(ZONE);
        LocalDate f = parse(from, today.withDayOfMonth(1));
        LocalDate t = parse(to, today);
        if (t.isBefore(f)) {
            LocalDate tmp = f;
            f = t;
            t = tmp;
        }
        long fromTs = f.atStartOfDay(ZONE).toInstant().toEpochMilli();
        long toTs = t.plusDays(1).atStartOfDay(ZONE).toInstant().toEpochMilli();
        return new long[]{fromTs, toTs};
    }

    private static LocalDate parse(String raw, LocalDate fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return LocalDate.parse(raw.trim().substring(0, 10));
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String dayLabel(long ts) {
        return java.time.Instant.ofEpochMilli(ts).atZone(ZONE).toLocalDate().toString();
    }

    private static String safe(String s) {
        return s == null ? "未知错误" : s.replace("\"", "'").replace("\n", " ");
    }
}
