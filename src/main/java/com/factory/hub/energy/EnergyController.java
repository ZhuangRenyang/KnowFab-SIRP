package com.factory.hub.energy;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 能耗相关接口。给前端「能耗分析」页用。
 * <p>
 * 这些都是管理端接口，和 /open/v1/** 的对外集成接口分开。
 * 客户要拿电费数据去对接 MES/ERP 时，应该在 OpenApiController 里另开一个带 Key 校验的端点，
 * 不要把这里的接口直接暴露出去。
 */
@RestController
@RequestMapping("/api/energy")
public class EnergyController {

    private final EnergyService energyService;
    private final TariffStore tariffStore;
    private final EnergyRepository repo;

    public EnergyController(EnergyService energyService, TariffStore tariffStore, EnergyRepository repo) {
        this.energyService = energyService;
        this.tariffStore = tariffStore;
        this.repo = repo;
    }

    /** 首页大数字 + 峰谷分布 + 日趋势 */
    @GetMapping("/overview")
    public Map<String, Object> overview(@RequestParam(defaultValue = "7") int days) {
        int d = Math.max(1, Math.min(90, days));
        return energyService.overview(d);
    }

    /** 设备能耗排行 */
    @GetMapping("/devices")
    public List<Map<String, Object>> devices(@RequestParam(defaultValue = "today") String range) {
        return energyService.deviceRank(range);
    }

    /** 单台设备某天的能耗明细 */
    @GetMapping("/device/{code}")
    public ResponseEntity<Map<String, Object>> device(@PathVariable String code,
                                                      @RequestParam(required = false) String date) {
        Map<String, Object> m = energyService.deviceDetail(code, date);
        if (m.get("name") == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(m);
    }

    /** 读当前电价方案 + 体检结果 */
    @GetMapping("/tariff")
    public Map<String, Object> tariff() {
        TariffPlan p = tariffStore.current();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("plan", p);
        out.put("warnings", p.validate());
        return out;
    }

    /**
     * 保存电价方案，立即生效，不用重启。
     * 体检有问题也允许保存——客户可能确实有特殊政策，
     * 但要把 warnings 带回去让人看见，不然电费对不上账会以为是软件错了。
     */
    @PostMapping("/tariff")
    public Map<String, Object> saveTariff(@RequestBody TariffPlan plan) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (plan == null || plan.getSegments() == null || plan.getSegments().isEmpty()) {
            out.put("ok", false);
            out.put("message", "至少要配置一个电价时段");
            return out;
        }
        if (plan.getName() == null || plan.getName().isBlank()) {
            plan.setName("未命名方案");
        }
        tariffStore.save(plan);
        out.put("ok", true);
        out.put("message", "已保存，立即生效");
        out.put("plan", tariffStore.current());
        out.put("warnings", tariffStore.current().validate());
        return out;
    }

    /**
     * 清空全部能耗历史。交付前把演示数据清掉用。
     * 必须带 {@code {"confirm": true}}，防止误点把客户几个月的电费记录删了。
     */
    @PostMapping("/purge")
    public Map<String, Object> purge(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (body == null || !Boolean.TRUE.equals(body.get("confirm"))) {
            out.put("ok", false);
            out.put("message", "这个操作会删除全部能耗历史，不可恢复。请传 {\"confirm\": true} 确认");
            return out;
        }
        int n = repo.purgeAll();
        out.put("ok", true);
        out.put("deleted", n);
        out.put("message", "已删除 " + n + " 条能耗记录。当前小时的实时数据会继续累积");
        return out;
    }
}
