package com.factory.hub.web;

import com.factory.hub.collect.CollectScheduler;
import com.factory.hub.collect.SnapshotStore;
import com.factory.hub.erp.ErpProdLine;
import com.factory.hub.erp.ErpProdLineRepository;
import com.factory.hub.model.DeviceEntity;
import com.factory.hub.model.PointDef;
import com.factory.hub.model.PointValue;
import com.factory.hub.service.DeviceService;
import com.factory.hub.web.dto.DeviceConfig;
import com.factory.hub.web.dto.DeviceUpsert;
import com.factory.hub.web.dto.DeviceView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 给前端管理后台用的接口。
 * 注意：对外集成请走 /open/v1/**，那条路径有 API Key 校验。
 */
@RestController
@RequestMapping("/api")
public class DeviceController {

    private final DeviceService deviceService;
    private final SnapshotStore store;
    private final CollectScheduler scheduler;
    private final ObjectMapper mapper;
    /** 设备列表要带"属于哪条产线、第几工位"，产线名从这张表查（只读，不反向依赖产线服务） */
    private final ErpProdLineRepository lineRepo;

    public DeviceController(DeviceService deviceService,
                            SnapshotStore store,
                            CollectScheduler scheduler,
                            ObjectMapper mapper,
                            ErpProdLineRepository lineRepo
                            ) {
        this.deviceService = deviceService;
        this.store = store;
        this.scheduler = scheduler;
        this.mapper = mapper;
        this.lineRepo = lineRepo;
    }

    @GetMapping("/devices")
    public List<DeviceView> list() {
        List<DeviceView> out = new ArrayList<>();
        for (DeviceEntity d : deviceService.all()) {
            out.add(toView(d));
        }
        return out;
    }

