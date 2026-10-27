package com.factory.hub.alarm;

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

    public AlarmController(AlarmService alarmService, AlarmRuleStore ruleStore,
                           NotifyStore notifyStore, AlarmNotifier notifier,
                           AlarmEngine engine, AlarmEventRepository repo,
                           DeviceService deviceService) {
        this.alarmService = alarmService;
        this.ruleStore = ruleStore;
        this.notifyStore = notifyStore;
        this.notifier = notifier;
        this.engine = engine;
        this.repo = repo;
        this.deviceService = deviceService;
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
        String err = alarmService.ack(id, by);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", err == null);
        out.put("message", err == null ? "已确认" : err);
        return out;
    }

    @PostMapping("/ack-all")
    public Map<String, Object> ackAll(@RequestBody(required = false) Map<String, Object> body) {
        String by = body == null ? null : str(body.get("by"));
        int n = alarmService.ackAll(by);
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
            m.put("error", r.validate());
            items.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("items", items);
        return out;
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

        AlarmRule saved = ruleStore.save(rule);

        // 从「开」改成「关」，还挂着的告警要收掉，不然列表里一直有条假告警
        if (wasEnabled && !saved.isEnabled()) {
            engine.closeByRule(saved.getId(), "规则已停用");
        }
        out.put("ok", true);
        out.put("message", "已保存，立即生效");
        out.put("rule", saved);
        return out;
    }

    @DeleteMapping("/rules/{id}")
    public Map<String, Object> deleteRule(@PathVariable String id) {
        Map<String, Object> out = new LinkedHashMap<>();
        engine.closeByRule(id, "规则已删除");
        boolean ok = ruleStore.delete(id);
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
            return out;
        }
        if (cfg.isEnabled()) {
            boolean any = cfg.getChannels().stream()
                    .anyMatch(c -> c.isEnabled() && c.validate() == null);
            if (!any) {
                out.put("ok", false);
                out.put("message", "没有可用的通知渠道，地址填完整再打开总开关");
                return out;
            }
        }
        notifyStore.save(cfg);
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
            return out;
        }
        int n = repo.purgeAll();
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

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
