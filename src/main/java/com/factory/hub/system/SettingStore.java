package com.factory.hub.system;

import com.factory.hub.config.AppSetting;
import com.factory.hub.config.AppSettingRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运行期可改的配置：数据库里存覆盖值，没存就用 application.yml 的值。
 * <p>
 * 三级取值顺序：库里的覆盖值 → yml 的配置值 → 代码里的兜底值。
 * 这样现场有两种改法都成立：页面点（立即生效，不用停机），或者改 yml（改完重启）。
 * 交付时客户如果把 yml 改乱了，页面上"恢复默认"能一键退回去。
 * <p>
 * 为什么覆盖值要单独存一张表的键前缀 {@code sys.}：{@code app_setting} 还存着电价、
 * 班次这些业务方案，前缀让"系统配置"和"业务方案"分得开，清库时不会误删。
 */
@Component
public class SettingStore {

    private static final Logger log = LoggerFactory.getLogger(SettingStore.class);

    /** 存库时的键前缀 */
    private static final String PREFIX = "sys.";

    public static final String UPDATE_SOURCES = "update.sources";
    public static final String UPDATE_ALLOW_WEB_RESTART = "update.allow-web-restart";
    public static final String DEMO_ENERGY = "energy.demo-seed";
    public static final String DEMO_REPORT = "report.demo-seed";
    public static final String DEMO_DEMAND = "demand.demo-seed";
    public static final String ALARM_STORM = "alarm.storm-threshold";
    public static final String ALARM_RETAIN_DAYS = "alarm.retain-days";

    /** 分组顺序就是页面上的显示顺序 */
    private static final String G_UPDATE = "更新与升级";
    private static final String G_ALARM = "告警";
    private static final String G_DEMO = "演示数据";

    private static final List<SettingDef> DEFS = List.of(
            SettingDef.area(UPDATE_SOURCES, G_UPDATE, "更新源",
                    "system.update.sources", "",
                    "留空 = 默认盯 GitHub Releases（github://ZhuangRenyang/KnowFab-SIRP），有新版本系统页直接提示升级。"
                            + "国内网络不稳可填镜像：version.json 所在目录或完整地址，多个用逗号或换行分隔，按顺序试。"),
            SettingDef.bool(UPDATE_ALLOW_WEB_RESTART, G_UPDATE, "允许网页上一键重启",
                    "system.update.allow-web-restart", "false", SettingDef.LIVE,
                    "打开后，「应用升级」按钮能直接重启服务，客户不用登服务器敲命令。"
                            + "前提是安装时用过 install.sh --allow-web-restart 给服务账号开过 sudo 规则，"
                            + "否则按钮会失败并提示原因。开不了就别硬开，让管理员执行 sudo systemctl restart device-hub 更稳妥。"),

            SettingDef.number(ALARM_STORM, G_ALARM, "批量告警合并阈值",
                    "alarm.storm-threshold", "8", 1, 1000,
                    "同一轮触发超过这个条数，合并成一条通知发出去。"
                            + "交换机一挂全厂离线，逐条发会把群刷爆，也没人看得出问题在哪。"),
            SettingDef.number(ALARM_RETAIN_DAYS, G_ALARM, "告警历史保留天数",
                    "alarm.retain-days", "180", 1, 3650,
                    "只清理已恢复的告警。没恢复的永远留着——那是没处理的事故，不能自动消失。"),

            SettingDef.bool(DEMO_ENERGY, G_DEMO, "能耗演示数据",
                    "energy.demo-seed", "true", SettingDef.RESTART,
                    "开关只影响「启动时要不要生成」。已经生成的假数据必须在下面点清除，否则客户会看到假电费。"),
            SettingDef.bool(DEMO_REPORT, G_DEMO, "报表演示数据",
                    "report.demo-seed", "true", SettingDef.RESTART,
                    "同上。关掉开关后记得清掉已有数据，否则报表里的产量是编的。"),
            SettingDef.bool(DEMO_DEMAND, G_DEMO, "需量演示数据",
                    "demand.demo-seed", "true", SettingDef.RESTART,
                    "同上。假需量会让基本电费算错，交付前必须清。")
    );

