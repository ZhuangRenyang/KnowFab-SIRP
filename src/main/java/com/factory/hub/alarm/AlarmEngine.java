package com.factory.hub.alarm;

import com.factory.hub.collect.SnapshotStore;
import com.factory.hub.system.SettingStore;
import com.factory.hub.model.DeviceEntity;
import com.factory.hub.model.PointValue;
import com.factory.hub.service.DeviceService;
import com.factory.hub.web.WsHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 告警评估引擎。
 * <p>
 * 每 2 秒扫一遍「规则 × 设备」，判断每个条件是否成立。成立后不是立刻报警，
 * 要连续成立 {@link AlarmRule#getForSeconds()} 秒才报。
 * <p>
 * <b>为什么独立线程，不挂在采集回调里：</b>
 * 采集线程一秒一次、几百个并发，里面塞判断逻辑，一旦某条判断抛异常或者查库变慢，
 * 整条采集链路跟着抖。而且采集线程本来就不该知道「告警」这件事。
 * <p>
 * <b>这套东西最容易翻车的地方，按踩坑顺序：</b>
 * <ol>
 *   <li><b>误报淹死人</b>——机器换刀停 30 秒就报警，第二天客户就把功能关了。
 *       对策：持续时间 + 恢复滞回 + 冷却期，三重过滤。</li>
 *   <li><b>下班后全厂报警</b>——晚上机器本来就停着。对策：规则带生效时段和星期。</li>
 *   <li><b>授权过期时全厂离线</b>——那是没付费，不是设备坏了。对策：授权不可用时跳过设备类告警。</li>
 *   <li><b>重启后告警丢失</b>——「3 号机从昨晚 11 点就停着」这条没了等于没报。
 *       对策：未恢复的告警落库，启动时捞回来继续跟踪。</li>
 *   <li><b>交换机一挂三百条通知</b>——群被刷屏，没人看得出真正问题。
 *       对策：同一轮触发过多时合并成一条「批量告警」。</li>
 * </ol>
 */
@Component
public class AlarmEngine {

    private static final Logger log = LoggerFactory.getLogger(AlarmEngine.class);

    private final AlarmRuleStore ruleStore;
    private final AlarmEventRepository repo;
    private final SnapshotStore snapshot;
    private final DeviceService deviceService;
    private final AlarmNotifier notifier;
    private final WsHandler ws;
    private final ObjectMapper mapper;

    /** 启动后多久内不评估。给采集线程一点时间把第一轮数据采回来 */
    @Value("${alarm.warmup-seconds:20}")
    private int warmupSeconds;

    /**
     * 合并阈值和保留天数都改成页面可调，所以走 SettingStore 现读。
     * 放在这里而不是 {@code @Value} 的原因：客户产线扩了要调阈值，
     * 为这点事重启服务会停采集几十秒，不值得。
     */
    private final SettingStore settings;

    /** key = ruleId|deviceCode */
    private final Map<String, Watch> watches = new ConcurrentHashMap<>();

    /** 当前未恢复的告警，key 同上。值就是数据库里那一行，改完统一保存 */
    private final Map<String, AlarmEvent> open = new ConcurrentHashMap<>();

    private volatile List<DeviceEntity> deviceCache = List.of();
    private volatile long deviceCacheAt = 0L;

    private final long startedAt = System.currentTimeMillis();

    private long lastCleanupDay = -1L;

    /** 只警告一次，避免每秒刷同一句 */
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    /** 一台设备的条件状态跟踪 */
    private static final class Watch {
        /** 条件开始成立的时刻，0 表示当前成立 */
        long badSince;
        /** 条件开始恢复的时刻 */
        long okSince;
        /** 上次触发时刻，用于冷却 */
        long lastFireAt;

        void reset() {
            badSince = 0;
            okSince = 0;
        }
    }

    /** 一次判断的结果 */
    private record Sample(boolean bad, Double value, String detail) {
        static Sample ok() {
            return new Sample(false, null, null);
        }

        static Sample bad(double value, String detail) {
            return new Sample(true, value, detail);
        }
    }

    private record Pending(String key, AlarmEvent event, AlarmRule rule) {
    }

    /** 注意：组件不能叫 notify / wait / equals，会和 Object 的方法冲突 */
    private record ClearPending(String key, AlarmEvent event, boolean shouldNotify) {
    }

    public AlarmEngine(AlarmRuleStore ruleStore, AlarmEventRepository repo, SnapshotStore snapshot,
                       DeviceService deviceService,
                       AlarmNotifier notifier, WsHandler ws, ObjectMapper mapper,
                       SettingStore settings) {
        this.ruleStore = ruleStore;
        this.repo = repo;
        this.snapshot = snapshot;
        this.deviceService = deviceService;
        this.notifier = notifier;
        this.ws = ws;
        this.mapper = mapper;
        this.settings = settings;
    }

    /* ==================== 启动：把没恢复的告警捞回来 ==================== */

    @EventListener(ApplicationReadyEvent.class)
    public void restore() {
        try {
            List<AlarmEvent> list = repo.findOpen();
            if (list.isEmpty()) {
                return;
            }
            List<AlarmRule> rules = ruleStore.current();
            Map<String, AlarmRule> ruleMap = new HashMap<>();
            for (AlarmRule r : rules) {
                ruleMap.put(r.getId(), r);
            }
            int restored = 0, closed = 0;
            for (AlarmEvent e : list) {
                AlarmRule r = ruleMap.get(e.getRuleId());
                if (r == null || !r.isEnabled()) {
                    e.setState(AlarmEvent.STATE_CLEARED);
                    e.setClearedAt(System.currentTimeMillis());
                    e.setClearReason("规则已删除或停用");
                    repo.save(e);
                    closed++;
                    continue;
                }
                open.put(key(e.getRuleId(), e.getDeviceCode()), e);
                restored++;
            }
            log.info("告警恢复：{} 条未处理告警继续跟踪，{} 条因规则失效自动关闭", restored, closed);
        } catch (Exception e) {
            log.error("加载未处理告警失败: {}", e.getMessage());
        }
    }

    /* ==================== 主循环 ==================== */

    @Scheduled(fixedDelayString = "${alarm.evaluate-ms:2000}")
    public synchronized void evaluate() {
        long now = System.currentTimeMillis();
        if (now - startedAt < warmupSeconds * 1000L) {
            return;
        }
        List<AlarmRule> rules = ruleStore.current();
        if (rules.isEmpty()) {
            return;
        }

        Map<String, DeviceEntity> devices = devices(now);
        List<Pending> fires = new ArrayList<>();
        List<ClearPending> clears = new ArrayList<>();

        for (AlarmRule rule : rules) {
            if (!rule.isEnabled()) {
                continue;
            }
            boolean inWindow = rule.activeAt(now);

            for (DeviceEntity d : devices.values()) {
                if (!rule.covers(d.getCode())) {
                    continue;
                }
                String k = key(rule.getId(), d.getCode());
                Watch w = watches.computeIfAbsent(k, x -> new Watch());

                if (!inWindow) {
                    // 出了生效时段（比如下班了），把还挂着的告警收掉，
                    // 但不算「设备修好了」，所以不发恢复通知
                    AlarmEvent cur = open.get(k);
                    if (cur != null) {
                        clears.add(new ClearPending(k, clear(cur, "已离开生效时段", w, now), false));
                    }
                    w.reset();
                    continue;
                }

                Sample s = sample(rule, d);
                if (s == null) {
                    continue;                 // 数据不足，这一轮不判断
                }

                if (s.bad()) {
                    w.okSince = 0;
                    if (w.badSince == 0) {
                        w.badSince = now;
                    }
                    AlarmEvent cur = open.get(k);
                    if (cur == null) {
                        long held = now - w.badSince;
                        if (held >= rule.getForSeconds() * 1000L
                                && now - w.lastFireAt >= rule.getCooldownSeconds() * 1000L) {
                            AlarmEvent ev = build(rule, d, s, now);
                            open.put(k, ev);
                            w.lastFireAt = now;
                            fires.add(new Pending(k, ev, rule));
                        }
                    } else if (repeatReached(cur, now) && notifier.fire(cur, rule)) {
                        // 只有真发出去才记账。免打扰 / 规则关了通知 / 没有可用渠道 都不算
                        markNotified(cur, now);
                    }
                } else {
                    w.badSince = 0;
                    AlarmEvent cur = open.get(k);
                    if (cur != null) {
                        if (w.okSince == 0) {
                            w.okSince = now;
                        }
                        if (now - w.okSince >= rule.getRecoverSeconds() * 1000L) {
                            clears.add(new ClearPending(k, clear(cur, "条件已恢复", w, now), true));
                        }
                    } else {
                        w.okSince = 0;
                    }
                }
            }
        }

        // 设备和规则被删掉的，它的告警不能永远挂在列表里
        cleanupOrphans(devices, rules, clears);

        persist(fires, clears);
        dispatch(fires, clears, now);
        pruneWatches(rules, devices);
        maybeCleanupHistory(now);
    }

    /* ==================== 条件判断 ==================== */

    /**
     * 判断一台设备是否满足规则。返回 null 表示「这一轮没法判断」，
     * 比如状态点还没采到——这种时候绝不能当成异常，否则每次改点表都会误报一轮。
     */
    private Sample sample(AlarmRule rule, DeviceEntity d) {
        Map<String, PointValue> vals = snapshot.latest(d.getCode());
        switch (rule.getType()) {
            case AlarmRule.TYPE_OFFLINE -> {
                SnapshotStore.Runtime rt = snapshot.runtime(d.getCode());
                return rt.online ? Sample.ok() : Sample.bad(0, "通讯中断");
            }
            case AlarmRule.TYPE_STOP -> {
                PointValue pv = statusPoint(vals);
                if (pv == null) {
                    if (warned.add("nostatus:" + d.getCode())) {
                        log.warn("设备 {} 没有 role=status 的点，停机类告警对它不起作用。"
                                + "到设备点表里把状态点标成 status", d.getCode());
                    }
                    return null;
                }
                if (!pv.isGood()) {
                    return null;              // 读坏了是通讯问题，交给离线规则
                }
                Double v = pv.asDouble();
                if (v == null) {
                    return null;
                }
                double target = rule.getThreshold() == null ? 0d : rule.getThreshold();
                return Math.abs(v - target) < 1e-9
                        ? Sample.bad(v, "状态值 " + fmtNum(v))
                        : Sample.ok();
            }
            case AlarmRule.TYPE_FLAG -> {
                String want = rule.getPointKey();
                for (PointValue pv : vals.values()) {
                    if (!"alarm".equals(pv.role()) || !pv.isGood()) {
                        continue;
                    }
                    if (!want.isBlank() && !want.equals(pv.pointKey())) {
                        continue;
                    }
                    Double v = pv.asDouble();
                    if (v != null && v != 0d) {
                        return Sample.bad(v, pv.name() + " = " + fmtNum(v));
                    }
                }
                return Sample.ok();
            }
            case AlarmRule.TYPE_THRESHOLD -> {
                PointValue pv = vals.get(rule.getPointKey());
                if (pv == null || !pv.isGood()) {
                    return null;
                }
                Double v = pv.asDouble();
                if (v == null || rule.getThreshold() == null) {
                    return null;
                }
                boolean bad = compare(v, rule.getOp(), rule.getThreshold());
                return bad
                        ? Sample.bad(v, pv.name() + " = " + fmtNum(v) + pv.unit())
                        : Sample.ok();
            }
            default -> {
                if (warned.add("type:" + rule.getId())) {
                    log.warn("告警规则「{}」的类型 {} 不认识，已跳过", rule.getName(), rule.getType());
                }
                return null;
            }
        }
    }

    private boolean compare(double v, String op, double t) {
        return switch (op == null ? ">" : op) {
            case ">" -> v > t;
            case ">=" -> v >= t;
            case "<" -> v < t;
            case "<=" -> v <= t;
            case "==" -> Math.abs(v - t) < 1e-9;
            case "!=" -> Math.abs(v - t) >= 1e-9;
            default -> false;
        };
    }

    private PointValue statusPoint(Map<String, PointValue> vals) {
        for (PointValue pv : vals.values()) {
            if ("status".equals(pv.role())) {
                return pv;
            }
        }
        return null;
    }

    /* ==================== 事件生命周期 ==================== */

    private AlarmEvent build(AlarmRule rule, DeviceEntity d, Sample s, long now) {
        String msg = message(rule, d, s);
        AlarmEvent e = new AlarmEvent(rule, d.getCode(), d.getName(), d.getLocation(),
                s.value() == null ? 0d : s.value(), msg);
        e.setStartedAt(now - rule.getForSeconds() * 1000L);   // 从条件开始成立那一刻算起
        return e;
    }

    private AlarmEvent clear(AlarmEvent e, String reason, Watch w, long now) {
        e.setState(AlarmEvent.STATE_CLEARED);
        e.setClearedAt(now);
        e.setClearReason(reason);
        open.remove(key(e.getRuleId(), e.getDeviceCode()));
        w.reset();
        w.lastFireAt = now;               // 冷却从恢复时刻开始算，防止刚修好又抖回来
        return e;
    }

    /** 到了重复提醒的时间点没有。纯判断，不改任何状态 */
    private boolean repeatReached(AlarmEvent e, long now) {
        int minutes = 0;
        try {
            minutes = notifyRepeatMinutes();
        } catch (Exception ignored) {
        }
        if (minutes <= 0) {
            return false;
        }
        long since = e.getLastNotifyAt() > 0 ? e.getLastNotifyAt() : e.getStartedAt();
        return now - since >= minutes * 60_000L;
    }

    /** 真的发出去了才记账。注意别在发送前调用，否则又变成「假装已通知」 */
    private void markNotified(AlarmEvent e, long now) {
        e.setLastNotifyAt(now);
        e.setNotifyCount(e.getNotifyCount() + 1);
        try {
            repo.save(e);
        } catch (Exception ex) {
            log.debug("更新通知计数失败: {}", ex.getMessage());
        }
    }

    private int notifyRepeatMinutes() {
        return notifier.repeatMinutes();
    }

    /** 模板里的占位符替换。客户可以自己写文案，比如带班组名字 */
    private String message(AlarmRule rule, DeviceEntity d, Sample s) {
        String dur = AlarmNotifier.fmtDuration(rule.getForSeconds() * 1000L);
        String tpl = rule.getMessage();
        if (tpl != null && !tpl.isBlank()) {
            return tpl.replace("{device}", d.getName())
                    .replace("{code}", d.getCode())
                    .replace("{value}", s.value() == null ? "--" : fmtNum(s.value()))
                    .replace("{threshold}", rule.getThreshold() == null ? "--" : fmtNum(rule.getThreshold()))
                    .replace("{duration}", dur)
                    .replace("{point}", rule.getPointKey());
        }
        return switch (rule.getType()) {
            case AlarmRule.TYPE_OFFLINE -> d.getName() + " 通讯中断，已超过 " + dur;
            case AlarmRule.TYPE_STOP -> d.getName() + " 处于停机状态，已超过 " + dur;
            case AlarmRule.TYPE_FLAG -> d.getName() + " 发出报警信号（" + s.detail() + "）";
            case AlarmRule.TYPE_THRESHOLD -> d.getName() + " " + s.detail()
                    + "，达到 " + rule.getOp() + " " + fmtNum(rule.getThreshold());
            default -> d.getName() + " 触发告警";
        };
    }

    /* ==================== 落库 + 通知 ==================== */

    private void persist(List<Pending> fires, List<ClearPending> clears) {
        if (!fires.isEmpty()) {
            List<AlarmEvent> rows = new ArrayList<>(fires.size());
            for (Pending p : fires) {
                rows.add(p.event());
            }
            try {
                List<AlarmEvent> saved = repo.saveAll(rows);
                for (int i = 0; i < fires.size() && i < saved.size(); i++) {
                    open.put(fires.get(i).key(), saved.get(i));
                }
            } catch (Exception e) {
                // 落库失败也要保留在内存里，下一轮继续跟踪，不能因为数据库抖一下就丢告警
                log.error("告警写库失败（{} 条），内存中继续跟踪: {}", rows.size(), e.getMessage());
            }
        }
        if (!clears.isEmpty()) {
            List<AlarmEvent> rows = new ArrayList<>(clears.size());
            for (ClearPending c : clears) {
                rows.add(c.event());
            }
            try {
                repo.saveAll(rows);
            } catch (Exception e) {
                log.error("告警恢复写库失败: {}", e.getMessage());
            }
        }
    }

    private void dispatch(List<Pending> fires, List<ClearPending> clears, long now) {
        if (!fires.isEmpty()) {
            if (fires.size() >= settings.getInt(SettingStore.ALARM_STORM)) {
                // 一次冒出这么多，八成是网络或网关问题，不是设备集体坏了。
                // 三百条通知只会让群里没人再看告警，一条汇总反而有用
                boolean allOffline = fires.stream()
                        .allMatch(p -> AlarmRule.TYPE_OFFLINE.equals(p.rule().getType()));
                String reason = allOffline
                        ? "多台设备同时离线，通常不是设备坏了，而是交换机、串口服务器、"
                        + "网关断电，或者这台工控机自己的网线松了"
                        : "多个告警同时触发，注意是不是同一片区域断电、断网";
                notifier.digest(fires.stream().map(Pending::event).toList(), reason);
                for (Pending p : fires) {
                    markNotified(p.event(), now);
                }
                log.warn("一轮触发 {} 条告警，已合并为一条批量通知", fires.size());
            } else {
                for (Pending p : fires) {
                    if (notifier.fire(p.event(), p.rule())) {
                        markNotified(p.event(), now);
                    }
                }
            }
            for (Pending p : fires) {
                push("alarm:new", p.event());
            }
            if (fires.size() > 20) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("type", "alarm:burst");
                m.put("count", fires.size());
                pushRaw(m);
            }
        }
        for (ClearPending c : clears) {
            if (c.shouldNotify()) {
                notifier.recover(c.event());
            }
            push("alarm:clear", c.event());
        }
    }

    private void push(String type, AlarmEvent e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("id", e.getId());
        m.put("deviceCode", e.getDeviceCode());
        m.put("deviceName", e.getDeviceName());
        m.put("level", e.getLevel());
        m.put("state", e.getState());
        m.put("ruleName", e.getRuleName());
        m.put("message", e.getMessage());
        m.put("startedAt", e.getStartedAt());
        pushRaw(m);
    }

    private void pushRaw(Map<String, Object> m) {
        try {
            ws.broadcast(mapper.writeValueAsString(m));
        } catch (Exception ex) {
            log.debug("告警推送失败: {}", ex.getMessage());
        }
    }

    /* ==================== 收尾 ==================== */

    /** 设备删了、规则删了，对应的未恢复告警要收掉 */
    private void cleanupOrphans(Map<String, DeviceEntity> devices, List<AlarmRule> rules,
                                List<ClearPending> clears) {
        if (open.isEmpty()) {
            return;
        }
        Set<String> ruleIds = new HashSet<>();
        for (AlarmRule r : rules) {
            ruleIds.add(r.getId());
        }
        long now = System.currentTimeMillis();
        for (Map.Entry<String, AlarmEvent> en : new ArrayList<>(open.entrySet())) {
            AlarmEvent e = en.getValue();
            boolean deviceGone = !devices.containsKey(e.getDeviceCode());
            boolean ruleGone = !ruleIds.contains(e.getRuleId());
            if (deviceGone || ruleGone) {
                clears.add(new ClearPending(en.getKey(),
                        clear(e, deviceGone ? "设备已删除" : "规则已删除",
                                watches.computeIfAbsent(en.getKey(), x -> new Watch()), now),
                        false));
            }
        }
    }

    /** 设备缓存。每秒查一次库没必要，30 秒刷一次足够 */
    private Map<String, DeviceEntity> devices(long now) {
        if (now - deviceCacheAt > 30_000L || deviceCache.isEmpty()) {
            try {
                List<DeviceEntity> list = deviceService.all();
                Map<String, DeviceEntity> m = new HashMap<>();
                for (DeviceEntity d : list) {
                    m.put(d.getCode(), d);
                }
                deviceCache = list;
                deviceCacheAt = now;
                return m;
            } catch (Exception e) {
                log.debug("刷新设备缓存失败: {}", e.getMessage());
            }
        }
        Map<String, DeviceEntity> m = new HashMap<>();
        for (DeviceEntity d : deviceCache) {
            m.put(d.getCode(), d);
        }
        return m;
    }

    /** 长期运行后 watches 会积累已删除设备的条目，定期扫掉 */
    private void pruneWatches(List<AlarmRule> rules, Map<String, DeviceEntity> devices) {
        if (watches.size() <= devices.size() * Math.max(1, rules.size()) + 128) {
            return;
        }
        int before = watches.size();
        watches.keySet().removeIf(k -> {
            int i = k.indexOf('|');
            return i <= 0 || !devices.containsKey(k.substring(i + 1));
        });
        if (before != watches.size()) {
            log.debug("清理失效的告警跟踪状态 {} 个", before - watches.size());
        }
    }

    /** 每天清一次。只删已恢复的，没恢复的告警永远保留——那是没处理的事故 */
    private void maybeCleanupHistory(long now) {
        long day = LocalDate.now().toEpochDay();
        if (lastCleanupDay == day) {
            return;
        }
        lastCleanupDay = day;
        try {
            int days = settings.getInt(SettingStore.ALARM_RETAIN_DAYS);
            int n = repo.deleteClearedOlderThan(now - days * 24L * 3_600_000L);
            if (n > 0) {
                log.info("清理 {} 天前的已恢复告警 {} 条", days, n);
            }
        } catch (Exception e) {
            log.warn("清理告警历史失败: {}", e.getMessage());
        }
    }

    /* ==================== 给外部用 ==================== */

    /** 手动确认。返回 null 表示成功，否则是错误原因 */
    public String ack(Long id, String user) {
        AlarmEvent e = repo.findById(id).orElse(null);
        if (e == null) {
            return "告警不存在";
        }
        if (!e.isOpen()) {
            return "这条告警已经恢复了，不用确认";
        }
        e.setState(AlarmEvent.STATE_ACKED);
        e.setAckAt(System.currentTimeMillis());
        e.setAckBy(user == null || user.isBlank() ? "值班人员" : user);
        repo.save(e);
        AlarmEvent mem = open.get(key(e.getRuleId(), e.getDeviceCode()));
        if (mem != null) {
            mem.setState(AlarmEvent.STATE_ACKED);
            mem.setAckAt(e.getAckAt());
            mem.setAckBy(e.getAckBy());
        }
        return null;
    }

    public int openCount() {
        return open.size();
    }

    /** 当前活跃告警的内存视图，页头铃铛用 */
    public List<AlarmEvent> openEvents() {
        return new ArrayList<>(open.values());
    }

    /** 停用某条规则时，把它挂着的告警一起收掉 */
    public void closeByRule(String ruleId, String reason) {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, AlarmEvent> en : new ArrayList<>(open.entrySet())) {
            AlarmEvent e = en.getValue();
            if (ruleId.equals(e.getRuleId())) {
                AlarmEvent c = clear(e, reason,
                        watches.computeIfAbsent(en.getKey(), x -> new Watch()), now);
                try {
                    repo.save(c);
                } catch (Exception ex) {
                    log.debug("关闭规则告警失败: {}", ex.getMessage());
                }
            }
        }
    }

    private static String key(String ruleId, String deviceCode) {
        return ruleId + "|" + deviceCode;
    }

    private static String fmtNum(double v) {
        if (Math.abs(v - Math.rint(v)) < 1e-9) {
            return String.valueOf((long) v);
        }
        return String.valueOf(Math.round(v * 100d) / 100d);
    }
}
