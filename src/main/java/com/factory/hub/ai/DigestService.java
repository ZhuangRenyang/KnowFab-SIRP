package com.factory.hub.ai;

import com.factory.hub.alarm.AlarmNotifier;
import com.factory.hub.config.AppSetting;
import com.factory.hub.config.AppSettingRepository;
import com.factory.hub.demand.DemandService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 早报生成。
 * <p>
 * <b>核心原则：AI 是润色，不是数据源。</b>
 * 所有数字由 {@link DigestDataBuilder} 在本地算好，模型只负责把它们写成一段人话。
 * 所以：
 * <ul>
 *   <li>没配 API Key → 用本地模板出早报，功能照常</li>
 *   <li>模型超时/欠费/被墙 → 降级到本地模板，并记录失败原因</li>
 *   <li>模型胡说八道 → 最坏是措辞难听，不会出现错误的生产数字</li>
 * </ul>
 * 这条设计决定了"客户断网也能收到早报"，是能不能交付的分界线。
 */
@Service
public class DigestService {

    private static final Logger log = LoggerFactory.getLogger(DigestService.class);
    private static final ZoneId ZONE = ZoneId.systemDefault();
    private static final String KEY_LAST_RUN = "ai.digest.lastRun";

    private final DigestDataBuilder builder;
    private final DailyDigestRepository repo;
    private final AiConfigStore configStore;
    private final AiClient aiClient;
    private final AlarmNotifier notifier;
    private final DemandService demandService;
    private final AppSettingRepository settings;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;

    public DigestService(DigestDataBuilder builder, DailyDigestRepository repo,
                         AiConfigStore configStore, AiClient aiClient,
                         AlarmNotifier notifier, DemandService demandService,
                         AppSettingRepository settings,
                         com.fasterxml.jackson.databind.ObjectMapper mapper) {
        this.builder = builder;
        this.repo = repo;
        this.configStore = configStore;
        this.aiClient = aiClient;
        this.notifier = notifier;
        this.demandService = demandService;
        this.settings = settings;
        this.mapper = mapper;
    }

