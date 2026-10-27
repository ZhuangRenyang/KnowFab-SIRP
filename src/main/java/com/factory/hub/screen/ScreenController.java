package com.factory.hub.screen;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 车间大屏接口。**不需要登录**（SecurityConfig 里放开的），
 * 所以这里只返回聚合数字和"设备名 + 状态"，不返回点表、接口地址、API Key 任何东西。
 * <p>
 * 只读 GET：大屏是挂在墙上的电视，不该有能改数据的入口。
 */
@RestController
@RequestMapping("/api/screen")
public class ScreenController {

    private final ScreenService service;

    public ScreenController(ScreenService service) {
        this.service = service;
    }

    /** 整屏数据一次给全，前端 5 秒拉一次 */
    @GetMapping("/snapshot")
    public Map<String, Object> snapshot() {
        return service.snapshot();
    }

    /** 探活，给运维判断"大屏是不是还活着"用 */
    @GetMapping("/ping")
    public Map<String, Object> ping() {
        return Map.of("ok", true, "ts", System.currentTimeMillis());
    }
}
