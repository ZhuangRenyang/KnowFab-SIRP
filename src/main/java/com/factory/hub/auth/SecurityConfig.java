package com.factory.hub.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 安全配置：谁能访问什么。
 * <p>
 * <b>两个必须放开的入口</b>（漏一个就是事故）：
 * <ol>
 *   <li>{@code /api/system/version}——install.sh 和 upgrade.sh 的健康检查靠它，
 *       被 401 挡住的话"装完了但脚本说服务没起来"，排查方向全错</li>
 *   <li>{@code /ws/**}——实时数据 WebSocket，握手不过登录校验，拦了页面就不刷新</li>
 * </ol>
 * 另外 {@code /open/**} 走设备 API Key，是给客户 MES 对接的，不能要求登录。
 * <p>
 * 写操作按角色收：设备增删改给管理员/车间主任，电价/班次/升级/用户只管管理员。
 * 前端会按角色隐藏按钮，但<b>真正的边界只在这里</b>——隐藏按钮不算权限。
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public UserDetailsService userDetailsService(UserService users) {
        return username -> users.byName(username)
                .map(u -> User.withUsername(u.getUsername())
                        .password(u.getPasswordHash())
                        // 库里存 ADMIN/MANAGER/LEADER，Spring 需要 ROLE_ 前缀
                        .roles(u.getRole())
                        .disabled(!u.isEnabledNow())
                        .build())
                .orElseThrow(() -> new UsernameNotFoundException("用户不存在"));
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration cfg) throws Exception {
        return cfg.getAuthenticationManager();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, ObjectMapper mapper) throws Exception {
        http
                // 纯 JSON 接口 + 同源页面，没有表单提交，关掉 CSRF；会话用 SameSite=Lax 兜底
                .csrf(csrf -> csrf.disable())
                // 不配 maximumSessions：那需要额外的 SessionRegistry，单机场景没有收益
                .sessionManagement(s -> s
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .authorizeHttpRequests(auth -> auth
                        // ---- 匿名可访问 ----
                        .requestMatchers("/login.html", "/favicon.ico").permitAll()
                        .requestMatchers("/api/auth/login", "/api/auth/me").permitAll()
                        .requestMatchers("/api/system/version").permitAll()
                        // 车间大屏：挂在墙上的电视，不能要求登录。只读聚合接口，
                        // 不返回点表/API Key，泄密风险可控（详见 ScreenController 注释）
                        .requestMatchers("/screen.html", "/screen", "/api/screen/**").permitAll()
                        // 故障维修大屏：同上，免登录只读，数据走 /api/screen/snapshot
                        .requestMatchers("/fault.html", "/fault").permitAll()
                        .requestMatchers("/open/**", "/ws/**").permitAll()
                        .requestMatchers("/error").permitAll()

                        // ---- 只有管理员 ----
                        .requestMatchers("/api/system/update/**", "/api/users/**",
                                "/api/system/settings/**", "/api/system/migrations/**")
                        .hasRole("ADMIN")
                        // 设备发现（网段扫描 + 寄存器探测）：本质是台网络扫描器，
                        // 只读且限了量，也只给管理员用
                        .requestMatchers("/api/discovery/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/ai/config", "/api/ai/test").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/energy/tariff").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/energy/demand/config").hasRole("ADMIN")

                        // ---- 管理员 / 车间主任 ----
                        .requestMatchers(HttpMethod.POST, "/api/devices/**").hasAnyRole("ADMIN", "MANAGER")
                        .requestMatchers(HttpMethod.PUT, "/api/devices/**").hasAnyRole("ADMIN", "MANAGER")
                        .requestMatchers(HttpMethod.DELETE, "/api/devices/**").hasAnyRole("ADMIN", "MANAGER")
                        .requestMatchers(HttpMethod.POST, "/api/alarms/rules", "/api/alarms/rules/**")
                        .hasAnyRole("ADMIN", "MANAGER")
                        .requestMatchers(HttpMethod.DELETE, "/api/alarms/rules/**")
                        .hasAnyRole("ADMIN", "MANAGER")
                        .requestMatchers(HttpMethod.POST, "/api/alarms/notify", "/api/alarms/notify/**")
                        .hasAnyRole("ADMIN", "MANAGER")
                        .requestMatchers(HttpMethod.POST, "/api/reports/shifts").hasAnyRole("ADMIN", "MANAGER")
                        // 生成早报会花钱调外部接口，但不改配置，车间主任也放开
                        .requestMatchers(HttpMethod.POST, "/api/ai/digest/generate")
                        .hasAnyRole("ADMIN", "MANAGER")

                        // ---- 其余一律要登录（班组长能看、能确认告警）----
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().authenticated())
                .exceptionHandling(e -> e
                        // 接口返回 401 JSON，页面跳登录页。
                        // 必须用显式委派：defaultAuthenticationEntryPointFor 的组合
                        // 在只配一个 matcher 时会被后续的 authenticationEntryPoint 盖掉，
                        // 结果 /api/** 拿到 302，前端 fetch 解析到一坨 HTML，报"JSON 解析失败"
                        .authenticationEntryPoint(this.entryPoint(mapper))
                        .accessDeniedHandler(this.denied(mapper)))
                .formLogin(f -> f.disable())
                .httpBasic(b -> b.disable())
                .logout(l -> l.disable());
        return http.build();
    }

    /** /api/** 给 401 JSON，其它（页面）跳登录页 */
    private org.springframework.security.web.AuthenticationEntryPoint entryPoint(ObjectMapper mapper) {
        var entries = new java.util.LinkedHashMap<
                org.springframework.security.web.util.matcher.RequestMatcher,
                org.springframework.security.web.AuthenticationEntryPoint>();
        entries.put(new AntPathRequestMatcher("/api/**"),
                (req, res, ex) -> write(res, mapper, HttpServletResponse.SC_UNAUTHORIZED, "请先登录"));
        var delegating = new org.springframework.security.web.authentication
                .DelegatingAuthenticationEntryPoint(entries);
        delegating.setDefaultEntryPoint((req, res, ex) -> res.sendRedirect("/login.html"));
        return delegating;
    }

    /** 接口给 403 JSON，页面（正常不会走到）跳回首页 */
    private org.springframework.security.web.access.AccessDeniedHandler denied(ObjectMapper mapper) {
        return (req, res, ex) -> {
            if (req.getRequestURI().startsWith("/api/")) {
                write(res, mapper, HttpServletResponse.SC_FORBIDDEN, "当前角色没有这个操作权限");
            } else {
                res.sendRedirect("/");
            }
        };
    }

    private void write(HttpServletResponse res, ObjectMapper mapper, int code, String msg) throws java.io.IOException {
        res.setStatus(code);
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding("UTF-8");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", false);
        m.put("message", msg);
        res.getWriter().write(mapper.writeValueAsString(m));
    }
}
