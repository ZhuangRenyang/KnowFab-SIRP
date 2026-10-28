package com.factory.hub.ai;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 助手接口（DH-47）。
 * <p>
 * 权限：POST /chat 任何登录用户可用——AI 侧的工具已按角色收口，
 * 这里不再设门槛（销售也能问库存，这正是"全员 AI 助手"的定义）。
 * GET /tools 给前端判断"当前角色能用哪些查询"，同样是登录即可。
 * <p>
 * 角色从会话取，<b>绝不信任前端传的 role</b>——模型再聪明也不能替用户提权。
 */
@RestController
@RequestMapping("/api/ai/chat")
public class AiChatController {

    private final AiChatService service;
    private final AiConfigStore store;

    public AiChatController(AiChatService service, AiConfigStore store) {
        this.service = service;
        this.store = store;
    }

    /** 当前角色可用的查询工具 + AI 开关状态（前端据此决定展示引导还是输入框） */
    @GetMapping("/tools")
    public Map<String, Object> tools() {
        String role = role();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("role", role);
        AiConfig cfg = store.current();
        out.put("enabled", cfg.isEnabled());
        out.put("configured", cfg.getApiKey() != null && !cfg.getApiKey().isBlank());
        out.put("model", cfg.getModel());
        out.put("tools", service.toolsFor(role));
        return out;
    }

    /** 本人最近对话（刷新/换设备后接续） */
    @GetMapping("/history")
    public Map<String, Object> history(@RequestParam(defaultValue = "30") int limit) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("messages", service.history(operator(), limit));
        return out;
    }

    /** 只清本人的记录 */
    @org.springframework.web.bind.annotation.DeleteMapping("/history")
    public Map<String, Object> clearHistory() {
        return service.clearHistory(operator());
    }

    /** 经营解读（DH-48）：事实本地算，AI 只组织语言；服务层限管理员/车间主任 */
    @PostMapping("/interpret")
    public Map<String, Object> interpret(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        return service.interpret(role(),
                b.get("from") == null ? null : String.valueOf(b.get("from")),
                b.get("to") == null ? null : String.valueOf(b.get("to")));
    }

    @PostMapping
    public Map<String, Object> chat(@RequestBody Map<String, Object> body) {
        Object raw = body.get("messages");
        List<Map<String, Object>> history = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> mm = (Map<String, Object>) m;
                    history.add(mm);
                }
            }
        }
        return service.chat(role(), operator(), history);
    }

    private String role() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null || a.getAuthorities().isEmpty()) {
            return "";
        }
        // ROLE_ 前缀是 Spring 加的，库里存的是裸角色名
        String r = a.getAuthorities().iterator().next().getAuthority();
        return r.startsWith("ROLE_") ? r.substring(5) : r;
    }

    private String operator() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a == null || a.getName() == null ? "anonymous" : a.getName();
    }
}
