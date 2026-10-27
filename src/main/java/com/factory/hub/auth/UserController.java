package com.factory.hub.auth;

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
 * 用户管理，只有管理员能调（权限在 SecurityConfig 里按 /api/users/** 收死）。
 * <p>
 * 不提供"删除自己"和"停用最后一个管理员"，这两个操作都会把人锁在系统外面。
 */
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService service;

    public UserController(UserService service) {
        this.service = service;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (UserAccount u : service.all()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", u.getId());
            m.put("username", u.getUsername());
            m.put("displayName", u.getDisplayName());
            m.put("role", u.getRole());
            m.put("roleText", u.roleText());
            m.put("enabled", u.isEnabledNow());
            m.put("createdAt", u.getCreatedAt());
            m.put("lastLoginAt", u.getLastLoginAt());
            out.add(m);
        }
        return out;
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        return service.create(str(body.get("username")), str(body.get("displayName")),
                str(body.get("password")), str(body.get("role")));
    }

    @PostMapping("/{id}")
    public Map<String, Object> update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Boolean enabled = body.get("enabled") == null ? null
                : Boolean.valueOf(String.valueOf(body.get("enabled")));
        return service.update(id, str(body.get("displayName")), str(body.get("role")), enabled);
    }

    @PostMapping("/{id}/password")
    public Map<String, Object> resetPassword(@PathVariable Long id,
                                             @RequestBody Map<String, Object> body) {
        return service.resetPassword(id, str(body.get("password")), true);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        return service.delete(id);
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }
}
