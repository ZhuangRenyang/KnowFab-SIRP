package com.factory.hub.demand;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 需量与基本电费接口。挂在 /api/energy/demand 下面，跟能耗页是一个功能组。
 */
@RestController
@RequestMapping("/api/energy/demand")
public class DemandController {

    private final DemandService service;
    private final DemandStore store;
    private final DemandWindowRepository repo;

    public DemandController(DemandService service, DemandStore store,
                            DemandWindowRepository repo) {
        this.service = service;
        this.store = store;
        this.repo = repo;
    }

    @GetMapping
    public Map<String, Object> overview(@RequestParam(required = false) String month,
                                        @RequestParam(required = false) String device) {
        return service.overview(month, device);
    }

    @GetMapping("/config")
    public Map<String, Object> config() {
        DemandConfig cfg = store.current();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("config", cfg);
        out.put("warnings", cfg.validate());
        return out;
    }

    @PostMapping("/config")
    public Map<String, Object> save(@RequestBody DemandConfig cfg) {
        // 合同需量填 0 等于把预警关掉，客户不会意识到，所以直接拦
        if (cfg.getContractDemandKw() <= 0) {
            Map<String, Object> bad = new LinkedHashMap<>();
            bad.put("ok", false);
            bad.put("message", "合同需量必须大于 0。这个数在供电合同上，照抄就行；"
                    + "不想预警可以关掉「启用预警」开关，别填 0");
            return bad;
        }
        store.save(cfg);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("message", "已保存，立即生效");
        out.put("warnings", cfg.validate());
        return out;
    }

    /** 清演示需量数据，交付前用 */
    @PostMapping("/purge")
    public Map<String, Object> purge(@RequestBody(required = false) Map<String, Object> body) {
        boolean confirm = body != null && Boolean.TRUE.equals(body.get("confirm"));
        Map<String, Object> out = new LinkedHashMap<>();
        if (!confirm) {
            out.put("ok", false);
            out.put("message", "需量数据清空不可恢复，请传 {\"confirm\":true}");
            return out;
        }
        int n = repo.purgeAll();
        out.put("ok", true);
        out.put("message", "已清空 " + n + " 条 15 分钟需量记录");
        return out;
    }

    /** 计费方式选项，前端下拉用，避免两边各写一份枚举 */
    @GetMapping("/modes")
    public List<Map<String, String>> modes() {
        return List.of(
                Map.of("value", DemandConfig.MODE_AUTO, "label", "自动（按需量与按容量取便宜的）"),
                Map.of("value", DemandConfig.MODE_DEMAND, "label", "按需量（kW × 需量电价）"),
                Map.of("value", DemandConfig.MODE_CAPACITY, "label", "按容量（kVA × 容量电价）"));
    }
}
