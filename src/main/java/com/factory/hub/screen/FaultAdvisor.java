package com.factory.hub.screen;

import com.factory.hub.ai.AiClient;
import com.factory.hub.ai.AiConfig;
import com.factory.hub.ai.AiConfigStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 故障原因分析：给大屏的「维修看板」供文本。
 * <p>
 * <b>为什么是服务端异步、而不是前端点按钮触发</b>：
 * 大屏不登录，谁都能访问；如果分析接口是同步的，刷接口 = 替你烧 AI 的钱。
 * 所以分析由快照构建时<b>按需触发</b>：报警集合或通讯状态变了才重新分析，
 * 结果缓存到内存，多次刷新、多块屏共享同一份。
 * <p>
 * <b>AI 只润色，事实本地算</b>——和早报同一个原则：
 * 报警内容、通讯参数、最后错误都是系统自己的数据，喂给模型让它组织成人话；
 * 模型挂了/欠费/断网就退回本地建议（基于规则类型的通用排查步骤），
 * 看板永远有内容，只是标明来源不同。
 */
@Service
public class FaultAdvisor {

    private static final Logger log = LoggerFactory.getLogger(FaultAdvisor.class);

    private static final String SYSTEM_PROMPT = """
            你是工厂设备维修助手。根据给出的设备事实（通讯方式、报警、点位值），输出三段：
            1) 最可能的原因（最多 3 条，按可能性排序）
            2) 现场排查步骤（3~5 条，具体到查什么线、什么参数、用什么工具）
            3) 风险提示（一句话）
            要求：中文；不要编造报警码含义，不知道就写"该代码需查对应品牌手册"；
            不要输出 Markdown 标题符号，用 1. 2. 3. 和短横线；总长不超过 220 字。""";

    private final AiClient client;
    private final AiConfigStore configStore;
    private final ObjectMapper mapper;

    private final ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "fault-advisor");
        t.setDaemon(true);
        return t;
    });

    /** key = 设备编码 */
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    public static final String SOURCE_PENDING = "PENDING";
    public static final String SOURCE_AI = "AI";
    public static final String SOURCE_LOCAL = "LOCAL";

    public record Entry(String signature, String text, String source, long at) {
    }

    public FaultAdvisor(AiClient client, AiConfigStore configStore, ObjectMapper mapper) {
        this.client = client;
        this.configStore = configStore;
        this.mapper = mapper;
    }

    /**
     * 取当前分析结果。没有/正在算返回 PENDING 占位，前端显示"分析中"。
     * 签名一致才复用：报警集合或通讯状态变了就重新算。
     */
    public Entry get(String code, String signature) {
        Entry e = cache.get(code);
        if (e != null && e.signature().equals(signature)) {
            return e;
        }
        return new Entry(signature, "正在结合设备数据分析…", SOURCE_PENDING, 0);
    }

    /** 签名变了才提交异步分析；同一个故障反复刷屏不会重复烧 token */
    public void request(String code, String signature, Map<String, Object> facts) {
        Entry e = cache.get(code);
        if (e != null && e.signature().equals(signature) && !SOURCE_PENDING.equals(e.source())) {
            return;
        }
        if (e == null || !e.signature().equals(signature)) {
            cache.put(code, new Entry(signature, "正在结合设备数据分析…", SOURCE_PENDING, System.currentTimeMillis()));
        }
        String factsJson;
        try {
            factsJson = mapper.writeValueAsString(facts);
        } catch (Exception ex) {
            return;
        }
        pool.submit(() -> {
            try {
                AiConfig cfg = configStore.current();
                String text;
                String source;
                if (cfg.isEnabled() && cfg.getApiKey() != null && !cfg.getApiKey().isBlank()) {
                    try {
                        AiClient.Reply r = client.chat(cfg, SYSTEM_PROMPT, factsJson);
                        text = r.text() == null || r.text().isBlank() ? null : r.text().trim();
                        source = SOURCE_AI;
                    } catch (Exception ex) {
                        log.warn("故障分析 AI 调用失败，退回本地建议：{}", ex.getMessage());
                        text = null;
                        source = SOURCE_LOCAL;
                    }
                } else {
                    text = null;               // 未启用 AI，走本地建议
                    source = SOURCE_LOCAL;
                }
                if (text == null) {
                    text = localAdvice(facts);
                }
                cache.put(code, new Entry(signature, text, source, System.currentTimeMillis()));
                log.info("故障分析完成：{}（{}）", code, source);
            } catch (Exception ex) {
                log.warn("故障分析线程异常：{}", ex.getMessage());
            }
        });
    }

    /** 没配 AI / AI 挂了时的兜底：按事实给通用排查步骤，明确标"本地建议"。
     *  步骤用计数器编号——按分支硬编码"3."这种序号，命中哪个分支就从几开始，很难看。 */
    @SuppressWarnings("unchecked")
    private String localAdvice(Map<String, Object> facts) {
        StringBuilder sb = new StringBuilder("本地建议（未启用 AI，按常见故障给出）：\n");
        int n = 0;
        boolean offline = Boolean.TRUE.equals(facts.get("offline"));
        String lastError = String.valueOf(facts.getOrDefault("lastError", ""));
        Map<String, Object> alarms = (Map<String, Object>) facts.getOrDefault("alarms", Map.of());
        Object typesObj = alarms.get("types");
        java.util.List<String> types = typesObj instanceof java.util.List<?> l
                ? l.stream().map(String::valueOf).toList() : java.util.List.of();

        if (offline || !lastError.isBlank() || types.contains("OFFLINE")) {
            sb.append(++n).append(". 通讯中断是第一嫌疑：检查网线/水晶头、交换机端口指示灯，确认设备侧电源；\n");
            sb.append(++n).append(". 在服务器上 ping ").append(facts.getOrDefault("target", "设备 IP"))
              .append("，通则是程序/寄存器问题，不通先查网络；\n");
        }
        if (types.contains("THRESHOLD")) {
            sb.append(++n).append(". 数值越限：先确认是真实工况还是传感器漂移（用手持表对一下读数）；\n");
        }
        Object plc = facts.get("plcWords");
        if (plc instanceof java.util.List<?> l && !l.isEmpty()) {
            sb.append(++n).append(". 报警字非零：查该品牌手册对应代码，先面板复位，复位不掉再断电重启；\n");
        }
        if (n == 0) {
            sb.append(++n).append(". 暂无典型故障特征，观察 10 分钟内报警是否自行恢复；\n");
        }
        sb.append("风险提示：带电插拔通讯线可能烧端口，处理前先确认设备状态。");
        return sb.toString();
    }

    /** 给测试看内部状态 */
    public int cachedCount() {
        return cache.size();
    }
}
