package com.factory.hub.alarm;

import com.factory.hub.service.DeviceService;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 告警查询与统计。
 * <p>
 * 统计一律交给数据库聚合，不把整天的告警拉进内存再算——
 * 几百台设备的厂一天几千条告警，全量加载会把内存和响应时间都拖垮。
 */
@Service
public class AlarmService {

    private final AlarmEventRepository repo;
    private final AlarmEngine engine;
    private final AlarmRuleStore ruleStore;
    private final NotifyStore notifyStore;
    private final DeviceService deviceService;

    public AlarmService(AlarmEventRepository repo, AlarmEngine engine, AlarmRuleStore ruleStore,
                        NotifyStore notifyStore, DeviceService deviceService) {
        this.repo = repo;
        this.engine = engine;
        this.ruleStore = ruleStore;
        this.notifyStore = notifyStore;
        this.deviceService = deviceService;
    }

    /** 列表。state 支持 ALL / OPEN / ACTIVE / ACKED / CLEARED */
    public Map<String, Object> list(String state, String level, String q, String range,
                                    int page, int size) {
        Specification<AlarmEvent> spec = (root, cq, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (state != null && !state.isBlank() && !"ALL".equalsIgnoreCase(state)) {
                if ("OPEN".equalsIgnoreCase(state)) {
                    ps.add(root.get("state").in(AlarmEvent.STATE_ACTIVE, AlarmEvent.STATE_ACKED));
                } else {
                    ps.add(cb.equal(root.get("state"), state.toUpperCase()));
                }
            }
            if (level != null && !level.isBlank() && !"ALL".equalsIgnoreCase(level)) {
                ps.add(cb.equal(root.get("level"), level.toUpperCase()));
            }
            if (q != null && !q.isBlank()) {
                String like = "%" + q.trim().toLowerCase() + "%";
                ps.add(cb.or(
                        cb.like(cb.lower(root.get("deviceCode")), like),
                        cb.like(cb.lower(root.get("deviceName")), like),
                        cb.like(cb.lower(root.get("message")), like),
                        cb.like(cb.lower(root.get("ruleName")), like)));
            }
            long from = rangeStart(range);
            if (from > 0) {
                ps.add(cb.greaterThanOrEqualTo(root.get("startedAt"), from));
            }
            return ps.isEmpty() ? cb.conjunction() : cb.and(ps.toArray(new Predicate[0]));
        };

        PageRequest pr = PageRequest.of(Math.max(0, page - 1), clampSize(size),
                Sort.by(Sort.Direction.DESC, "startedAt"));
        Page<AlarmEvent> p = repo.findAll(spec, pr);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", p.getTotalElements());
        out.put("page", page);
        out.put("size", pr.getPageSize());
        out.put("pages", p.getTotalPages());
        out.put("items", p.getContent());
        return out;
    }

    /** 页头数字 + 趋势 + 排行 */
    public Map<String, Object> summary() {
        long dayStart = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        long weekAgo = dayStart - 6L * 86_400_000L;

        Map<String, Object> out = new LinkedHashMap<>();
        long active = repo.countByState(AlarmEvent.STATE_ACTIVE);
        long acked = repo.countByState(AlarmEvent.STATE_ACKED);
        out.put("unhandled", active);              // 还没人确认的
        out.put("acked", acked);                   // 确认了但设备还没好
        out.put("open", active + acked);
        out.put("criticalOpen", openByLevel("CRITICAL"));
        out.put("majorOpen", openByLevel("MAJOR"));
        out.put("minorOpen", openByLevel("MINOR"));
        out.put("todayNew", repo.countByStartedAtGreaterThanEqual(dayStart));
        out.put("todayRecovered", repo.countByClearedAtGreaterThanEqual(dayStart));

        // 近 7 天趋势。只取时间戳回来按天分组，数据量小；
        // 真到几十万条级别换成 SQL 分组或按天预聚合表
        Map<String, Integer> byDay = new HashMap<>();
        List<Long> times = repo.startedTimesSince(weekAgo);
        ZoneId zone = ZoneId.systemDefault();
        for (long ts : times) {
            String d = LocalDate.ofInstant(Instant.ofEpochMilli(ts), zone).toString();
            byDay.merge(d, 1, Integer::sum);
        }
        List<Map<String, Object>> trend = new ArrayList<>();
        for (int i = 6; i >= 0; i--) {
            String d = LocalDate.now().minusDays(i).toString();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", d);
            row.put("count", byDay.getOrDefault(d, 0));
            trend.add(row);
        }
        out.put("trend", trend);

        // 哪台设备最吵。吵不代表设备坏，也可能是规则配得太敏感
        Map<String, String> names = new HashMap<>();
        try {
            deviceService.all().forEach(d -> names.put(d.getCode(), d.getName()));
        } catch (Exception ignored) {
        }
        List<Map<String, Object>> byDevice = new ArrayList<>();
        for (Object[] r : repo.countByDeviceSince(dayStart)) {
            Map<String, Object> row = new LinkedHashMap<>();
            String code = String.valueOf(r[0]);
            row.put("code", code);
            row.put("name", names.getOrDefault(code, code));
            row.put("count", ((Number) r[1]).longValue());
            byDevice.add(row);
        }
        out.put("topDevices", byDevice);

        List<Map<String, Object>> byRule = new ArrayList<>();
        for (Object[] r : repo.countByRuleSince(weekAgo)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("ruleId", String.valueOf(r[0]));
            row.put("ruleName", String.valueOf(r[1]));
            row.put("count", ((Number) r[2]).longValue());
            byRule.add(row);
        }
        out.put("topRules", byRule);

        // 未配通知时要在页面上提醒：很多人以为装上就会发微信，其实要自己配地址
        NotifyConfig cfg = notifyStore.current();
        out.put("notifyEnabled", cfg.isEnabled());
        out.put("notifyChannels", cfg.getChannels().size());
        out.put("ruleCount", ruleStore.current().size());
        out.put("ruleEnabled", ruleStore.current().stream().filter(AlarmRule::isEnabled).count());

        return out;
    }

    private long openByLevel(String level) {
        return repo.countByStateAndLevel(AlarmEvent.STATE_ACTIVE, level)
                + repo.countByStateAndLevel(AlarmEvent.STATE_ACKED, level);
    }

    public String ack(Long id, String by) {
        return engine.ack(id, by);
    }

    /** 批量确认。只确认还没恢复的，已恢复的跳过 */
    public int ackAll(String by) {
        int n = 0;
        for (AlarmEvent e : engine.openEvents()) {
            if (AlarmEvent.STATE_ACTIVE.equals(e.getState())) {
                if (engine.ack(e.getId(), by) == null) {
                    n++;
                }
            }
        }
        return n;
    }

    private static int clampSize(int size) {
        if (size <= 0) {
            return 20;
        }
        return Math.min(size, 200);
    }

    /** range = today / 7d / 30d / all */
    private static long rangeStart(String range) {
        if (range == null || range.isBlank() || "all".equalsIgnoreCase(range)) {
            return 0L;
        }
        long dayStart = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        return switch (range.toLowerCase()) {
            case "today" -> dayStart;
            case "7d" -> dayStart - 6L * 86_400_000L;
            case "30d" -> dayStart - 29L * 86_400_000L;
            default -> 0L;
        };
    }
}
