package com.factory.hub.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容的 chat/completions 客户端。
 * <p>
 * 只用 JDK 自带的 HttpClient，不引第三方 SDK——那些 SDK 动辄几十兆，
 * 而我们只调一个接口。协议是业界事实标准，DeepSeek / 通义 / 智谱 / Moonshot /
 * Ollama / vLLM 都认。
 * <p>
 * <b>超时与失败策略：</b>连接 8 秒、整体 40 秒。这是给"早上七点自动跑"用的，
 * 慢一点没关系，但不能卡死——卡住会让调度线程一直占着，
 * 所以任何异常都往上抛，由调用方降级到本地模板。
 */
@Component
public class AiClient {

    private static final Logger log = LoggerFactory.getLogger(AiClient.class);

    private final ObjectMapper mapper;

    public AiClient(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** 调用结果：文本 + 用了多少 token（方便算钱） */
    public record Reply(String text, int tokens) {
    }

    public Reply chat(AiConfig cfg, String systemPrompt, String userContent) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", cfg.getModel());
        body.put("temperature", cfg.getTemperature());
        body.put("max_tokens", cfg.getMaxTokens());
        body.put("stream", false);
        body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userContent)));

        JsonNode root = post(cfg, body);
        JsonNode choice = root.path("choices").path(0).path("message").path("content");
        String text = choice.asText("");
        if (text.isBlank()) {
            throw new IllegalStateException("接口返回里没有内容，检查模型名是否正确");
        }
        int tokens = root.path("usage").path("total_tokens").asInt(0);
        log.info("AI 调用成功：模型 {}", cfg.getModel());
        return new Reply(text.trim(), tokens);
    }

    /** 模型要求调用的一个工具 */
    public record ToolCall(String id, String name, String arguments) {
    }

    /** 带工具的回复：content 为空且 toolCalls 非空 = 模型要先把活交给工具干 */
    public record ToolReply(String content, List<ToolCall> toolCalls, int tokens) {
    }

    /**
     * 助手专用：带 tools 的对话。messages 直接透传前端历史 + 工具结果，
     * 返回原始 assistant message（content + tool_calls），循环由 AiChatService 控制。
     */
    public ToolReply chatWithTools(AiConfig cfg, List<Map<String, Object>> messages,
                                   List<Map<String, Object>> tools) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", cfg.getModel());
        body.put("temperature", Math.min(0.5, cfg.getTemperature()));
        // 助手要引用一串数字，700 太紧；但也不给天价
        body.put("max_tokens", Math.max(600, Math.min(2000, cfg.getMaxTokens())));
        body.put("stream", false);
        body.put("messages", messages);
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", tools);
            body.put("tool_choice", "auto");
        }
        JsonNode root = post(cfg, body);
        JsonNode msg = root.path("choices").path(0).path("message");
        List<ToolCall> calls = new ArrayList<>();
        for (JsonNode c : msg.path("tool_calls")) {
            calls.add(new ToolCall(c.path("id").asText(""), c.path("function").path("name").asText(""),
                    c.path("function").path("arguments").asText("{}")));
        }
        return new ToolReply(msg.path("content").asText(""), calls,
                root.path("usage").path("total_tokens").asInt(0));
    }

    /** 公共 HTTP：超时/错误处理只有这一份，错误信息截短给用户看得懂 */
    private JsonNode post(AiConfig cfg, Map<String, Object> body) throws Exception {
        String json = mapper.writeValueAsString(body);
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(cfg.endpoint()))
                .timeout(Duration.ofSeconds(45))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + safeKey(cfg.getApiKey()))
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();

        long t0 = System.currentTimeMillis();
        HttpResponse<String> resp = client.send(req,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        long cost = System.currentTimeMillis() - t0;

        if (resp.statusCode() / 100 != 2) {
            // 把服务端的错误信息截出来，欠费/模型名错/key 失效 都靠这句话定位
            String snippet = resp.body() == null ? "" : resp.body().replaceAll("\\s+", " ");
            if (snippet.length() > 180) {
                snippet = snippet.substring(0, 180);
            }
            throw new IllegalStateException("HTTP " + resp.statusCode() + "：" + snippet);
        }
        log.info("AI 接口耗时 {} ms（模型 {}）", cost, cfg.getModel());
        return mapper.readTree(resp.body());
    }

    /** 连通性测试：一句话往返，页面「测试」按钮用 */
    public Reply ping(AiConfig cfg) throws Exception {
        return chat(cfg, "你是一个测试助手。", "只回复两个字：连接正常");
    }

    private static String safeKey(String key) {
        return key == null ? "" : key.trim();
    }
}
