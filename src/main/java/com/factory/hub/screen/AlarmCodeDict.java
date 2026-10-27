package com.factory.hub.screen;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 伺服/PLC 报警码字典：把「报警字 = 33」翻译成「33 = 过电压，原因是…，处理是…」。
 * <p>
 * <b>只内置厂家标准码</b>（三菱、台达伺服先行），这类码全网公开、几十年不变；
 * <b>PLC 报警字不做内置表</b>——那是设备厂程序里自己定义的位，每台机器都不同，
 * 猜错一次比不显示严重。真要做，让现场把点表对应的位含义整理成 JSON 放进来（见 README）。
 * <p>
 * 字典是 JSON 资源文件（{@code alarmcodes/<brand>.json}），加品牌就是加文件 + 重启，
 * 不用改代码、不用重新设计数据库。换字典文件后要重启服务（缓存在内存里）。
 */
@Component
public class AlarmCodeDict {

    private static final Logger log = LoggerFactory.getLogger(AlarmCodeDict.class);

    private final ObjectMapper mapper;

    /** brand -> {brandText, note, codes} */
    private final Map<String, Map<String, Object>> brands = new LinkedHashMap<>();

    public AlarmCodeDict(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @PostConstruct
    public void load() {
        brands.clear();
        // 目录清单放在 index.txt 里：Spring Boot 的 fat jar 是嵌套资源，
        // 列目录在 jar 里会失败。加一个品牌 = 加一个 json + 在 index.txt 里加一行。
        List<String> files = new ArrayList<>();
        try {
            String idx = new String(new ClassPathResource("alarmcodes/index.txt").getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            for (String line : idx.split("\\r?\\n")) {
                String f = line.trim();
                if (!f.isEmpty() && !f.startsWith("#")) {
                    files.add(f);
                }
            }
        } catch (Exception e) {
            log.info("没有 alarmcodes/index.txt，报警码字典为空（报警字只显示原始码值）");
        }
        for (String f : files) {
            try {
                JsonNode node = mapper.readTree(new ClassPathResource("alarmcodes/" + f).getInputStream());
                String brand = node.path("brand").asText("");
                if (brand.isBlank()) {
                    continue;
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("brandText", node.path("brandText").asText(brand));
                m.put("note", node.path("note").asText(""));
                Map<String, Object> codes = new LinkedHashMap<>();
                node.path("codes").fields().forEachRemaining(e ->
                        codes.put(e.getKey(), mapper.convertValue(e.getValue(), Object.class)));
                m.put("codes", codes);
                brands.put(brand, m);
            } catch (Exception e) {
                log.warn("报警码字典 {} 解析失败：{}", f, e.getMessage());
            }
        }
        if (!brands.isEmpty()) {
            log.info("报警码字典已加载：{} 个品牌（{}）", brands.size(), String.join("、", brands.keySet()));
        }
    }

    public static record Resolved(String key, String code, String brand, String brandText,
                                  String name, String cause, String remedy, boolean known) {
    }

    /**
     * 查一个报警码。brand 为空或字典里没有该品牌时，known=false、只带原始码。
     * code 会被归一化：去掉 AL. / Er. 前缀、去空格、转大写，所以传 "AL.33" 和 "33" 一样。
     */
    public Resolved lookup(String brand, String key, String rawCode) {
        String code = normalize(rawCode);
        Map<String, Object> dict = brand == null ? null : brands.get(brand);
        if (dict != null) {
            Map<String, Object> entry = (Map<String, Object>) dict.get("codes");
            Object hit = entry == null ? null : entry.get(code);
            if (hit instanceof Map) {
                Map<String, Object> c = (Map<String, Object>) hit;
                return new Resolved(key, code, brand,
                        String.valueOf(dict.get("brandText")),
                        str(c.get("name")), str(c.get("cause")), str(c.get("remedy")), true);
            }
            return new Resolved(key, code, brand,
                    String.valueOf(dict.get("brandText")),
                    null, null, null, false);
        }
        return new Resolved(key, code, brand, null, null, null, null, false);
    }

    /**
     * 把一台设备的报警字点位翻译成带含义的列表。
     * 非零才进列表；返回 hasAlarmPoint 让前端区分「没配报警字」和「配了但全为 0」。
     */
    public Map<String, Object> resolve(String brand, List<com.factory.hub.model.PointDef> defs,
                                       Map<String, com.factory.hub.model.PointValue> pts) {
        List<Map<String, Object>> words = new ArrayList<>();
        boolean has = false;
        Map<String, String> roles = new LinkedHashMap<>();
        for (com.factory.hub.model.PointDef pd : defs) {
            roles.put(pd.getKey(), pd.getRole());
        }
        if (pts != null) {
            for (Map.Entry<String, com.factory.hub.model.PointValue> pe : pts.entrySet()) {
                if (!"alarm".equals(roles.get(pe.getKey()))) {
                    continue;
                }
                has = true;
                Object v = pe.getValue().value();
                if (v instanceof Number n && n.doubleValue() != 0) {
                    int c = (int) Math.round(n.doubleValue());
                    Resolved r = lookup(brand, pe.getKey(), String.valueOf(c));
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("key", r.key());
                    m.put("code", r.code());
                    if (r.brandText() != null) {
                        m.put("brandText", r.brandText());
                    }
                    if (r.known()) {
                        m.put("name", r.name());
                        m.put("cause", r.cause());
                        m.put("remedy", r.remedy());
                    }
                    m.put("known", r.known());
                    words.add(m);
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("words", words);
        out.put("hasAlarmPoint", has);
        return out;
    }

    /** 设备表单下拉用：支持的品牌列表 */
    public List<Map<String, Object>> brandOptions() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> e : brands.entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("brand", e.getKey());
            m.put("brandText", e.getValue().get("brandText"));
            out.add(m);
        }
        return out;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replaceAll("(?i)(AL|ER|ERR|NO)", "")
                .replace(".", "").replace("#", "")
                .replaceAll("\\s", "").toUpperCase();
    }
}
