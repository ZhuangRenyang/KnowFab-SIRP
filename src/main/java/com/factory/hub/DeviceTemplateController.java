package com.factory.hub;

import com.factory.hub.model.DeviceEntity;
import com.factory.hub.model.DeviceTemplateEntity;
import com.factory.hub.repo.DeviceTemplateRepository;
import com.factory.hub.service.DeviceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 设备模板（2026-10-29）：把配好的一台设备存成模板，同型号设备直接套用。
 *
 * <p>设计口径：
 * <ul>
 *   <li><b>内置模板不入库</b>——西门子/台达/电表等随前端发布（deviceTemplates.ts），
 *       升级即生效，不需要现场维护数据；</li>
 *   <li><b>客户模板入库</b>——"配一台、存模板"是现场资产，可备份、可删改，
 *       接口里 source=custom 供前端和内置模板合并展示；</li>
 *   <li><b>从设备抓取</b>——{@code POST /from-device/{code}} 直接拿现成设备的
 *       协议/端口/从站/点表生成模板，避免让用户在表单里重填一遍。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/device-templates")
public class DeviceTemplateController {

    private final DeviceTemplateRepository repo;
    private final DeviceService deviceService;
    private final ObjectMapper mapper;

    public DeviceTemplateController(DeviceTemplateRepository repo, DeviceService deviceService,
                                    ObjectMapper mapper) {
        this.repo = repo;
        this.deviceService = deviceService;
        this.mapper = mapper;
    }

    /** 全部客户模板（新→旧）。内置模板在前端常量里，两者前端合并展示 */
    @GetMapping
    public Map<String, Object> list() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (DeviceTemplateEntity t : repo.findAllByOrderByIdDesc()) {
            items.add(view(t));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("items", items);
        return out;
    }

