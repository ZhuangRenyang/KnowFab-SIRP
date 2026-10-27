package com.factory.hub.demand;

import com.factory.hub.config.AppSetting;
import com.factory.hub.config.AppSettingRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 基本电费参数的存取。跟电价、班次一个套路：内存缓存一份，改完立即生效。
 */
@Component
public class DemandStore {

    private static final Logger log = LoggerFactory.getLogger(DemandStore.class);
    private static final String KEY = "energy.demand";

    private final AppSettingRepository repo;
    private final ObjectMapper mapper;

    private volatile DemandConfig cache;

    public DemandStore(AppSettingRepository repo, ObjectMapper mapper) {
        this.repo = repo;
        this.mapper = mapper;
    }

    @PostConstruct
    public void init() {
        DemandConfig c = current();
        log.info("基本电费：合同需量 {} kW，需量电价 {} 元/kW·月，计费方式 {}",
                c.getContractDemandKw(), c.getDemandPrice(), c.getBillingMode());
        for (String w : c.validate()) {
            log.warn("基本电费配置：{}", w);
        }
    }

    public DemandConfig current() {
        DemandConfig c = cache;
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

    private DemandConfig load() {
        try {
            AppSetting s = repo.findById(KEY).orElse(null);
            if (s != null && s.getValue() != null && !s.getValue().isBlank()) {
                DemandConfig c = mapper.readValue(s.getValue(), DemandConfig.class);
                if (c != null) {
                    return c;
                }
            }
        } catch (Exception e) {
            log.warn("基本电费配置解析失败，回退默认值：{}", e.getMessage());
        }
        DemandConfig def = new DemandConfig();
        save(def);
        return def;
    }

    public void save(DemandConfig cfg) {
        try {
            repo.save(new AppSetting(KEY, mapper.writeValueAsString(cfg)));
        } catch (Exception e) {
            log.error("基本电费配置保存失败: {}", e.getMessage());
        }
        cache = cfg;
        log.info("基本电费配置已更新：合同需量 {} kW，计费方式 {}", cfg.getContractDemandKw(), cfg.getBillingMode());
    }
}
