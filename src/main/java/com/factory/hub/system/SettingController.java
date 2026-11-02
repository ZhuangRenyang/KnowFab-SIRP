package com.factory.hub.system;

import com.factory.hub.audit.AuditLog;
import com.factory.hub.audit.AuditService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 系统配置接口。只有管理员能读写（SecurityConfig 里锁的）。
 * <p>
 * 单独开一个 Controller 而不是塞进 UpdateController：这些配置里既有升级源，
 * 也有告警和演示数据，混进"升级"下面会让下一个接手的人找不到。
 */
@RestController
@RequestMapping("/api/system/settings")
public class SettingController {

    private final SettingStore store;
    private final AuditService audit;

    public SettingController(SettingStore store, AuditService audit) {
        this.store = store;
        this.audit = audit;
    }

    @GetMapping
    public Map<String, Object> get() {
        return store.view();
    }

    /**
     * 保存。请求体是扁平的 key → value，例如
     * {@code {"update.sources":"https://...","alarm.retain-days":"90"}}。
     * 也接受 {@code {"values":{...}}} 的包一层写法，前端改版时不用动后端。
     */
    @SuppressWarnings("unchecked")
    @PostMapping
    public Map<String, Object> save(@RequestBody Map<String, Object> body) {
        Map<String, String> values = new LinkedHashMap<>();
        Object inner = body == null ? null : body.get("values");
        Map<String, Object> src = inner instanceof Map ? (Map<String, Object>) inner : body;
        if (src != null) {
            for (Map.Entry<String, Object> e : src.entrySet()) {
                if ("values".equals(e.getKey())) {
                    continue;
                }
                values.put(e.getKey(), e.getValue() == null ? "" : String.valueOf(e.getValue()));
            }
        }
        // 旧值必须在保存之前取：存完就只剩新值了，"从 1.1 改成 1.3" 就写不出来了
        Map<String, String> before = new LinkedHashMap<>();
        for (String k : values.keySet()) {
            before.put(k, store.get(k));
        }
        Map<String, Object> out = store.save(values);
        String detail = describeChanges(values, before, labels());
        if (Boolean.TRUE.equals(out.get("ok"))) {
            audit.ok(AuditLog.CAT_SYSTEM, AuditLog.ACT_UPDATE, "settings", "系统配置", detail);
        } else {
            audit.fail(AuditLog.CAT_SYSTEM, AuditLog.ACT_UPDATE, "settings", "系统配置",
                    detail, String.valueOf(out.get("message")));
        }
        return out;
    }

    /** 恢复某一项的配置文件默认值 */
    @DeleteMapping("/{key}")
    public Map<String, Object> reset(@PathVariable String key) {
        String label = labels().getOrDefault(key, key);
        Map<String, Object> out = store.reset(key);
        String detail = "恢复设置 " + key + "（" + label + "）为默认值";
        if (Boolean.TRUE.equals(out.get("ok"))) {
            audit.ok(AuditLog.CAT_SYSTEM, AuditLog.ACT_UPDATE, key, label, detail);
        } else {
            audit.fail(AuditLog.CAT_SYSTEM, AuditLog.ACT_UPDATE, key, label,
                    detail, String.valueOf(out.get("message")));
        }
        return out;
    }

    /* ==================== 审计辅助 ==================== */

    /**
     * 配置变更摘要，写成人话：「把告警历史保留天数从 180 改成 200」。
     * <p>
     * 敏感项（密码、密钥、token 一类）只写"已更新"，值本身不落审计表。
     */
    private String describeChanges(Map<String, String> values, Map<String, String> before,
                                   Map<String, String> labels) {
        if (values.isEmpty()) {
            return "保存系统配置（没有改动任何配置项）";
        }
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, String> e : values.entrySet()) {
            String key = e.getKey();
            String label = labels.getOrDefault(key, key);
            if (isSensitive(key)) {
                parts.add(label + " 已更新（敏感值不记录）");
                continue;
            }
            String old = before.get(key);
            String now = e.getValue();
            if (old != null && old.equals(now)) {
                continue;                       // 值没变，不写进审计，免得看着像改过
            }
            parts.add("把「" + label + "」从 " + (old == null || old.isEmpty() ? "空" : old)
                    + " 改成 " + (now == null || now.isEmpty() ? "空" : now));
        }
        if (parts.isEmpty()) {
            return "保存系统配置（值没有变化）";
        }
        return String.join("；", parts);
    }

    /**
     * 配置项 key → 中文名。取自 SettingStore 的定义，不在这里另抄一份，
     * 免得改了 SettingStore 的名字、审计里还是旧名字，两边对不上。
     */
    private Map<String, String> labels() {
        Map<String, String> out = new LinkedHashMap<>();
        try {
            Object groups = store.view().get("groups");
            if (groups instanceof List<?> gs) {
                for (Object g : gs) {
                    if (!(g instanceof Map<?, ?> gm)) {
                        continue;
                    }
                    Object items = gm.get("items");
                    if (!(items instanceof List<?> is)) {
                        continue;
                    }
                    for (Object it : is) {
                        if (it instanceof Map<?, ?> im) {
                            Object k = im.get("key");
                            Object lb = im.get("label");
                            if (k != null) {
                                out.put(String.valueOf(k),
                                        lb == null ? String.valueOf(k) : String.valueOf(lb));
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // 取不到名字就用 key，不影响审计
        }
        return out;
    }

    /** 哪些配置项算敏感。key 里带这些词的一律不记值 */
    private static boolean isSensitive(String key) {
        if (key == null) {
            return false;
        }
        String k = key.toLowerCase();
        return k.contains("password") || k.contains("pwd") || k.contains("secret")
                || k.contains("token") || k.contains("credential")
                || k.contains("apikey") || k.contains("api-key") || k.contains("api_key");
    }
}