    /** 按当前表单内容存模板 */
    @PostMapping
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        String name = str(body.get("name"));
        if (name.isBlank()) {
            return fail("模板名不能为空");
        }
        if (repo.existsByName(name)) {
            return fail("模板名已存在：" + name);
        }
        String pointsJson = str(body.get("pointsJson"));
        String bad = checkPoints(pointsJson);
        if (bad != null) {
            return fail(bad);
        }
        DeviceTemplateEntity t = new DeviceTemplateEntity();
        t.setName(name);
        applyCommon(t, body);
        t.setPointsJson(pointsJson);
        t.setNote(str(body.get("note")));
        repo.save(t);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("message", "模板已保存：" + name);
        out.put("item", view(t));
        return out;
    }

    /**
     * 从现有设备抓取成模板：协议/端口/从站/采集周期/点表全部照抄，只让用户起个名字。
     * 点表里的 role（count/status/alarm）也一并带过来——这正是"配一次、复用多次"的价值。
     */
    @PostMapping("/from-device/{code}")
    public Map<String, Object> fromDevice(@PathVariable String code, @RequestBody(required = false) Map<String, Object> body) {
        DeviceEntity d = deviceService.byCode(code);
        if (d == null) {
            return fail("设备不存在：" + code);
        }
        String name = body == null ? "" : str(body.get("name"));
        if (name.isBlank()) {
            name = (d.getName() == null || d.getName().isBlank() ? d.getCode() : d.getName()) + " 模板";
        }
        if (repo.existsByName(name)) {
            return fail("模板名已存在：" + name);
        }
        String bad = checkPoints(d.getPointsJson());
        if (bad != null) {
            return fail("该设备点表不可用：" + bad);
        }
        DeviceTemplateEntity t = new DeviceTemplateEntity();
        t.setName(name);
        t.setBrand(str(body == null ? null : body.get("brand")));
        t.setModel(d.getName() == null ? d.getCode() : d.getName());
        t.setProtocol(d.getProtocol());
        t.setPort(d.getPort());
        t.setUnitId(d.getUnitId());
        t.setPollIntervalMs(d.getPollIntervalMs());
        t.setTimeoutMs(d.getTimeoutMs());
        // 扫网段建出来的设备可能没填串口参数（协议是 TCP），这里给 485 默认值兜底
        t.setSerialPort(d.getSerialPort() == null || d.getSerialPort().isBlank() ? "COM3" : d.getSerialPort());
        t.setBaudRate(d.getBaudRate() == null ? 9600 : d.getBaudRate());
        t.setDataBits(d.getDataBits() == null ? 8 : d.getDataBits());
        t.setStopBits(d.getStopBits() == null ? 1 : d.getStopBits());
        t.setParity(d.getParity() == null ? 0 : d.getParity());
        t.setPointsJson(d.getPointsJson());
        t.setNote(body == null ? "" : str(body.get("note")));
        repo.save(t);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("message", "已从设备 " + code + " 存为模板：" + name);
        out.put("item", view(t));
        return out;
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        DeviceTemplateEntity t = repo.findById(id).orElse(null);
        if (t == null) {
            return fail("模板不存在");
        }
        String name = str(body.get("name"));
        if (!name.isBlank() && !name.equals(t.getName())) {
            if (repo.existsByName(name)) {
                return fail("模板名已存在：" + name);
            }
            t.setName(name);
        }
        if (body.get("pointsJson") != null) {
            String bad = checkPoints(str(body.get("pointsJson")));
            if (bad != null) {
                return fail(bad);
            }
        }
        applyCommon(t, body);
        if (body.get("pointsJson") != null) {
            t.setPointsJson(str(body.get("pointsJson")));
        }
        if (body.get("note") != null) {
            t.setNote(str(body.get("note")));
        }
        t.setUpdatedAt(System.currentTimeMillis());
        repo.save(t);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("message", "模板已更新");
        out.put("item", view(t));
        return out;
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        if (!repo.existsById(id)) {
            return fail("模板不存在");
        }
        repo.deleteById(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("message", "模板已删除");
        return out;
    }

    /* ---------------- 内部 ---------------- */

    private void applyCommon(DeviceTemplateEntity t, Map<String, Object> body) {
        if (body.get("brand") != null) t.setBrand(str(body.get("brand")));
        if (body.get("model") != null) t.setModel(str(body.get("model")));
        String protocol = str(body.get("protocol"));
        if (!protocol.isBlank()) {
            if (!protocol.equals("modbus-tcp") && !protocol.equals("modbus-rtu-tcp")
                    && !protocol.equals("modbus-rtu")) {
                t.setProtocol("modbus-tcp");     // 兜底，不让脏协议进库
            } else {
                t.setProtocol(protocol);
            }
        }
        if (body.get("port") != null) t.setPort((int) num(body.get("port"), 502));
        if (body.get("unitId") != null) t.setUnitId((int) num(body.get("unitId"), 1));
        if (body.get("pollIntervalMs") != null) t.setPollIntervalMs((int) num(body.get("pollIntervalMs"), 1000));
        if (body.get("timeoutMs") != null) t.setTimeoutMs((int) num(body.get("timeoutMs"), 800));
        // 485 串口参数：波特率/数据位/停止位/校验基本是行业固定值，模板里一并存
        if (body.get("serialPort") != null) t.setSerialPort(str(body.get("serialPort")));
        if (body.get("baudRate") != null) t.setBaudRate((int) num(body.get("baudRate"), 9600));
        if (body.get("dataBits") != null) t.setDataBits((int) num(body.get("dataBits"), 8));
        if (body.get("stopBits") != null) t.setStopBits((int) num(body.get("stopBits"), 1));
        if (body.get("parity") != null) t.setParity((int) num(body.get("parity"), 0));
        t.setUpdatedAt(System.currentTimeMillis());
    }

    /** 点表必须是 JSON 数组且非空——库里存坏数据比没有更麻烦 */
    private String checkPoints(String json) {
        if (json == null || json.isBlank()) {
            return "点表不能为空";
        }
        try {
            var node = mapper.readTree(json);
            if (!node.isArray() || node.isEmpty()) {
                return "点表必须是非空 JSON 数组";
            }
        } catch (Exception e) {
            return "点表 JSON 格式错误：" + e.getMessage();
        }
        return null;
    }

    private Map<String, Object> view(DeviceTemplateEntity t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId());
        m.put("source", "custom");           // 前端据此与内置模板分组
        m.put("name", t.getName());
        m.put("brand", t.getBrand());
        m.put("model", t.getModel());
        m.put("protocol", t.getProtocol());
        m.put("port", t.getPort());
        m.put("unitId", t.getUnitId());
        m.put("pollIntervalMs", t.getPollIntervalMs());
        m.put("timeoutMs", t.getTimeoutMs());
        // 老模板（串口字段上线前存的）null 值按行业默认返回，前端拿到就能直接套
        m.put("serialPort", t.getSerialPort() == null ? "COM3" : t.getSerialPort());
        m.put("baudRate", t.getBaudRate() == null ? 9600 : t.getBaudRate());
        m.put("dataBits", t.getDataBits() == null ? 8 : t.getDataBits());
        m.put("stopBits", t.getStopBits() == null ? 1 : t.getStopBits());
        m.put("parity", t.getParity() == null ? 0 : t.getParity());
        m.put("pointsJson", t.getPointsJson());
        m.put("note", t.getNote());
        m.put("updatedAt", t.getUpdatedAt());
        return m;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private static double num(Object o, double def) {
        try {
            return o == null ? def : Double.parseDouble(String.valueOf(o));
        } catch (Exception e) {
            return def;
        }
    }

    private static Map<String, Object> fail(String msg) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        out.put("message", msg);
        return out;
    }
}
