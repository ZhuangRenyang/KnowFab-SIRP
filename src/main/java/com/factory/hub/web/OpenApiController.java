package com.factory.hub.web;

import com.factory.hub.alarm.AlarmEvent;
import com.factory.hub.alarm.AlarmEventRepository;
import com.factory.hub.collect.SnapshotStore;
import com.factory.hub.screen.AlarmCodeDict;
import com.factory.hub.model.DeviceEntity;
import com.factory.hub.model.PointValue;
import com.factory.hub.service.DeviceService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 对外集成接口。每台设备一个独立路径 + 独立 API Key，
 * 客户要接 MES / ERP / 自己的小程序，直接给这一条接口，不暴露内部结构。
 * <p>
 * 调用方式：
 * GET /open/v1/CNC-01/status?key=xxxx
 * 或 header: X-Api-Key: xxxx
 */
@RestController
@RequestMapping("/open/v1")
public class OpenApiController {

    private final DeviceService deviceService;
    private final SnapshotStore store;
    private final AlarmEventRepository alarmRepo;
    private final AlarmCodeDict alarmDict;

    public OpenApiController(DeviceService deviceService, SnapshotStore store,
                             AlarmEventRepository alarmRepo, AlarmCodeDict alarmDict) {
        this.deviceService = deviceService;
        this.store = store;
        this.alarmRepo = alarmRepo;
        this.alarmDict = alarmDict;
    }

