package com.factory.hub.ai;

import com.factory.hub.alarm.AlarmService;
import com.factory.hub.erp.ErpFinanceService;
import com.factory.hub.erp.ErpReportService;
import com.factory.hub.erp.ErpLineService;
import com.factory.hub.erp.ErpPurchaseService;
import com.factory.hub.erp.ErpSalesQueryService;
import com.factory.hub.erp.ErpSalesService;
import com.factory.hub.collect.SnapshotStore;
import com.factory.hub.model.DeviceEntity;
import com.factory.hub.service.DeviceService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AI 助手（DH-46/47/48）：让员工用自然语言查系统里的数。
 * <p>
 * <b>三条安全底线，一条都不能松：</b>
 * <ol>
 *   <li><b>AI 永远不碰数据库、不生成 SQL</b>——它只能调用这里预定义的工具，
 *       每个工具都是写死的查询，参数只有关键词/状态/条数这种粒度；
 *       模型说"帮我删单"没有工具可调，天然做不了。</li>
 *   <li><b>权限按角色收口</b>——工具表里写死谁能用；即使模型执意要调，
 *       runTool 也会拒绝并把这个结果告诉模型，让它向用户解释权限不足。</li>
 *   <li><b>限流与封顶</b>——每用户每分钟 20 次对话、单次最多 4 轮工具调用、
 *       历史 12 条、单条 4000 字。防止循环调用把 token 账单打爆。</li>
 * </ol>
 * 数字口径全部复用业务服务（销售查询/产线/订单/财务），AI 只是"会说话的查询窗口"，
 * 它说出来的数和页面上看到的数必然一致——这是"AI 智慧工厂"的信任底线。
 */
@Service
public class AiChatService {

    private static final Logger log = LoggerFactory.getLogger(AiChatService.class);

    private static final int MAX_ROUNDS = 4;
    private static final int MAX_HISTORY = 12;
    private static final int MAX_CONTENT = 4000;
    private static final int MAX_TOOL_RESULT = 4000;
    private static final int RATE_PER_MIN = 20;
    private static final ZoneId ZONE = ZoneId.systemDefault();

    private final AiConfigStore configStore;
    private final AiClient client;
    private final ObjectMapper mapper;

    private final DeviceService deviceService;
    private final SnapshotStore store;
    private final ErpLineService lineService;
    private final ErpSalesQueryService salesQueryService;
    private final ErpPurchaseService purchaseService;
    private final ErpSalesService salesService;
    private final ErpFinanceService financeService;
    private final AlarmService alarmService;
    private final AiChatLogRepository chatRepo;
    private final ErpReportService reportService;

    /** 每用户滑动窗口限流 */
    private final Map<String, Deque<Long>> rate = new HashMap<>();

    public AiChatService(AiConfigStore configStore, AiClient client, ObjectMapper mapper,
                         DeviceService deviceService, SnapshotStore store,
                         ErpLineService lineService, ErpSalesQueryService salesQueryService,
                         ErpPurchaseService purchaseService, ErpSalesService salesService,
                         ErpFinanceService financeService, AlarmService alarmService,
                         AiChatLogRepository chatRepo, ErpReportService reportService) {
        this.configStore = configStore;
        this.client = client;
        this.mapper = mapper;
        this.deviceService = deviceService;
        this.store = store;
        this.lineService = lineService;
        this.salesQueryService = salesQueryService;
        this.purchaseService = purchaseService;
        this.salesService = salesService;
        this.financeService = financeService;
        this.alarmService = alarmService;
        this.chatRepo = chatRepo;
        this.reportService = reportService;
    }

    /* ==================== 工具定义 ==================== */

    private record Tool(String name, String desc, Map<String, Object> schema, Set<String> roles) {
    }

    private static final Set<String> SHOP_FLOOR = Set.of("ADMIN", "MANAGER", "LEADER");
    private static final Set<String> MANAGER_UP = Set.of("ADMIN", "MANAGER");

