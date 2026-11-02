package com.factory.hub.auth;

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
 * 用户管理，只有管理员能调（权限在 SecurityConfig 里按 /api/users/** 收死）。
 * <p>
 * 不提供"删除自己"和"停用最后一个管理员"，这两个操作都会把人锁在系统外面。
 */
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService service;
    private final AuditService audit;

    public UserController(UserService service, AuditService audit) {
        this.service = service;
        this.audit = audit;
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
        String username = str(body.get("username"));
        String displayName = str(body.get("displayName"));
        String password = str(body.get("password"));
        String role = str(body.get("role"));
        Map<String, Object> out = service.create(username, displayName, password, role);
        // 新建完才能查到角色中文名，查不到就退回原始角色码
        UserAccount u = findByName(username);
        String roleText = u != null ? u.roleText() : (role.isEmpty() ? "未指定" : role);
        String name = username.isEmpty() ? "（用户名未填）" : username;
        // 密码绝不入审计：这里只写"新增了谁、给了什么角色"
        String detail = "新增用户 " + name + "，角色 " + roleText;
        if (isOk(out)) {
            audit.ok(AuditLog.CAT_USER, AuditLog.ACT_CREATE, name, name + "（" + roleText + "）", detail);
        } else {
            audit.fail(AuditLog.CAT_USER, AuditLog.ACT_CREATE, name, name + "（" + roleText + "）",
                    detail, messageOf(out));
        }
        return out;
    }

    @PostMapping("/{id}")
    public Map<String, Object> update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Boolean enabled = body.get("enabled") == null ? null
                : Boolean.valueOf(String.valueOf(body.get("enabled")));
        UserAccount before = findById(id);
        String username = before == null ? "#" + id : before.getUsername();
        Map<String, Object> out = service.update(id, str(body.get("displayName")),
                str(body.get("role")), enabled);
        String detail = "修改用户 " + username + "：" + describeChanges(body, before);
        if (isOk(out)) {
            audit.ok(AuditLog.CAT_USER, AuditLog.ACT_UPDATE, username, username, detail);
        } else {
            audit.fail(AuditLog.CAT_USER, AuditLog.ACT_UPDATE, username, username,
                    detail, messageOf(out));
        }
        return out;
    }

    @PostMapping("/{id}/password")
    public Map<String, Object> resetPassword(@PathVariable Long id,
                                             @RequestBody Map<String, Object> body) {
        UserAccount before = findById(id);
        String username = before == null ? "#" + id : before.getUsername();
        Map<String, Object> out = service.resetPassword(id, str(body.get("password")), true);
        // 密码本身不记入审计：只记谁给谁重置了
        String detail = "重置用户 " + username + " 的密码（密码本身不记入审计）";
        if (isOk(out)) {
            audit.ok(AuditLog.CAT_USER, AuditLog.ACT_RESET, username, username, detail);
        } else {
            audit.fail(AuditLog.CAT_USER, AuditLog.ACT_RESET, username, username,
                    detail, messageOf(out));
        }
        return out;
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        UserAccount before = findById(id);
        String username = before == null ? "#" + id : before.getUsername();
        Map<String, Object> out = service.delete(id);
        String detail = "删除用户 " + username;
        if (isOk(out)) {
            audit.ok(AuditLog.CAT_USER, AuditLog.ACT_DELETE, username, username, detail);
        } else {
            audit.fail(AuditLog.CAT_USER, AuditLog.ACT_DELETE, username, username,
                    detail, messageOf(out));
        }
        return out;
    }

    /* ==================== 审计辅助 ==================== */

    /**
     * 这次改了哪些字段，写成人话。
     * <p>
     * 只看请求体里带了哪些键——前端没传的字段就不是这次改的，
     * 凭当前值反推会把「没动过的」也写成改了，审计就不可信了。
     */
    private String describeChanges(Map<String, Object> body, UserAccount before) {
        List<String> parts = new ArrayList<>();
        if (body.containsKey("displayName")) {
            String v = str(body.get("displayName"));
            String old = before == null ? "" : before.getDisplayName();
            parts.add("姓名改为 " + (v.isEmpty() ? "（空）" : v)
                    + (v.equals(old) ? "" : "（原 " + (old == null || old.isEmpty() ? "空" : old) + "）"));
        }
        if (body.containsKey("role")) {
            String v = str(body.get("role")).toUpperCase();
            String old = before == null ? "" : before.getRole();
            parts.add("角色改为 " + (v.isEmpty() ? "（空）" : v)
                    + (v.equals(old) ? "" : "（原 " + old + "）"));
        }
        if (body.containsKey("enabled")) {
            boolean v = Boolean.parseBoolean(String.valueOf(body.get("enabled")));
            Boolean old = before == null ? null : before.getEnabled();
            // 注意：不能写成 "启用账号" + 后缀 的三元表达式——"+" 优先级高于 "?:"，
            // 那样启用分支会丢掉「原状态」，审计里就看不出到底改没改过
            String suffix = (old != null && old.booleanValue() == v)
                    ? "" : "（原 " + (old != null && old.booleanValue() ? "启用" : "停用") + "）";
            parts.add((v ? "启用账号" : "停用账号") + suffix);
        }
        if (parts.isEmpty()) {
            return "保存用户信息";
        }
        return String.join("、", parts);
    }

    private UserAccount findById(Long id) {
        if (id == null) {
            return null;
        }
        for (UserAccount u : service.all()) {
            if (id.equals(u.getId())) {
                return u;
            }
        }
        return null;
    }

    private UserAccount findByName(String username) {
        if (username == null || username.isEmpty()) {
            return null;
        }
        for (UserAccount u : service.all()) {
            if (username.equals(u.getUsername())) {
                return u;
            }
        }
        return null;
    }

    private static boolean isOk(Map<String, Object> out) {
        return out != null && Boolean.TRUE.equals(out.get("ok"));
    }

    private static String messageOf(Map<String, Object> out) {
        if (out == null) {
            return "未知错误";
        }
        Object m = out.get("message");
        return m == null ? "操作失败" : String.valueOf(m);
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }
}
