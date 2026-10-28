package com.factory.hub.screen;

import com.factory.hub.alarm.AlarmEvent;
import com.factory.hub.alarm.AlarmEventRepository;
import com.factory.hub.collect.SnapshotStore;
import com.factory.hub.model.DeviceEntity;
import com.factory.hub.model.PointDef;
import com.factory.hub.model.PointValue;
import com.factory.hub.report.ReportService;
import com.factory.hub.service.DeviceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

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
 * 车间大屏的数据快照。
 * <p>
 * 大屏是<b>只读聚合视图</b>，只输出"能挂在墙上给所有人看"的东西：
 * 台数、产量、停机、报警、故障维修卡。设备点表全文、API Key、配置一概不出现——
 * 这块屏幕不登录就能看，漏出去等于把现场拓扑公开了。
 * <p>
 * 一次请求返回整屏数据，而不是前端发五六个请求：大屏常年开着、每 5 秒刷一次，
 * 五六个请求意味着一天几十万次调用，还容易出现各块数字对不上的瞬间。
 */
@Service
public class ScreenService {

    private static final Logger log = LoggerFactory.getLogger(ScreenService.class);
    private static final ZoneId ZONE = ZoneId.systemDefault();
    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter HMS = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** 3 秒缓存：两块屏同时开时不会把数据库打两遍，也不影响"看起来是实时" */
    private static final long CACHE_MS = 3000L;

    private final DeviceService deviceService;
    private final SnapshotStore snapshot;
    private final ReportService reportService;
    private final AlarmEventRepository alarmRepo;
    private final FaultAdvisor advisor;
    private final AlarmCodeDict alarmDict;

    private volatile Map<String, Object> cache;
    private volatile long cacheAt;

    private final com.factory.hub.erp.ErpLineService lineService;

    public ScreenService(DeviceService deviceService, SnapshotStore snapshot,
                         ReportService reportService, AlarmEventRepository alarmRepo,
                         FaultAdvisor advisor, AlarmCodeDict alarmDict,
                         com.factory.hub.erp.ErpLineService lineService) {
        this.deviceService = deviceService;
        this.snapshot = snapshot;
        this.reportService = reportService;
        this.alarmRepo = alarmRepo;
        this.advisor = advisor;
        this.alarmDict = alarmDict;
        this.lineService = lineService;
    }

    public Map<String, Object> snapshot() {
        long now = System.currentTimeMillis();
        Map<String, Object> c = cache;
        if (c != null && now - cacheAt < CACHE_MS) {
            return c;
        }
        Map<String, Object> out = build(now);
        cache = out;
        cacheAt = now;
        return out;
    }

    private Map<String, Object> build(long now) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("now", now);
        out.put("clock", Instant.ofEpochMilli(now).atZone(ZONE).format(HMS));
        out.put("date", LocalDate.now(ZONE).format(YMD));

        Devices dev = devices();
        out.put("device", dev.counts);
        out.put("deviceList", dev.rows);

        out.put("prod", production());
        // 产线产量：没有配产线时返回空数组，大屏对应的一条不显示（不能为了新功能把老屏幕顶乱）
        try {
            out.put("lines", lineService.brief());
        } catch (Exception e) {
            log.warn("大屏读取产线失败：{}", e.getMessage());
            out.put("lines", List.of());
        }
        out.put("hours", hours());
        out.put("stopReasons", stopReasons());