    private final AppSettingRepository repo;
    private final Environment env;

    private final Map<String, SettingDef> byKey = new LinkedHashMap<>();

    /** 库里的覆盖值，启动时一次读入，改完重建 */
    private volatile Map<String, String> overrides = Map.of();

    /** 启动那一刻实际生效的值。RESTART 类的项靠它判断"改了但还没生效" */
    private Map<String, String> bootValues = Map.of();

    public SettingStore(AppSettingRepository repo, Environment env) {
        this.repo = repo;
        this.env = env;
        for (SettingDef d : DEFS) {
            byKey.put(d.key(), d);
        }
    }

    @PostConstruct
    public void init() {
        reload();
        Map<String, String> boot = new LinkedHashMap<>();
        for (SettingDef d : DEFS) {
            boot.put(d.key(), get(d.key()));
        }
        this.bootValues = Map.copyOf(boot);
        log.info("运行期配置已加载：{} 项，其中 {} 项是页面覆盖值",
                DEFS.size(),
                overrides.keySet().stream().filter(k -> byKey.containsKey(k)).count());
    }

    /* ==================== 读 ==================== */

    public String get(String key) {
        String v = overrides.get(key);
        if (v != null) {
            return v;
        }
        SettingDef d = byKey.get(key);
        if (d == null) {
            return "";
        }
        // yml 里有就用 yml 的，没有才用代码兜底
        return env.getProperty(d.ymlKey(), d.fallback());
    }

    public boolean getBool(String key) {
        return parseBool(get(key));
    }

    public int getInt(String key) {
        try {
            return Integer.parseInt(get(key).trim());
        } catch (Exception e) {
            SettingDef d = byKey.get(key);
            return d == null ? 0 : parseInt(d.fallback(), 0);
        }
    }

    /* ==================== 页面视图 ==================== */

    /**
     * 给页面用的完整快照：分组配置项 + 只读项 + 待重启标记。
     */
    public Map<String, Object> view() {
        List<Map<String, Object>> groups = new ArrayList<>();
        Map<String, Map<String, Object>> byGroup = new LinkedHashMap<>();
        List<String> pending = new ArrayList<>();

        for (SettingDef d : DEFS) {
            Map<String, Object> g = byGroup.get(d.group());
            if (g == null) {
                g = new LinkedHashMap<>();
                g.put("name", d.group());
                g.put("items", new ArrayList<Map<String, Object>>());
                byGroup.put(d.group(), g);
                groups.add(g);
            }

            String value = get(d.key());
            boolean changed = d.isRestartScoped() && !value.equals(bootValues.get(d.key()));
            if (changed) {
                pending.add(d.key());
            }

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("key", d.key());
            item.put("label", d.label());
            item.put("type", d.type());
            item.put("value", value);
            item.put("defaultValue", env.getProperty(d.ymlKey(), d.fallback()));
            item.put("overridden", overrides.containsKey(d.key()));
            item.put("scope", d.scope());
            item.put("desc", d.desc());
            item.put("min", d.min());
            item.put("max", d.max());
            item.put("pendingRestart", changed);
            item.put("fromYml", env.getProperty(d.ymlKey()) != null);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) g.get("items");
            items.add(item);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("groups", groups);
        out.put("pendingRestart", pending);
        out.put("readonly", readonlyItems());
        return out;
    }

