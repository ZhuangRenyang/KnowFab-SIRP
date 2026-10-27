package com.factory.hub.report;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 报表接口，给前端「报表」页用。
 * <p>
 * 导出走单独的 GET：客户点一下就要下载，不能靠前端拼表格再导。
 * 文件名带日期，财务存盘时不用改名字。
 */
@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private final ReportService service;
    private final ShiftStore shiftStore;
    private final ProdHourlyRepository repo;

    public ReportController(ReportService service, ShiftStore shiftStore,
                            ProdHourlyRepository repo) {
        this.service = service;
        this.shiftStore = shiftStore;
        this.repo = repo;
    }

    @GetMapping("/daily")
    public Map<String, Object> daily(@RequestParam(required = false) String date) {
        LocalDate d = parse(date);
        return service.daily(d);
    }

    /** 近 N 天趋势。days 上限 90，再多就该去做月报了，不是这个页面的事 */
    @GetMapping("/range")
    public Map<String, Object> range(@RequestParam(defaultValue = "7") int days,
                                     @RequestParam(required = false) String to) {
        return service.range(parse(to), days);
    }

    @GetMapping("/shifts")
    public Map<String, Object> shifts() {
        ShiftPlan p = shiftStore.current();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("plan", p);
        out.put("warnings", p.validate());
        return out;
    }

    @PostMapping("/shifts")
    public Map<String, Object> saveShifts(@RequestBody ShiftPlan plan) {
        List<String> problems = plan.validate();
        // 重叠直接拒绝：产量会算两遍，这种错客户自己发现不了，只能在这里拦住
        for (String w : problems) {
            if (w.contains("重叠")) {
                Map<String, Object> bad = new LinkedHashMap<>();
                bad.put("ok", false);
                bad.put("message", w);
                return bad;
            }
        }
        shiftStore.save(plan);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("message", "班次已保存，历史报表按新班次重算");
        out.put("warnings", problems);
        return out;
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@RequestParam(required = false) String date) {
        LocalDate d = parse(date);
        try {
            byte[] body = ProdExcel.build(service.daily(d));
            String name = "生产报表-" + d + ".xlsx";
            String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"report-" + d + ".xlsx\"; filename*=UTF-8''" + encoded)
                    .contentType(MediaType.parseMediaType(
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(body);
        } catch (Exception e) {
            // 导出失败要让客户看见原因，不能返回一个 0 字节的 xlsx
            return ResponseEntity.internalServerError()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(("{\"ok\":false,\"message\":\"导出失败：" + safe(e.getMessage()) + "\"}")
                            .getBytes(StandardCharsets.UTF_8));
        }
    }

    /** 清演示数据。交付前必须执行，否则客户会看到假产量 */
    @PostMapping("/purge")
    public Map<String, Object> purge(@RequestBody(required = false) Map<String, Object> body) {
        boolean confirm = body != null && Boolean.TRUE.equals(body.get("confirm"));
        Map<String, Object> out = new LinkedHashMap<>();
        if (!confirm) {
            out.put("ok", false);
            out.put("message", "演示数据清空不可恢复，请传 {\"confirm\":true}");
            return out;
        }
        int n = repo.purgeAll();
        out.put("ok", true);
        out.put("message", "已清空 " + n + " 行小时生产数据");
        return out;
    }

    private LocalDate parse(String date) {
        try {
            if (date != null && !date.isBlank()) {
                return LocalDate.parse(date.trim());
            }
        } catch (Exception ignored) {
        }
        return LocalDate.now();
    }

    private String safe(String s) {
        if (s == null) {
            return "未知错误";
        }
        return s.replace("\"", "'").replace("\n", " ");
    }
}
