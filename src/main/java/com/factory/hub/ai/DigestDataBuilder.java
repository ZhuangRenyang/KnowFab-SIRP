package com.factory.hub.ai;

import com.factory.hub.alarm.AlarmEvent;
import com.factory.hub.alarm.AlarmEventRepository;
import com.factory.hub.collect.SnapshotStore;
import com.factory.hub.demand.DemandConfig;
import com.factory.hub.demand.DemandStore;
import com.factory.hub.energy.EnergyRecord;
import com.factory.hub.energy.EnergyRepository;
import com.factory.hub.model.DeviceEntity;
import com.factory.hub.report.ProdHourly;
import com.factory.hub.report.ProdHourlyRepository;
import com.factory.hub.service.DeviceService;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把「昨天」的情况压成一段紧凑的事实清单，喂给模型。
 * <p>
 * <b>为什么在本地算完再给模型：</b>
 * <ol>
 *   <li>几百台设备的明细塞不进上下文，也不该塞——模型算加减会出错，本地算百分百准</li>
 *   <li>模型只负责"把数字写成话"，这样即使它胡说，也只是措辞问题，不会给出错误的生产数据</li>
 *   <li>换模型、关模型都不影响早报内容的事实部分</li>
 * </ol>
 */
@Component
public class DigestDataBuilder {

    private static final ZoneId ZONE = ZoneId.systemDefault();

    private final DeviceService deviceService;
    private final SnapshotStore snapshot;
    private final AlarmEventRepository alarmRepo;
    private final ProdHourlyRepository prodRepo;
    private final EnergyRepository energyRepo;
    private final DemandStore demandStore;

    public DigestDataBuilder(DeviceService deviceService, SnapshotStore snapshot,
                             AlarmEventRepository alarmRepo, ProdHourlyRepository prodRepo,
                             EnergyRepository energyRepo, DemandStore demandStore) {
        this.deviceService = deviceService;
        this.snapshot = snapshot;
        this.alarmRepo = alarmRepo;
        this.prodRepo = prodRepo;
        this.energyRepo = energyRepo;
        this.demandStore = demandStore;
    }

    /** 早报的数据快照（不含任何 AI 生成内容） */
    public static class Facts {
        public String date;
        public int deviceTotal;
        public int online;
        public List<String> offlineTop = new ArrayList<>();
        public int alarmNew;
        public int alarmOpen;
        public int alarmCriticalOpen;
        public List<String> alarmTopDevices = new ArrayList<>();
        public double output;
        public double outputAvg7;
        public double stopHours;
        public int stopEvents;
        public double kwh;
        public double cost;
        public double kwhAvg7;
        public double demandPeak;
        public double contractDemand;
        public String peakAt = "--";

        /** 给模型的纯事实文本，不带任何指令 */
        public String toPromptText() {
            StringBuilder sb = new StringBuilder();
            sb.append("日期：").append(date).append('\n');
            sb.append("设备：共 ").append(deviceTotal).append(" 台，在线 ").append(online)
                    .append(" 台，离线 ").append(deviceTotal - online).append(" 台\n");
            if (!offlineTop.isEmpty()) {
                sb.append("离线设备：").append(String.join("、", offlineTop)).append('\n');
            }
            sb.append("报警：昨天新增 ").append(alarmNew).append(" 条；当前未处理 ").append(alarmOpen)
                    .append(" 条，其中严重 ").append(alarmCriticalOpen).append(" 条\n");
            if (!alarmTopDevices.isEmpty()) {
                sb.append("报警最多的设备：").append(String.join("、", alarmTopDevices)).append('\n');
            }
            sb.append("产量：昨天 ").append(fmt(output)).append(" 件；前 7 天日均 ")
                    .append(fmt(outputAvg7)).append(" 件\n");
            sb.append("停机：").append(fmt(stopHours)).append(" 小时，停机告警 ").append(stopEvents).append(" 次\n");
            sb.append("用电：昨天 ").append(fmt(kwh)).append(" kWh，电费 ").append(fmt(cost))
                    .append(" 元；前 7 天日均 ").append(fmt(kwhAvg7)).append(" kWh\n");
            sb.append("需量：本月最大 ").append(fmt(demandPeak)).append(" kW（").append(peakAt)
                    .append("），合同 ").append(fmt(contractDemand)).append(" kW\n");
            return sb.toString();
        }

        /** 本地模板用的摘要（不调模型时也能出早报） */
        public List<String> localLines() {
            List<String> out = new ArrayList<>();
            int offline = deviceTotal - online;
            out.add(offline == 0
                    ? "设备全部在线（共 " + deviceTotal + " 台）。"
                    : "设备 " + deviceTotal + " 台中 " + offline + " 台离线"
                      + (offlineTop.isEmpty() ? "。" : "：" + String.join("、", offlineTop) + "。"));
            out.add("昨天产量 " + fmt(output) + " 件，前 7 天日均 " + fmt(outputAvg7)
                    + " 件；停机 " + fmt(stopHours) + " 小时（" + stopEvents + " 次停机告警）。");
            out.add("昨天用电 " + fmt(kwh) + " kWh，电费 " + fmt(cost) + " 元；前 7 天日均 " + fmt(kwhAvg7) + " kWh。");
            out.add("报警：新增 " + alarmNew + " 条，未处理 " + alarmOpen + " 条（严重 " + alarmCriticalOpen + " 条）。");
            if (contractDemand > 0) {
                double ratio = demandPeak / contractDemand * 100;
                out.add("本月最大需量 " + fmt(demandPeak) + " kW，占合同 " + fmt(ratio) + "%"
                        + (ratio >= 90 ? "，已经贴近合同值，注意错峰。" : "，余量充足。"));
            }
            return out;
        }
    }

