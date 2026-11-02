package com.factory.hub.manual;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;

/**
 * 数据补录接口（DH-13）。
 * <p>
 * 管理端接口：补录会改报表数字，不能让 /open/v1 的外部 Key 调。
 */
@RestController
@RequestMapping("/api/manual")
public class ManualController {

    private final ManualEntryService service;

    public ManualController(ManualEntryService service) {
        this.service = service;
    }

    /** 补录记录（含已撤销），默认当月 */
    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) String from,
                                    @RequestParam(required = false) String to) {
        return service.list(parse(from), parse(to));
    }

    /** 新增补录：设备 + 日期范围 + 电量/产量 + 原因 */
    @PostMapping
    public Map<String, Object> create(@RequestBody ManualEntryRequest req,
                                      jakarta.servlet.http.HttpServletRequest http) {
        return service.create(req, operatorOf(http));
    }

    /** 撤销一条补录；记录保留，只是不再进统计 */
    @PostMapping("/{id}/revoke")
    public Map<String, Object> revoke(@PathVariable Long id,
                                      jakarta.servlet.http.HttpServletRequest http) {
        return service.revoke(id, operatorOf(http));
    }

    /** 审计日志里记谁下的手（与设备下发同一套取法） */
    private String operatorOf(jakarta.servlet.http.HttpServletRequest req) {
        try {
            java.security.Principal p = req.getUserPrincipal();
            if (p != null && p.getName() != null && !p.getName().isBlank()) {
                return p.getName();
            }
            return req.getRemoteAddr();
        } catch (Exception e) {
            return "unknown";
        }
    }

    private LocalDate parse(String s) {
        try {
            if (s != null && !s.isBlank()) {
                return LocalDate.parse(s.trim());
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
