package com.factory.hub.report;

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
 * 给演示环境灌 7 天的生产数据。
 * <p>
 * <b>为什么需要：</b> 报表是靠时间积累出价值的。刚装上的系统，报表页一片 0，
 * 客户看不出这东西能干什么。灌 7 天之后，一打开就能看到
 * 「3 号机早班比夜班多产 30%」「停机 47 分钟，原因全是等料」——
 * 这才是能让他签字验收的画面。
 * <p>
 * <b>正式交付前务必关掉</b>：{@code report.demo-seed: false}，
 * 或者调 {@code POST /api/reports/purge {"confirm":true}}。
 * 只在表为空时生成，绝不覆盖真实数据。
 */
@Component
@Order(3)
public class DemoProdSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoProdSeeder.class);

    private static final int DAYS = 7;
    private static final long HOUR_MS = 3_600_000L;

    private final SettingStore settings;
    private final ProdHourlyRepository repo;
    private final DeviceRepository deviceRepo;
    private final DeviceService deviceService;

    public DemoProdSeeder(SettingStore settings, ProdHourlyRepository repo, DeviceRepository deviceRepo,
                          DeviceService deviceService) {
        this.settings = settings;
        this.repo = repo;
        this.deviceRepo = deviceRepo;
        this.deviceService = deviceService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!settings.getBool(SettingStore.DEMO_REPORT)) {
            return;
        }
        try {
            if (repo.count() > 0) {
                return;
            }
            List<DeviceEntity> devices = deviceRepo.findByEnabledTrue();
            if (devices.isEmpty()) {
                return;
            }
            List<ProdHourly> rows = seed(devices);
            repo.saveAll(rows);
            log.info("已生成 {} 天演示生产数据（{} 台设备，{} 行）。交付前请把 report.demo-seed 改为 false",
                    DAYS, devices.size(), rows.size());
        } catch (Exception e) {
            log.warn("生成演示生产数据失败（不影响启动）: {}", e.getMessage());
        }
    }

    private List<ProdHourly> seed(List<DeviceEntity> devices) {
        ZoneId zone = ZoneId.systemDefault();
        Random rnd = new Random(20261026L);          // 固定种子，每次装出来一样，方便对账演示
        List<ProdHourly> rows = new ArrayList<>();
        LocalDate today = LocalDate.now();

        for (int d = DAYS - 1; d >= 1; d--) {         // 今天不生成，让实时数据自己跑出来
            LocalDate day = today.minusDays(d);
            for (DeviceEntity dev : devices) {
                // 每台设备给一个固定的「性格」：有的快有的慢，有的爱停机
                int seed = Math.abs(dev.getCode().hashCode());
                double baseRate = 40 + seed % 50;               // 每小时理论产量
                double stopRatio = 0.04 + (seed % 7) * 0.02;    // 停机占比
                double idleRatio = 0.10 + (seed % 5) * 0.03;    // 待机占比
                // 电表、水表这种没有产量点，别给它造产量——报表里出现「总电表产 1265 件」
                // 客户一眼就看出数据是编的，整份报表的可信度就没了
                boolean hasCount = deviceService.pointsOf(dev).stream()
                        .anyMatch(p -> "count".equals(p.getRole()));

                for (int h = 0; h < 24; h++) {
                    long hourStart = day.atStartOfDay(zone).toInstant().toEpochMilli() + h * HOUR_MS;

                    // 夜班（0-7 点）产量天然低一点，这才像真厂
                    double nightFactor = (h < 7) ? 0.55 : 1.0;
                    double runRatio = Math.max(0.15,
                            1.0 - stopRatio - idleRatio - rnd.nextDouble() * 0.08);

                    double runSec = 3600 * runRatio;
                    double idleSec = 3600 * (idleRatio + rnd.nextDouble() * 0.05);
                    double stopSec = 3600 * stopRatio;
                    double sum = runSec + idleSec + stopSec;
                    if (sum > 3600) {                           // 归一化，不能出现一小时 3700 秒
                        double k = 3600 / sum;
                        runSec *= k;
                        idleSec *= k;
                        stopSec *= k;
                    }
                    double alarmSec = rnd.nextInt(10) == 0 ? 60 + rnd.nextInt(300) : 0;

                    ProdHourly p = new ProdHourly(dev.getCode(), dev.getName(), hourStart);
                    p.setRunSec(round(runSec));
                    p.setIdleSec(round(idleSec));
                    p.setStopSec(round(stopSec));
                    p.setAlarmSec(round(alarmSec));
                    p.setUnknownSec(0);
                    p.setOutput(hasCount ? Math.round(runSec / 3600d * baseRate * nightFactor) : 0);
                    rows.add(p);
                }
            }
        }
        return rows;
    }

    private double round(double v) {
        return Math.round(v * 10d) / 10d;
    }
}
