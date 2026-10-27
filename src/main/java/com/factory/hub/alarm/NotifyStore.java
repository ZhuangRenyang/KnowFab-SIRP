package com.factory.hub.alarm;

import com.factory.hub.config.AppSetting;
import com.factory.hub.config.AppSettingRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 通知配置的存取。存 app_setting 表，改完立即生效。
 */
@Component
public class NotifyStore {

    private static final Logger log = LoggerFactory.getLogger(NotifyStore.class);
    private static final String KEY = "alarm.notify";

    private final AppSettingRepository repo;
    private final ObjectMapper mapper;

    private volatile NotifyConfig cache;

    public NotifyStore(AppSettingRepository repo, ObjectMapper mapper) {
        this.repo = repo;
        this.mapper = mapper;
    }

    @PostConstruct
    public void init() {
        NotifyConfig c = current();
        if (c.isEnabled()) {
            long ok = c.getChannels().stream().filter(NotifyChannel::isEnabled).count();
            log.info("告警通知已启用，{} 个渠道", ok);
        } else {
            log.info("告警通知未启用（告警照常记录，只是不往外发）");
        }
    }

    public NotifyConfig current() {
        NotifyConfig c = cache;
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

    private NotifyConfig load() {
        try {
            AppSetting s = repo.findById(KEY).orElse(null);
            if (s != null && s.getValue() != null && !s.getValue().isBlank()) {
                NotifyConfig c = mapper.readValue(s.getValue(), NotifyConfig.class);
                if (c != null) {
                    return c;
                }
            }
        } catch (Exception e) {
            log.warn("通知配置解析失败，使用默认（不发送）：{}", e.getMessage());
        }
        return new NotifyConfig();
    }

    public void save(NotifyConfig c) {
        try {
            repo.save(new AppSetting(KEY, mapper.writeValueAsString(c)));
            cache = c;
            log.info("通知配置已更新，总开关={}，{} 个渠道", c.isEnabled(), c.getChannels().size());
        } catch (Exception e) {
            log.error("通知配置保存失败: {}", e.getMessage());
        }
    }
}
