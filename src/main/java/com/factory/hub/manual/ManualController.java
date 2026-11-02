package com.factory.hub.manual;

import com.factory.hub.audit.AuditLog;
import com.factory.hub.audit.AuditService;
import com.factory.hub.model.DeviceEntity;
import com.factory.hub.service.DeviceService;
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
    private final DeviceService deviceService;
    private final AuditService audit;

    public ManualController(ManualEntryService service, DeviceService deviceService,
                            AuditService audit) {
        this.service = service;
        this.deviceService = deviceService;
        this.audit = audit;
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
        Map<String, Object> out = service.create(req, operatorOf(http));
        String code = req == null || req.deviceCode() == null ? "" : req.deviceCode().trim();
        String deviceName = deviceNameOf(code);
        String detail = describe(req, deviceName);
        if (isOk(out)) {
            audit.ok(AuditLog.CAT_MANUAL, AuditLog.ACT_CREATE, code, deviceName, detail);
        } else {
            audit.fail(AuditLog.CAT_MANUAL, AuditLog.ACT_CREATE, code, deviceName,
                    detail, messageOf(out));
        }
        return out;
    }

    /** 撤销一条补录；记录保留，只是不再进统计 */
    @PostMapping("/{id}/revoke")
    public Map<String, Object> revoke(@PathVariable Long id,
                                      jakarta.servlet.http.HttpServletRequest http) {
        Map<String, Object> out = service.revoke(id, operatorOf(http));
        String detail = "撤销补录 #" + id;
        if (isOk(out)) {
            audit.ok(AuditLog.CAT_MANUAL, AuditLog.ACT_REVOKE, String.valueOf(id),
                    "补录 #" + id, detail);
        } else {
            audit.fail(AuditLog.CAT_MANUAL, AuditLog.ACT_REVOKE, String.valueOf(id),
                    "补录 #" + id, detail, messageOf(out));
        }
        return out;
    }

    /* ==================== 审计辅助 ==================== */

    /** 补录摘要：谁、哪台设备、哪段时间、多少电多少产量、为什么补 */
    private String describe(ManualEntryRequest req, String deviceName) {
        if (req == null) {
            return "补录数据";
        }
        String from = req.fromDay() == null ? "?" : req.fromDay().toString();
        String to = req.toDay() == null ? from : req.toDay().toString();
        // 服务层会把「结束日期晚于今天」截到今天，审计文字要跟着截，
        // 否则审计里写的区间和真正入库的对不上，这条记录就不可信了
        LocalDate today = LocalDate.now();
        if (req.fromDay() != null && req.toDay() != null && req.toDay().isAfter(today)) {
            to = today.toString();
        }
        String name = deviceName == null || deviceName.isBlank() ? "未知设备" : deviceName;
        return "补录 " + name + " " + from + "~" + to
                + "，电量 " + nz(req.kwh()) + " kWh，产量 " + nz(req.output()) + " 件"
                + "，原因：" + (req.reason() == null || req.reason().isBlank() ? "未填写" : req.reason());
    }

    /** 设备名。查不到就用编码，总比空着强 */
    private String deviceNameOf(String code) {
        if (code == null || code.isEmpty()) {
            return "";
        }
        try {
            DeviceEntity d = deviceService.byCode(code);
            return d == null || d.getName() == null || d.getName().isBlank() ? code : d.getName();
        } catch (Exception e) {
            return code;
        }
    }

    private static double nz(Double v) {
        return v == null ? 0d : Math.round(v * 100d) / 100d;
    }

    private static boolean isOk(Map<String, Object> out) {
        return out != null && Boolean.TRUE.equals(out.get("ok"));
    }

    private static String messageOf(Map<String, Object> out) {
        if (out == null) {
            return "未知错误";
        }
        Object m = out.get("message");
        return m == null ? "操作失败" : String.valueOf(m);
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
