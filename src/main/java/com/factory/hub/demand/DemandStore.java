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

    /**
     * 返回一份<b>副本</b>，专给「会改数字」的调用方用（如试算）。
     * <p>
     * ⚠️ {@link #current()} 返回的是缓存里的**同一个对象**。谁要是拿它改了字段，
     * 等于把全应用看到的配置改了，而且没落库——重启后变回来，运行期间一直用错数。
     * 这种 bug 不会报错、不会白屏，只是所有电费数字悄悄变了。
     * <p>
     * 所以：<b>只读用它就返回引用，要改就必须先 snapshot()</b>。
     */
    public DemandConfig snapshot() {
        DemandConfig c = current();
        try {
            return mapper.readValue(mapper.writeValueAsString(c), DemandConfig.class);
        } catch (Exception e) {
            // 序列化失败也要给出独立对象——宁可手工抄一遍，也不能返回共享引用
            log.warn("基本电费配置复制失败，改用手工复制：{}", e.getMessage());
            DemandConfig n = new DemandConfig();
            n.setContractDemandKw(c.getContractDemandKw());
            n.setDemandPrice(c.getDemandPrice());
            n.setTransformerKva(c.getTransformerKva());
            n.setCapacityPrice(c.getCapacityPrice());
            n.setBillingMode(c.getBillingMode());
            n.setWarnRatio(c.getWarnRatio());
            n.setEnabled(c.isEnabled());
            return n;
        }
    }

    /**
     * 当前配置（<b>共享引用，只读</b>）。
     *
     * @see #snapshot() 要改字段时必须用这个
     */
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
