package com.factory.hub.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 用户与密码。
 * <p>
 * <b>首次启动自动建管理员</b>：密码取配置 {@code system.admin.password}，默认 {@code 123456}。
 * <p>
 * <b>为什么默认 123456（而不是随机密码）</b>：随机密码要客户去 data/initial-admin.txt
 * 里找文件，现场装机的师傅经常卡在这一步（2026-11-01 实测反馈）。
 * 默认密码写死为客户能直接记住的 123456，客户装完即用；
 * 风险用两道措施兜住：① 登录后系统提示改密码，且修改时强制 ≥8 位；
 * ② 交付前可在 config/application.yml 或环境变量里覆盖成更强的密码。
 * <p>
 * <b>最后一道保险：不能把最后一个启用的管理员停用/降级/删除。</b>
 * 真发生了客户就得改数据库才能救回来，这种事故一次都不该有。
 */
@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private static final int MIN_PASSWORD = 8;

    private final UserRepository repo;
    private final PasswordEncoder encoder;

    @Value("${system.admin.username:admin}")
    private String initUsername;

    @Value("${system.admin.password:123456}")
    private String initPassword;

    @Value("${system.admin.display-name:系统管理员}")
    private String initDisplayName;

    public UserService(UserRepository repo, PasswordEncoder encoder) {
        this.repo = repo;
        this.encoder = encoder;
    }

    /** 等应用完全起来再建账号：避免和 JPA 初始化抢顺序 */
    @EventListener(ApplicationReadyEvent.class)
    public void seed() {
        try {
            if (repo.count() > 0) {
                return;
            }
            String raw = (initPassword == null || initPassword.isBlank()) ? "123456" : initPassword.trim();
            UserAccount admin = new UserAccount(initUsername, initDisplayName,
                    encoder.encode(raw), UserAccount.ROLE_ADMIN);
            repo.save(admin);

            Path p = Paths.get("data", "initial-admin.txt");
            Files.createDirectories(p.getParent());
            Files.writeString(p, "用户名：" + initUsername + "\n密码：" + raw
                    + "\n\n这是默认密码，拿到安装包的人都可能知道。"
                    + "首次登录后请立刻在「系统 → 用户管理」里改掉，然后删除本文件。\n");
            log.warn("已创建管理员 {}，初始密码 {}（也写在 {}）。登录后请立刻改密码",
                    initUsername, raw, p.toAbsolutePath());
        } catch (Exception e) {
            log.error("初始化管理员失败: {}", e.getMessage());
        }
    }

    /* ==================== 查询 ==================== */

    public Optional<UserAccount> byName(String username) {
        return repo.findByUsername(username == null ? "" : username.trim());
    }

    public List<UserAccount> all() {
        return repo.findAllByOrderByIdAsc();
    }

    public void touchLogin(UserAccount u) {
        u.setLastLoginAt(System.currentTimeMillis());
        repo.save(u);
    }

    /* ==================== 增改删 ==================== */

    public Map<String, Object> create(String username, String displayName,
                                      String rawPassword, String role) {
        String name = username == null ? "" : username.trim();
        if (name.length() < 3) {
            return err("用户名至少 3 位");
        }
        if (!name.matches("[A-Za-z0-9_.\\-]{3,32}")) {
            return err("用户名只能用字母、数字、下划线、点、横线（3~32 位）");
        }
        if (repo.findByUsername(name).isPresent()) {
            return err("用户名已存在");
        }
        if (rawPassword == null || rawPassword.length() < MIN_PASSWORD) {
            return err("密码至少 " + MIN_PASSWORD + " 位");
        }
        String r = normalizeRole(role);
        if (r == null) {
            return err("角色只能是 ADMIN / MANAGER / LEADER / SALES");
        }
        UserAccount u = new UserAccount(name,
                displayName == null || displayName.isBlank() ? name : displayName.trim(),
                encoder.encode(rawPassword), r);
        repo.save(u);
        log.info("新增用户 {}（{}）", name, u.roleText());
        return ok("已创建用户 " + name);
    }

    public Map<String, Object> update(Long id, String displayName, String role, Boolean enabled) {
        UserAccount u = repo.findById(id).orElse(null);
        if (u == null) {
            return err("用户不存在");
        }
        if (displayName != null && !displayName.isBlank()) {
            u.setDisplayName(displayName.trim());
        }
        // 只传 enabled 的时候 role 是空串，不能当成"非法角色"
        if (role != null && !role.isBlank()) {
            String r = normalizeRole(role);
            if (r == null) {
                return err("角色不合法");
            }
            if (!r.equals(u.getRole()) && isLastAdmin(u)) {
                return err("这是最后一个启用的管理员，改成别的角色就没人能管理系统了");
            }
            u.setRole(r);
        }
        if (enabled != null) {
            if (!enabled && isLastAdmin(u)) {
                return err("这是最后一个启用的管理员，停用后就进不来系统了");
            }
            u.setEnabled(enabled);
            if (!enabled) {
                // 停用立即生效：把该用户现有会话踢掉由前端下次请求 401 自然处理
                log.info("用户 {} 已被停用", u.getUsername());
            }
        }
        repo.save(u);
        return ok("已保存");
    }

    public Map<String, Object> resetPassword(Long id, String rawPassword, boolean forceChange) {
        UserAccount u = repo.findById(id).orElse(null);
        if (u == null) {
            return err("用户不存在");
        }
        if (rawPassword == null || rawPassword.length() < MIN_PASSWORD) {
            return err("密码至少 " + MIN_PASSWORD + " 位");
        }
        u.setPasswordHash(encoder.encode(rawPassword));
        repo.save(u);
        log.info("管理员重置了 {} 的密码", u.getUsername());
        return ok("密码已重置");
    }

    public Map<String, Object> changeOwnPassword(String username, String oldRaw, String newRaw) {
        UserAccount u = repo.findByUsername(username).orElse(null);
        if (u == null) {
            return err("用户不存在");
        }
        if (oldRaw == null || !encoder.matches(oldRaw, u.getPasswordHash())) {
            return err("原密码不对");
        }
        if (newRaw == null || newRaw.length() < MIN_PASSWORD) {
            return err("新密码至少 " + MIN_PASSWORD + " 位");
        }
        if (oldRaw.equals(newRaw)) {
            return err("新密码不能和原密码一样");
        }
        u.setPasswordHash(encoder.encode(newRaw));
        repo.save(u);
        log.info("用户 {} 修改了自己的密码", username);
        return ok("密码已修改，下次登录用新密码");
    }

    public Map<String, Object> delete(Long id) {
        UserAccount u = repo.findById(id).orElse(null);
        if (u == null) {
            return err("用户不存在");
        }
        if (isLastAdmin(u)) {
            return err("这是最后一个启用的管理员，不能删除");
        }
        repo.delete(u);
        return ok("已删除用户 " + u.getUsername());
    }

    /** 是不是「最后一个还启用的管理员」 */
    private boolean isLastAdmin(UserAccount u) {
        if (!UserAccount.ROLE_ADMIN.equals(u.getRole()) || !u.isEnabledNow()) {
            return false;
        }
        return repo.countByRoleAndEnabled(UserAccount.ROLE_ADMIN, Boolean.TRUE) <= 1;
    }

    private String normalizeRole(String role) {
        if (role == null) {
            return null;
        }
        String r = role.trim().toUpperCase();
        return switch (r) {
            case "ADMIN", "MANAGER", "LEADER", "SALES" -> r;
            default -> null;
        };
    }

    private Map<String, Object> ok(String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("message", msg);
        return m;
    }

    private Map<String, Object> err(String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", false);
        m.put("message", msg);
        return m;
    }
}
