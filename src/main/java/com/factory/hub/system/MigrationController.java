package com.factory.hub.system;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 数据库结构迁移的查看接口（管理员）。
 * <p>
 * 只读：迁移由启动阶段自动执行，不给网页按钮"现在跑一下"。
 * 理由是表结构变更执行到一半没法回退，必须配一次重启和一次备份才有意义，
 * 点一下就执行的按钮只会制造事故。
 */
@RestController
@RequestMapping("/api/system/migrations")
public class MigrationController {

    private final MigrationService service;

    public MigrationController(MigrationService service) {
        this.service = service;
    }

    @GetMapping
    public Map<String, Object> status() {
        return service.status();
    }

    /** 看某个脚本会被拆成哪几条语句 */
    @GetMapping("/{version}/sql")
    public Map<String, Object> sql(@PathVariable String version) {
        return service.preview(version);
    }
}
