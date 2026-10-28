package com.factory.hub.ai;

import java.util.ArrayList;
import java.util.List;

/**
 * AI 早报配置。
 * <p>
 * 用 OpenAI 兼容协议（`/v1/chat/completions`）而不是绑定某一家：
 * DeepSeek、通义千问、智谱、Moonshot、本地 Ollama、vLLM 都兼容这个格式，
 * 客户想换供应商只改 baseUrl 和 model，代码不用动。国内客户大多用不了境外接口，
 * 所以默认值是 DeepSeek，但**不写死在代码里**，页面上可改。
 * <p>
 * <b>明确不做的事：</b>不让模型直接读数据库、不让它生成 SQL、不让它下发控制指令。
 * 它只做一件事——把已经算好的数字写成一段人话。数据和结论都在本地算，
 * 模型挂了最多没有润色，不会给出错误的生产数字。
 */
public class AiConfig {

    /** 总开关。关掉后早报仍然会生成，但用本地模板（不调模型、不花钱） */
    private boolean enabled = false;

    /** 形如 https://api.deepseek.com/v1（末尾不要带 /chat/completions） */
    private String baseUrl = "https://api.deepseek.com/v1";

    private String apiKey = "";

    private String model = "deepseek-chat";

    /** 输出上限。早报就是一屏，给太多纯浪费钱 */
    private int maxTokens = 700;

    /** 低温：早报要稳，不要发挥 */
    private double temperature = 0.3;

    /** 每天几点几分生成（服务器本地时区） */
    private int hour = 7;

    private int minute = 0;

    /** 生成后推到企业微信/钉钉群 */
    private boolean pushToWechat = true;

    /** 自定义提示词，留空用内置的 */
    private String systemPrompt = "";

    /** 给非管理员回显用：其它字段照给，Key 清空（页面只展示"已配置/未配置"） */
    public AiConfig copyWithoutKey() {
        AiConfig c = new AiConfig();
        c.setEnabled(enabled);
        c.setBaseUrl(baseUrl);
        c.setModel(model);
        c.setMaxTokens(maxTokens);
        c.setTemperature(temperature);
        c.setHour(hour);
        c.setMinute(minute);
        c.setPushToWechat(pushToWechat);
        c.setSystemPrompt(systemPrompt);
        return c;
    }

    public List<String> validate() {
        List<String> out = new ArrayList<>();
        if (!enabled) {
            return out;
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            out.add("没填 API 地址（如 https://api.deepseek.com/v1）");
        } else if (!baseUrl.startsWith("http")) {
            out.add("API 地址要以 http:// 或 https:// 开头");
        }
        if (apiKey == null || apiKey.isBlank()) {
            out.add("没填 API Key，早报会退回本地模板（能出，但只有数据没有分析）");
        }
        if (model == null || model.isBlank()) {
            out.add("没填模型名（如 deepseek-chat / qwen-plus / glm-4）");
        }
        if (hour < 0 || hour > 23 || minute < 0 || minute > 59) {
            out.add("生成时间不合法");
        }
        return out;
    }

    /** 拼出完整的 chat/completions 地址，容忍用户把 /v1 漏了或带多了斜杠 */
    public String endpoint() {
        String b = baseUrl == null ? "" : baseUrl.trim();
        while (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        if (b.endsWith("/chat/completions")) {
            return b;
        }
        return b + "/chat/completions";
    }

    public String effectivePrompt() {
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            return systemPrompt;
        }
        return """
                你是工厂设备管理助手。根据下面这份「昨天」的统计写一份中文早报。
                要求：
                1. 先一句总体结论，再 3~5 条要点，每条一行，用「-」开头；
                2. 只使用给定的数字，不要编造，没给的就不要提；
                3. 有异常（离线、停机多、报警未处理、需量逼近合同、产量下滑）时要点名设备并给一句可执行建议；
                4. 一切正常就直说正常，不要强行找问题；
                5. 全文不超过 250 字，不要用 markdown 标题和代码块。
                """;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(int maxTokens) {
        this.maxTokens = maxTokens;
    }

    public double getTemperature() {
        return temperature;
    }

    public void setTemperature(double temperature) {
        this.temperature = temperature;
    }

    public int getHour() {
        return hour;
    }

    public void setHour(int hour) {
        this.hour = hour;
    }

    public int getMinute() {
        return minute;
    }

    public void setMinute(int minute) {
        this.minute = minute;
    }

    public boolean isPushToWechat() {
        return pushToWechat;
    }

    public void setPushToWechat(boolean pushToWechat) {
        this.pushToWechat = pushToWechat;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }
}
