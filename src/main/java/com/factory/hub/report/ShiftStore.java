package com.factory.hub.report;

import com.factory.hub.config.AppSetting;
import com.factory.hub.config.AppSettingRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 班次方案的存取。跟电价方案一个套路：内存缓存一份，改完立即生效。
 */
@Component
public class ShiftStore {

    private static final Logger log = LoggerFactory.getLogger(ShiftStore.class);
    private static final String KEY = "report.shifts";

    private final AppSettingRepository repo;
    private final ObjectMapper mapper;

    private volatile ShiftPlan cache;

    public ShiftStore(AppSettingRepository repo, ObjectMapper mapper) {
        this.repo = repo;
        this.mapper = mapper;
    }

    @PostConstruct
    public void init() {
        ShiftPlan p = current();
        log.info("班次方案：{}，共 {} 个班次", p.getName(), p.getShifts().size());
        for (String w : p.validate()) {
            log.warn("班次配置：{}", w);
        }
    }

    public ShiftPlan current() {
        ShiftPlan c = cache;
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

    private ShiftPlan load() {
        try {
            AppSetting s = repo.findById(KEY).orElse(null);
            if (s != null && s.getValue() != null && !s.getValue().isBlank()) {
                ShiftPlan p = mapper.readValue(s.getValue(), ShiftPlan.class);
                if (p != null && p.getShifts() != null && !p.getShifts().isEmpty()) {
                    return p;
                }
            }
        } catch (Exception e) {
            log.warn("班次配置解析失败，回退到默认三班倒：{}", e.getMessage());
        }
        ShiftPlan def = ShiftPlan.defaults();
        save(def);
        return def;
    }

    public void save(ShiftPlan plan) {
        try {
            repo.save(new AppSetting(KEY, mapper.writeValueAsString(plan)));
        } catch (Exception e) {
            log.error("班次方案保存失败: {}", e.getMessage());
        }
        cache = plan;
        log.info("班次方案已更新：{}", plan.getName());
    }
}
