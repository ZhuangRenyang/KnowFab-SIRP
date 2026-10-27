package com.factory.hub.config;

import com.factory.hub.model.DeviceEntity;
import com.factory.hub.model.PointDef;
import com.factory.hub.repo.DeviceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 首次启动灌几条模拟数据，让你打开浏览器就能看到东西在动。
 * 数据库里已经有设备了就不会再插。
 * <p>
 * 必须用 ApplicationRunner 而不是 ApplicationReadyEvent ——
 * 后者和 CollectScheduler 是同一事件，顺序没保证，调度器可能先跑起来看到 0 台设备。
 * <p>
 * 加了能耗功能之后还多一件事：给老库里的模拟设备补上电量点，
 * 否则升级上来的人打开能耗页什么都看不到。
 * <b>只给 sim 设备补，真实设备一个点都不动</b>——真实设备的寄存器地址
 * 是现场核过的，随便加两个点去读不存在的寄存器会把采集搞挂。
 */
@Component
@Order(1)
public class DataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private final DeviceRepository repo;
    private final ObjectMapper mapper;

    public DataInitializer(DeviceRepository repo, ObjectMapper mapper) {
        this.repo = repo;
        this.mapper = mapper;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        seed();
        migrateSimDevices();
    }

    private void seed() throws Exception {
        if (repo.count() > 0) {
            return;
        }
        log.info("首次启动，写入示例设备（模拟数据，不接硬件也能跑）");

        repo.save(build("CNC-01", "一号加工中心", "一车间 A 区", "sim", 1000, sampleCnc()));
        repo.save(build("INJ-01", "一号注塑机", "二车间 B 区", "sim", 1500, sampleInjection()));
        repo.save(build("PRESS-01", "315T 冲压机", "一车间 C 区", "sim", 800, samplePress()));
        repo.save(build("METER-01", "一车间总电表", "配电房", "sim", 2000, sampleMeter()));
    }

    /** 老库升级：模拟设备没有电量点就补上，让能耗页有数据可看 */
    private void migrateSimDevices() throws Exception {
        int changed = 0;
        for (DeviceEntity d : repo.findAll()) {
            if (!"sim".equalsIgnoreCase(d.getProtocol())) {
                continue;
            }
            List<PointDef> pts = readPoints(d);
            if (pts.isEmpty()) {
                continue;
            }
            boolean hasEnergy = pts.stream().anyMatch(p -> "energy".equals(p.getRole()));
            if (hasEnergy) {
                continue;
            }
            int next = 0;
            for (PointDef p : pts) {
                next = Math.max(next, p.getAddress() + p.registerCount());
            }
            pts.add(point("power", "有功功率", next, "u16", "kW", "power", 2, 0.01));
            pts.add(point("energy", "累计电量", next + 1, "u32", "kWh", "energy", 2, 0.01));
            d.setPointsJson(mapper.writeValueAsString(pts));
            repo.save(d);
            changed++;
            log.info("设备 {} 已补充电量点（power@{}, energy@{}）", d.getCode(), next, next + 1);
        }
        if (changed > 0) {
            log.info("共为 {} 台模拟设备补上电量点。接真实电表时，"
                    + "请把 energy 点改到电表的累计电量寄存器，role 必须是 energy", changed);
        }
        seedDemoMeter();
    }

    /**
     * 演示环境专用的补充：老库升上来只有加工设备、没有电表，
     * 能耗页能算钱但看不到「柜里加一块表」这种接入方式。
     * <p>
     * 只在「全部设备都是模拟设备」且没有电表设备时补一台，
     * 真实项目里 protocol 不可能是 sim，所以绝不会污染客户数据。
     */
    private void seedDemoMeter() throws Exception {
        List<DeviceEntity> all = repo.findAll();
        if (all.isEmpty() || all.size() > 6) {
            return;
        }
        boolean allSim = all.stream().allMatch(d -> "sim".equalsIgnoreCase(d.getProtocol()));
        boolean hasMeter = all.stream().anyMatch(d -> d.getCode().toUpperCase().startsWith("METER-"));
        if (!allSim || hasMeter) {
            return;
        }
        repo.save(build("METER-01", "一车间总电表", "配电房", "sim", 2000, sampleMeter()));
        log.info("演示环境已补充「METER-01 一车间总电表」——这是现场最省事的接入方式："
                + "不改机器，只在电控柜里加一块多功能电表，走 485 读电量");
    }

    private List<PointDef> readPoints(DeviceEntity d) {
        List<PointDef> list = new ArrayList<>();
        String json = d.getPointsJson();
        if (json == null || json.isBlank()) {
            return list;
        }
        try {
            for (PointDef p : mapper.readValue(json, PointDef[].class)) {
                if (p.getKey() != null && !p.getKey().isBlank()) {
                    list.add(p);
                }
            }
        } catch (Exception e) {
            log.warn("设备 {} 点表解析失败，跳过迁移: {}", d.getCode(), e.getMessage());
        }
        return list;
    }

    private DeviceEntity build(String code, String name, String location,
                               String protocol, int pollMs, List<PointDef> points) throws Exception {
        DeviceEntity d = new DeviceEntity();
        d.setCode(code);
        d.setName(name);
        d.setLocation(location);
        d.setProtocol(protocol);
        d.setPollIntervalMs(pollMs);
        d.setEnabled(true);
        d.setUnitId(1);
        d.setApiKey("key-" + code.toLowerCase());
        d.setPointsJson(mapper.writeValueAsString(points));
        return d;
    }

    private PointDef point(String key, String name, int address, String dataType,
                           String unit, String role, int decimals) {
        return point(key, name, address, dataType, unit, role, decimals, 1.0);
    }

    private PointDef point(String key, String name, int address, String dataType,
                           String unit, String role, int decimals, double scale) {
        PointDef p = new PointDef();
        p.setKey(key);
        p.setName(name);
        p.setAddress(address);
        p.setFunctionCode(3);
        p.setDataType(dataType);
        p.setUnit(unit);
        p.setRole(role);
        p.setDecimals(decimals);
        p.setScale(scale);
        return p;
    }

    private List<PointDef> sampleCnc() {
        List<PointDef> list = new ArrayList<>();
        list.add(point("status", "运行状态", 0, "u16", "", "status", 0));
        list.add(point("speed", "主轴转速", 1, "u16", "rpm", "value", 0));
        list.add(point("temperature", "主轴温度", 2, "s16", "℃", "value", 1));
        list.add(point("output", "累计产量", 4, "u32", "件", "count", 0));
        list.add(point("alarm", "报警标志", 6, "u16", "", "alarm", 0));
        list.add(point("power", "有功功率", 7, "u16", "kW", "power", 2, 0.01));
        list.add(point("energy", "累计电量", 8, "u32", "kWh", "energy", 2, 0.01));
        return list;
    }

    private List<PointDef> sampleInjection() {
        List<PointDef> list = new ArrayList<>();
        list.add(point("status", "运行状态", 0, "u16", "", "status", 0));
        list.add(point("cycle", "成型周期", 1, "u16", "s", "value", 1));
        list.add(point("temperature", "料筒温度", 2, "s16", "℃", "value", 1));
        list.add(point("output", "模次计数", 4, "u32", "模", "count", 0));
        list.add(point("alarm", "报警标志", 6, "u16", "", "alarm", 0));
        list.add(point("power", "有功功率", 7, "u16", "kW", "power", 2, 0.01));
        list.add(point("energy", "累计电量", 8, "u32", "kWh", "energy", 2, 0.01));
        return list;
    }

    private List<PointDef> samplePress() {
        List<PointDef> list = new ArrayList<>();
        list.add(point("status", "运行状态", 0, "u16", "", "status", 0));
        list.add(point("count", "冲次计数", 1, "u32", "次", "count", 0));
        list.add(point("current", "主电机电流", 3, "u16", "A", "value", 1));
        list.add(point("alarm", "报警标志", 5, "u16", "", "alarm", 0));
        list.add(point("power", "有功功率", 6, "u16", "kW", "power", 2, 0.01));
        list.add(point("energy", "累计电量", 7, "u32", "kWh", "energy", 2, 0.01));
        return list;
    }

    /** 独立电表：现场最常见的接入方式——不动机器，柜里加一块多功能电表 */
    private List<PointDef> sampleMeter() {
        List<PointDef> list = new ArrayList<>();
        list.add(point("voltage", "电压", 0, "u16", "V", "value", 1, 0.1));
        list.add(point("current", "电流", 1, "u16", "A", "value", 2, 0.01));
        list.add(point("power", "有功功率", 2, "u32", "kW", "power", 3, 0.001));
        list.add(point("energy", "累计电量", 4, "u32", "kWh", "energy", 2, 0.01));
        return list;
    }
}
