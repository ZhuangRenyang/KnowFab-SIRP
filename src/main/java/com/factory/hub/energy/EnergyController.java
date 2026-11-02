package com.factory.hub.energy;

import com.factory.hub.audit.AuditLog;
import com.factory.hub.audit.AuditService;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
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
    private final BenchmarkStore benchmarkStore;
    private final EnergyBenchmarkService benchmarkService;
    private final AuditService audit;

    public EnergyController(EnergyService energyService, TariffStore tariffStore, EnergyRepository repo,
                            BenchmarkStore benchmarkStore, EnergyBenchmarkService benchmarkService,
                            AuditService audit) {
        this.energyService = energyService;
        this.tariffStore = tariffStore;
        this.repo = repo;
        this.benchmarkStore = benchmarkStore;
        this.benchmarkService = benchmarkService;
        this.audit = audit;
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

    /** 用能单元（DH-11）：全厂 → 车间 → 产线 三级能耗，合计带自证字段 */
    @GetMapping("/by-node")
    public Map<String, Object> byNode(@RequestParam(defaultValue = "today") String range) {
        return energyService.byNode(range);
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
        audit.ok(AuditLog.CAT_ENERGY, AuditLog.ACT_UPDATE, "tariff", plan.getName(),
                "保存电价方案「" + plan.getName() + "」，共 " + plan.getSegments().size() + " 个时段");
        out.put("ok", true);
        out.put("message", "已保存，立即生效");
        out.put("plan", tariffStore.current());
        out.put("warnings", tariffStore.current().validate());
        return out;
    }

    /* ==================== 能效对标（DH-12） ==================== */

    /** 对标值列表（含停用），附带每条配置的体检结果 */
    @GetMapping("/benchmarks")
    public Map<String, Object> benchmarks() {
        Map<String, Object> out = new LinkedHashMap<>();
        List<EnergyBenchmark> rows = benchmarkStore.list();
        List<String> warnings = new java.util.ArrayList<>();
        for (EnergyBenchmark b : rows) {
            for (String w : b.validate()) {
                warnings.add(b.displayName() + "：" + w);
            }
        }
        out.put("ok", true);
        out.put("rows", rows);
        out.put("warnings", warnings);
        return out;
    }

    /**
     * 保存一条对标值（id 为空是新增）。
     * <p>
     * 名称空、或三个限额值一个都没填，直接拒绝——这种记录进了库也永远算不出结论，
     * 不如当场让客户补全。其他问题（数值大小关系不对）允许保存但把 warnings 带回去，
     * 客户可能是按企业内部标准填的，软件不该硬拦。
     */
    @PostMapping("/benchmarks")
    public Map<String, Object> saveBenchmark(@RequestBody EnergyBenchmark bm) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (bm == null) {
            out.put("ok", false);
            out.put("message", "没有收到对标值内容");
            return out;
        }
        List<String> warnings = bm.validate();
        boolean hard = warnings.stream().anyMatch(w ->
                w.startsWith("对标名称") || w.startsWith("先进值 / 准入值 / 限定值至少"));
        if (hard) {
            out.put("ok", false);
            out.put("message", String.join("；", warnings));
            out.put("warnings", warnings);
            return out;
        }
        boolean created = bm.getId() == null || bm.getId().isBlank();
        EnergyBenchmark saved = benchmarkStore.save(bm);
        audit.ok(AuditLog.CAT_ENERGY, created ? AuditLog.ACT_CREATE : AuditLog.ACT_UPDATE,
                saved.getId(), saved.displayName(),
                (created ? "新增能效对标 " : "修改能效对标 ") + saved.displayName()
                        + "（" + saved.getStandardNo() + "，" + saved.normScope() + "）"
                        + " 先进=" + saved.getAdvancedValue() + " 准入=" + saved.getAccessValue()
                        + " 限定=" + saved.getLimitValue());
        out.put("ok", true);
        out.put("message", "已保存，报表立即按新标杆计算");
        out.put("row", saved);
        out.put("warnings", warnings);
        return out;
    }

    /** 删除一条对标值 */
    @DeleteMapping("/benchmarks/{id}")
    public Map<String, Object> removeBenchmark(@PathVariable String id) {
        String bmName = benchmarkStore.list().stream()
                .filter(b -> id != null && id.equals(b.getId()))
                .map(EnergyBenchmark::displayName).findFirst().orElse(id);
        Map<String, Object> out = new LinkedHashMap<>();
        boolean removed = benchmarkStore.remove(id);
        if (removed) {
            audit.ok(AuditLog.CAT_ENERGY, AuditLog.ACT_DELETE, id, bmName,
                    "删除能效对标 " + bmName);
        }
        out.put("ok", removed);
        out.put("message", removed ? "已删除" : "没有找到这条对标值");
        return out;
    }

    /** 对标报表：实测单位产品能耗 vs 先进值/准入值/限定值 */
    @GetMapping("/benchmark-report")
    public Map<String, Object> benchmarkReport(@RequestParam(defaultValue = "month") String range) {
        return benchmarkService.report(range);
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
        audit.ok(AuditLog.CAT_ENERGY, AuditLog.ACT_PURGE, "energy", "能耗历史",
                "清空能耗历史 " + n + " 条（不可恢复）");
        out.put("ok", true);
        out.put("deleted", n);
        out.put("message", "已删除 " + n + " 条能耗记录。当前小时的实时数据会继续累积");
        return out;
    }
}
