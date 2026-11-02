package com.factory.hub.alarm;

import com.factory.hub.audit.AuditLog;
import com.factory.hub.audit.AuditService;
import com.factory.hub.model.DeviceEntity;
import com.factory.hub.model.PointDef;
import com.factory.hub.service.DeviceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 告警相关接口，给前端「报警」页用。
 * <p>
 * 和 /open/v1/** 的对外接口分开：客户要把告警对接到自己的 MES 时，
 * 应该另开带 Key 校验的端点，不要把这里的确认、改规则接口暴露出去——
 * 否则任何能访问内网的人都能把报警关掉。
 */
@RestController
@RequestMapping("/api/alarms")
public class AlarmController {

    private final AlarmService alarmService;
    private final AlarmRuleStore ruleStore;
    private final NotifyStore notifyStore;
    private final AlarmNotifier notifier;
    private final AlarmEngine engine;
    private final AlarmEventRepository repo;
    private final DeviceService deviceService;
    private final AuditService audit;
    /** 告警的人话描述（审计文案用），单独的类——文案和告警逻辑不是一回事 */
    private final AlarmDescribe describe;

    public AlarmController(AlarmService alarmService, AlarmRuleStore ruleStore,
                           NotifyStore notifyStore, AlarmNotifier notifier,
                           AlarmEngine engine, AlarmEventRepository repo,
                           DeviceService deviceService, AuditService audit,
                           AlarmDescribe describe) {
        this.alarmService = alarmService;
        this.ruleStore = ruleStore;
        this.notifyStore = notifyStore;
        this.notifier = notifier;
        this.engine = engine;
        this.repo = repo;
        this.deviceService = deviceService;
        this.audit = audit;
        this.describe = describe;
    }

    /* ==================== 告警列表 ==================== */