    @GetMapping("/devices/{code}")
    public ResponseEntity<DeviceView> one(@PathVariable String code) {
        DeviceEntity d = deviceService.byCode(code);
        if (d == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(toView(d));
    }

    /** 编辑用：读回完整配置（含点表 JSON）。列表接口不带点表，否则太大 */
    @GetMapping("/devices/{code}/config")
    public ResponseEntity<DeviceConfig> config(@PathVariable String code) {
        DeviceEntity d = deviceService.byCode(code);
        if (d == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(new DeviceConfig(
                d.getId(), d.getCode(), d.getName(), d.getLocation(), d.getProtocol(),
                d.getHost(), d.getPort(), d.getSerialPort(), d.getBaudRate(),
                d.getDataBits(), d.getStopBits(), d.getParity(), d.getUnitId(),
                d.getPollIntervalMs(), d.getServoBrand(), d.getTimeoutMs(), d.getOfflineAfterFails(),
                d.isApiKeyEnabled(),
                d.getEnabled(), d.getApiKey(), d.getPointsJson(),
                d.getProdSkuId(), d.getProdWhId(), d.getProdResetHour(), d.isProdAutoStockin()));
    }

    /** 新增或按 code 覆盖 */
    @PostMapping("/devices")
    public ResponseEntity<?> save(@RequestBody DeviceUpsert req) throws Exception {
        String err = validatePoints(req.points());
        if (err != null) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "message", err));
        }
        DeviceEntity d = deviceService.byCode(req.code());
        if (d == null) {
            d = new DeviceEntity();
        }
        apply(d, req);
        deviceService.save(d);
        scheduler.reload();
        return ResponseEntity.ok(toView(d));
    }

    @PutMapping("/devices/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody DeviceUpsert req)
            throws Exception {
        String err = validatePoints(req.points());
        if (err != null) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "message", err));
        }
        DeviceEntity d = deviceService.all().stream()
                .filter(x -> x.getId().equals(id))
                .findFirst()
                .orElse(null);
        if (d == null) {
            return ResponseEntity.notFound().build();
        }
        apply(d, req);
        deviceService.save(d);
        scheduler.reload();
        return ResponseEntity.ok(toView(d));
    }

    @DeleteMapping("/devices/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        DeviceEntity d = deviceService.all().stream()
                .filter(x -> x.getId().equals(id))
                .findFirst()
                .orElse(null);
        deviceService.delete(id);
        scheduler.reload();
        if (d != null) {
            store.clear(d.getCode());
        }
        return Map.of("ok", true);
    }

    /** 改完配置不想重启服务就调这个 */
    @PostMapping("/devices/reload")
    public Map<String, Object> reload() {
        scheduler.reload();
        return Map.of("ok", true, "running", scheduler.running());
    }

    @GetMapping("/devices/{code}/history")
    public Map<String, Object> history(@PathVariable String code,
                                       @RequestParam String point,
                                       @RequestParam(defaultValue = "180") int limit) {
        List<double[]> raw = store.history(code, point, limit);
        List<Map<String, Object>> list = new ArrayList<>();
        for (double[] r : raw) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ts", (long) r[0]);
            m.put("v", r[1]);
            list.add(m);
        }
        return Map.of("deviceCode", code, "point", point, "data", list);
    }

    /** 远程下发。演示用，真实项目要加权限控制 */
    @PostMapping("/devices/{code}/write")
    public Map<String, Object> write(@PathVariable String code, @RequestBody Map<String, Object> body) {
        Object pointKey = body.get("pointKey");
        Object value = body.get("value");
        if (pointKey == null) {
            return Map.of("ok", false, "message", "缺少 pointKey");
        }
        boolean ok = scheduler.write(code, String.valueOf(pointKey), value);
        return Map.of("ok", ok, "message", ok ? "已下发" : "下发失败，设备未在线或点不存在");
    }

    @GetMapping("/system/info")
    public Map<String, Object> info() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("running", scheduler.running());
        m.put("deviceCount", deviceService.all().size());
        m.put("time", System.currentTimeMillis());
        return m;
    }


    /**
     * 重置对外 API Key。旧 key 立即失效，对接方要同步换。
     * 用 SecureRandom 生成而不是 UUID：UUID 带固定版本位，长度也不好念，
     * 现场经常要人对着电话报 key。
     */
    @PostMapping("/devices/{code}/apikey")
    public ResponseEntity<?> resetApiKey(@PathVariable String code) {
        DeviceEntity d = deviceService.byCode(code);
        if (d == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("ok", false, "message", "设备不存在"));
        }
        String key = newKey(code);
        d.setApiKey(key);
        deviceService.save(d);
        return ResponseEntity.ok(Map.of("ok", true, "apiKey", key,
                "message", "已重置，旧 Key 立即失效"));
    }

    /** 启停对外接口。停用不影响采集，只挡 /open/v1/** */
    @PostMapping("/devices/{code}/apikey/toggle")
    public ResponseEntity<?> toggleApiKey(@PathVariable String code,
                                          @RequestBody Map<String, Object> body) {
        DeviceEntity d = deviceService.byCode(code);
        if (d == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("ok", false, "message", "设备不存在"));
        }
        boolean enabled = Boolean.TRUE.equals(body.get("enabled"));
        d.setApiKeyEnabled(enabled);
        deviceService.save(d);
        return ResponseEntity.ok(Map.of("ok", true, "apiKeyEnabled", enabled,
                "message", enabled ? "对外接口已启用" : "对外接口已停用（采集不受影响）"));
    }

    /** 生成 key：设备码前缀 + 24 位随机，既好认又猜不到 */
    private String newKey(String code) {
        String alphabet = "abcdefghijkmnpqrstuvwxyz23456789";   // 去掉容易看错的 l/o/0/1
        StringBuilder sb = new StringBuilder();
        java.security.SecureRandom rnd = new java.security.SecureRandom();
        for (int i = 0; i < 24; i++) {
            sb.append(alphabet.charAt(rnd.nextInt(alphabet.length())));
        }
        String prefix = code == null ? "dev" : code.toLowerCase().replaceAll("[^a-z0-9]", "");
        if (prefix.length() > 10) {
            prefix = prefix.substring(0, 10);
        }
        return prefix + "_" + sb;
    }

    private static final java.util.Set<Integer> VALID_FC = java.util.Set.of(1, 2, 3, 4);
    private static final java.util.Set<String> VALID_DT = java.util.Set.of("u16", "s16", "u32", "s32", "f32", "bool");
    private static final java.util.Set<String> VALID_ROLE = java.util.Set.of("status", "value", "count", "alarm", "power", "energy", "cost");

    /**
     * 点表校验。之前完全没有——负地址、非法功能码都能入库，
     * 脏点表会让这台设备的采集悄悄起不来（自测脚本 C4 抓出来的）。
     */
    private String validatePoints(java.util.List<com.factory.hub.model.PointDef> points) {
        if (points == null) {
            return null;
        }
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (int i = 0; i < points.size(); i++) {
            com.factory.hub.model.PointDef p = points.get(i);
            String at = "第 " + (i + 1) + " 个点";
            if (p == null || p.getKey() == null || p.getKey().isBlank()) {
                return at + "：key 不能为空";
            }
            if (!keys.add(p.getKey())) {
                return at + "：key「" + p.getKey() + "」重复";
            }
            if (p.getAddress() < 0) {
                return at + "（" + p.getKey() + "）：地址不能为负数";
            }
            if (!VALID_FC.contains(p.getFunctionCode())) {
                return at + "（" + p.getKey() + "）：功能码必须是 1/2/3/4";
            }
            if (p.getDataType() == null || !VALID_DT.contains(p.getDataType())) {
                return at + "（" + p.getKey() + "）：dataType 只能是 u16/s16/u32/s32/f32/bool";
            }
            if (p.getRole() != null && !p.getRole().isBlank() && !VALID_ROLE.contains(p.getRole())) {
                return at + "（" + p.getKey() + "）：role 只能是 status/value/count/alarm/power/energy/cost";
            }
        }
        return null;
    }

    private void apply(DeviceEntity d, DeviceUpsert req) throws Exception {
        d.setCode(req.code());
        d.setName(req.name());
        d.setLocation(req.location());
        d.setProtocol(req.protocol() == null ? "modbus-tcp" : req.protocol());
        d.setHost(req.host());
        d.setPort(req.port() == null ? 502 : req.port());
        d.setSerialPort(req.serialPort());
        d.setBaudRate(req.baudRate() == null ? 9600 : req.baudRate());
        d.setDataBits(req.dataBits() == null ? 8 : req.dataBits());
        d.setStopBits(req.stopBits() == null ? 1 : req.stopBits());
        d.setParity(req.parity() == null ? 0 : req.parity());
        d.setUnitId(req.unitId() == null ? 1 : req.unitId());
        d.setPollIntervalMs(req.pollIntervalMs() == null ? 1000 : req.pollIntervalMs());
        // 走 WiFi / 4G 的设备要把超时调大、离线阈值调高，这两个值必须能按设备配
        d.setTimeoutMs(req.timeoutMs() == null ? 800 : req.timeoutMs());
        d.setOfflineAfterFails(req.offlineAfterFails() == null ? 2 : req.offlineAfterFails());
        d.setServoBrand(req.servoBrand() == null || req.servoBrand().isBlank() ? null : req.servoBrand());
        d.setEnabled(req.enabled() == null || req.enabled());
        d.setApiKey(req.apiKey());
        d.setApiKeyEnabled(req.apiKeyEnabled() == null || req.apiKeyEnabled());
        d.setProdSkuId(req.prodSkuId());
        d.setProdWhId(req.prodWarehouseId());
        d.setProdResetHour(req.prodResetHour() == null ? 0 : Math.max(0, Math.min(23, req.prodResetHour())));
        d.setProdAutoStockin(req.prodAutoStockin() != null && req.prodAutoStockin());
        if (req.points() != null) {
            d.setPointsJson(mapper.writeValueAsString(req.points()));
        }
    }

    private DeviceView toView(DeviceEntity d) {
        SnapshotStore.Runtime rt = store.runtime(d.getCode());
        Map<String, PointValue> latest = store.latest(d.getCode());

        // 按点表配置顺序输出，前端不用排序
        List<PointValue> points = new ArrayList<>();
        for (PointDef p : deviceService.pointsOf(d)) {
            PointValue v = latest.get(p.getKey());
            points.add(v != null ? v : PointValue.bad(d.getCode(), p, System.currentTimeMillis()));
        }

        String lineName = null;
        if (d.getProdLineId() != null) {
            lineName = lineRepo.findById(d.getProdLineId()).map(ErpProdLine::getName).orElse(null);
        }

        return new DeviceView(
                d.getId(), d.getCode(), d.getName(), d.getLocation(), d.getProtocol(),
                d.getEnabled(), d.getHost(), d.getPort(), d.getSerialPort(), d.getBaudRate(),
                d.getParity(), d.getUnitId(), d.getPollIntervalMs(),
                d.getServoBrand(),
                d.getApiKey(), d.isApiKeyEnabled(),
                rt.online, rt.lastOkTs == 0 ? null : rt.lastOkTs, rt.lastError,
                rt.successCount, rt.failCount, rt.lastCostMs,
                d.getProdLineId(), lineName, d.getLineSeq(), points);
    }
}
