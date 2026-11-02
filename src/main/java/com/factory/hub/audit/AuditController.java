package com.factory.hub.audit;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;

/**
 * 操作审计查询（DH-24）。只读：没有新增/修改/删除接口。
 * <p>
 * 审计由业务代码在动作发生时写入，不靠前端上报——前端能伪造的操作人没有意义。
 */
@RestController
@RequestMapping("/api/audit")
public class AuditController {

    private final AuditService service;

    public AuditController(AuditService service) {
        this.service = service;
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) String from,
                                    @RequestParam(required = false) String to,
                                    @RequestParam(required = false, defaultValue = "") String category,
                                    @RequestParam(required = false, defaultValue = "") String keyword,
                                    @RequestParam(required = false, defaultValue = "200") int limit) {
        return service.query(parse(from), parse(to), category, keyword, limit);
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
