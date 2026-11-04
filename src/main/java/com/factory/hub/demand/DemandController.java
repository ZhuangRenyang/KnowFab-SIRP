package com.factory.hub.demand;

import com.factory.hub.audit.AuditLog;
import com.factory.hub.audit.AuditService;
import com.fasterxml.jackson.databind.ObjectMapper;

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
    private final AuditService audit;
    private final ObjectMapper mapper;

    public DemandController(DemandService service, DemandStore store,
                            DemandWindowRepository repo, AuditService audit,
                            ObjectMapper mapper) {
        this.service = service;
        this.store = store;
        this.repo = repo;
        this.audit = audit;
        this.mapper = mapper;
    }

    @GetMapping
    public Map<String, Object> overview(@RequestParam(required = false) String month,
                                        @RequestParam(required = false) String device) {
        return service.overview(month, device);
    }

    /**
     * 取需量配置。
     * <p>
     * @param device 总表（进线）编号；不传 = 全局那份（兼作各进线的默认值）
     */
    @GetMapping("/config")
    public Map<String, Object> config(@RequestParam(required = false) String device) {
        boolean perMeter = device != null && !device.isBlank();
        DemandConfig cfg = perMeter ? store.forMeter(device) : store.current();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("device", perMeter ? device : null);
        out.put("config", cfg);
        out.put("warnings", cfg.validate());
        return out;
    }

    /**
     * 保存需量配置。
     * <p>
     * body 里带 {@code device} = 保存到那块总表（进线）名下；不带 = 保存为全局默认。
     * 多路进线分别跟供电局签合同，合同需量与单价都不同，必须能各存各的。
     */
    @PostMapping("/config")
    public Map<String, Object> save(@RequestBody Map<String, Object> body) {
        Map<String, Object> src = body == null ? Map.of() : body;
        Object devObj = src.get("device");
        String device = devObj == null ? null : String.valueOf(devObj).trim();
        boolean perMeter = device != null && !device.isBlank();
        DemandConfig cfg;
        try {
            cfg = mapper.convertValue(src, DemandConfig.class);
        } catch (Exception e) {
            Map<String, Object> bad = new LinkedHashMap<>();
            bad.put("ok", false);
            bad.put("message", "参数格式不对：" + e.getMessage());
            return bad;
        }
        // 合同需量填 0 等于把预警关掉，客户不会意识到，所以直接拦
        if (cfg.getContractDemandKw() <= 0) {
            Map<String, Object> bad = new LinkedHashMap<>();
            bad.put("ok", false);
            bad.put("message", "合同需量必须大于 0。这个数在供电合同上，照抄就行；"
                    + "不想预警可以关掉「启用预警」开关，别填 0");
            return bad;
        }
        if (perMeter) {
            store.saveForMeter(device, cfg);
        } else {
            store.save(cfg);
        }
        audit.ok(AuditLog.CAT_ENERGY, AuditLog.ACT_UPDATE, "demand", "基本电费配置",
                "保存基本电费配置："
                        + (perMeter ? "进线 " + device + "；" : "全局默认；")
                        + "计费模式=" + cfg.getBillingMode()
                        + "，合同需量=" + cfg.getContractDemandKw() + " kW"
                        + "，变压器容量=" + cfg.getTransformerKva() + " kVA"
                        + "，需量单价=" + cfg.getDemandPrice() + "，容量单价=" + cfg.getCapacityPrice());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("message", "已保存，立即生效");
        out.put("warnings", cfg.validate());
        return out;
    }

    /**
     * 试算：用一组临时参数算基本电费，**不保存、不写审计、不影响别人**。
     * <p>
     * 场景：「合同需量报 400 还是 500」「按需量还是按容量划算」——
     * 以前只能改配置→保存→看结果→改回来，改一次写一次审计。
     * <p>
     * {@code month} 不传就是本月；{@code device} 不传是全厂。
     * <b>峰值取自真实数据不可改</b>：可改的是合同需量、两个单价、变压器容量、计费方式。
     */
    @PostMapping("/preview")
    public Map<String, Object> preview(@RequestBody Map<String, Object> body) {
        try {
            // ⚠️ 必须 snapshot() 不能 current()：current() 返回共享引用，
            // 下面那句 setContractDemandKw 会把全应用的配置改掉（还不落库）
            DemandConfig probe = store.snapshot();
            if (body != null) {
                if (body.get("contractDemandKw") != null) {
                    probe.setContractDemandKw(num(body.get("contractDemandKw"), probe.getContractDemandKw()));
                }
                if (body.get("demandPrice") != null) {
                    probe.setDemandPrice(num(body.get("demandPrice"), probe.getDemandPrice()));
                }
                if (body.get("transformerKva") != null) {
                    probe.setTransformerKva(num(body.get("transformerKva"), probe.getTransformerKva()));
                }
                if (body.get("capacityPrice") != null) {
                    probe.setCapacityPrice(num(body.get("capacityPrice"), probe.getCapacityPrice()));
                }
                if (body.get("billingMode") != null) {
                    probe.setBillingMode(str(body.get("billingMode")));
                }
            }
            Object month = body == null ? null : body.get("month");
            Object device = body == null ? null : body.get("device");
            return service.preview(probe,
                    month == null ? null : String.valueOf(month),
                    device == null ? null : String.valueOf(device));
        } catch (Exception e) {
            Map<String, Object> bad = new LinkedHashMap<>();
            bad.put("ok", false);
            bad.put("message", e.getMessage() == null ? "试算失败" : e.getMessage());
            return bad;
        }
    }

    private static double num(Object v, double dft) {
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(v).trim());
        } catch (Exception e) {
            return dft;
        }
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v).trim();
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
        audit.ok(AuditLog.CAT_ENERGY, AuditLog.ACT_PURGE, "demand", "需量记录",
                "清空需量记录 " + n + " 条（不可恢复）");
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