    /**
     * 生成某一天的早报。
     *
     * @param date 要总结的日期（通常是昨天）
     * @param push 是否推到微信群
     */
    public Map<String, Object> generate(LocalDate date, boolean push) {
        AiConfig cfg = configStore.current();
        DigestDataBuilder.Facts f = builder.build(date);
        fillDemand(f);

        String content;
        String source;
        String model = null;
        int tokens = 0;
        String error = null;

        if (cfg.isEnabled() && cfg.getApiKey() != null && !cfg.getApiKey().isBlank()) {
            try {
                AiClient.Reply r = aiClient.chat(cfg, cfg.effectivePrompt(), f.toPromptText());
                content = r.text();
                source = "AI";
                model = cfg.getModel();
                tokens = r.tokens();
            } catch (Exception e) {
                // 不抛给上层：早报必须有，哪怕降级。
                // ConnectException 的 getMessage() 是 null，只写 message 会得到空原因，
                // 客户看到"已降级"却不知道为什么——把异常类型带上
                String msg = e.getMessage();
                error = e.getClass().getSimpleName() + (msg == null || msg.isBlank() ? "" : "：" + msg);
                log.warn("AI 早报生成失败，降级本地模板：{}", error);
                content = localText(f, date, error);
                source = "LOCAL";
            }
        } else {
            content = localText(f, date, cfg.isEnabled() ? "没配 API Key" : "AI 未启用");
            source = "LOCAL";
        }

        // 不用 orElseGet(lambda)：content/source 在分支里赋值，不是 effectively final
        DailyDigest d = repo.findByDate(date.toString()).orElse(null);
        if (d == null) {
            d = new DailyDigest();
        }
        d.setDate(date.toString());
        d.setContent(content);
        d.setSource(source);
        d.setModel(model);
        d.setTokens(tokens);
        d.setOk(true);
        d.setError(error == null ? null : (error.length() > 480 ? error.substring(0, 480) : error));
        d.setCreatedAt(System.currentTimeMillis());
        try {
            d.setFactsJson(mapper.writeValueAsString(factsMap(f)));
        } catch (Exception ignored) {
        }
        repo.save(d);

        boolean pushed = false;
        if (push) {
            try {
                pushed = notifier.sendBulletin("设备早报 · " + date, toMarkdown(d, f));
            } catch (Exception e) {
                log.warn("早报推送失败：{}", e.getMessage());
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("date", d.getDate());
        out.put("content", d.getContent());
        out.put("source", d.getSource());
        out.put("model", d.getModel());
        out.put("tokens", d.getTokens());
        out.put("degraded", "LOCAL".equals(d.getSource()) && cfg.isEnabled());
        out.put("error", d.getError());
        out.put("pushed", pushed);
        out.put("facts", factsMap(f));
        return out;
    }

    /** 需量峰值单独补：避免 DigestDataBuilder 反向依赖 DemandService */
    private void fillDemand(DigestDataBuilder.Facts f) {
        try {
            double peak = demandService.monthPeakKw(YearMonth.now());
            f.demandPeak = peak;
            if (peak <= 0) {
                f.peakAt = "--";
            }
        } catch (Exception ignored) {
        }
    }

    private String localText(DigestDataBuilder.Facts f, LocalDate date, String reason) {
        StringBuilder sb = new StringBuilder();
        sb.append(date).append(" 设备早报").append(reason == null || reason.isBlank() ? "" : "（AI 未启用）").append('\n');
        for (String line : f.localLines()) {
            sb.append("- ").append(line).append('\n');
        }
        if (reason != null && !reason.isBlank()) {
            sb.append("注：本次未使用 AI（").append(reason).append("），以上为系统直接统计。");
        }
        return sb.toString().trim();
    }

    /** 推到微信群时加一层 markdown：手机上看要短、要有重点 */
    private String toMarkdown(DailyDigest d, DigestDataBuilder.Facts f) {
        StringBuilder sb = new StringBuilder(content2Md(d.getContent()));
        sb.append("\n\n> 设备 ").append(f.online).append('/').append(f.deviceTotal).append(" 在线")
                .append("，报警未处理 ").append(f.alarmOpen).append(" 条")
                .append("，昨天产量 ").append(Math.round(f.output)).append(" 件");
        if (d.getSource().startsWith("LOCAL")) {
            sb.append("\n> 本次为系统统计（未调用 AI）");
        }
        return sb.toString();
    }

    /** 把模型给的「- 列表」贴到企业微信 markdown 里，避免整段挤成一坨 */
    private String content2Md(String text) {
        if (text == null) {
            return "";
        }
        String t = text.replace("\r", "");
        return "**" + t.replace("\n", "  \n") + "**";
    }

    private Map<String, Object> factsMap(DigestDataBuilder.Facts f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("deviceTotal", f.deviceTotal);
        m.put("online", f.online);
        m.put("alarmNew", f.alarmNew);
        m.put("alarmOpen", f.alarmOpen);
        m.put("alarmCriticalOpen", f.alarmCriticalOpen);
        m.put("output", Math.round(f.output));
        m.put("outputAvg7", Math.round(f.outputAvg7));
        m.put("stopHours", Math.round(f.stopHours * 10d) / 10d);
        m.put("kwh", Math.round(f.kwh));
        m.put("cost", Math.round(f.cost));
        m.put("demandPeak", Math.round(f.demandPeak));
        m.put("contractDemand", Math.round(f.contractDemand));
        m.put("offlineTop", f.offlineTop);
        m.put("alarmTopDevices", f.alarmTopDevices);
        return m;
    }

    /* ==================== 查询 ==================== */

    public Map<String, Object> latest() {
        Map<String, Object> out = new LinkedHashMap<>();
        List<DailyDigest> all = repo.findTop30ByOrderByDateDesc();
        AiConfig cfg = configStore.current();
        out.put("ok", true);
        out.put("enabled", cfg.isEnabled());
        out.put("hour", cfg.getHour());
        out.put("minute", cfg.getMinute());
        out.put("model", cfg.getModel());
        out.put("hasKey", cfg.getApiKey() != null && !cfg.getApiKey().isBlank());
        out.put("warnings", cfg.validate());
        out.put("lastRunDate", lastRun());
        out.put("nextRun", nextRunText(cfg));
        if (!all.isEmpty()) {
            out.put("digest", view(all.get(0)));
        }
        out.put("history", all.stream().map(this::view).toList());
        return out;
    }

    private Map<String, Object> view(DailyDigest d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("date", d.getDate());
        m.put("content", d.getContent());
        m.put("source", d.getSource());
        m.put("model", d.getModel());
        m.put("tokens", d.getTokens());
        m.put("error", d.getError());
        m.put("createdAt", d.getCreatedAt());
        m.put("createdText", Instant.ofEpochMilli(d.getCreatedAt()).atZone(ZONE)
                .format(DateTimeFormatter.ofPattern("MM-dd HH:mm")));
        if (d.getFactsJson() != null && !d.getFactsJson().isBlank()) {
            try {
                m.put("facts", mapper.readValue(d.getFactsJson(), Map.class));
            } catch (Exception ignored) {
            }
        }
        return m;
    }

    private String nextRunText(AiConfig cfg) {
        String last = lastRun();
        LocalDate today = LocalDate.now(ZONE);
        boolean doneToday = today.toString().equals(last);
        LocalDate day = doneToday ? today.plusDays(1) : today;
        return day + String.format(" %02d:%02d", cfg.getHour(), cfg.getMinute());
    }

    /* ==================== 调度标记 ==================== */

    public String lastRun() {
        try {
            return settings.findById(KEY_LAST_RUN).map(AppSetting::getValue).orElse("");
        } catch (Exception e) {
            return "";
        }
    }

    public void markRun(LocalDate date) {
        try {
            settings.save(new AppSetting(KEY_LAST_RUN, date.toString()));
        } catch (Exception e) {
            log.warn("记录早报运行日期失败: {}", e.getMessage());
        }
    }
}
