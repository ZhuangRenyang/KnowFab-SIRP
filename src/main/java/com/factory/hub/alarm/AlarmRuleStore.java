package com.factory.hub.alarm;

import com.factory.hub.config.AppSetting;
import com.factory.hub.config.AppSettingRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 告警规则的存取。和电价一样走 app_setting 表，改完立即生效不用重启。
 * <p>
 * 规则数量是个位数到几十条，全量读进内存缓存，评估线程每秒遍历也毫无压力。
 */
@Component
public class AlarmRuleStore {

    private static final Logger log = LoggerFactory.getLogger(AlarmRuleStore.class);
    private static final String KEY = "alarm.rules";

    private final AppSettingRepository repo;
    private final ObjectMapper mapper;

    private volatile List<AlarmRule> cache;

    public AlarmRuleStore(AppSettingRepository repo, ObjectMapper mapper) {
        this.repo = repo;
        this.mapper = mapper;
    }

    @PostConstruct
    public void init() {
        List<AlarmRule> list = current();
        long on = list.stream().filter(AlarmRule::isEnabled).count();
        log.info("告警规则：共 {} 条，启用 {} 条", list.size(), on);
        for (AlarmRule r : list) {
            String bad = r.validate();
            if (bad != null) {
                log.warn("告警规则「{}」配置有问题：{}", r.getName(), bad);
            }
        }
    }

    /** 当前规则表，永远不返回 null */
    public List<AlarmRule> current() {
        List<AlarmRule> c = cache;
        if (c != null) {
            return c;
        }
        synchronized (this) {
            if (cache == null) {
                cache = load();
            }
            return cache;
        }
    }

    private List<AlarmRule> load() {
        try {
            AppSetting s = repo.findById(KEY).orElse(null);
            if (s != null && s.getValue() != null && !s.getValue().isBlank()) {
                AlarmRule[] arr = mapper.readValue(s.getValue(), AlarmRule[].class);
                if (arr != null) {
                    return new ArrayList<>(List.of(arr));
                }
            }
        } catch (Exception e) {
            log.warn("告警规则解析失败，回退到默认规则：{}", e.getMessage());
        }
        List<AlarmRule> def = defaults();
        persist(def);
        return def;
    }

    /** 保存整张规则表 */
    public void saveAll(List<AlarmRule> rules) {
        List<AlarmRule> copy = new ArrayList<>();
        for (AlarmRule r : rules) {
            if (r.getId() == null || r.getId().isBlank()) {
                r.setId("r-" + UUID.randomUUID().toString().substring(0, 8));
            }
            copy.add(r);
        }
        persist(copy);
        cache = copy;
        log.info("告警规则已更新，共 {} 条", copy.size());
    }

    /** 新增或更新一条（按 id 匹配） */
    public AlarmRule save(AlarmRule rule) {
        if (rule.getId() == null || rule.getId().isBlank()) {
            rule.setId("r-" + UUID.randomUUID().toString().substring(0, 8));
        }
        List<AlarmRule> list = new ArrayList<>(current());
        boolean replaced = false;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).getId().equals(rule.getId())) {
                list.set(i, rule);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            list.add(rule);
        }
        saveAll(list);
        return rule;
    }

    public boolean delete(String id) {
        List<AlarmRule> list = new ArrayList<>(current());
        boolean removed = list.removeIf(r -> r.getId().equals(id));
        if (removed) {
            saveAll(list);
        }
        return removed;
    }

    public AlarmRule byId(String id) {
        for (AlarmRule r : current()) {
            if (r.getId().equals(id)) {
                return r;
            }
        }
        return null;
    }

    private void persist(List<AlarmRule> rules) {
        try {
            repo.save(new AppSetting(KEY, mapper.writeValueAsString(rules)));
        } catch (Exception e) {
            log.error("告警规则保存失败: {}", e.getMessage());
        }
    }

    /**
     * 默认规则。故意只开两条最不容易误报的：
     * <ul>
     *   <li>离线 2 分钟——通讯断了是硬故障，不会误报</li>
     *   <li>报警信号——设备自己报的警，直接透传</li>
     * </ul>
     * 停机规则默认关着，因为「停多久算异常」只有客户自己知道。
     * 默认帮客户开一个错的阈值，比不帮他开更糟。
     */
    public static List<AlarmRule> defaults() {
        List<AlarmRule> list = new ArrayList<>();

        AlarmRule offline = new AlarmRule();
        offline.setId("r-offline");
        offline.setName("设备离线");
        offline.setType(AlarmRule.TYPE_OFFLINE);
        offline.setForSeconds(120);
        offline.setRecoverSeconds(10);
        offline.setCooldownSeconds(300);
        offline.setLevel("CRITICAL");
        offline.setEnabled(true);
        list.add(offline);

        AlarmRule flag = new AlarmRule();
        flag.setId("r-flag");
        flag.setName("设备报警信号");
        flag.setType(AlarmRule.TYPE_FLAG);
        flag.setForSeconds(10);
        flag.setRecoverSeconds(10);
        flag.setCooldownSeconds(300);
        flag.setLevel("CRITICAL");
        flag.setEnabled(true);
        list.add(flag);

        AlarmRule stop = new AlarmRule();
        stop.setId("r-stop");
        stop.setName("工作时间停机超 10 分钟");
        stop.setType(AlarmRule.TYPE_STOP);
        stop.setThreshold(0d);
        stop.setForSeconds(600);
        stop.setRecoverSeconds(60);
        stop.setCooldownSeconds(1800);
        stop.setLevel("MAJOR");
        stop.setSchedule("08:00-20:00");
        stop.setDays("1,2,3,4,5,6");
        stop.setEnabled(false);
        stop.setMessage("{device} 已停机 {duration}，当前状态值 {value}");
        list.add(stop);

        AlarmRule temp = new AlarmRule();
        temp.setId("r-temp");
        temp.setName("主轴温度过高");
        temp.setType(AlarmRule.TYPE_THRESHOLD);
        // 点位名必须和设备点表里的 key 一字不差。示例设备的温度点叫 temperature，
        // 这里曾经写成 temp，结果规则看着是好的、实际永远不触发（rules/check 会抓出来）
        temp.setPointKey("temperature");
        temp.setOp(">");
        temp.setThreshold(75d);
        temp.setForSeconds(60);
        temp.setRecoverSeconds(60);
        temp.setCooldownSeconds(900);
        temp.setLevel("MINOR");
        temp.setEnabled(false);
        list.add(temp);

        return list;
    }
}
