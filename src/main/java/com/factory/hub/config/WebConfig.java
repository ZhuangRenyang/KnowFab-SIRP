package com.factory.hub.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 新前端（Vue 3，构建产物 static/app/）入口转发。
 *
 * Spring Boot 只为根路径 / 自动提供静态欢迎页（index.html），子目录 /app/ 不会，
 * 登录后访问 /app/ 会得到 404。这里显式把 /app 与 /app/ 转发到 /app/index.html。
 * hash 路由（#/devices 等）下服务器只需认识 /app/ 一个路径，其余交给前端路由。
 *
 * 除本类外后端零改动：SecurityConfig 白名单、pom、application.yml 均未动，
 * /app/** 落入 anyRequest().authenticated() 默认受保护（匿名 302 → /login.html）。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/app").setViewName("forward:/app/index.html");
        registry.addViewController("/app/").setViewName("forward:/app/index.html");
    }
}
