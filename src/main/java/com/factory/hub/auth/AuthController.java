package com.factory.hub.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 登录 / 退出 / 当前用户 / 改密码。
 * <p>
 * 登录用接口而不是 Spring 默认的表单页：前端是单页应用，
 * 表单跳转会把 fetch 的 401 JSON 变成 302 + HTML，前端处理起来很别扭。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final AuthenticationManager authManager;
    private final UserService users;
    private final LoginThrottle throttle;

    public AuthController(AuthenticationManager authManager, UserService users,
                          LoginThrottle throttle) {
        this.authManager = authManager;
        this.users = users;
        this.throttle = throttle;
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, Object> body,
                                                     HttpServletRequest req) {
        String username = str(body.get("username"));
        String password = str(body.get("password"));
        String ip = ipOf(req);

        long locked = throttle.lockedSeconds(ip);
        if (locked > 0) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(msg(false, "密码错误次数太多，请 " + locked + " 秒后再试"));
        }
        if (username.isBlank() || password.isBlank()) {
            return ResponseEntity.badRequest().body(msg(false, "请输入用户名和密码"));
        }

        try {
            Authentication auth = authManager.authenticate(
                    new UsernamePasswordAuthenticationToken(username, password));

            SecurityContext ctx = SecurityContextHolder.createEmptyContext();
            ctx.setAuthentication(auth);
            SecurityContextHolder.setContext(ctx);
            // 关键：把 SecurityContext 写进 session，否则下一个请求又不认识你
            HttpSession session = req.getSession(true);
            session.setAttribute(
                    HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, ctx);

            users.byName(username).ifPresent(users::touchLogin);
            throttle.success(ip);
            log.info("登录成功：{}（{}）from {}", username, auth.getAuthorities(), ip);

            Map<String, Object> out = msg(true, "登录成功");
            out.put("username", username);
            users.byName(username).ifPresent(u -> {
                out.put("displayName", u.getDisplayName());
                out.put("role", u.getRole());
                out.put("roleText", u.roleText());
            });
            return ResponseEntity.ok(out);
        } catch (AuthenticationException e) {
            throttle.fail(ip);
            // 不区分"用户不存在"和"密码错"：区分了等于告诉对方哪些账号存在
            log.warn("登录失败：{} from {}（{}）", username, ip, e.getClass().getSimpleName());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(msg(false, "用户名或密码不对"));
        }
    }

    /** 前端每次打开页面先问这个：没登录返回 authenticated=false，不报错 */
    @GetMapping("/me")
    public Map<String, Object> me() {
        Map<String, Object> out = new LinkedHashMap<>();
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean logged = auth != null && auth.isAuthenticated()
                && !"anonymousUser".equals(String.valueOf(auth.getPrincipal()));
        out.put("authenticated", logged);
        if (logged) {
            String name = auth.getName();
            out.put("username", name);
            users.byName(name).ifPresent(u -> {
                out.put("displayName", u.getDisplayName());
                out.put("role", u.getRole());
                out.put("roleText", u.roleText());
                out.put("lastLoginAt", u.getLastLoginAt());
            });
        }
        return out;
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(HttpServletRequest req) {
        HttpSession s = req.getSession(false);
        if (s != null) {
            s.invalidate();
        }
        SecurityContextHolder.clearContext();
        return msg(true, "已退出登录");
    }

    @PostMapping("/password")
    public Map<String, Object> changePassword(@RequestBody Map<String, Object> body) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return msg(false, "请先登录");
        }
        return users.changeOwnPassword(auth.getName(),
                str(body.get("oldPassword")), str(body.get("newPassword")));
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private String ipOf(HttpServletRequest req) {
        // 只取直连地址。X-Forwarded-For 可以伪造，没配可信代理时用它比不用更危险
        String ip = req.getRemoteAddr();
        return ip == null ? "unknown" : ip;
    }

    private static Map<String, Object> msg(boolean ok, String text) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", ok);
        m.put("message", text);
        return m;
    }
}