    /**
     * 某台设备的报警。MES 最常问的问题是"这台机器现在为什么停"，
     * 光有 status 点位（3=报警中）不够——要知道是哪条规则、什么时候开始的、触发值是多少。
     * <p>
     * open=true（默认）只返回未恢复的（含已确认）；open=false 返回最近 100 条历史，含已恢复。
     */
    @GetMapping("/{code}/alarms")
    public ResponseEntity<Map<String, Object>> alarms(@PathVariable String code,
                                                      @RequestParam(required = false) String key,
                                                      @RequestParam(required = false) String apiKey,
                                                      @RequestParam(defaultValue = "true") boolean open,
                                                      @RequestParam(defaultValue = "20") int limit,
                                                      @RequestHeader(value = "X-Api-Key", required = false) String headerKey) {
        DeviceEntity d = deviceService.byCode(code);
        if (d == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("ok", false, "message", "设备不存在: " + code));
        }
        if (!auth(d, firstOf(key, apiKey, headerKey))) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("ok", false, "message", "API Key 无效"));
        }

        List<AlarmEvent> list = open
                ? alarmRepo.findByDeviceCodeAndStateInOrderByStartedAtDesc(code,
                        List.of(AlarmEvent.STATE_ACTIVE, AlarmEvent.STATE_ACKED))
                : alarmRepo.findTop100ByDeviceCodeOrderByStartedAtDesc(code);
        int cap = Math.max(1, Math.min(100, limit));
        if (list.size() > cap) {
            list = list.subList(0, cap);
        }

        List<Map<String, Object>> items = new ArrayList<>();
        for (AlarmEvent e : list) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ruleName", e.getRuleName());
            m.put("ruleType", e.getRuleType());
            m.put("level", e.getLevel());
            m.put("levelText", levelText(e.getLevel()));
            m.put("message", e.getMessage());
            m.put("value", e.getValue());
            m.put("threshold", e.getThreshold());
            m.put("startedAt", e.getStartedAt());
            m.put("clearedAt", e.getClearedAt() > 0 ? e.getClearedAt() : null);
            boolean isOpen = !AlarmEvent.STATE_CLEARED.equals(e.getState());
            m.put("open", isOpen);
            m.put("acked", e.getAckAt() > 0);
            long end = e.getClearedAt() > 0 ? e.getClearedAt() : System.currentTimeMillis();
            m.put("durationMin", Math.max(0, (end - e.getStartedAt()) / 60_000));
            items.add(m);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("code", d.getCode());
        body.put("name", d.getName());
        body.put("openCount", items.stream().filter(i -> Boolean.TRUE.equals(i.get("open"))).count());
        // 伺服/PLC 报警字（按设备上配的品牌翻译），MES 判断"为什么停"时经常要先看这个
        try {
            body.put("plcWords", alarmDict.resolve(d.getServoBrand(),
                    deviceService.pointsOf(d), store.latest(code)).get("words"));
        } catch (Exception e) {
            body.put("plcWords", List.of());
        }
        body.put("items", items);
        return ResponseEntity.ok(body);
    }

    private static String levelText(String level) {
        if ("CRITICAL".equals(level)) return "严重";
        if ("MAJOR".equals(level)) return "重要";
        return "一般";
    }

    /** 只要状态和关键指标，最常用的一个接口 */
    @GetMapping("/{code}/status")
    public ResponseEntity<Map<String, Object>> status(@PathVariable String code,
                                                      @RequestParam(required = false) String key,
                                                      @RequestParam(required = false) String apiKey,
                                                      @RequestHeader(value = "X-Api-Key", required = false) String headerKey) {
        DeviceEntity d = deviceService.byCode(code);
        if (d == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("ok", false, "message", "设备不存在: " + code));
        }
        if (!auth(d, firstOf(key, apiKey, headerKey))) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("ok", false, "message", "API Key 无效"));
        }

        SnapshotStore.Runtime rt = store.runtime(code);
        Map<String, PointValue> latest = store.latest(code);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("code", d.getCode());
        body.put("name", d.getName());
        body.put("location", d.getLocation());
        body.put("online", rt.online);
        body.put("lastOkTs", rt.lastOkTs == 0 ? null : rt.lastOkTs);
        body.put("error", rt.lastError);

        // 扁平化：{"status": 2, "output": 1234, ...}，对接方最好处理
        Map<String, Object> values = new LinkedHashMap<>();
        for (PointValue v : latest.values()) {
            values.put(v.pointKey(), v.value());
        }
        body.put("values", values);
        return ResponseEntity.ok(body);
    }

    /** 带质量码和时间戳的完整数据 */
    @GetMapping("/{code}/values")
    public ResponseEntity<Map<String, Object>> values(@PathVariable String code,
                                                      @RequestParam(required = false) String key,
                                                      @RequestHeader(value = "X-Api-Key", required = false) String headerKey) {
        DeviceEntity d = deviceService.byCode(code);
        if (d == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("ok", false, "message", "设备不存在: " + code));
        }
        if (!auth(d, firstOf(key, headerKey))) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("ok", false, "message", "API Key 无效"));
        }
        return ResponseEntity.ok(Map.of(
                "ok", true,
                "code", code,
                "ts", System.currentTimeMillis(),
                "items", store.latest(code).values()));
    }

    /** 历史曲线，给第三方画图用 */
    @GetMapping("/{code}/history")
    public ResponseEntity<Map<String, Object>> history(@PathVariable String code,
                                                       @RequestParam String point,
                                                       @RequestParam(defaultValue = "180") int limit,
                                                       @RequestParam(required = false) String key,
                                                       @RequestHeader(value = "X-Api-Key", required = false) String headerKey) {
        DeviceEntity d = deviceService.byCode(code);
        if (d == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("ok", false, "message", "设备不存在: " + code));
        }
        if (!auth(d, firstOf(key, headerKey))) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("ok", false, "message", "API Key 无效"));
        }
        List<double[]> raw = store.history(code, point, limit);
        List<Map<String, Object>> data = new ArrayList<>();
        for (double[] r : raw) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ts", (long) r[0]);
            m.put("v", r[1]);
            data.add(m);
        }
        return ResponseEntity.ok(Map.of("ok", true, "code", code, "point", point, "data", data));
    }

    /** Key 三来源取第一个非空：query key → query apiKey → X-Api-Key 请求头（文档承诺过的）。 */
    private String firstOf(String... candidates) {
        for (String c : candidates) {
            if (c != null && !c.isBlank()) return c;
        }
        return null;
    }

    private boolean auth(DeviceEntity d, String key) {
        if (!d.isApiKeyEnabled()) {
            return false;                 // 后台停用了对外接口
        }
        String expect = d.getApiKey();
        // 没配 key 的设备不对外开放，避免忘了配就裸奔
        if (expect == null || expect.isBlank()) {
            return false;
        }
        return expect.equals(key);
    }

}
