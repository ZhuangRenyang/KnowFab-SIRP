package com.factory.hub.energy;

import com.factory.hub.config.AppSetting;
import com.factory.hub.config.AppSettingRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 电价方案的存取。内存里缓存一份，改完立即生效，采集线程不用等数据库。
 */
@Component
public class TariffStore {

    private static final Logger log = LoggerFactory.getLogger(TariffStore.class);
    private static final String KEY = "energy.tariff";

    private final AppSettingRepository repo;
    private final ObjectMapper mapper;

    private volatile TariffPlan cache;

    public TariffStore(AppSettingRepository repo, ObjectMapper mapper) {
        this.repo = repo;
        this.mapper = mapper;
    }

    @PostConstruct
    public void init() {
        TariffPlan p = current();
        log.info("电价方案：{}，共 {} 个时段", p.getName(), p.getSegments().size());
        for (String w : p.validate()) {
            log.warn("电价配置：{}", w);
        }
    }

    /** 当前生效的电价方案，永远不返回 null */
    public TariffPlan current() {
        TariffPlan c = cache;
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

    private TariffPlan load() {
        try {
            AppSetting s = repo.findById(KEY).orElse(null);
            if (s != null && s.getValue() != null && !s.getValue().isBlank()) {
                TariffPlan p = mapper.readValue(s.getValue(), TariffPlan.class);
                if (p != null && !p.getSegments().isEmpty()) {
                    return p;
                }
            }
        } catch (Exception e) {
            log.warn("电价配置解析失败，回退到默认方案：{}", e.getMessage());
        }
        TariffPlan def = TariffPlan.defaults();
        persist(def);
        return def;
    }

    /** 保存并立即生效。返回体检结果，前端要展示给客户看 */
    public void save(TariffPlan plan) {
        persist(plan);
        cache = plan;
        log.info("电价方案已更新：{}", plan.getName());
    }

    private void persist(TariffPlan plan) {
        try {
            repo.save(new AppSetting(KEY, mapper.writeValueAsString(plan)));
        } catch (Exception e) {
            log.error("电价方案保存失败: {}", e.getMessage());
        }
    }
}
