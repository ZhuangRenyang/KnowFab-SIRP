package com.factory.hub.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.IOException;

/**
 * 新前端（Vue 3，构建产物 static/app/）入口转发。
 *
 * Spring Boot 只为根路径 / 自动提供静态欢迎页（index.html），子目录 /app/ 不会，
 * 登录后访问 /app/ 会得到 404。这里显式把 / 与 /app 转发到 /app/index.html。
 * hash 路由（#/devices 等）下服务器只需认识 /app/ 一个路径，其余交给前端路由。
 *
 * 2026-10-29 旧版跳转壳（static/index.html、login.html、discover.html）已删除，
 * / 欢迎页改由本类转发到 SPA；未登录匿名访问由 SecurityConfig 入口点送 /app/。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/").setViewName("forward:/app/index.html");
        registry.addViewController("/app").setViewName("forward:/app/index.html");
        registry.addViewController("/app/").setViewName("forward:/app/index.html");
        // 旧书签兼容（2026-10-29 补）：壳页删除后已登录用户访问 /index.html、
        // /login.html 会 404（匿名有 SecurityConfig 入口点 302 兜底，已登录没有）。
        // 转发到 SPA 壳与原跳转壳行为等价：已登录→主界面，未登录→路由守卫送 #/login。
        registry.addViewController("/index.html").setViewName("forward:/app/index.html");
        registry.addViewController("/login.html").setViewName("forward:/app/index.html");
    }

    /**
     * 入口页禁用缓存。
     * <p>
     * SPA 壳页被浏览器缓存后，页面引用的旧 assets 指纹会一直用着，
     * 用户看到的就是"改了没生效、必须手动刷新才更新"——
     * 这在产线现场最坑：明明服务端是新代码，现场大屏还是旧界面。
     * 壳页不缓存、指纹资源长缓存（内容变则文件名变），才是正确姿势。
     */
    @Bean
    @Order(100)   // 排在 Spring Security 之后执行，才能覆盖它默认写的 no-store
    public FilterRegistrationBean<OncePerRequestFilter> spaShellNoCacheFilter() {
        FilterRegistrationBean<OncePerRequestFilter> reg = new FilterRegistrationBean<>();
        reg.setFilter(new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res,
                                            FilterChain chain) throws ServletException, IOException {
                String uri = req.getRequestURI();
                boolean shell = uri.equals("/") || uri.equals("/app") || uri.equals("/app/")
                        || uri.equals("/index.html") || uri.equals("/login.html")
                        || uri.endsWith("/app/index.html");
                chain.doFilter(req, res);
                // 头要在链内 handler 之后再定：静态资源 handler 与 Spring Security
                // 都会自己写 Cache-Control，先写会被它们覆盖
                try {
                    if (uri.contains("/assets/")) {
                        // 指纹资源：文件名随内容变，可以永久缓存
                        res.setHeader("Cache-Control", "public, max-age=31536000, immutable");
                    } else if (shell || uri.startsWith("/api/")) {
                        // 壳页不缓存（否则改了不生效、必须手动刷新）；
                        // 接口同样不缓存，避免现场看到过期数据
                        res.setHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
                        res.setHeader("Pragma", "no-cache");
                        res.setHeader("Expires", "0");
                    }
                } catch (IllegalStateException ignored) {
                    // 响应已提交，忽略
                }
            }
        });
        reg.addUrlPatterns("/*");
        return reg;
    }
}
