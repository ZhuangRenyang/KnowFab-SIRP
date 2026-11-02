package com.factory.hub.ai;

import com.factory.hub.audit.AuditLog;
import com.factory.hub.audit.AuditService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 早报与 AI 配置接口 */
@RestController
@RequestMapping("/api/ai")
public class DigestController {

    private final DigestService service;
    private final AiConfigStore store;
    private final AiClient client;
    private final AuditService audit;

    public DigestController(DigestService service, AiConfigStore store, AiClient client,
                            AuditService audit) {
        this.service = service;
        this.store = store;
        this.client = client;
        this.audit = audit;
    }

    @GetMapping("/digest")
    public Map<String, Object> latest() {
        return service.latest();
    }

    /** 手动生成。默认生成昨天的；传 date 可以补生成某一天 */
    @PostMapping("/digest/generate")
    public Map<String, Object> generate(@RequestBody(required = false) Map<String, Object> body) {
        LocalDate date = LocalDate.now().minusDays(1);
        boolean push = true;
        if (body != null) {
            Object d = body.get("date");
            if (d != null && !String.valueOf(d).isBlank()) {
                try {
                    date = LocalDate.parse(String.valueOf(d).trim());
                } catch (Exception ignored) {
                }
            }
            push = !Boolean.FALSE.equals(body.get("push"));
        }
        return service.generate(date, push);
    }

    @GetMapping("/config")
    public Map<String, Object> config() {
        AiConfig c = store.current();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        if (isAdmin()) {
            out.put("config", c);
        } else {
            // 非管理员只回"有没有配 Key"，绝不回明文——否则任何登录用户都能把 Key 偷走
            AiConfig masked = c.copyWithoutKey();
            out.put("config", masked);
            out.put("hasKey", c.getApiKey() != null && !c.getApiKey().isBlank());
        }
        out.put("warnings", c.validate());
        return out;
    }

    private boolean isAdmin() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a != null && a.getAuthorities().stream()
                .anyMatch(x -> "ROLE_ADMIN".equals(x.getAuthority()));
    }

    /**
     * 保存 AI 早报配置。
     * <p>
     * 审计写"改了哪些字段"，所以必须先把旧配置读出来再存——
     * 存完只剩新值，就说不清到底改了什么。
     * <p>
     * <b>API Key 绝不进审计</b>：只记"密钥已更新"，不记值本身。
     */
    @PostMapping("/config")
    public Map<String, Object> save(@RequestBody AiConfig cfg) {
        AiConfig old = store.current();
        store.save(cfg);
        audit.ok(AuditLog.CAT_SYSTEM, AuditLog.ACT_UPDATE, "ai-config", "AI 早报配置",
                describeChanges(old, cfg));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("message", "已保存，下一次生成时生效");
        out.put("warnings", cfg.validate());
        return out;
    }

    /* ==================== 审计辅助 ==================== */

    /** 配置变更摘要。密钥类字段只说"已更新"，不写值 */
    private String describeChanges(AiConfig old, AiConfig now) {
        List<String> parts = new java.util.ArrayList<>();
        if (old == null) {
            parts.add("首次保存 AI 早报配置");
        } else {
            if (old.isEnabled() != now.isEnabled()) {
                parts.add(now.isEnabled() ? "启用 AI 早报" : "停用 AI 早报");
            }
            if (!same(old.getModel(), now.getModel())) {
                parts.add("模型由 " + show(old.getModel()) + " 改为 " + show(now.getModel()));
            }
            if (!same(old.getBaseUrl(), now.getBaseUrl())) {
                parts.add("API 地址由 " + show(old.getBaseUrl()) + " 改为 " + show(now.getBaseUrl()));
            }
            if (old.getMaxTokens() != now.getMaxTokens()) {
                parts.add("输出上限由 " + old.getMaxTokens() + " 改为 " + now.getMaxTokens());
            }
            if (old.getHour() != now.getHour() || old.getMinute() != now.getMinute()) {
                parts.add("生成时间由 " + hhmm(old.getHour(), old.getMinute())
                        + " 改为 " + hhmm(now.getHour(), now.getMinute()));
            }
            if (old.isPushToWechat() != now.isPushToWechat()) {
                parts.add(now.isPushToWechat() ? "开启推送到群" : "关闭推送到群");
            }
            // 密钥：只比较"变没变"，值一个字都不碰
            if (!same(old.getApiKey(), now.getApiKey())) {
                parts.add("（密钥已更新，不记录）");
            }
        }
        if (parts.isEmpty()) {
            return "保存 AI 早报配置（值没有变化）";
        }
        return "保存 AI 早报配置：" + String.join("、", parts);
    }

    private static boolean same(String a, String b) {
        return (a == null ? "" : a).trim().equals(b == null ? "" : b.trim());
    }

    private static String show(String s) {
        return s == null || s.isBlank() ? "（空）" : s.trim();
    }

    private static String hhmm(int hour, int minute) {
        return String.format("%02d:%02d", hour, minute);
    }

    /** 连通性测试：真发一句话过去，把模型的回复或错误原样带回来 */
    @PostMapping("/test")
    public Map<String, Object> test() {
        AiConfig cfg = store.current();
        Map<String, Object> out = new LinkedHashMap<>();
        if (cfg.getApiKey() == null || cfg.getApiKey().isBlank()) {
            out.put("ok", false);
            out.put("message", "还没填 API Key");
            return out;
        }
        try {
            AiClient.Reply r = client.ping(cfg);
            out.put("ok", true);
            out.put("message", "连接正常，模型回复：" + r.text());
            out.put("tokens", r.tokens());
        } catch (Exception e) {
            out.put("ok", false);
            out.put("message", "调用失败：" + e.getMessage());
        }
        return out;
    }

    /** 提示词模板，前端展示用 */
    @GetMapping("/prompt")
    public List<String> prompt() {
        return List.of(new AiConfig().effectivePrompt());
    }
}
