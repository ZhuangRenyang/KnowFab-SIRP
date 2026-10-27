package com.factory.hub.config;

import com.factory.hub.energy.EnergyRecord;
import com.factory.hub.energy.EnergyRepository;
import com.factory.hub.energy.EnergyRollup;
import com.factory.hub.energy.TariffPlan;
import com.factory.hub.energy.TariffSegment;
import com.factory.hub.energy.TariffStore;
import com.factory.hub.model.DeviceEntity;
import com.factory.hub.model.PointDef;
import com.factory.hub.repo.DeviceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import com.factory.hub.system.SettingStore;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 给演示环境灌 7 天的历史能耗数据。
 * <p>
 * <b>为什么需要这个：</b> 能耗功能是靠时间积累出价值的。刚装上的系统
 * 「今日电费」是个 0，趋势图是一条平线，峰谷分布只有一根柱子——
 * 客户根本看不出这东西有什么用，更不会为它加钱。
 * 灌 7 天数据之后，打开页面就能看到「3 号机比 2 号机多花 38% 电费」。
 * <p>
 * <b>正式交付前务必关掉</b>：application.yml 里 {@code energy.demo-seed: false}，
 * 或者调 {@code POST /api/energy/purge} 清空能耗表。
 * 只会在能耗表为空时生成，不会覆盖真实数据。
 */
@Component
@Order(2)
public class DemoEnergySeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoEnergySeeder.class);

    private static final int DAYS = 7;
    private static final long HOUR_MS = 3_600_000L;

    private final SettingStore settings;
    private final EnergyRepository energyRepo;
    private final DeviceRepository deviceRepo;
    private final TariffStore tariffStore;
    private final ObjectMapper mapper;

    public DemoEnergySeeder(SettingStore settings, EnergyRepository energyRepo,
                            DeviceRepository deviceRepo,
                            TariffStore tariffStore,
                            ObjectMapper mapper) {
        this.settings = settings;
        this.energyRepo = energyRepo;
        this.deviceRepo = deviceRepo;
        this.tariffStore = tariffStore;
        this.mapper = mapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!settings.getBool(SettingStore.DEMO_ENERGY)) {
            return;
        }
        try {
            seed();
        } catch (Exception e) {
            log.warn("生成演示能耗数据失败（不影响使用）: {}", e.getMessage());
        }
    }

    private void seed() {
        if (energyRepo.count() > 0) {
            return;
        }
        List<DeviceEntity> devices = new ArrayList<>();
        for (DeviceEntity d : deviceRepo.findAll()) {
            if (hasEnergyPoint(d)) {
                devices.add(d);
            }
        }
        if (devices.isEmpty()) {
            return;
        }

        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        long nowHour = System.currentTimeMillis() / HOUR_MS * HOUR_MS;
        TariffPlan plan = tariffStore.current();
        Random random = new Random(20261026L);

        List<EnergyRecord> rows = new ArrayList<>();
        for (DeviceEntity d : devices) {
            double baseKw = 3.0 + Math.abs(d.getCode().hashCode() % 8) * 1.5;
            for (int back = DAYS - 1; back >= 0; back--) {
                LocalDate day = today.minusDays(back);
                for (int h = 0; h < 24; h++) {
                    long hourStart = day.atStartOfDay(zone).toInstant().toEpochMilli() + h * HOUR_MS;
                    if (hourStart >= nowHour) {
                        continue;         // 当前小时交给实时采集，不伪造
                    }
                    double kwh = baseKw * dayFactor(h) * (0.85 + random.nextDouble() * 0.3);
                    TariffSegment seg = plan.segmentAt(hourStart);
                    String type = seg != null ? seg.getType() : EnergyRollup.SEG_UNPRICED;
                    double price = seg != null ? seg.getPrice() : 0d;
                    rows.add(new EnergyRecord(d.getCode(), hourStart, type, round(kwh, 3), price, 0d));
                }
            }
        }
        if (rows.isEmpty()) {
            return;
        }
        energyRepo.saveAll(rows);
        log.info("已生成 {} 条演示能耗数据（{} 天 × {} 台设备）。"
                        + "正式交付前请把 application.yml 的 energy.demo-seed 设为 false 并清空能耗表",
                rows.size(), DAYS, devices.size());
    }

    /** 白班满负荷、午休降一半、夜班只有保温功耗——这是大多数车间的真实曲线 */
    private double dayFactor(int hour) {
        if (hour >= 8 && hour < 12) {
            return 1.0;
        }
        if (hour >= 12 && hour < 14) {
            return 0.55;
        }
        if (hour >= 14 && hour < 18) {
            return 1.0;
        }
        if (hour >= 18 && hour < 22) {
            return 0.5;
        }
        return 0.15;
    }

    private boolean hasEnergyPoint(DeviceEntity d) {
        String json = d.getPointsJson();
        if (json == null || json.isBlank()) {
            return false;
        }
        try {
            for (PointDef p : mapper.readValue(json, PointDef[].class)) {
                if ("energy".equals(p.getRole())) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            // 点表坏了，这台设备不生成演示数据
        }
        return false;
    }

    private static double round(double v, int digits) {
        double p = Math.pow(10, digits);
        return Math.round(v * p) / p;
    }
}
