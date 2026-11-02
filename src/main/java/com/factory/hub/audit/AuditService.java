package com.factory.hub.audit;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.security.Principal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 审计记录。业务代码一行调用，操作人/IP 自动从当前请求里取。
 * <p>
 * <b>绝不因为审计失败影响业务</b>：记日志挂了只 warn，不能让客户改电价失败。
 * 代价是极端情况下可能丢一条审计，但比"功能不可用"好。
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditLogRepository repo;

    public AuditService(AuditLogRepository repo) {
        this.repo = repo;
    }

    public void ok(String category, String action, String target, String targetName, String detail) {
        write(category, action, target, targetName, detail, true, null);
    }

    public void fail(String category, String action, String target, String targetName,
                     String detail, String message) {
        write(category, action, target, targetName, detail, false, message);
    }

    private void write(String category, String action, String target, String targetName,
                       String detail, boolean ok, String message) {
        try {
            repo.save(new AuditLog(System.currentTimeMillis(),
                    category, action, cut(target, 120), cut(targetName, 150),
                    cut(detail, 1900), operator(), ip(), ok, cut(message, 280)));
        } catch (Exception e) {
            log.warn("审计写入失败（不影响业务）：{} {} {} - {}", category, action, target, e.getMessage());
        }
    }

    /** 查询。默认最近 7 天，limit 上限 1000 条，避免一次把几年日志拉进浏览器 */
    public Map<String, Object> query(LocalDate from, LocalDate to, String category,
                                     String keyword, int limit) {
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        LocalDate f = from == null ? today.minusDays(6) : from;
        LocalDate t = to == null ? today : to;
        if (t.isBefore(f)) {
            LocalDate tmp = f;
            f = t;
            t = tmp;
        }
        long fromTs = f.atStartOfDay(zone).toInstant().toEpochMilli();
        long toTs = t.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
        String cat = category == null ? "" : category.trim().toUpperCase();
        String kw = keyword == null ? "" : keyword.trim().toLowerCase();
        int n = Math.max(1, Math.min(1000, limit));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (AuditLog a : repo.search(fromTs, toTs, cat, kw, "%" + kw + "%", PageRequest.of(0, n))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", a.getId());
            m.put("at", a.getAt());
            m.put("category", a.getCategory());
            m.put("action", a.getAction());
            m.put("target", a.getTarget());
            m.put("targetName", a.getTargetName());
            m.put("detail", a.getDetail());
            m.put("operator", a.getOperator());
            m.put("ip", a.getIp());
            m.put("ok", a.isOk());
            m.put("message", a.getMessage());
            rows.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("from", f.toString());
        out.put("to", t.toString());
        out.put("limit", n);
        out.put("rows", rows);
        return out;
    }

    private static String operator() {
        try {
            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) {
                return "system";
            }
            HttpServletRequest req = attrs.getRequest();
            Principal p = req.getUserPrincipal();
            if (p != null && p.getName() != null && !p.getName().isBlank()) {
                return p.getName();
            }
            return "anonymous";
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static String ip() {
        try {
            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) {
                return "";
            }
            HttpServletRequest req = attrs.getRequest();
            String xff = req.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                // 取第一段：后面的都是代理
                int i = xff.indexOf(',');
                return (i > 0 ? xff.substring(0, i) : xff).trim();
            }
            return req.getRemoteAddr();
        } catch (Exception e) {
            return "";
        }
    }

    private static String cut(String s, int max) {
        if (s == null) {
            return "";
        }
        String v = s.replace("\r", " ").replace("\n", " ").trim();
        return v.length() <= max ? v : v.substring(0, max) + "…";
    }
}