        List<AlarmEvent> open = fetchOpen();
        out.put("alarms", alarms(now, open));
        out.put("faults", faults(now, open, dev.entities));
        return out;
    }

    private List<AlarmEvent> fetchOpen() {
        try {
            return alarmRepo.findOpen();
        } catch (Exception e) {
            log.warn("大屏读取报警失败：{}", e.getMessage());
            return List.of();
        }
    }

    /* ==================== 设备状态 ==================== */

    private static final class Devices {
        Map<String, Object> counts = new LinkedHashMap<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, DeviceEntity> entities = new LinkedHashMap<>();
    }

    /**
     * 状态码沿用采集侧约定：0 停机 / 1 待机 / 2 运行 / 3 报警，离线单独算。
     * 没采到状态点的设备按"在线"处理（跟主界面一致），不然大屏上会有一片假停机。
     */
    private Devices devices() {
        Devices d = new Devices();
        int run = 0, idle = 0, stop = 0, alarm = 0, offline = 0, online = 0;
        List<DeviceEntity> list;
        try {
            list = deviceService.enabledDevices();
        } catch (Exception e) {
            log.warn("大屏读取设备列表失败：{}", e.getMessage());
            list = List.of();
        }

        for (DeviceEntity e : list) {
            boolean isOnline = snapshot.runtime(e.getCode()).online;
            int st = -1;
            if (isOnline) {
                online++;
                st = statusOf(e.getCode());
            } else {
                offline++;
            }
            d.entities.put(e.getCode(), e);

            String state;
            String text;
            if (!isOnline) {
                state = "offline";
                text = "离线";
            } else {
                switch (st) {
                    case 0 -> { state = "stop"; text = "停机"; stop++; }
                    case 1 -> { state = "idle"; text = "待机"; idle++; }
                    case 3 -> { state = "alarm"; text = "报警"; alarm++; }
                    default -> { state = "run"; text = "运行"; run++; }
                }
            }

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", e.getCode());
            row.put("name", e.getName() == null ? e.getCode() : e.getName());
            row.put("state", state);
            row.put("text", text);
            d.rows.add(row);
        }

        int total = list.size();
        d.counts.put("total", total);
        d.counts.put("online", online);
        d.counts.put("offline", offline);
        d.counts.put("run", run);
        d.counts.put("idle", idle);
        d.counts.put("stop", stop);
        d.counts.put("alarm", alarm);
        d.counts.put("onlineRate", total == 0 ? 0 : Math.round(online * 1000d / total) / 10d);
        return d;
    }

    /** 取设备的 status 点位；取不到返回 2（运行），避免把"没配状态点"显示成停机 */
    private int statusOf(String code) {
        try {
            Map<String, PointValue> pts = snapshot.latest(code);
            if (pts == null) {
                return 2;
            }
            PointValue pv = pts.get("status");
            if (pv == null || pv.value() == null) {
                return 2;
            }
            // PointValue.value() 是 Object（不同类型点位共用），这里只接受数值
            return pv.value() instanceof Number n ? (int) Math.round(n.doubleValue()) : 2;
        } catch (Exception e) {
            return 2;
        }
    }

    /* ==================== 产量 / 停机 ==================== */

    @SuppressWarnings("unchecked")
    private Map<String, Object> production() {
        Map<String, Object> m = new LinkedHashMap<>();
        try {
            Map<String, Object> daily = reportService.daily(LocalDate.now(ZONE));
            Map<String, Object> totals = (Map<String, Object>) daily.getOrDefault("totals", Map.of());
            List<Map<String, Object>> reasons =
                    (List<Map<String, Object>>) daily.getOrDefault("stopReasons", List.of());

            int stopCount = 0;
            double stopMin = 0;
            for (Map<String, Object> r : reasons) {
                stopCount += num(r.get("count")).intValue();
                stopMin += num(r.get("totalMin")).doubleValue();
            }

            double out = num(totals.get("output")).doubleValue();
            double avail = num(totals.get("availability")).doubleValue();

            m.put("output", Math.round(out));
            // 稼动率算不出来时报表给 -1，大屏要显示"--"而不是 -1%
            m.put("availability", avail < 0 ? null : avail);
            m.put("stopSec", num(totals.get("stopSec")).doubleValue());
            m.put("runSec", num(totals.get("runSec")).doubleValue());
            m.put("stopCount", stopCount);
            m.put("stopMin", Math.round(stopMin));
            m.put("planName", daily.getOrDefault("planName", ""));
            m.put("hasData", out > 0 || num(totals.get("countedSec")).doubleValue() > 0);
        } catch (Exception e) {
            log.warn("大屏读取产量失败：{}", e.getMessage());
            m.put("output", 0);
            m.put("availability", null);
            m.put("stopSec", 0);
            m.put("stopCount", 0);
            m.put("stopMin", 0);
            m.put("hasData", false);
        }
        return m;
    }

    /** 今日每小时产量，零点到当前小时，缺的补 0（停机就是 0，不是没数据） */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> hours() {
        List<Map<String, Object>> rows = new ArrayList<>();
        try {
            Map<String, Object> daily = reportService.daily(LocalDate.now(ZONE));
            List<Map<String, Object>> hours =
                    (List<Map<String, Object>>) daily.getOrDefault("hours", List.of());
            int nowHour = java.time.LocalTime.now(ZONE).getHour();
            for (Map<String, Object> h : hours) {
                int hh = num(h.get("hour")).intValue();
                if (hh > nowHour) {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("h", String.format("%02d", hh));
                row.put("output", Math.round(num(h.get("output")).doubleValue()));
                rows.add(row);
            }
        } catch (Exception e) {
            log.warn("大屏读取小时产量失败：{}", e.getMessage());
        }
        return rows;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> stopReasons() {
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            Map<String, Object> daily = reportService.daily(LocalDate.now(ZONE));
            List<Map<String, Object>> reasons =
                    (List<Map<String, Object>>) daily.getOrDefault("stopReasons", List.of());
            for (Map<String, Object> r : reasons) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("reason", String.valueOf(r.getOrDefault("reason", "未命名")));
                row.put("count", num(r.get("count")).intValue());
                row.put("totalMin", Math.round(num(r.get("totalMin")).doubleValue()));
                out.add(row);
                if (out.size() >= 6) {
                    break;
                }
            }
        } catch (Exception e) {
            log.warn("大屏读取停机原因失败：{}", e.getMessage());
        }
        return out;
    }

    /* ==================== 报警 ==================== */

    private Map<String, Object> alarms(long now, List<AlarmEvent> open) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> items = new ArrayList<>();
        int critical = 0, major = 0, minor = 0;

        for (AlarmEvent e : open) {
            switch (e.getLevel() == null ? "" : e.getLevel()) {
                case "CRITICAL" -> critical++;
                case "MAJOR" -> major++;
                default -> minor++;
            }
        }
        // 严重的排前面，同级按开始时间倒序
        List<AlarmEvent> sorted = new ArrayList<>(open);
        sorted.sort((a, b) -> {
            int la = levelRank(a.getLevel());
            int lb = levelRank(b.getLevel());
            if (la != lb) {
                return Integer.compare(la, lb);
            }
            return Long.compare(b.getStartedAt(), a.getStartedAt());
        });

        for (AlarmEvent e : sorted) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("device", e.getDeviceName() == null ? e.getDeviceCode() : e.getDeviceName());
            row.put("code", e.getDeviceCode());
            row.put("rule", e.getRuleName());
            row.put("level", e.getLevel());
            row.put("levelText", levelText(e.getLevel()));
            row.put("since", e.getStartedAt() > 0
                    ? Instant.ofEpochMilli(e.getStartedAt()).atZone(ZONE).format(HHMM) : "");
            long mins = e.getStartedAt() > 0 ? Math.max(0, (now - e.getStartedAt()) / 60_000) : 0;
            row.put("minutes", mins);
            row.put("acked", e.getAckAt() > 0);
            items.add(row);
            if (items.size() >= 14) {
                break;
            }
        }

        out.put("open", open.size());
        out.put("critical", critical);
        out.put("major", major);
        out.put("minor", minor);
        out.put("items", items);
        return out;
    }

    /* ==================== 故障维修卡 ==================== */

    /**
     * 有未恢复报警的设备，每台出一张「维修卡」：
     * 报警明细 + 通讯/端口信息 + 伺服/PLC 报警字 + AI（或本地）故障分析。
     * <p>
     * 只有<b>真实存在于设备列表</b>的编码才出卡——「全厂 PLANT」这类聚合主体
     * 是需量告警的虚拟主体，没有端口可查，混进来会让维修工找一台不存在的机器。
     */
    private List<Map<String, Object>> faults(long now, List<AlarmEvent> open,
                                             Map<String, DeviceEntity> entities) {
        Map<String, List<AlarmEvent>> byDevice = new LinkedHashMap<>();
        for (AlarmEvent e : open) {
            String code = e.getDeviceCode();
            if (code != null && entities.containsKey(code)) {
                byDevice.computeIfAbsent(code, k -> new ArrayList<>()).add(e);
            }
        }

        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, List<AlarmEvent>> en : byDevice.entrySet()) {
            String code = en.getKey();
            DeviceEntity d = entities.get(code);
            List<AlarmEvent> events = en.getValue();

            // 通讯 / 端口信息：维修第一件事是确认"链路通不通"
            SnapshotStore.Runtime rt = snapshot.runtime(code);
            Map<String, Object> comm = new LinkedHashMap<>();
            comm.put("protocol", d.getProtocol());
            comm.put("target", targetOf(d));
            comm.put("unitId", d.getUnitId());
            comm.put("pollMs", d.getPollIntervalMs());
            comm.put("timeoutMs", d.getTimeoutMs());
            comm.put("online", rt.online);
            comm.put("lastError", rt.lastError);

            // 伺服 / PLC 报警字：role=alarm 的点位，非零走字典翻译成「名字+原因+处理」。
            // 没配品牌 / 字典没收录的码，known=false，前端显示原始码并提示查手册。
            List<Map<String, Object>> plc = new ArrayList<>();
            boolean plcHasPoint = false;
            Map<String, Object> values = new LinkedHashMap<>();
            try {
                var res = alarmDict.resolve(d.getServoBrand(), deviceService.pointsOf(d),
                        snapshot.latest(code));
                plc = (List<Map<String, Object>>) res.get("words");
                plcHasPoint = Boolean.TRUE.equals(res.get("hasAlarmPoint"));
                Map<String, PointValue> pts = snapshot.latest(code);
                if (pts != null) {
                    for (Map.Entry<String, PointValue> pe : pts.entrySet()) {
                        Object v = pe.getValue().value();
                        if (v instanceof Number n) {
                            values.put(pe.getKey(), n.doubleValue());
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("读取 {} 报警字失败：{}", code, e.getMessage());
            }

            // 报警明细（给维修工看的部分）
            List<Map<String, Object>> items = new ArrayList<>();
            List<String> ids = new ArrayList<>();
            List<String> types = new ArrayList<>();
            for (AlarmEvent e : events) {
                ids.add(String.valueOf(e.getId()));
                if (e.getRuleType() != null && !types.contains(e.getRuleType())) {
                    types.add(e.getRuleType());
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("rule", e.getRuleName());
                row.put("levelText", levelText(e.getLevel()));
                row.put("message", e.getMessage());
                row.put("value", e.getValue());
                row.put("threshold", e.getThreshold());
                long mins = e.getStartedAt() > 0 ? Math.max(0, (now - e.getStartedAt()) / 60_000) : 0;
                row.put("minutes", mins);
                row.put("since", e.getStartedAt() > 0
                        ? Instant.ofEpochMilli(e.getStartedAt()).atZone(ZONE).format(HHMM) : "");
                items.add(row);
            }
            items.sort(Comparator.comparingInt(
                    (Map<String, Object> m) -> levelRank(String.valueOf(m.get("levelText")))));

            // 签名 = 报警集合 + 通讯状态 + 报警字。
            // 千万别把 values 放进来：温度每秒都在跳，签名就永远在变，
            // AI 会被迫每 3 秒重新分析一次，token 白烧，分析也永远显示"生成中"。
            String signature = String.join("|", ids) + "|" + rt.online + "|" + rt.lastError
                    + "|" + plc;

            Map<String, Object> facts = new LinkedHashMap<>();
            facts.put("device", d.getName());
            facts.put("code", code);
            facts.put("protocol", d.getProtocol());
            facts.put("target", targetOf(d));
            facts.put("offline", !rt.online);
            facts.put("lastError", rt.lastError == null ? "" : rt.lastError);
            facts.put("alarms", Map.of("count", events.size(), "types", types, "items", items));
            facts.put("plcWords", plc);
            facts.put("plcNormal", plcHasPoint && plc.isEmpty());
            facts.put("values", values);

            advisor.request(code, signature, facts);
            FaultAdvisor.Entry entry = advisor.get(code, signature);

            Map<String, Object> fault = new LinkedHashMap<>();
            fault.put("code", code);
            fault.put("name", d.getName() == null ? code : d.getName());
            fault.put("location", d.getLocation());
            fault.put("state", rt.online ? statusText(statusOf(code)) : "离线");
            fault.put("comm", comm);
            fault.put("alarms", items);
            fault.put("plcWords", plc);
            fault.put("plcHasPoint", plcHasPoint);
            fault.put("plcNormal", plcHasPoint && plc.isEmpty());
            fault.put("analysis", Map.of(
                    "text", entry.text(),
                    "source", entry.source()));
            out.add(fault);
        }
        return out;
    }

    private String targetOf(DeviceEntity d) {
        String p = d.getProtocol() == null ? "" : d.getProtocol();
        if ("sim".equals(p)) {
            return "模拟设备（无实连接）";
        }
        if (p.contains("rtu") && !"modbus-rtu-tcp".equals(p)) {
            return "串口 " + d.getSerialPort() + " @" + d.getBaudRate();
        }
        return (d.getHost() == null ? "?" : d.getHost()) + ":" + (d.getPort() == null ? 502 : d.getPort());
    }

    private String statusText(int st) {
        return switch (st) {
            case 0 -> "停机";
            case 1 -> "待机";
            case 3 -> "报警";
            default -> "运行";
        };
    }

    private int levelRank(String level) {
        if ("CRITICAL".equals(level)) {
            return 0;
        }
        if ("MAJOR".equals(level)) {
            return 1;
        }
        return 2;
    }

    private String levelText(String level) {
        if ("CRITICAL".equals(level)) {
            return "严重";
        }
        if ("MAJOR".equals(level)) {
            return "重要";
        }
        return "一般";
    }

    private Number num(Object o) {
        return o instanceof Number n ? n : 0;
    }
}
