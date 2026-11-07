package com.factory.hub.erp.quality.defect;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.factory.hub.erp.ErpQcBody;

/**
 * 质量看板与帕累托接口（QC-SPEC §4.2）。
 * <p>
 * <b>只给数与结构，不给面向用户的文案</b>：口径说明由前端 i18n 写
 * （后端塞中文则英文界面蹦中文，塞英文则中文界面蹦英文）。
 * 两个口径（质检 / 设备）分两个字段给，不合成一个数。
 */
@RestController
@RequestMapping("/api/erp/qc")
public class ErpQcBoardController {

    private final ErpQcBoardService service;

    public ErpQcBoardController(ErpQcBoardService service) {
        this.service = service;
    }

    /**
     * 看板。{@code from}/{@code to} 是毫秒时间戳；不传默认最近 30 天。
     */
    @GetMapping("/dashboard")
    public Map<String, Object> dashboard(@RequestParam(required = false) Long from,
                                         @RequestParam(required = false) Long to) {
        try {
            long toTs = to == null ? System.currentTimeMillis() : to;
            long fromTs = from == null ? toTs - 30L * 24 * 3600_000L : from;
            return service.dashboard(fromTs, toTs);
        } catch (Exception e) {
            return ErpQcBody.fail(e);
        }
    }

    /**
     * 帕累托。{@code by=code} 按缺陷分类、{@code by=device} 按设备。
     * 未分类固定占一行，不许被静默丢掉。
     */
    @GetMapping("/pareto")
    public Map<String, Object> pareto(@RequestParam(defaultValue = "code") String by,
                                      @RequestParam(required = false) Long from,
                                      @RequestParam(required = false) Long to) {
        try {
            long toTs = to == null ? System.currentTimeMillis() : to;
            long fromTs = from == null ? toTs - 30L * 24 * 3600_000L : from;
            List<Map<String, Object>> rows = service.pareto(by, fromTs, toTs);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("by", "device".equalsIgnoreCase(by) ? "device" : "code");
            out.put("from", fromTs);
            out.put("to", toTs);
            out.put("rows", rows);
            out.put("total", rows.size());
            return out;
        } catch (Exception e) {
            return ErpQcBody.fail(e);
        }
    }

    /** 看板默认窗口（前端展示"最近 N 天"时用同一口径） */
    static long[] lastDays(int days) {
        long to = System.currentTimeMillis();
        long from = to - days * 24L * 3600_000L;
        return new long[]{from, to};
    }

    /** 按天取窗口（导出报表按天切时用） */
    static long[] dayRange(LocalDate day, ZoneId zone) {
        long from = day.atStartOfDay(zone).toInstant().toEpochMilli();
        long to = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
        return new long[]{from, to};
    }
}
