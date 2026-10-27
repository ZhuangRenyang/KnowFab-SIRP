package com.factory.hub.demand;

import com.factory.hub.model.DeviceEntity;
import com.factory.hub.repo.DeviceRepository;
import com.factory.hub.service.DeviceService;
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
 * 演示用 15 分钟需量数据。
 * <p>
 * 没有历史需量，需量面板就是一条平线和 0，客户看不出「基本电费」这回事。
 * 灌 6 天之后，打开就能看到「下午两点冲到 470 kW，离合同 500 只差 30」——
 * 这个画面才有说服力。
 * <p>
 * 只给有 role=energy 点位的设备造（总电表），其他设备本来就没有电量。
 * 交付前把 {@code demand.demo-seed} 改成 false，再调 purge 清掉。
 */
@Component
@Order(4)
public class DemoDemandSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDemandSeeder.class);

    private static final int DAYS = 6;
    private static final long WINDOW_MS = 900_000L;

    private final SettingStore settings;
    private final DemandWindowRepository repo;
    private final DeviceRepository deviceRepo;
    private final DeviceService deviceService;

    public DemoDemandSeeder(SettingStore settings, DemandWindowRepository repo, DeviceRepository deviceRepo,
                            DeviceService deviceService) {
        this.settings = settings;
        this.repo = repo;
        this.deviceRepo = deviceRepo;
        this.deviceService = deviceService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!settings.getBool(SettingStore.DEMO_DEMAND)) {
            return;
        }
        try {
            if (repo.count() > 0) {
                return;
            }
            List<DeviceEntity> meters = new ArrayList<>();
            for (DeviceEntity d : deviceRepo.findByEnabledTrue()) {
                boolean hasEnergy = deviceService.pointsOf(d).stream()
                        .anyMatch(p -> "energy".equals(p.getRole()));
                if (hasEnergy) {
                    meters.add(d);
                }
            }
            if (meters.isEmpty()) {
                return;
            }
            List<DemandWindow> rows = seed(meters);
            repo.saveAll(rows);
            log.info("已生成 {} 天演示需量数据（{} 块电表，{} 行）。交付前请把 demand.demo-seed 改为 false",
                    DAYS, meters.size(), rows.size());
        } catch (Exception e) {
            log.warn("生成演示需量数据失败（不影响启动）: {}", e.getMessage());
        }
    }

    private List<DemandWindow> seed(List<DeviceEntity> meters) {
        ZoneId zone = ZoneId.systemDefault();
        Random rnd = new Random(20261026L);
        List<DemandWindow> rows = new ArrayList<>();
        LocalDate today = LocalDate.now();

        for (int d = DAYS; d >= 1; d--) {
            LocalDate day = today.minusDays(d);
            long dayStart = day.atStartOfDay(zone).toInstant().toEpochMilli();
            for (int i = 0; i < 96; i++) {
                long win = dayStart + i * WINDOW_MS;
                int hour = (i * 15) / 60;
                // 车间作息：白班高、夜班低，中午有个小回落（换班吃饭）
                double shape = switch (hour) {
                    case 0, 1, 2, 3, 4, 5 -> 0.28;
                    case 6, 7 -> 0.55;
                    case 8, 9, 10, 11 -> 0.92;
                    case 12 -> 0.62;
                    case 13, 14, 15, 16 -> 1.0;
                    case 17, 18 -> 0.78;
                    case 19, 20, 21 -> 0.6;
                    default -> 0.4;
                };
                for (DeviceEntity m : meters) {
                    int seed = Math.abs(m.getCode().hashCode());
                    double base = 60 + seed % 45;              // 这块表的基础负荷 kW
                    double kw = base * shape * (0.9 + rnd.nextDouble() * 0.2);
                    double kwh = kw / 4d;                      // 15 分钟的电量
                    rows.add(new DemandWindow(m.getCode(), win, round(kwh)));
                }
            }
        }
        return rows;
    }

    private double round(double v) {
        return Math.round(v * 1000d) / 1000d;
    }
}
