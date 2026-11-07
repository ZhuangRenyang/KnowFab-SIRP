package com.factory.hub.erp.production;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 生产工单排程（DH-72）。
 * <p>
 * <b>权限</b>：读登录即可；排程/取消排程沿用 {@code /api/erp/**} 的 ADMIN|MANAGER
 * （排程会改动产线的占用关系，班组长不能自己改）。
 * <p>
 * 失败一律 200 + {@code ok:false}：前端要把「与同产线的工单 WOxxx 时间重叠」
 * 这句人话弹出来给人看。
 * <p>
 * <b>这不是自动排产</b>——只是登记计划时间 + 检测同产线冲突 + 供甘特视图取数。
 */
@RestController
@RequestMapping("/api/erp/schedule")
public class ErpScheduleController {

    private final ErpScheduleService service;

    public ErpScheduleController(ErpScheduleService service) {
        this.service = service;
    }

    /** 甘特数据：按产线分组 + 未排程清单 + 全部冲突 */
    @GetMapping
    public Map<String, Object> gantt(@RequestParam(required = false) Long from,
                                     @RequestParam(required = false) Long to) {
        return service.gantt(from, to);
    }

    /** 全部冲突（同产线时间重叠） */
    @GetMapping("/conflicts")
    public Map<String, Object> conflicts() {
        Map<String, Object> out = new LinkedHashMap<>();
        List<ErpScheduleService.Conflict> cs = service.allConflicts();
        out.put("ok", true);
        out.put("count", cs.size());
        out.put("conflicts", cs);
        return out;
    }

    /**
     * 给工单排程。
     *
     * @param body planStart / planEnd（毫秒时间戳），force=true 时有冲突也排
     */
    @PostMapping("/{orderNo}")
    public Map<String, Object> schedule(@PathVariable String orderNo,
                                        @RequestBody Map<String, Object> body) {
        Long start = num(body.get("planStart"));
        Long end = num(body.get("planEnd"));
        boolean force = Boolean.TRUE.equals(body.get("force"));
        String lineCode = body.get("lineCode") == null ? null : String.valueOf(body.get("lineCode"));
        ErpScheduleService.ScheduleResult r = service.schedule(orderNo, start, end, force, lineCode);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", r.ok);
        out.put("message", r.message);
        out.put("conflicts", r.conflicts);
        out.put("warnings", r.warnings);
        return out;
    }

    /** 取消排程（回到未排程） */
    @DeleteMapping("/{orderNo}")
    public Map<String, Object> unschedule(@PathVariable String orderNo) {
        ErpScheduleService.ScheduleResult r = service.unschedule(orderNo);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", r.ok);
        out.put("message", r.message);
        return out;
    }

    private static Long num(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number) {
            return ((Number) o).longValue();
        }
        try {
            return Long.parseLong(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