    /**
     * 只能改文件、不能在页面上改的项。
     * <p>
     * 不是偷懒不给做，是这几项误改的代价太大：数据库地址错了服务直接起不来，
     * 授权开关关掉等于软件白送。把原因写在页面上，比让人翻 README 强。
     */
    private List<Map<String, Object>> readonlyItems() {
        List<Map<String, Object>> out = new ArrayList<>();
        out.add(readonly("服务端口", env.getProperty("server.port", "8080"),
                "改端口要同步改 systemd 的启动参数和防火墙规则，重装时用 PORT=9000 sudo ./install.sh"));
        out.add(readonly("数据库", dbLabel(),
                "库地址和密码在 $DIR/application-db.yml（或 application.yml）。换库要停服务、导数据，"
                        + "不能在线切——半路切过去表结构对不上，数据就废了。"));
        out.add(readonly("升级暂存目录", env.getProperty("system.update.dir", "./updates"),
                "和 systemd 的启动前脚本约定死了（bin/apply-pending.sh 读这个目录），改了升级会失效。"));
        out.add(readonly("采集数据目录", "./data",
                "整个 data 目录就是客户的全部业务数据。要备份直接拷，要迁移就整目录搬。"));
        return out;
    }

    private Map<String, Object> readonly(String label, String value, String why) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("label", label);
        m.put("value", value);
        m.put("why", why);
        return m;
    }

    private String dbLabel() {
        String url = env.getProperty("spring.datasource.url", "");
        // H2 已废弃（2026-10-27，驱动已从包里移除），这里不再有 jdbc:h2 分支
        if (url.startsWith("jdbc:mysql")) {
            return "MySQL（" + mask(url) + "）";
        }
        if (url.startsWith("jdbc:postgresql")) {
            return "PostgreSQL（" + mask(url) + "）";
        }
        return mask(url);
    }

    /** 连接串里可能带密码，页面上不能明文露出来 */
    private String mask(String url) {
        if (url == null) {
            return "";
        }
        String s = url.replaceAll("(?i)(password|pwd)=[^;&]*", "$1=***");
        int q = s.indexOf('?');
        return q > 0 ? s.substring(0, q) + "?…" : s;
    }

    /* ==================== 写 ==================== */

    /**
     * 保存一批配置。
     * <p>
     * 规则：值与默认值相同就把覆盖行删掉，让 {@code app_setting} 里只留真正改过的项——
     * 否则客户来回点几次会积一堆等于默认值的垃圾键，将来排查"到底哪项被动过"很费劲。
     *
     * @return ok / message / errors / pendingRestart / warnings
     */
    public synchronized Map<String, Object> save(Map<String, String> input) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> changed = new ArrayList<>();

        Map<String, String> normalized = new LinkedHashMap<>();
        if (input != null) {
            for (Map.Entry<String, String> e : input.entrySet()) {
                SettingDef d = byKey.get(e.getKey());
                if (d == null) {
                    errors.add("未知配置项：" + e.getKey());
                    continue;
                }
                String raw = e.getValue() == null ? "" : e.getValue();
                String val = validate(d, raw, errors, warnings);
                if (val != null) {
                    normalized.put(d.key(), val);
                }
            }
        }
        if (!errors.isEmpty()) {
            out.put("ok", false);
            out.put("message", "有配置项不合法，一项都没保存");
            out.put("errors", errors);
            return out;
        }

        for (Map.Entry<String, String> e : normalized.entrySet()) {
            String key = PREFIX + e.getKey();
            writeOverride(e.getKey(), e.getValue(), key);
        }
        reload();

        for (SettingDef d : DEFS) {
            String now = get(d.key());
            String before = bootValues.get(d.key());
            if (!now.equals(before)) {
                changed.add(d.label());
            }
        }

        List<String> pending = new ArrayList<>();
        for (SettingDef d : DEFS) {
            if (d.isRestartScoped() && !get(d.key()).equals(bootValues.get(d.key()))) {
                pending.add(d.label());
            }
        }

        String who = currentUser();
        log.info("系统配置被修改 by {}：{}", who, normalized);

        out.put("ok", true);
        out.put("pendingRestart", pending);
        out.put("changed", changed);
        out.put("warnings", warnings);
        out.put("message", pending.isEmpty()
                ? "已保存并生效"
                : "已保存。其中「" + String.join("、", pending) + "」要重启服务后生效——"
                  + "这几项只在启动时读一次，热改会让已经跑起来的采集和已有数据对不上。");
        return out;
    }

    /** 恢复单项默认值 */
    public synchronized Map<String, Object> reset(String key) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!byKey.containsKey(key)) {
            out.put("ok", false);
            out.put("message", "未知配置项：" + key);
            return out;
        }
        repo.deleteById(PREFIX + key);
        reload();
        log.info("系统配置恢复默认 by {}：{}", currentUser(), key);
        out.put("ok", true);
        out.put("message", "已恢复为配置文件里的值");
        return out;
    }

    private void writeOverride(String key, String value, String dbKey) {
        String def = defaultOf(key);
        if (value.equals(def)) {
            repo.deleteById(dbKey);
        } else {
            AppSetting s = repo.findById(dbKey).orElse(null);
            if (s == null) {
                repo.save(new AppSetting(dbKey, value));
            } else if (!value.equals(s.getValue())) {
                s.setValue(value);
                repo.save(s);
            }
        }
    }

    private String defaultOf(String key) {
        SettingDef d = byKey.get(key);
        return d == null ? "" : env.getProperty(d.ymlKey(), d.fallback());
    }

    private void reload() {
        Map<String, String> m = new LinkedHashMap<>();
        for (AppSetting s : repo.findAll()) {
            if (s.getKey() != null && s.getKey().startsWith(PREFIX)) {
                m.put(s.getKey().substring(PREFIX.length()), s.getValue() == null ? "" : s.getValue());
            }
        }
        this.overrides = Map.copyOf(m);
    }

    /* ==================== 校验 ==================== */

    private String validate(SettingDef d, String raw, List<String> errors, List<String> warnings) {
        String v = raw.trim();
        switch (d.type()) {
            case SettingDef.BOOL -> {
                if (!"true".equalsIgnoreCase(v) && !"false".equalsIgnoreCase(v)) {
                    errors.add("「" + d.label() + "」只能是 true 或 false");
                    return null;
                }
                return v.toLowerCase();
            }
            case SettingDef.INT -> {
                int n;
                try {
                    n = Integer.parseInt(v);
                } catch (Exception e) {
                    errors.add("「" + d.label() + "」必须是整数");
                    return null;
                }
                if (n < d.min() || n > d.max()) {
                    errors.add("「" + d.label() + "」要在 " + d.min() + " ~ " + d.max() + " 之间");
                    return null;
                }
                return String.valueOf(n);
            }
            case SettingDef.AREA -> {
                if (UPDATE_SOURCES.equals(d.key())) {
                    return validateSources(v, errors, warnings);
                }
                return v;
            }
            default -> {
                return v;
            }
        }
    }

    /**
     * 更新源校验。
     * <p>
     * 必须在保存时就挡住格式错误：现场把地址写在页面上，你以为生效了，
     * 直到某天想升级才发现"检查更新"一直报连不上，那时候已经换了几茬人。
     */
    private String validateSources(String v, List<String> errors, List<String> warnings) {
        if (v.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        boolean httpOnly = false;
        for (String raw : v.split("[,\\n\\r]+")) {
            String s = raw.trim();
            if (s.isEmpty()) {
                continue;
            }
            if (!s.startsWith("http://") && !s.startsWith("https://")) {
                errors.add("更新源必须以 http:// 或 https:// 开头：" + s);
                continue;
            }
            if (s.startsWith("http://")) {
                httpOnly = true;
            }
            parts.add(s);
        }
        if (!errors.isEmpty()) {
            return null;
        }
        if (httpOnly) {
            warnings.add("有更新源是 http，未加密。可以在这种地址上检查版本，"
                    + "但下载升级包只允许 https——防止内网有人替换包。");
        }
        return String.join(",", parts);
    }

    private boolean parseBool(String s) {
        if (s == null) {
            return false;
        }
        String v = s.trim();
        return "true".equalsIgnoreCase(v) || "1".equals(v) || "yes".equalsIgnoreCase(v);
    }

    private int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    /** 谁改的。审计先落到日志，等做操作审计表时再升级 */
    private String currentUser() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a == null || a.getName() == null ? "未知" : a.getName();
    }
}
