package com.factory.hub.ai;

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

    public DigestController(DigestService service, AiConfigStore store, AiClient client) {
        this.service = service;
        this.store = store;
        this.client = client;
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

    @PostMapping("/config")
    public Map<String, Object> save(@RequestBody AiConfig cfg) {
        store.save(cfg);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("message", "已保存，下一次生成时生效");
        out.put("warnings", cfg.validate());
        return out;
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
