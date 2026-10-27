package com.factory.hub.system;

import com.factory.hub.config.AppSetting;
import com.factory.hub.config.AppSettingRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * 当前版本信息。
 * <p>
 * 版本号从 jar 里的 pom.properties 读，不写死在代码里——写死的话，
 * 打完包忘了改，客户看到「1.0.0」却已经是第三个补丁，售后对不上账。
 */
@Component
public class VersionService {

    private static final Logger log = LoggerFactory.getLogger(VersionService.class);
    private static final String KEY_APPLIED = "system.appliedVersion";
    private static final String KEY_SCHEMA = "system.schemaVersion";

    private final AppSettingRepository repo;

    private String version = "dev";
    private String buildTime = "--";

    public VersionService(AppSettingRepository repo) {
        this.repo = repo;
    }

    @PostConstruct
    public void load() {
        try (InputStream in = getClass().getResourceAsStream(
                "/META-INF/maven/com.factory/device-hub/pom.properties")) {
            if (in != null) {
                Properties p = new Properties();
                p.load(in);
                String v = p.getProperty("version");
                if (v != null && !v.isBlank()) {
                    version = v.trim();
                }
            }
        } catch (Exception e) {
            log.debug("读取版本号失败: {}", e.getMessage());
        }
        try {
            Path jar = Paths.get(VersionService.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            buildTime = fmt(Files.getLastModifiedTime(jar).toMillis());
        } catch (Exception ignored) {
        }
        log.info("系统版本 {}（构建于 {}）", version, buildTime);
    }

    public String version() {
        return version;
    }

    public String buildTime() {
        return buildTime;
    }

    /** 升级完成时写一笔，用来回答「这个版本是什么时候装上的」 */
    public void markApplied(String v) {
        try {
            repo.save(new AppSetting(KEY_APPLIED, v + "|" + System.currentTimeMillis()));
        } catch (Exception e) {
            log.debug("记录升级时间失败: {}", e.getMessage());
        }
    }

    public Map<String, Object> appliedInfo() {
        Map<String, Object> m = new LinkedHashMap<>();
        try {
            String raw = repo.findById(KEY_APPLIED).map(AppSetting::getValue).orElse("");
            if (raw.contains("|")) {
                String[] parts = raw.split("\\|", 2);
                m.put("version", parts[0]);
                m.put("at", fmt(Long.parseLong(parts[1])));
            }
        } catch (Exception ignored) {
        }
        return m;
    }

    public String schemaVersion() {
        return repo.findById(KEY_SCHEMA).map(AppSetting::getValue).orElse("0");
    }

    public void schemaVersion(String v) {
        try {
            repo.save(new AppSetting(KEY_SCHEMA, v));
        } catch (Exception e) {
            log.warn("记录数据库结构版本失败: {}", e.getMessage());
        }
    }

    /** 版本比较：a>b 返回正数，相等 0，a<b 负数 */
    public static int compare(String a, String b) {
        String[] x = safe(a).split("[.\\-]");
        String[] y = safe(b).split("[.\\-]");
        int n = Math.max(x.length, y.length);
        for (int i = 0; i < n; i++) {
            int xi = i < x.length ? parseInt(x[i]) : 0;
            int yi = i < y.length ? parseInt(y[i]) : 0;
            if (xi != yi) {
                return xi - yi;
            }
        }
        return 0;
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.replaceAll("[^0-9]", ""));
        } catch (Exception e) {
            return 0;
        }
    }

    private static String safe(String s) {
        return s == null ? "0" : s.trim();
    }

    private static String fmt(long ts) {
        return Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
}