    @GetMapping
    public Map<String, Object> list(@RequestParam(defaultValue = "OPEN") String state,
                                    @RequestParam(defaultValue = "ALL") String level,
                                    @RequestParam(defaultValue = "") String q,
                                    @RequestParam(defaultValue = "7d") String range,
                                    @RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "20") int size) {
        return alarmService.list(state, level, q, range, page, size);
    }

    @GetMapping("/summary")
    public Map<String, Object> summary() {
        return alarmService.summary();
    }

    /** 确认一条。表示「人已经知道了」，不等于设备修好了 */
    @PostMapping("/{id}/ack")
    public Map<String, Object> ack(@PathVariable Long id,
                                   @RequestBody(required = false) Map<String, Object> body) {
        String by = body == null ? null : str(body.get("by"));
        // 先把告警读出来：确认之后就没法从引擎里拿到标题了，审计要写给人看
        AlarmEvent ev = repo.findById(id).orElse(null);
        String err = alarmService.ack(id, by);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", err == null);
        out.put("message", err == null ? "已确认" : err);
        String title = ev == null ? null : describe.alarmTitle(ev);
        String detail = "确认告警 #" + id + (title == null ? "" : "：" + title);
        if (err == null) {
            audit.ok(AuditLog.CAT_ALARM, AuditLog.ACT_UPDATE, String.valueOf(id),
                    title == null ? String.valueOf(id) : title, detail);
        } else {
            audit.fail(AuditLog.CAT_ALARM, AuditLog.ACT_UPDATE, String.valueOf(id),
                    title == null ? String.valueOf(id) : title, detail, err);
        }
        return out;
    }

    @PostMapping("/ack-all")
    public Map<String, Object> ackAll(@RequestBody(required = false) Map<String, Object> body) {
        String by = body == null ? null : str(body.get("by"));
        int n = alarmService.ackAll(by);
        audit.ok(AuditLog.CAT_ALARM, AuditLog.ACT_UPDATE, "all", "全部未处理告警",
                "批量确认告警 " + n + " 条");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("count", n);
        out.put("message", "已确认 " + n + " 条");
        return out;
    }

    /* ==================== 规则 ==================== */

    @GetMapping("/rules")
    public Map<String, Object> rules() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (AlarmRule r : ruleStore.current()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("rule", r);
            m.put("typeName", r.typeName());
            // 这条规则自己的等级文案。页面上直接显示，省得前端再翻一遍字典
            m.put("levelText", AlarmTierResolver.levelText(r.getLevel()));
            // 阈值单位（℃ / kW / MPa…）。规则本身不存单位，从点位定义反查。
            // 同一个数值在温度点是 ℃、在压力点是 MPa，写错了客户会照着错单位去判断
            m.put("unit", describe.unitOf(r));
            m.put("error", r.validate());
            items.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("items", items);
        // 等级下拉的文案由后端给：预警 / 告警 / 停机 只有这一份定义，
        // 前端只负责渲染，别在两边各写一份然后越改越不一样
        out.put("levelOptions", levelOptions());
        return out;
    }

    /** 等级枚举 + 中文文案，给规则表单的等级下拉用 */
    private static List<Map<String, Object>> levelOptions() {
        List<Map<String, Object>> list = new ArrayList<>(3);
        for (String lv : List.of(AlarmTierResolver.CRITICAL, AlarmTierResolver.MAJOR,
                AlarmTierResolver.MINOR)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("value", lv);
            m.put("label", AlarmTierResolver.levelText(lv));
            list.add(m);
        }
        return list;
    }

    /**
     * 新增或更新一条规则。
     * <p>
     * 配置有硬伤直接拒绝（比如数值越限没填阈值）——这种规则存进去也永远不会触发，
     * 客户会以为「系统不报警」，最后查半天是配置问题。
     */
    @PostMapping("/rules")
    public Map<String, Object> saveRule(@RequestBody AlarmRule rule) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (rule == null) {
            out.put("ok", false);
            out.put("message", "规则为空");
            return out;
        }
        String bad = rule.validate();
        if (bad != null) {
            out.put("ok", false);
            out.put("message", bad);
            return out;
        }
        boolean wasEnabled = false;
        AlarmRule old = rule.getId() == null ? null : ruleStore.byId(rule.getId());
        if (old != null) {
            wasEnabled = old.isEnabled();
        }
        // 库里找不到同 id 的规则 = 这是一条新规则
        boolean created = old == null;

        AlarmRule saved = ruleStore.save(rule);

        // 从「开」改成「关」，还挂着的告警要收掉，不然列表里一直有条假告警
        if (wasEnabled && !saved.isEnabled()) {
            engine.closeByRule(saved.getId(), "规则已停用");
        }
        // 改了档位要让人一眼看出来。只写「档位：预警>80 / 告警>85 / 停机>90」，
        // 事后翻审计根本不知道是改了哪一档。
        String tierChange = created ? null : describe.describeTierChange(old, saved);
        String detail = (created ? "新增告警规则 " : "修改告警规则 ") + saved.getName()
                + "（" + saved.typeName() + "，" + describe.describeCondition(saved, tierChange)
                + "，" + (saved.isEnabled() ? "已启用" : "已停用") + "）";
        audit.ok(AuditLog.CAT_ALARM, created ? AuditLog.ACT_CREATE : AuditLog.ACT_UPDATE,
                saved.getId(), saved.getName(), detail);
        out.put("ok", true);
        out.put("message", "已保存，立即生效");
        out.put("rule", saved);
        return out;
    }

    @DeleteMapping("/rules/{id}")
    public Map<String, Object> deleteRule(@PathVariable String id) {
        Map<String, Object> out = new LinkedHashMap<>();
        // 先记下规则名，删完就查不到了
        AlarmRule old = ruleStore.byId(id);
        String ruleName = old == null ? id : old.getName();
        engine.closeByRule(id, "规则已删除");
        boolean ok = ruleStore.delete(id);
        if (ok) {
            audit.ok(AuditLog.CAT_ALARM, AuditLog.ACT_DELETE, id, ruleName,
                    "删除告警规则「" + ruleName + "」，该规则挂着的告警一并关闭");
        } else {
            audit.fail(AuditLog.CAT_ALARM, AuditLog.ACT_DELETE, id, ruleName,
                    "删除告警规则「" + ruleName + "」", "规则不存在");
        }
        out.put("ok", ok);
        out.put("message", ok ? "已删除" : "规则不存在");
        return out;
    }

    /**
     * 规则体检。把「为什么这条规则不会触发」讲清楚，
     * 比如状态点没标 role=status、点位不存在。
     */
    @GetMapping("/rules/check")
    public Map<String, Object> checkRules() {
        List<String> warnings = new ArrayList<>();
        List<AlarmRule> rules = ruleStore.current();
        long enabled = rules.stream().filter(AlarmRule::isEnabled).count();
        if (enabled == 0) {
            warnings.add("当前没有任何启用的规则，系统不会报警。到「报警 → 规则」里打开至少要监控的几项。");
        }
        for (AlarmRule r : rules) {
            String bad = r.validate();
            if (bad != null) {
                warnings.add("规则「" + r.getName() + "」配置有问题：" + bad);
            }
        }
        warnings.addAll(checkPointsExist(rules));
        NotifyConfig cfg = notifyStore.current();
        if (!cfg.isEnabled()) {
            warnings.add("通知未启用，告警只会显示在网页上，不会发到手机。配好机器人地址后打开总开关。");
        } else if (cfg.getChannels().stream().noneMatch(c -> c.isEnabled() && c.validate() == null)) {
            warnings.add("通知已打开，但没有一个可用的渠道。检查地址是否填完整。");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("warnings", warnings);
        return out;
    }

    /**
     * 规则里写的点位名，在它管的设备点表里到底有没有。
     * <p>
     * 必须查：点位名写错（点表里叫 temperature，规则里写 temp）时，规则既不报错也不触发，
     * 页面一片安静，客户以为「设备一直很正常」，实际这条监控从来没工作过。
     * 静默失效比直接报错难查一百倍，这种体检就是用来堵它的。
     */
    private List<String> checkPointsExist(List<AlarmRule> rules) {
        List<String> out = new ArrayList<>();
        List<DeviceEntity> devices = deviceService.all();
        if (devices.isEmpty()) {
            return out;
        }
        for (AlarmRule r : rules) {
            String key = r.getPointKey();
            if (key == null || key.isBlank()) {
                continue;                       // 离线 / 停机规则不需要点位
            }
            boolean hit = false;
            java.util.LinkedHashSet<String> keys = new java.util.LinkedHashSet<>();
            boolean covered = false;
            for (DeviceEntity d : devices) {
                if (!r.covers(d.getCode())) {
                    continue;
                }
                covered = true;
                for (PointDef p : deviceService.pointsOf(d)) {
                    keys.add(p.getKey());
                    if (key.equalsIgnoreCase(p.getKey())) {
                        hit = true;
                    }
                }
            }
            if (covered && !hit) {
                String hint = keys.isEmpty() ? ""
                        : "这台设备上的点位是：" + String.join(" / ", keys.stream().limit(6).toList());
                out.add("规则「" + r.getName() + "」用的点位 " + key
                        + " 在它管的设备上不存在，这条规则永远不会触发。" + hint);
            }
        }
        return out;
    }

    /* ==================== 通知配置 ==================== */
    @GetMapping("/notify")
    public Map<String, Object> notifyConfig() {
        NotifyConfig cfg = notifyStore.current();
        List<Map<String, Object>> chs = new ArrayList<>();
        for (NotifyChannel c : cfg.getChannels()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("channel", c);
            m.put("typeName", c.typeName());
            m.put("error", c.validate());
            chs.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        // 这里返回明文地址。曾经想过遮罩，但遮罩后客户点保存会把 **** 写回数据库，
        // 通知从此静默失效——这种坑比截图泄漏更难查。页面已提示不要把地址外传。
        out.put("config", cfg);
        out.put("channels", chs);
        return out;
    }

    @PostMapping("/notify")
    public Map<String, Object> saveNotify(@RequestBody NotifyConfig cfg) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (cfg == null) {
            out.put("ok", false);
            out.put("message", "配置为空");
            audit.fail(AuditLog.CAT_ALARM, AuditLog.ACT_UPDATE, "notify", "通知配置",
                    "保存通知配置", "配置为空");
            return out;
        }
        if (cfg.isEnabled()) {
            boolean any = cfg.getChannels().stream()
                    .anyMatch(c -> c.isEnabled() && c.validate() == null);
            if (!any) {
                out.put("ok", false);
                out.put("message", "没有可用的通知渠道，地址填完整再打开总开关");
                audit.fail(AuditLog.CAT_ALARM, AuditLog.ACT_UPDATE, "notify", "通知配置",
                        "保存通知配置", "没有可用的通知渠道，地址填完整再打开总开关");
                return out;
            }
        }
        notifyStore.save(cfg);
        // 只记渠道名称和启停状态，不记地址和 token——那两样等于把机器人钥匙贴到日志上
        audit.ok(AuditLog.CAT_ALARM, AuditLog.ACT_UPDATE, "notify", "通知配置",
                describe.describeNotify(cfg));
        out.put("ok", true);
        out.put("message", "已保存，立即生效");
        out.put("config", notifyStore.current());
        return out;
    }

    /**
     * 测试发送。直接收前端正在编辑的渠道对象，不用先保存——
     * 让人先点测试确认能收到，再保存，比「保存了但收不到再回来改」省一轮。
     */
    @PostMapping("/notify/test")
    public Map<String, Object> testNotify(@RequestBody NotifyChannel channel) {
        if (channel == null) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", false);
            out.put("message", "渠道为空");
            return out;
        }
        return notifier.test(channel);
    }

    /* ==================== 维护 ==================== */

    /**
     * 清空告警历史。交付前清演示数据用。
     * 必须带 {@code {"confirm": true}}——客户半年的故障记录删了就找不回来了。
     */
    @PostMapping("/purge")
    public Map<String, Object> purge(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (body == null || !Boolean.TRUE.equals(body.get("confirm"))) {
            out.put("ok", false);
            out.put("message", "这个操作会删除全部告警历史，不可恢复。请传 {\"confirm\": true} 确认");
            audit.fail(AuditLog.CAT_ALARM, AuditLog.ACT_PURGE, "alarms", "告警历史",
                    "清空告警历史", "未确认，操作被拒绝");
            return out;
        }
        int n = repo.purgeAll();
        audit.ok(AuditLog.CAT_ALARM, AuditLog.ACT_PURGE, "alarms", "告警历史",
                "清空告警历史 " + n + " 条（不可恢复）");
        out.put("ok", true);
        out.put("deleted", n);
        out.put("message", "已删除 " + n + " 条告警记录");
        return out;
    }

    /** 单条详情，排查时看判定条件用 */
    @GetMapping("/{id}")
    public ResponseEntity<AlarmEvent> detail(@PathVariable Long id) {
        return repo.findById(id).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /* ==================== 审计辅助 ==================== */
    /* 告警的人话描述（标题 / 规则条件 / 通知配置）已经挪到 AlarmDescribe：
       那几个是纯展示逻辑，跟告警的增删改查是两件事，混在一起两边都不好找。 */

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
