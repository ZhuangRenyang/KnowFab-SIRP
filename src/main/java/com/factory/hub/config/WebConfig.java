package com.factory.hub.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

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
}
