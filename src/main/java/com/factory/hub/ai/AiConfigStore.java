package com.factory.hub.ai;

import com.factory.hub.config.AppSetting;
import com.factory.hub.config.AppSettingRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** AI 配置存取。跟电价/班次一个套路：内存缓存，改完立即生效 */
@Component
public class AiConfigStore {

    private static final Logger log = LoggerFactory.getLogger(AiConfigStore.class);
    private static final String KEY = "ai.config";

    private final AppSettingRepository repo;
    private final ObjectMapper mapper;

    private volatile AiConfig cache;

    public AiConfigStore(AppSettingRepository repo, ObjectMapper mapper) {
        this.repo = repo;
        this.mapper = mapper;
    }

    @PostConstruct
    public void init() {
        AiConfig c = current();
        log.info("AI 早报：{}，每天 {:02d}:{:02d}，模型 {}",
                c.isEnabled() ? "已启用" : "未启用（用本地模板）", c.getHour(), c.getMinute(), c.getModel());
    }

    public AiConfig current() {
        AiConfig c = cache;
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

    private AiConfig load() {
        try {
            AppSetting s = repo.findById(KEY).orElse(null);
            if (s != null && s.getValue() != null && !s.getValue().isBlank()) {
                AiConfig c = mapper.readValue(s.getValue(), AiConfig.class);
                if (c != null) {
                    return c;
                }
            }
        } catch (Exception e) {
            log.warn("AI 配置解析失败，用默认值：{}", e.getMessage());
        }
        return new AiConfig();
    }

    public void save(AiConfig cfg) {
        try {
            repo.save(new AppSetting(KEY, mapper.writeValueAsString(cfg)));
        } catch (Exception e) {
            log.error("AI 配置保存失败: {}", e.getMessage());
        }
        cache = cfg;
        log.info("AI 配置已更新：{}，每天 {:02d}:{:02d}", cfg.isEnabled() ? "启用" : "关闭",
                cfg.getHour(), cfg.getMinute());
    }
}