    private List<Tool> tools() {
        List<Tool> list = new ArrayList<>();
        list.add(new Tool("query_stock", "查询商品库存与可承诺量（现存量-订单占用）。参数：kw 关键词（编码/名称/规格），category 类别，limit 条数（默认 10，最多 20）",
                Map.of("type", "object",
                        "properties", Map.of(
                                "kw", Map.of("type", "string", "description", "关键词，可空"),
                                "category", Map.of("type", "string", "description", "类别，如 成品/原材料/外购件，可空"),
                                "limit", Map.of("type", "integer", "description", "返回条数，默认 10")),
                        "required", List.of()),
                Set.of("ADMIN", "MANAGER", "LEADER", "SALES")));
        list.add(new Tool("query_lines", "查询各产线今日/本月产量与在线设备数",
                Map.of("type", "object", "properties", Map.of(), "required", List.of()),
                SHOP_FLOOR));
        list.add(new Tool("query_devices", "查询设备清单与在线状态（不含任何密钥与内部地址）",
                Map.of("type", "object", "properties", Map.of(), "required", List.of()),
                SHOP_FLOOR));
        list.add(new Tool("query_alarms", "查询当前未处理报警汇总（各级数量）",
                Map.of("type", "object", "properties", Map.of(), "required", List.of()),
                SHOP_FLOOR));
        list.add(new Tool("query_purchase_orders", "查询采购订单统计与最近待收货订单。参数：limit 条数（默认 5，最多 10）",
                Map.of("type", "object",
                        "properties", Map.of("limit", Map.of("type", "integer", "description", "条数，默认 5")),
                        "required", List.of()),
                MANAGER_UP));
        list.add(new Tool("query_sales_orders", "查询销售订单统计与最近待发货订单。参数：limit 条数（默认 5，最多 10）",
                Map.of("type", "object",
                        "properties", Map.of("limit", Map.of("type", "integer", "description", "条数，默认 5")),
                        "required", List.of()),
                MANAGER_UP));
        list.add(new Tool("query_finance", "查询应收应付汇总（应收未收/应付未付/本月收付款/账龄）",
                Map.of("type", "object", "properties", Map.of(), "required", List.of()),
                MANAGER_UP));
        return list;
    }

