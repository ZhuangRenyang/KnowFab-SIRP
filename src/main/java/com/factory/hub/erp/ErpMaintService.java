package com.factory.hub.erp;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.factory.hub.ai.AiClient;
import com.factory.hub.ai.AiConfig;
import com.factory.hub.ai.AiConfigStore;
import com.factory.hub.alarm.AlarmEvent;
import com.factory.hub.alarm.AlarmEventRepository;
import com.factory.hub.alarm.AlarmNotifier;
import com.factory.hub.auth.UserAccount;
import com.factory.hub.auth.UserRepository;
import com.factory.hub.model.DeviceEntity;
import com.factory.hub.screen.AlarmCodeDict;
import com.factory.hub.service.DeviceService;
import tools.jackson.databind.ObjectMapper;

/**
 * 设备维修闭环：AI 诊断 → 转工单 → 修完回填实际原因 → 下次同类报警参考历史。
 * <p>
 * <b>为什么要这个闭环</b>：{@code FaultAdvisor} 早就在用 AI 分析故障了，
 * 但结果只在内存里、只给大屏看，看完就消失：
 * <ul>
 *   <li>重启就没了 → 事后问「当时 AI 说是什么原因」答不上来</li>
 *   <li>没有「派给谁修」的动作 → 建议停在屏幕上，落不到人</li>
 *   <li>修好了没人记实际原因 → <b>经验永远不积累</b>，下次同类报警还是从零猜</li>
 * </ul>
 * 本类补的就是这三件事。
 * <p>
 * <b>一条铁律：AI 挂了也要有可用输出。</b>
 * 客户没买模型、Key 欠费、断网——这些现场都会发生。此时退回
 * 「字典含义 + 历史同类记录 + 通用排查」，文本照样给，但 {@code aiSource}
 * 明确标 {@code LOCAL}。<b>把本地规则说成 AI 结论，是骗现场。</b>
 */
@Service
public class ErpMaintService {

    private static final Logger log = LoggerFactory.getLogger(ErpMaintService.class);

    /** 历史样本最多取几条喂给模型：太多会稀释重点，3 条足够看出规律 */
    private static final int HISTORY_LIMIT = 3;

    private static final String PROMPT = """
            你是工厂设备维修助手。根据给出的设备事实、报警码字典含义、历史维修记录，
            给出可执行的排查建议。

            输出三段（不要用 Markdown 标题符号，用 1. 2. 3. 和短横线）：
            1) 最可能的原因（最多 3 条，按可能性排序，每条一句话）
            2) 现场排查步骤（3~5 条，具体到查什么线、什么参数、用什么工具）
            3) 风险提示（一句话）

            要求：
            - 中文；总长不超过 300 字
            - 不要编造报警码含义：字典里没有的码，写「该代码需查对应品牌手册」
            - **有历史维修记录时优先参考**：如果这台机同样的码上次是某个原因造成的，
              第一条就指向那个原因，并注明「历史同类」
            - 信息不足就直说「信息不足，建议补充 XX」，不要猜
            总原则：宁可少说，不可编造——现场会照着你的步骤去拆机器。""";

    private final ErpMaintOrderRepository repo;
    private final AlarmEventRepository alarmRepo;
    private final DeviceService deviceService;
    private final AlarmCodeDict alarmDict;
    private final AiClient aiClient;
    private final AiConfigStore aiConfigStore;
    private final ObjectMapper mapper;
    private final UserRepository userRepo;
    private final AlarmNotifier notifier;

    /**
     * 能承接维修工单的角色（DH-85）。
     * <p>
     * 只派给**人**，不派给角色——三个 LEADER 会互相以为对方在做（SPEC-DH85 §2.1）。
     * SALES 只读销售查询，不承接维修。
     */
    private static final List<String> ASSIGNABLE_ROLES =
            List.of(UserAccount.ROLE_ADMIN, UserAccount.ROLE_MANAGER, UserAccount.ROLE_LEADER);

    public ErpMaintService(ErpMaintOrderRepository repo,
                           AlarmEventRepository alarmRepo,
                           DeviceService deviceService,
                           AlarmCodeDict alarmDict,
                           AiClient aiClient,
                           AiConfigStore aiConfigStore,
                           ObjectMapper mapper,
                           UserRepository userRepo,
                           AlarmNotifier notifier) {
        this.repo = repo;
        this.alarmRepo = alarmRepo;
        this.deviceService = deviceService;
        this.alarmDict = alarmDict;
        this.aiClient = aiClient;
        this.aiConfigStore = aiConfigStore;
        this.mapper = mapper;
        this.userRepo = userRepo;
        this.notifier = notifier;
    }