    public Facts build(LocalDate date) {
        Facts f = new Facts();
        f.date = date.toString();

        /* 设备在线情况 */
        List<DeviceEntity> devices = deviceService.all();
        f.deviceTotal = devices.size();
        for (DeviceEntity d : devices) {
            if (snapshot.runtime(d.getCode()).online) {
                f.online++;
            }
        }
        devices.stream()
                .filter(d -> !snapshot.runtime(d.getCode()).online)
                .sorted(Comparator.comparingLong(d -> snapshot.runtime(d.getCode()).lastOkTs))
                .limit(5)
                .forEach(d -> {
                    long last = snapshot.runtime(d.getCode()).lastOkTs;
                    f.offlineTop.add(d.getName() + "（最后正常 "
                            + (last > 0 ? fmtTime(last, "MM-dd HH:mm") : "从未连上") + "）");
                });

        /* 报警 */
        long from = date.atStartOfDay(ZONE).toInstant().toEpochMilli();
        long to = date.plusDays(1).atStartOfDay(ZONE).toInstant().toEpochMilli();
        List<AlarmEvent> events;
        try {
            events = alarmRepo.findByStartedAtBetween(from, to);
        } catch (Exception e) {
            events = List.of();
        }
        f.alarmNew = events.size();
        Map<String, Integer> byDevice = new LinkedHashMap<>();
        for (AlarmEvent e : events) {
            byDevice.merge(e.getDeviceName() == null ? e.getDeviceCode() : e.getDeviceName(), 1, Integer::sum);
        }
        byDevice.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(3)
                .forEach(e -> f.alarmTopDevices.add(e.getKey() + " " + e.getValue() + " 条"));
        f.alarmOpen = (int) (alarmRepo.countByState("ACTIVE") + alarmRepo.countByState("ACKED"));
        f.alarmCriticalOpen = (int) alarmRepo.countByStateAndLevel("ACTIVE", "CRITICAL");

        /* 产量与停机 */
        double output = 0, stopSec = 0;
        for (ProdHourly p : prodRepo.findByHourStartBetween(from, to)) {
            output += p.getOutput();
            stopSec += p.getStopSec();
        }
        f.output = output;
        f.stopHours = stopSec / 3600d;
        f.stopEvents = (int) events.stream()
                .filter(e -> "STOP".equals(e.getRuleType()) || "OFFLINE".equals(e.getRuleType()))
                .count();
        f.outputAvg7 = avgProd(date.minusDays(7), date.minusDays(1));

        /* 用电 */
        double kwh = 0, cost = 0;
        for (EnergyRecord r : energyRepo.findByHourStartBetween(from, to)) {
            kwh += r.getKwh();
            cost += r.getCost();
        }
        f.kwh = kwh;
        f.cost = cost;
        double kwh7 = 0;
        int days = 0;
        for (int i = 7; i >= 1; i--) {
            LocalDate d = date.minusDays(i);
            long a = d.atStartOfDay(ZONE).toInstant().toEpochMilli();
            long b = d.plusDays(1).atStartOfDay(ZONE).toInstant().toEpochMilli();
            double sum = 0;
            for (EnergyRecord r : energyRepo.findByHourStartBetween(a, b)) {
                sum += r.getKwh();
            }
            if (sum > 0) {
                kwh7 += sum;
                days++;
            }
        }
        f.kwhAvg7 = days == 0 ? 0 : kwh7 / days;

        /* 需量 */
        try {
            DemandConfig c = demandStore.current();
            f.contractDemand = c.getContractDemandKw();
            f.demandPeak = 0;                       // 由 DigestService 用 DemandService 补齐，避免循环依赖
        } catch (Exception ignored) {
        }
        return f;
    }

    private double avgProd(LocalDate from, LocalDate to) {
        double sum = 0;
        int days = 0;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            long a = d.atStartOfDay(ZONE).toInstant().toEpochMilli();
            long b = d.plusDays(1).atStartOfDay(ZONE).toInstant().toEpochMilli();
            double day = 0;
            for (ProdHourly p : prodRepo.findByHourStartBetween(a, b)) {
                day += p.getOutput();
            }
            if (day > 0) {
                sum += day;
                days++;
            }
        }
        return days == 0 ? 0 : sum / days;
    }

    private static String fmt(double v) {
        if (v == 0) {
            return "0";
        }
        double abs = Math.abs(v);
        if (abs >= 100) {
            return String.valueOf(Math.round(v));
        }
        return String.valueOf(Math.round(v * 10d) / 10d);
    }

    private static String fmtTime(long ts, String pattern) {
        return Instant.ofEpochMilli(ts).atZone(ZONE).format(DateTimeFormatter.ofPattern(pattern));
    }
}