    /** 当前角色可用的工具（开放接口用） */
    public List<Map<String, Object>> toolsFor(String role) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Tool t : tools()) {
            if (t.roles().contains(role)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", t.name());
                m.put("desc", t.desc());
                out.add(m);
            }
        }
        return out;
    }

    /* ==================== 对话主流程 ==================== */

    /**
     * @param role    当前登录用户角色（控制器从会话取，不信前端）
     * @param userKey 限流用身份（用户名）
     * @param history 前端传来的对话历史 [{role,content}]，user/assistant 交替
     */
    public synchronized Map<String, Object> chat(String role, String userKey,
                                                 List<Map<String, Object>> history) {
        Map<String, Object> out = new LinkedHashMap<>();

        AiConfig cfg = configStore.current();
        if (!cfg.isEnabled()) {
            out.put("ok", false);
            out.put("code", "AI_DISABLED");
            out.put("message", "AI 未启用：请管理员在「AI 设置」里开启（支持 DeepSeek/通义/智谱或本地 Ollama）");
            return out;
        }
        if (cfg.getApiKey() == null || cfg.getApiKey().isBlank()) {
            out.put("ok", false);
            out.put("code", "AI_NOT_CONFIGURED");
            out.put("message", "AI 已启用但没填 API Key，请管理员在「AI 设置」里填写");
            return out;
        }
        if (!rateOk(userKey)) {
            out.put("ok", false);
            out.put("code", "RATE_LIMITED");
            out.put("message", "问得太快了，每分钟最多 " + RATE_PER_MIN + " 条，稍等再问");
            return out;
        }

        List<Map<String, Object>> tools = new ArrayList<>();
        List<Tool> defs = new ArrayList<>();
        for (Tool t : tools()) {
            if (t.roles().contains(role)) {
                tools.add(toolSchema(t));
                defs.add(t);
            }
        }
        if (defs.isEmpty()) {
            out.put("ok", false);
            out.put("code", "NO_TOOLS");
            out.put("message", "当前角色没有可查询的数据");
            return out;
        }

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt(role)));
        for (Map<String, Object> m : sanitize(history)) {
            messages.add(m);
        }

        long tokens = 0;
        List<Map<String, Object>> trace = new ArrayList<>();
        try {
            for (int round = 0; round < MAX_ROUNDS; round++) {
                AiClient.ToolReply r = client.chatWithTools(cfg, messages, tools);
                tokens += r.tokens();
                if (r.toolCalls().isEmpty()) {
                    out.put("ok", true);
                    out.put("content", r.content());
                    out.put("tools", trace);
                    out.put("tokens", tokens);
                    out.put("rounds", round + 1);
                    saveLog(userKey, role, history, r.content(), trace, tokens);
                    return out;
                }
                // 记录 assistant 的工具调用请求，随后把结果一条条喂回去
                Map<String, Object> assistantMsg = new LinkedHashMap<>();
                assistantMsg.put("role", "assistant");
                if (r.content() != null && !r.content().isBlank()) {
                    assistantMsg.put("content", r.content());
                }
                List<Map<String, Object>> rawCalls = new ArrayList<>();
                for (AiClient.ToolCall c : r.toolCalls()) {
                    rawCalls.add(Map.of("id", c.id(), "type", "function",
                            "function", Map.of("name", c.name(), "arguments", c.arguments())));
                }
                assistantMsg.put("tool_calls", rawCalls);
                messages.add(assistantMsg);

                for (AiClient.ToolCall c : r.toolCalls()) {
                    long t0 = System.currentTimeMillis();
                    String result = runTool(role, defs, c);
                    trace.add(Map.of("name", c.name(),
                            "args", safeJson(c.arguments()),
                            "ms", System.currentTimeMillis() - t0));
                    Map<String, Object> tr = new LinkedHashMap<>();
                    tr.put("role", "tool");
                    tr.put("tool_call_id", c.id());
                    tr.put("content", result);
                    messages.add(tr);
                }
            }
            out.put("ok", true);
            out.put("content", "这个问题需要查的数据有点多，我先说到这里。你可以把问题拆小一点再问我。");
            out.put("tools", trace);
            out.put("tokens", tokens);
            out.put("rounds", MAX_ROUNDS);
            return out;
        } catch (Exception e) {
            log.warn("AI 助手调用失败：{}", e.getMessage());
            out.put("ok", false);
            out.put("code", "AI_ERROR");
            out.put("message", humanError(e));
            return out;
        }
    }

    /* ==================== 工具执行 ==================== */

    /** 每个工具的查询都写死在这里；AI 只能传关键词/条数这种粒度 */
    private String runTool(String role, List<Tool> defs, AiClient.ToolCall call) {
        Tool def = null;
        for (Tool t : defs) {
            if (t.name().equals(call.name())) {
                def = t;
                break;
            }
        }
        if (def == null) {
            return err("没有这个查询：" + call.name());
        }
        if (!def.roles().contains(role)) {
            return err("当前角色（" + roleLabel(role) + "）没有权限使用「" + call.name() + "」，"
                    + "请向用户说明权限不足，不要尝试其他方式获取");
        }
        Map<String, Object> args;
        try {
            args = mapper.readValue(call.arguments() == null || call.arguments().isBlank()
                    ? "{}" : call.arguments(), Map.class);
        } catch (Exception e) {
            return err("参数不是合法 JSON");
        }
        try {
            Object data = switch (call.name()) {
                case "query_stock" -> stock(num(args.get("limit"), 10),
                        str(args.get("kw")), str(args.get("category")));
                case "query_lines" -> lineService.brief();
                case "query_devices" -> devices();
                case "query_alarms" -> alarms();
                case "query_purchase_orders" -> purchase(num(args.get("limit"), 5));
                case "query_sales_orders" -> sales(num(args.get("limit"), 5));
                case "query_finance" -> finance();
                default -> err("未实现的工具");
            };
            String json = mapper.writeValueAsString(data);
            if (json.length() > MAX_TOOL_RESULT) {
                json = json.substring(0, MAX_TOOL_RESULT) + "…（结果过长已截断，请缩小查询范围）";
            }
            return json;
        } catch (Exception e) {
            return err("查询失败：" + e.getMessage());
        }
    }

    private Map<String, Object> stock(int limit, String kw, String category) {
        Map<String, Object> r = salesQueryService.stock(kw, category, null, false);
        List<Map<String, Object>> rows = new ArrayList<>();
        int n = Math.max(1, Math.min(20, limit));
        for (Map<String, Object> row : (List<Map<String, Object>>) r.getOrDefault("rows", List.of())) {
            if (rows.size() >= n) {
                break;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", row.get("code"));
            m.put("name", row.get("name"));
            m.put("spec", row.get("spec"));
            m.put("unit", row.get("unit"));
            m.put("category", row.get("category"));
            m.put("onHand", row.get("onHand"));
            m.put("allocated", row.get("allocated"));
            m.put("available", row.get("available"));
            m.put("state", row.get("state"));
            m.put("warehouses", row.get("warehouseText"));
            rows.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("totals", r.get("totals"));
        out.put("rows", rows);
        out.put("note", "available=可承诺量（现存-已确认订单未发货），state: OK充足/LOW紧张/OUT已无货");
        return out;
    }

    private List<Map<String, Object>> devices() {
        List<Map<String, Object>> out = new ArrayList<>();
        int online = 0;
        for (DeviceEntity d : deviceService.all()) {
            boolean on = store.runtime(d.getCode()).online;
            if (on) {
                online++;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", d.getCode());
            m.put("name", d.getName());
            m.put("location", d.getLocation());
            m.put("online", on);
            out.add(m);
        }
        // 给模型一句汇总，省它自己数
        if (!out.isEmpty()) {
            Map<String, Object> head = new LinkedHashMap<>();
            head.put("summary", "共 " + out.size() + " 台，在线 " + online + " 台");
            out.add(0, head);
        }
        return out;
    }

    private Map<String, Object> alarms() {
        Map<String, Object> s = alarmService.summary();
        Map<String, Object> out = new LinkedHashMap<>();
        for (String k : List.of("open", "unhandled", "acked", "criticalOpen", "majorOpen",
                "minorOpen", "todayNew")) {
            out.put(k, s.get(k));
        }
        return out;
    }

    private Map<String, Object> purchase(int limit) {
        return trade(purchaseService.stats(), purchaseService.openOrders(Math.max(1, Math.min(10, limit))));
    }

    private Map<String, Object> sales(int limit) {
        return trade(salesService.stats(), salesService.openOrders(Math.max(1, Math.min(10, limit))));
    }

    private Map<String, Object> trade(Map<String, Object> stats, List<Map<String, Object>> open) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String k : List.of("monthAmount", "openCount", "overdueCount")) {
            out.put(k, stats.get(k));
        }
        out.put("openOrders", open);
        return out;
    }

    private Map<String, Object> finance() {
        Map<String, Object> s = financeService.summary();
        Map<String, Object> out = new LinkedHashMap<>();
        for (String k : List.of("arOutstanding", "arOverdue", "apOutstanding", "apOverdue",
                "arPre", "apPre", "monthIn", "monthOut", "arAging", "apAging")) {
            out.put(k, s.get(k));
        }
        out.put("note", "金额单位元；ar=应收 ap=应付；Pre=预收/预付；Aging 为账龄分档金额");
        return out;
    }

    /* ==================== 杂项 ==================== */

    /* ==================== 对话留痕（本人可查可删） ==================== */

    /** 只回本人的最近 N 条（时间正序），换设备/刷新后对话还在 */
    public List<Map<String, Object>> history(String username, int limit) {
        int n = Math.max(1, Math.min(100, limit));
        List<Map<String, Object>> out = new ArrayList<>();
        for (AiChatLog c : chatRepo.findByUsernameOrderByCreatedAtAsc(username,
                org.springframework.data.domain.PageRequest.of(0, n))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("role", "user");
            m.put("content", c.getQuestion());
            out.add(m);
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("role", "assistant");
            a.put("content", c.getAnswer());
            out.add(a);
        }
        return out;
    }

    public Map<String, Object> clearHistory(String username) {
        int n = chatRepo.deleteByUsername(username);
        return Map.of("ok", true, "message", "已清空 " + n + " 条对话记录");
    }

    private void saveLog(String username, String role, List<Map<String, Object>> history,
                         String answer, List<Map<String, Object>> trace, long tokens) {
        try {
            String question = "";
            for (int i = history.size() - 1; i >= 0; i--) {
                Map<String, Object> m = history.get(i);
                if ("user".equals(String.valueOf(m.get("role")))) {
                    question = String.valueOf(m.get("content"));
                    break;
                }
            }
            AiChatLog logRow = new AiChatLog();
            logRow.setUsername(username);
            logRow.setRole(role);
            logRow.setQuestion(question.length() > 4000 ? question.substring(0, 4000) : question);
            logRow.setAnswer(answer.length() > 8000 ? answer.substring(0, 8000) : answer);
            logRow.setToolsJson(mapper.writeValueAsString(trace));
            logRow.setTokens((int) Math.min(Integer.MAX_VALUE, tokens));
            chatRepo.save(logRow);
        } catch (Exception e) {
            log.warn("AI 对话留痕失败（不影响回答）：{}", e.getMessage());
        }
    }

    /** 每天 03:40 清理 180 天前的对话留痕 */
    @org.springframework.scheduling.annotation.Scheduled(cron = "0 40 3 * * *")
    public void cleanupLogs() {
        try {
            int n = chatRepo.deleteOlderThan(
                    System.currentTimeMillis() - 180L * 86_400_000L);
            if (n > 0) {
                log.info("AI 对话留痕清理 {} 条", n);
            }
        } catch (Exception e) {
            log.warn("AI 对话留痕清理失败：{}", e.getMessage());
        }
    }

    /* ==================== AI 经营解读（DH-48 第一块） ==================== */

    /**
     * 把经营概况的事实数据交给模型组织语言：风险点、异常、建议。
     * 数字全部本地算好塞进提示词，模型只负责"说人话"——延续"事实本地算，AI 只润色"的红线。
     */
    public synchronized Map<String, Object> interpret(String role, String from, String to) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!Set.of("ADMIN", "MANAGER").contains(role)) {
            out.put("ok", false);
            out.put("message", "经营解读包含财务数据，仅管理员与车间主任可用");
            return out;
        }
        AiConfig cfg = configStore.current();
        if (!cfg.isEnabled() || cfg.getApiKey() == null || cfg.getApiKey().isBlank()) {
            out.put("ok", false);
            out.put("code", "AI_DISABLED");
            out.put("message", "AI 未启用或未配置 Key，请管理员在「AI 设置」里开启");
            return out;
        }
        long[] r = reportRange(from, to);
        Map<String, Object> d = reportService.overview(r[0], r[1]);

        // 只挑模型用得上的字段：数字已经很密了，别把整个 JSON 灌进去
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("区间", d.get("fromDate") + " ~ " + d.get("toDate"));
        facts.put("采购入库额", d.get("purchaseAmount"));
        facts.put("销售出库额", d.get("salesAmount"));
        facts.put("生产入库量", d.get("prodInQty"));
        facts.put("当前库存金额", d.get("stockValue"));
        facts.put("采购订单数", d.get("poCount"));
        facts.put("销售订单数", d.get("soCount"));
        facts.put("应收未收", d.get("arOutstanding"));
        facts.put("应收逾期", d.get("arOverdue"));
        facts.put("应付未付", d.get("apOutstanding"));
        facts.put("应付逾期", d.get("apOverdue"));
        facts.put("每日趋势", d.get("trend"));
        facts.put("客户排行", d.get("topCustomers"));
        facts.put("供应商排行", d.get("topSuppliers"));
        facts.put("商品动销", d.get("topSkus"));

        String system = """
                你是工厂经营分析助手。用户会给一段时期的生产经营事实数据（JSON），
                请写一份给老板看的简短解读（350 字以内）：
                1. 先一句话总结本期经营状态；
                2. 指出 2~3 个值得注意的风险或异常（例如销售高采购低库存降、应收逾期占比、动销集中度），
                   每条都要引用数据里的具体数字；
                3. 给 1~2 条可执行的建议；
                4. 只使用给定的数字，禁止编造或自行计算新数字；单位：数量见字段、金额为元。
                用简体中文，直接输出正文，不要标题和客套。
                """;
        try {
            String factsJson = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(facts);
            AiClient.Reply reply = client.chat(cfg, system, factsJson);
            out.put("ok", true);
            out.put("content", reply.text());
            out.put("tokens", reply.tokens());
        } catch (Exception e) {
            out.put("ok", false);
            out.put("message", "AI 解读失败：" + e.getMessage());
        }
        return out;
    }

    /** [fromTs, toTsExclusive]；默认本月 1 号到今天 */
    private static long[] reportRange(String from, String to) {
        LocalDate today = LocalDate.now(ZONE);
        LocalDate f = parseDay(from, today.withDayOfMonth(1));
        LocalDate t = parseDay(to, today);
        if (t.isBefore(f)) {
            LocalDate tmp = f;
            f = t;
            t = tmp;
        }
        return new long[]{
                f.atStartOfDay(ZONE).toInstant().toEpochMilli(),
                t.plusDays(1).atStartOfDay(ZONE).toInstant().toEpochMilli()};
    }

    private static LocalDate parseDay(String raw, LocalDate fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return LocalDate.parse(raw.trim().substring(0, 10));
        } catch (Exception e) {
            return fallback;
        }
    }

    private String systemPrompt(String role) {
        String now = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                .format(Instant.now().atZone(ZONE));
        return """
                你是「知造 SIRP」AI 智慧工厂管理系统的助手，当前用户角色：%s。
                规则：
                1. 所有数字必须来自工具返回的结果，禁止编造或心算汇总；工具没给出的数据就回答"系统里没有这项数据"。
                2. 如果工具返回权限不足，向用户说明当前角色查不了这项数据，不要换别的方式套取。
                3. 用简体中文回答：先给结论，再给关键数字；尽量简短（300 字以内），多条数据用列表。
                4. 数量必须带单位（件/kg/套），金额用 ¥，不要把不同单位的数量相加。
                5. 当前时间：%s。数据都是实时查询的，不必提醒用户数据可能过期。
                """.formatted(roleLabel(role), now);
    }

    private static String roleLabel(String role) {
        return switch (role == null ? "" : role) {
            case "ADMIN" -> "管理员";
            case "MANAGER" -> "车间主任";
            case "LEADER" -> "班组长";
            case "SALES" -> "销售";
            default -> role == null ? "" : role;
        };
    }

    /** 历史清洗：只留 user/assistant、限长限条数 */
    private List<Map<String, Object>> sanitize(List<Map<String, Object>> history) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (history == null) {
            return out;
        }
        for (Map<String, Object> m : history) {
            String r = String.valueOf(m.get("role"));
            if (!"user".equals(r) && !"assistant".equals(r)) {
                continue;
            }
            String c = m.get("content") == null ? "" : String.valueOf(m.get("content"));
            if (c.isBlank()) {
                continue;
            }
            if (c.length() > MAX_CONTENT) {
                c = c.substring(0, MAX_CONTENT);
            }
            out.add(Map.of("role", r, "content", c));
        }
        while (out.size() > MAX_HISTORY) {
            out.remove(0);
        }
        return out;
    }

    private Map<String, Object> toolSchema(Tool t) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("name", t.name());
        f.put("description", t.desc());
        f.put("parameters", t.schema());
        return Map.of("type", "function", "function", f);
    }

    private boolean rateOk(String userKey) {
        long now = System.currentTimeMillis();
        synchronized (rate) {
            Deque<Long> q = rate.computeIfAbsent(userKey, k -> new ArrayDeque<>());
            while (!q.isEmpty() && now - q.peekFirst() > 60_000L) {
                q.pollFirst();
            }
            if (q.size() >= RATE_PER_MIN) {
                return false;
            }
            q.addLast(now);
            if (rate.size() > 500) {
                rate.clear();                       // 粗粒度防涨：限流表不追求精确
            }
            return true;
        }
    }

    private String safeJson(String raw) {
        try {
            JsonNode n = mapper.readTree(raw == null || raw.isBlank() ? "{}" : raw);
            String s = mapper.writeValueAsString(n);
            return s.length() > 200 ? s.substring(0, 200) : s;
        } catch (Exception e) {
            return String.valueOf(raw);
        }
    }

    private String err(String msg) {
        try {
            return mapper.writeValueAsString(Map.of("error", msg));
        } catch (Exception e) {
            return "{\"error\":\"查询失败\"}";
        }
    }

    /** 把技术报错翻译成人话：欠费/key 错/网络不通 各有各的说法 */
    private static String humanError(Exception e) {
        String m = String.valueOf(e.getMessage());
        if (m.contains("401") || m.contains("403")) {
            return "AI 服务拒绝了请求（Key 无效或没权限），请管理员检查 API Key";
        }
        if (m.contains("429")) {
            return "AI 服务限流了，请稍等几秒再问";
        }
        if (m.contains("402")) {
            return "AI 账户余额不足，请管理员充值后重试";
        }
        if (m.contains("ConnectException") || m.contains("HttpTimeoutException")
                || m.contains("timed out") || m.contains("connection")) {
            return "连不上 AI 服务（网络不通或地址不对），请检查「AI 设置」里的 API 地址";
        }
        return "AI 调用失败：" + (m.length() > 160 ? m.substring(0, 160) : m);
    }

    private static int num(Object o, int def) {
        try {
            return Integer.parseInt(String.valueOf(o));
        } catch (Exception e) {
            return def;
        }
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }
}