    /* ==================== 诊断（核心） ==================== */

    /**
     * 对一条报警做诊断并落库成维修单。
     * <p>
     * <b>同步调用</b>（用户点按钮在等），但 AI 超时是 45 秒——
     * 前端要显示「分析中，通常 5~10 秒」，别让人以为卡死。
     * <p>
     * 同一条报警如果已有未结案的单，直接返回那张（不重复建）。
     */
    @Transactional
    public Map<String, Object> analyze(Long alarmEventId, String operator) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (alarmEventId == null) {
            return fail(out, "没给报警事件 id");
        }
        Optional<AlarmEvent> aOpt = alarmRepo.findById(alarmEventId);
        if (aOpt.isEmpty()) {
            return fail(out, "报警事件不存在（可能已被保留期清理）：" + alarmEventId);
        }
        AlarmEvent ev = aOpt.get();

        // 已经诊断过且没结案 → 直接把那张返回，别重复建单
        List<ErpMaintOrder> opened = repo.findByAlarmEventIdAndStatusInOrderByCreatedAtDesc(
                alarmEventId, List.of(ErpMaintOrder.ST_ANALYZED, ErpMaintOrder.ST_IN_PROGRESS));
        if (!opened.isEmpty()) {
            out.put("ok", true);
            out.put("reused", true);
            out.put("message", "这条报警已经有维修单了：" + opened.get(0).getOrderNo());
            out.put("order", row(opened.get(0)));
            return out;
        }

        DeviceEntity dev = null;
        try {
            dev = deviceService.byCode(ev.getDeviceCode());
        } catch (Exception e) {
            log.debug("取设备失败（不影响诊断）：{}", e.getMessage());
        }

        String rawCode = ev.getValue() == null ? null : trimNum(ev.getValue());
        // 字典：品牌从设备配置取。normalize 会去掉 AL./Er. 前缀，传 "AL.33" 和 "33" 一样
        AlarmCodeDict.Resolved dict = null;
        if (rawCode != null) {
            String brand = dev == null ? null : dev.getServoBrand();
            dict = alarmDict.lookup(brand, "alarm", rawCode);
        }

        // 历史样本：同设备 + 同报警码 + 已结案
        List<ErpMaintOrder> history = rawCode == null ? List.of()
                : repo.findByDeviceCodeAndAlarmCodeAndStatusOrderByResolvedAtDesc(
                        ev.getDeviceCode(), dict == null ? rawCode : dict.code(),
                        ErpMaintOrder.ST_RESOLVED, PageRequest.of(0, HISTORY_LIMIT));

        Map<String, Object> facts = buildFacts(ev, dev, dict, history);

        long t0 = System.currentTimeMillis();
        String advice;
        String source;
        try {
            AiConfig cfg = aiConfigStore.current();
            if (cfg.isEnabled() && cfg.getApiKey() != null && !cfg.getApiKey().isBlank()) {
                AiClient.Reply r = aiClient.chat(cfg, PROMPT, mapper.writeValueAsString(facts));
                advice = r.text() == null ? null : r.text().trim();
                source = advice == null || advice.isBlank() ? ErpMaintOrder.SRC_LOCAL : ErpMaintOrder.SRC_AI;
            } else {
                advice = null;
                source = ErpMaintOrder.SRC_LOCAL;
            }
        } catch (Exception e) {
            log.warn("诊断 AI 调用失败，退回本地建议：{}", e.getMessage());
            advice = null;
            source = ErpMaintOrder.SRC_LOCAL;
        }
        if (advice == null || advice.isBlank()) {
            advice = localAdvice(dict, history, ev);
        }

