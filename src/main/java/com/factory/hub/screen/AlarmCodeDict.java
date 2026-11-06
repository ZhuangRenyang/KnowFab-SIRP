package com.factory.hub.screen;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
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

    /**
     * brand -> 别名索引（原始键 + 去前导零别名）。
     *
     * <p><b>为什么需要别名索引</b>：
     * 设备报警字是<b>数值型点位</b>，读上来的是裸整数（{@code trimNum(double)} → "1" / "11"），
     * 而厂家手册上的码常带补零（台达 {@code AL.001}）。两者归一化后不相等，
     * 于是台达 14 条字典<b>在生产中 100% 不可达</b> —— 字典写得再对也没用。
     *
     * <p><b>实现契约（改这段前先读，改错会给现场一个错含义）</b>：
     * <ol>
     *   <li><b>两遍扫描</b>：第 1 遍登记全部原始键（{@code put} 覆盖语义），
     *       第 2 遍才注册别名（{@code putIfAbsent}）。</li>
     *   <li>原始键用 {@code put}、别名才用 {@code putIfAbsent}。
     *       若两者都用 {@code putIfAbsent}，{@code {"010","10"}} 会出现
     *       「别名 "10" 先占位 → 真键 "10" 被跳过」→ <b>"10" 命中 "010" 的含义</b>。</li>
     *   <li>别名仅在「去前导零形态<b>不是</b>本品牌已有的原始键」时才注册（G5）。
     *       否则 {@code "010"} 的别名会抢占 {@code "10"}。</li>
     * </ol>
     */
    private final Map<String, Map<String, Object>> aliases = new LinkedHashMap<>();

    public AlarmCodeDict(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @PostConstruct
    public void load() {
        brands.clear();
        aliases.clear();
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
                node.path("codes").properties().forEach(e ->
                        codes.put(e.getKey(), mapper.convertValue(e.getValue(), Object.class)));
                m.put("codes", codes);
                brands.put(brand, m);
                aliases.put(brand, buildAliases(codes));
            } catch (Exception e) {
                log.warn("报警码字典 {} 解析失败：{}", f, e.getMessage());
            }
        }
        if (!brands.isEmpty()) {
            log.info("报警码字典已加载：{} 个品牌（{}）", brands.size(), String.join("、", brands.keySet()));
        }
    }

    /**
     * 构建别名索引：原始键 + 条目自带的 aliases[] + 去前导零别名。
     *
     * <p>见 {@link #aliases} 的实现契约 —— 这里的顺序与 put/putIfAbsent 的选择都是契约的一部分，
     * 改之前先读完那段注释。
     *
     * <p>抽成包可见的静态方法是为了能单测：真跑要构造字典文件，
     * 而「别名不能抢占已有原始键」这条恰恰最容易写错。
     */
    static Map<String, Object> buildAliases(Map<String, Object> codes) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (codes == null || codes.isEmpty()) {
            return map;
        }
        // 第 1 遍：登记全部原始键（put 覆盖语义 —— 原始键优先于任何别名）
        for (Map.Entry<String, Object> e : codes.entrySet()) {
            map.put(e.getKey(), e.getValue());
        }
        // 第 2 遍：注册条目自带的 aliases[]（厂家形态 / 手册裸数字等另一种写法）
        // 别名不得覆盖任何原始键 —— 否则那个原始键会指向别的含义，给现场一个错含义。
        for (Map.Entry<String, Object> e : codes.entrySet()) {
            Object item = e.getValue();
            if (!(item instanceof Map)) {
                continue;
            }
            Object list = ((Map<?, ?>) item).get("aliases");
            if (!(list instanceof List)) {
                continue;
            }
            for (Object a : (List<?>) list) {
                if (a == null) {
                    continue;
                }
                String alias = String.valueOf(a).trim();
                if (alias.isEmpty()) {
                    continue;
                }
                map.putIfAbsent(alias, e.getValue());
            }
        }
        // 第 3 遍：注册去前导零别名（存量台达那类补零键）
        for (Map.Entry<String, Object> e : codes.entrySet()) {
            String key = e.getKey();
            if (key == null || !key.matches("\\d+")) {
                continue;
            }
            String stripped = key.replaceFirst("^0+(?=.)", "");
            if (stripped.equals(key)) {
                continue;
            }
            if (codes.containsKey(stripped)) {
                // G5：该品牌已有这个原始键，别名会让它指向错误含义 —— 不注册
                continue;
            }
            map.putIfAbsent(stripped, e.getValue());
        }
        return map;
    }

    /**
     * 查出来的字典条目。
     *
     * @param code        归一化后的运行时码（= normalize(rawCode)），不是字典键
     * @param displayCode 厂家原始形态（如 "A.720h" / "Err.11.0" / "E410.0"），没有则 null。
     *                    维修工看到的是它，查手册也是照它查；{@code code} 只是裸数字。
     */
    public static record Resolved(String key, String code, String brand, String brandText,
                                  String name, String cause, String remedy, boolean known,
                                  String displayCode) {
    }

    /**
     * 查一个报警码。brand 为空或字典里没有该品牌时，known=false、只带原始码。
     * code 会被归一化：去掉 AL. / Er. 前缀、去空格、转大写，所以传 "AL.33" 和 "33" 一样。
     *
     * <p>查表走别名索引：设备上报的是裸整数，而厂家手册的码可能带补零（台达 AL.001），
     * 没有别名索引的话那些码永远查不到。
     */
    public Resolved lookup(String brand, String key, String rawCode) {
        String code = normalize(rawCode);
        Map<String, Object> dict = brand == null ? null : brands.get(brand);
        if (dict != null) {
            Map<String, Object> alias = aliases.get(brand);
            Map<String, Object> entry = (Map<String, Object>) dict.get("codes");
            Object hit = null;
            if (entry != null) {
                hit = entry.get(code);
            }
            if (hit == null && alias != null) {
                hit = alias.get(code);
            }
            if (hit instanceof Map) {
                Map<String, Object> c = (Map<String, Object>) hit;
                return new Resolved(key, code, brand,
                        String.valueOf(dict.get("brandText")),
                        str(c.get("name")), str(c.get("cause")), str(c.get("remedy")), true,
                        str(c.get("displayCode")));
            }
            return new Resolved(key, code, brand,
                    String.valueOf(dict.get("brandText")),
                    null, null, null, false, null);
        }
        return new Resolved(key, code, brand, null, null, null, null, false, null);
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
                    if (r.displayCode() != null) {
                        m.put("displayCode", r.displayCode());
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
