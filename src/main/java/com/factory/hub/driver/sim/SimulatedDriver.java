package com.factory.hub.driver.sim;

import com.factory.hub.driver.DriverContext;
import com.factory.hub.driver.DriverFactory;
import com.factory.hub.driver.ProtocolDriver;
import com.factory.hub.driver.modbus.ModbusUtil;
import com.factory.hub.model.PointDef;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 模拟驱动。手上没有真设备时用它把整条链路跑通：
 * 采集 -> 快照 -> WebSocket -> 看板 -> 能耗统计 -> 电费。
 * <p>
 * 它还故意模拟了真实设备的行为：状态会循环切换、产量在运行时才累加、
 * 偶尔报警、电量随时段有峰谷差异。你拿它压测和演示都够用。
 */
public class SimulatedDriver implements ProtocolDriver {

    /**
     * 演示加速倍数。真实 1 秒的电量太小（9kW 的设备一秒才 0.0025 度），
     * 客户盯着屏幕看两分钟数字都不动，会以为系统卡了。
     * 这里把电量的增长加速 30 倍——只影响模拟驱动，接真设备完全不受影响。
     */
    private static final double SIM_SPEED = 30d;

    /** 单次补记上限，防止驱动停很久后一上来灌一大笔 */
    private static final long MAX_GAP_MS = 60_000L;

    private final Random random = new Random();
    private boolean connected;
    private int tick = 0;
    /** 设备状态机：0 停机 / 1 待机 / 2 运行 / 3 报警 */
    private int status = 2;
    private long outputCount = 0;
    private double temperature = 42.0;

    /** 模拟电表的累计电量，只增不减。负数表示还没初始化 */
    private double energyKwh = -1d;
    private long lastEnergyTs = 0L;

    @Override
    public String type() {
        return "sim";
    }

    @Override
    public void connect(DriverContext ctx) {
        this.connected = true;
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    @Override
    public Map<String, Object> read(DriverContext ctx, List<PointDef> points) {
        tick++;

        // 每 30 个周期换一次状态，模拟真实的换活/停机节奏
        if (tick % 30 == 0) {
            int r = random.nextInt(100);
            if (r < 55) {
                status = 2;
            } else if (r < 80) {
                status = 1;
            } else if (r < 92) {
                status = 0;
            } else {
                status = 3;
            }
        }

        long now = System.currentTimeMillis();
        if (energyKwh < 0) {
            energyKwh = 1200d + random.nextInt(800);   // 假装是一块跑了很久的老表
            lastEnergyTs = now;
        }
        double kw = powerKw();
        long gap = Math.min(now - lastEnergyTs, MAX_GAP_MS);
        if (gap > 0) {
            lastEnergyTs = now;
            energyKwh += kw * (gap / 3_600_000d) * SIM_SPEED;
        }

        if (status == 2) {
            outputCount += random.nextInt(3);
        }
        temperature += (random.nextDouble() - 0.45) * 0.6;
        temperature = Math.max(30.0, Math.min(85.0, temperature));

        Map<String, Object> result = new LinkedHashMap<>();
        for (PointDef p : points) {
            Object raw;
            if ("energy".equals(p.getRole())) {
                // 点表里电量的 scale 通常是 0.01（寄存器单位 0.01kWh）。
                // 这里把 kWh 反算回寄存器原始值，交给 applyScale 还原，
                // 这样客户用任何 scale 都能对上，和真实驱动的语义也一致。
                raw = Math.round(energyKwh / safeScale(p));
            } else if ("power".equals(p.getRole())) {
                raw = Math.round(kw / safeScale(p));
            } else {
                raw = simulate(p.getKey());
            }
            result.put(p.getKey(), ModbusUtil.applyScale(raw, p));
        }
        return result;
    }

    private double safeScale(PointDef p) {
        double s = p.getScale();
        return s == 0d ? 1d : s;
    }

    /**
     * 当前有功功率（kW）。白天负荷高、夜里低，
     * 这样跑一天下来峰谷分布图上能看出真实的用电习惯。
     */
    private double powerKw() {
        int hour = LocalTime.now().getHour();
        boolean daytime = hour >= 8 && hour < 18;
        double base = switch (status) {
            case 2 -> 9.0;      // 运行
            case 1 -> 1.2;      // 待机
            case 3 -> 2.5;      // 报警（还在通电，但没干活）
            default -> 0.25;    // 停机
        };
        return base * (daytime ? 1.0 : 0.45) * (0.9 + random.nextDouble() * 0.2);
    }

    private Object simulate(String key) {
        String k = key == null ? "" : key.toLowerCase();
        switch (k) {
            case "status":
                return status;
            case "output":
            case "count":
            case "parts":
                return outputCount;
            case "temperature":
            case "temp":
                return Math.round(temperature * 10) / 10.0;
            case "speed":
                return status == 2 ? 1200 + random.nextInt(300) : 0;
            case "current":
                return status == 2 ? 8.5 + random.nextDouble() * 2 : 0.3;
            case "voltage":
                return Math.round((219 + random.nextDouble() * 4) * 10) / 10.0;
            case "alarm":
                return status == 3 ? 1 : 0;
            case "cycle":
                return status == 2 ? 42 + random.nextInt(6) : 0;
            default:
                // 未知点给个随机数，保证链路不崩
                return random.nextInt(1000);
        }
    }

    @Override
    public void write(DriverContext ctx, PointDef point, Object value) {
        // 模拟驱动不写，直接忽略
    }

    @Override
    public void disconnect() {
        connected = false;
    }

    @Component
    public static class Factory implements DriverFactory {
        @Override
        public String type() {
            return "sim";
        }

        @Override
        public ProtocolDriver create() {
            return new SimulatedDriver();
        }
    }
}
