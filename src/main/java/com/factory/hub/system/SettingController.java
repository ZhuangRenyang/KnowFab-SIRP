package com.factory.hub.system;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
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

    public SettingController(SettingStore store) {
        this.store = store;
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
        return store.save(values);
    }

    /** 恢复某一项的配置文件默认值 */
    @DeleteMapping("/{key}")
    public Map<String, Object> reset(@PathVariable String key) {
        return store.reset(key);
    }
}