        ErpMaintOrder o = new ErpMaintOrder();
        o.setOrderNo(nextOrderNo());
        o.setAlarmEventId(alarmEventId);
        o.setDeviceCode(ev.getDeviceCode());
        o.setDeviceName(ev.getDeviceName());
        o.setAlarmCode(dict == null ? rawCode : dict.code());
        o.setAlarmText(ev.getMessage() == null ? ev.getRuleName() : ev.getMessage());
        o.setAiAdvice(advice);
        o.setAiSource(source);
        o.setAiAt(System.currentTimeMillis());
        o.setAiElapsedMs((int) (System.currentTimeMillis() - t0));
        o.setStatus(ErpMaintOrder.ST_ANALYZED);
        o.setCreatedBy(operator);
        o.setCreatedAt(System.currentTimeMillis());
        repo.save(o);

        out.put("ok", true);
        out.put("message", "已生成维修单 " + o.getOrderNo() + "（诊断来源：" + source + "）");
        out.put("order", row(o));
        out.put("history", history.stream().map(ErpMaintService::sampleRow).toList());
        return out;
    }

    /** 组 facts：给模型的事实必须是系统自己的数据，不让它凭空想 */
    private Map<String, Object> buildFacts(AlarmEvent ev, DeviceEntity dev,
                                           AlarmCodeDict.Resolved dict,
                                           List<ErpMaintOrder> history) {
        Map<String, Object> f = new LinkedHashMap<>();
        Map<String, Object> device = new LinkedHashMap<>();
        device.put("code", ev.getDeviceCode());
        device.put("name", ev.getDeviceName());
        device.put("location", ev.getLocation());
        if (dev != null) {
            device.put("brand", dev.getServoBrand());
            device.put("protocol", dev.getProtocol());
        }
        f.put("device", device);

        Map<String, Object> alarm = new LinkedHashMap<>();
        alarm.put("code", ev.getValue() == null ? null : trimNum(ev.getValue()));
        alarm.put("text", ev.getMessage() == null ? ev.getRuleName() : ev.getMessage());
        alarm.put("level", ev.getLevel());
        alarm.put("ruleDesc", ev.getRuleDesc());
        f.put("alarm", alarm);

        // 字典命中就把准确含义给模型，让它不用猜（也就不会编）
        if (dict != null && dict.known()) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("brandText", dict.brandText());
            d.put("name", dict.name());
            d.put("cause", dict.cause());
            d.put("remedy", dict.remedy());
            f.put("dictionary", d);
        } else if (dict != null) {
            f.put("dictionary", Map.of("note",
                    "该品牌字典里没有这个码（品牌：" + (dict.brandText() == null ? "未配置" : dict.brandText())
                            + "），不要编造含义"));
        }

        if (!history.isEmpty()) {
            List<Map<String, Object>> hs = new ArrayList<>();
            for (ErpMaintOrder h : history) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("rootCause", h.getRootCause());
                m.put("solution", h.getSolution());
                m.put("partsReplaced", h.getPartsReplaced());
                m.put("downtimeMin", h.getDowntimeMin());
                m.put("category", h.getCauseCategory());
                hs.add(m);
            }
            f.put("history", hs);
        }
        return f;
    }

    /**
     * 没配 AI / AI 挂了时的兜底。
     * <p>
     * 比通用规则强的地方：<b>字典里有这个码的准确原因和处理</b>，
     * 历史里还有这台机上次的真实原因——这两样已经能解决大部分场景，
     * AI 的增量价值主要在「字典没有的码」和「多因素综合判断」。
     */
    private String localAdvice(AlarmCodeDict.Resolved dict, List<ErpMaintOrder> history, AlarmEvent ev) {
        StringBuilder sb = new StringBuilder("本地建议（未启用 AI，按字典与历史给出）：\n");
        int n = 0;
        if (dict != null && dict.known()) {
            sb.append("- 报警码 ").append(dict.code()).append(" 含义：").append(nz(dict.name()))
                    .append("；可能原因：").append(nz(dict.cause())).append("\n");
            if (dict.remedy() != null && !dict.remedy().isBlank()) {
                sb.append(++n).append(". ").append(dict.remedy()).append("\n");
            }
        } else if (dict != null && dict.code() != null) {
            sb.append("- 报警码 ").append(dict.code()).append(" 不在内置字典里（")
                    .append(dict.brandText() == null ? "设备未配置品牌" : dict.brandText())
                    .append("），请查对应品牌手册\n");
        }
        if (!history.isEmpty()) {
            ErpMaintOrder h = history.get(0);
            sb.append(++n).append(". 历史同类记录：上次实际原因是「").append(nz(h.getRootCause()))
                    .append("」，处理「").append(nz(h.getSolution())).append("」");
            if (h.getPartsReplaced() != null && !h.getPartsReplaced().isBlank()) {
                sb.append("，换件「").append(h.getPartsReplaced()).append("」");
            }
            sb.append("；先把这条排除掉\n");
        }
        if (ev.getState() != null && "ACTIVE".equals(ev.getState())) {
            sb.append(++n).append(". 报警仍未消除：先确认设备已停机并挂检修牌，处理前不要复位\n");
        }
        if (n == 0) {
            sb.append(++n).append(". 无字典与历史可参考，按常规顺序查：电源 → 通讯线 → 参数 → 机械负载\n");
        }
        sb.append("风险提示：带电插拔通讯线可能烧端口，处理前先确认设备状态。");
        return sb.toString();
    }

    /* ==================== 流转 ==================== */

    @Transactional
    public Map<String, Object> start(String orderNo, String operator) {
        Map<String, Object> out = new LinkedHashMap<>();
        ErpMaintOrder o = repo.findByOrderNo(orderNo).orElse(null);
        if (o == null) {
            return fail(out, "维修单不存在：" + orderNo);
        }
        if (!ErpMaintOrder.ST_ANALYZED.equals(o.getStatus())) {
            return fail(out, "只有「已诊断」的单能开始维修，当前：" + statusText(o.getStatus()));
        }
        o.setStatus(ErpMaintOrder.ST_IN_PROGRESS);
        o.setStartedAt(System.currentTimeMillis());
        repo.save(o);
        out.put("ok", true);
        out.put("message", "已开始维修");
        out.put("order", row(o));
        return out;
    }

    /**
     * 结案：回填实际原因。
     * <p>
     * <b>这几项是样本的全部价值</b>，所以 {@code causeCategory} 与 {@code rootCause} 必填。
     * 只写「修好了」三个字，下次同类报警照样从零猜——那这个闭环就白做了。
     */
    @Transactional
    public Map<String, Object> resolve(String orderNo, Map<String, Object> body, String operator) {
        Map<String, Object> out = new LinkedHashMap<>();
        ErpMaintOrder o = repo.findByOrderNo(orderNo).orElse(null);
        if (o == null) {
            return fail(out, "维修单不存在：" + orderNo);
        }
        if (ErpMaintOrder.ST_RESOLVED.equals(o.getStatus())) {
            return fail(out, "这张单已经结案了");
        }
        if (ErpMaintOrder.ST_CANCELLED.equals(o.getStatus())) {
            return fail(out, "已取消的单不能结案");
        }
        String cat = str(body.get("causeCategory"));
        if (cat == null || !inCategories(cat)) {
            return fail(out, "请选择原因分类（电气/机械/参数/操作/来料/其他）——"
                    + "没有分类就统计不出「这台机主要坏在哪一类」");
        }
        String cause = str(body.get("rootCause"));
        if (cause == null || cause.length() < 2) {
            return fail(out, "请写清实际原因——这是下次同类报警能参考的全部依据，"
                    + "只写「修好了」等于没记");
        }
        o.setCauseCategory(cat);
        o.setRootCause(cause);
        o.setSolution(str(body.get("solution")));
        o.setPartsReplaced(str(body.get("partsReplaced")));
        Integer dt = integer(body.get("downtimeMin"));
        if (dt != null && dt < 0) {
            return fail(out, "停机分钟数不能是负数");
        }
        o.setDowntimeMin(dt);
        o.setStatus(ErpMaintOrder.ST_RESOLVED);
        o.setResolvedAt(System.currentTimeMillis());
        o.setResolvedBy(operator);
        repo.save(o);
        out.put("ok", true);
        out.put("message", "已结案，这条经验会用于下次同类报警的诊断");
        out.put("order", row(o));
        return out;
    }

    @Transactional
    public Map<String, Object> cancel(String orderNo, Map<String, Object> body, String operator) {
        Map<String, Object> out = new LinkedHashMap<>();
        ErpMaintOrder o = repo.findByOrderNo(orderNo).orElse(null);
        if (o == null) {
            return fail(out, "维修单不存在：" + orderNo);
        }
        if (ErpMaintOrder.ST_RESOLVED.equals(o.getStatus())) {
            return fail(out, "已结案的单不能取消");
        }
        // 取消理由要说真话：以后统计「多少维修单是误报」全靠它
        String why = str(body.get("reason"));
        if (why == null || why.length() < 2) {
            return fail(out, "请写明取消原因（误报/重复报/已在别处处理…）——"
                    + "「多少报警是误报」这个数只能从这里来");
        }
        o.setStatus(ErpMaintOrder.ST_CANCELLED);
        o.setRootCause("【已取消】" + why);
        o.setResolvedAt(System.currentTimeMillis());
        o.setResolvedBy(operator);
        repo.save(o);
        out.put("ok", true);
        out.put("message", "已取消");
        out.put("order", row(o));
        return out;
    }

    /* ==================== 派工（DH-85） ==================== */

    /**
     * 指派负责人。
     * <p>
     * 设计要点（详见 {@code docs/SPEC-DH85-WORKFLOW.md}）：
     * <ul>
     *   <li>派给<b>人</b>不派给角色 —— 派给 LEADER 等于三个 LEADER 互相以为对方在做</li>
     *   <li>指派<b>不是状态机的一部分</b>：ANALYZED + 指派仍是 ANALYZED，只多了责任人</li>
     *   <li>传空 assignee = 取消指派</li>
     *   <li>通知失败<b>不能让指派回滚</b> —— 小厂没配 webhook 就派不了工，等于功能不可用</li>
     * </ul>
     */
    @Transactional
    public Map<String, Object> assign(String orderNo, String assignee, String operator) {
        Map<String, Object> out = new LinkedHashMap<>();
        ErpMaintOrder o = repo.findByOrderNo(orderNo).orElse(null);
        if (o == null) {
            return fail(out, "维修单不存在：" + orderNo);
        }
        if (ErpMaintOrder.ST_RESOLVED.equals(o.getStatus())
                || ErpMaintOrder.ST_CANCELLED.equals(o.getStatus())) {
            return fail(out, "工单已" + statusText(o.getStatus()) + "，不能再指派");
        }
        String who = assignee == null ? "" : assignee.trim();
        if (who.isEmpty()) {
            o.setAssignee(null);
            o.setAssignedAt(null);
            o.setAssignedBy(null);
            repo.save(o);
            out.put("ok", true);
            out.put("assignee", null);
            out.put("message", "已取消指派");
            out.put("order", row(o));
            return out;
        }
        UserAccount u = userRepo.findByUsername(who).orElse(null);
        if (u == null) {
            return fail(out, "找不到用户：" + who);
        }
        if (!u.isEnabledNow()) {
            return fail(out, "用户已停用：" + who);
        }
        if (!ASSIGNABLE_ROLES.contains(String.valueOf(u.getRole()))) {
            return fail(out, "该用户角色是 " + u.getRole()
                    + "，不承接维修工单（只能派给 ADMIN / MANAGER / LEADER）");
        }
        o.setAssignee(who);
        o.setAssignedAt(System.currentTimeMillis());
        o.setAssignedBy(operator);
        repo.save(o);
        notifyAssign(o, who);
        out.put("ok", true);
        out.put("assignee", who);
        out.put("assignedAt", o.getAssignedAt());
        out.put("message", "已指派给 " + (u.getDisplayName() == null ? who : u.getDisplayName()));
        out.put("order", row(o));
        return out;
    }

    /** 指派通知：失败只记日志，**绝不回滚指派**（SPEC-DH85 §2.5、AC-08）。 */
    private void notifyAssign(ErpMaintOrder o, String who) {
        try {
            String dev = o.getDeviceName() == null ? o.getDeviceCode() : o.getDeviceName();
            notifier.sendBulletin("维修工单指派",
                    "工单 " + o.getOrderNo() + "（" + dev + "）已派给 " + who + "，请及时处理");
        } catch (Exception e) {
            log.warn("指派通知发送失败（不影响指派）：{}", e.getMessage());
        }
    }

    /**
     * 我的待办：直接查工单表，<b>不建待办表</b>。
     * 同一个事实存两份迟早不同步 —— DH-80（库存两个来源同屏两个数）就是这么来的。
     */
    public List<Map<String, Object>> my(String username) {
        if (username == null || username.isBlank()) {
            return List.of();
        }
        List<ErpMaintOrder> rows = repo.findByAssigneeAndStatusInOrderByAssignedAtDesc(
                username, List.of(ErpMaintOrder.ST_ANALYZED, ErpMaintOrder.ST_IN_PROGRESS));
        List<Map<String, Object>> list = new ArrayList<>();
        for (ErpMaintOrder o : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("orderNo", o.getOrderNo());
            m.put("deviceCode", o.getDeviceCode());
            m.put("deviceName", o.getDeviceName());
            m.put("alarmCode", o.getAlarmCode());
            m.put("alarmText", o.getAlarmText());
            m.put("status", o.getStatus());
            m.put("statusText", statusText(o.getStatus()));
            m.put("assignedAt", o.getAssignedAt());
            m.put("assignedBy", o.getAssignedBy());
            m.put("aiAdvice", o.getAiAdvice());
            m.put("createdAt", o.getCreatedAt());
            list.add(m);
        }
        return list;
    }

    /** 可选人员：只列能接单的角色，且只下发前端必要字段（不碰密码、盐等）。 */
    public List<Map<String, Object>> assignable() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (UserAccount u : userRepo.findAllByOrderByIdAsc()) {
            if (!u.isEnabledNow() || !ASSIGNABLE_ROLES.contains(String.valueOf(u.getRole()))) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("username", u.getUsername());
            m.put("displayName", u.getDisplayName());
            m.put("role", u.getRole());
            list.add(m);
        }
        return list;
    }

    /* ==================== AI 工具入口（DH-85） ==================== */

    /**
     * 直接从报警建工单，<b>不调模型</b>——给 AI 工具 {@code create_maint_order} 用。
     * <p>
     * 为什么不复用 {@link #analyze}：analyze 会<b>再调一次大模型</b>做诊断，
     * 而这个方法的调用场景恰恰是「模型已经在对话里判断过了」——
     * 再调一次既慢又花钱，还会产出两份互相不一致的建议。
     * 模型自己的判断通过 {@code advice} 带进来，落进 aiAdvice，aiSource = AGENT
     * （与「点按钮触发的 AI 分析」= AI 分开记，事后能查清这单到底是怎么来的）。
     * <p>
     * 同一条报警已有未结案单时不重复建；若这次带了 assignee 而原单没指派，顺手派掉。
     */
    @Transactional
    public Map<String, Object> createFromAlarm(Long alarmEventId, String assignee,
                                               String advice, String operator) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (alarmEventId == null) {
            return fail(out, "没给报警事件 id");
        }
        Optional<AlarmEvent> aOpt = alarmRepo.findById(alarmEventId);
        if (aOpt.isEmpty()) {
            return fail(out, "报警事件不存在（可能已被保留期清理）：" + alarmEventId);
        }
        AlarmEvent ev = aOpt.get();

        List<ErpMaintOrder> opened = repo.findByAlarmEventIdAndStatusInOrderByCreatedAtDesc(
                alarmEventId, List.of(ErpMaintOrder.ST_ANALYZED, ErpMaintOrder.ST_IN_PROGRESS));
        if (!opened.isEmpty()) {
            ErpMaintOrder exist = opened.get(0);
            String extra = "";
            if (assignee != null && !assignee.isBlank() && exist.getAssignee() == null) {
                Map<String, Object> as = assign(exist.getOrderNo(), assignee, operator);
                extra = Boolean.TRUE.equals(as.get("ok"))
                        ? "，并已指派给 " + assignee
                        : "；指派失败：" + as.get("message");
            }
            out.put("ok", true);
            out.put("reused", true);
            out.put("message", "这条报警已经有维修单了：" + exist.getOrderNo() + extra);
            out.put("order", row(exist));
            return out;
        }

        DeviceEntity dev = null;
        try {
            dev = deviceService.byCode(ev.getDeviceCode());
        } catch (Exception e) {
            log.debug("取设备失败（不影响建单）：{}", e.getMessage());
        }
        String rawCode = ev.getValue() == null ? null : trimNum(ev.getValue());
        AlarmCodeDict.Resolved dict = null;
        if (rawCode != null) {
            dict = alarmDict.lookup(dev == null ? null : dev.getServoBrand(), "alarm", rawCode);
        }

        ErpMaintOrder o = new ErpMaintOrder();
        o.setOrderNo(nextOrderNo());
        o.setAlarmEventId(alarmEventId);
        o.setDeviceCode(ev.getDeviceCode());
        o.setDeviceName(ev.getDeviceName() == null ? ev.getDeviceCode() : ev.getDeviceName());
        o.setAlarmCode(dict == null ? rawCode : dict.code());
        o.setAlarmText(ev.getMessage() == null ? ev.getRuleName() : ev.getMessage());
        if (advice != null && !advice.isBlank()) {
            o.setAiAdvice(advice.trim());
            o.setAiSource(ErpMaintOrder.SRC_AGENT);
            o.setAiAt(System.currentTimeMillis());
        } else {
            o.setAiSource(ErpMaintOrder.SRC_LOCAL);
        }
        o.setStatus(ErpMaintOrder.ST_ANALYZED);
        o.setCreatedBy(operator);
        o.setCreatedAt(System.currentTimeMillis());
        repo.save(o);

        String extra = "";
        if (assignee != null && !assignee.isBlank()) {
            Map<String, Object> as = assign(o.getOrderNo(), assignee, operator);
            extra = Boolean.TRUE.equals(as.get("ok"))
                    ? "，已指派给 " + assignee
                    : "；但指派失败：" + as.get("message");
        }
        out.put("ok", true);
        out.put("message", "已建维修单 " + o.getOrderNo() + extra);
        out.put("order", row(repo.findByOrderNo(o.getOrderNo()).orElse(o)));
        return out;
    }

    /* ==================== 查询与统计 ==================== */

    public Map<String, Object> list(String status, String kw, int page, int size) {
        int p = Math.max(0, page);
        int s = Math.min(Math.max(1, size), 200);
        PageRequest pr = PageRequest.of(p, s);
        Page<ErpMaintOrder> pg;
        String kwv = kw == null ? "" : kw.trim();
        if (status != null && !status.isBlank()) {
            pg = kwv.isEmpty() ? repo.findByStatusOrderByCreatedAtDesc(status, pr)
                    : repo.searchByStatus(status, kwv, pr);
        } else {
            pg = kwv.isEmpty() ? repo.findByOrderByCreatedAtDesc(pr) : repo.search(kwv, pr);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("total", pg.getTotalElements());
        m.put("rows", pg.getContent().stream().map(ErpMaintService::row).toList());
        m.put("analyzed", repo.countByStatus(ErpMaintOrder.ST_ANALYZED));
        m.put("inProgress", repo.countByStatus(ErpMaintOrder.ST_IN_PROGRESS));
        m.put("resolved", repo.countByStatus(ErpMaintOrder.ST_RESOLVED));
        return m;
    }

    /**
     * 经验统计——这块数据是「闭环有没有用」的唯一证据。
     * <p>
     * 三个维度回答三个真问题：<b>哪类故障最多</b>（备件储备）、
     * <b>哪台机最爱坏</b>（预防性维护）、<b>哪个码最常见</b>（该不该补进字典）。
     */
    public Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);

        List<Map<String, Object>> byCause = new ArrayList<>();
        for (Object[] r : repo.countByCauseCategory()) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("category", r[0]);
            x.put("text", causeText((String) r[0]));
            x.put("count", r[1]);
            byCause.add(x);
        }
        m.put("byCause", byCause);

        List<Map<String, Object>> byDevice = new ArrayList<>();
        for (Object[] r : repo.countByDevice()) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("deviceCode", r[0]);
            x.put("deviceName", r[1]);
            x.put("count", r[2]);
            x.put("avgDowntimeMin", r[3] == null ? null : Math.round(((Number) r[3]).doubleValue() * 10) / 10.0);
            byDevice.add(x);
        }
        m.put("byDevice", byDevice);

        List<Map<String, Object>> byCode = new ArrayList<>();
        for (Object[] r : repo.countByAlarmCode()) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("alarmCode", r[0]);
            x.put("count", r[1]);
            x.put("avgDowntimeMin", r[2] == null ? null : Math.round(((Number) r[2]).doubleValue() * 10) / 10.0);
            byCode.add(x);
        }
        m.put("byAlarmCode", byCode);

        m.put("total", repo.count());
        m.put("resolved", repo.countByStatus(ErpMaintOrder.ST_RESOLVED));
        // 结案率低说明「建了单没人修」或「修了没人回填」——两种都是闭环没跑起来
        long total = repo.count();
        m.put("resolveRate", total == 0 ? null
                : Math.round(repo.countByStatus(ErpMaintOrder.ST_RESOLVED) * 1000.0 / total) / 10.0);
        return m;
    }

    /* ==================== 内部工具 ==================== */

    private String nextOrderNo() {
        String prefix = "MO" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        String max = repo.findMaxOrderNo(prefix);
        int seq = 1;
        if (max != null && max.length() > prefix.length()) {
            try {
                seq = Integer.parseInt(max.substring(prefix.length())) + 1;
            } catch (NumberFormatException ignored) {
                // 单号被人手工改成非数字：退回从 1 开始，唯一键会兜住冲突
            }
        }
        return prefix + String.format("%04d", seq);
    }

    private static Map<String, Object> row(ErpMaintOrder o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("orderNo", o.getOrderNo());
        m.put("alarmEventId", o.getAlarmEventId());
        m.put("deviceCode", o.getDeviceCode());
        m.put("deviceName", o.getDeviceName());
        m.put("alarmCode", o.getAlarmCode());
        m.put("alarmText", o.getAlarmText());
        m.put("aiAdvice", o.getAiAdvice());
        m.put("aiSource", o.getAiSource());
        m.put("aiAt", o.getAiAt());
        m.put("aiElapsedMs", o.getAiElapsedMs());
        m.put("causeCategory", o.getCauseCategory());
        m.put("causeCategoryText", causeText(o.getCauseCategory()));
        m.put("rootCause", o.getRootCause());
        m.put("solution", o.getSolution());
        m.put("partsReplaced", o.getPartsReplaced());
        m.put("downtimeMin", o.getDowntimeMin());
        m.put("status", o.getStatus());
        m.put("statusText", statusText(o.getStatus()));
        m.put("createdBy", o.getCreatedBy());
        m.put("createdAt", o.getCreatedAt());
        m.put("startedAt", o.getStartedAt());
        m.put("resolvedAt", o.getResolvedAt());
        m.put("resolvedBy", o.getResolvedBy());
        return m;
    }

    private static Map<String, Object> sampleRow(ErpMaintOrder h) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("orderNo", h.getOrderNo());
        m.put("rootCause", h.getRootCause());
        m.put("solution", h.getSolution());
        m.put("partsReplaced", h.getPartsReplaced());
        m.put("downtimeMin", h.getDowntimeMin());
        m.put("causeCategoryText", causeText(h.getCauseCategory()));
        m.put("resolvedAt", h.getResolvedAt());
        return m;
    }

    public static String statusText(String s) {
        if (s == null) {
            return "--";
        }
        return switch (s) {
            case ErpMaintOrder.ST_ANALYZED -> "已诊断";
            case ErpMaintOrder.ST_IN_PROGRESS -> "维修中";
            case ErpMaintOrder.ST_RESOLVED -> "已结案";
            case ErpMaintOrder.ST_CANCELLED -> "已取消";
            default -> s;
        };
    }

    public static String causeText(String c) {
        if (c == null) {
            return null;
        }
        return switch (c) {
            case ErpMaintOrder.CAUSE_ELECTRIC -> "电气";
            case ErpMaintOrder.CAUSE_MECHANICAL -> "机械";
            case ErpMaintOrder.CAUSE_PARAM -> "参数/程序";
            case ErpMaintOrder.CAUSE_OPERATION -> "操作";
            case ErpMaintOrder.CAUSE_MATERIAL -> "来料/工件";
            case ErpMaintOrder.CAUSE_OTHER -> "其他";
            default -> c;
        };
    }

    private static boolean inCategories(String c) {
        for (String x : ErpMaintOrder.CAUSE_CATEGORIES) {
            if (x.equals(c)) {
                return true;
            }
        }
        return false;
    }

    /** 报警码是数字（metric_value 是 Double），转成 "33" 而不是 "33.0" */
    private static String trimNum(double v) {
        if (v == Math.rint(v) && !Double.isInfinite(v)) {
            return String.valueOf((long) v);
        }
        return String.valueOf(v);
    }

    private static String nz(String s) {
        return s == null || s.isBlank() ? "--" : s;
    }

    private static String str(Object v) {
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    private static Integer integer(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return (int) Double.parseDouble(String.valueOf(v).trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static Map<String, Object> fail(Map<String, Object> out, String msg) {
        out.put("ok", false);
        out.put("message", msg);
        return out;
    }
}
