package com.factory.hub.erp;

import com.factory.hub.collect.SnapshotStore;
import com.factory.hub.model.DeviceEntity;
import com.factory.hub.report.ProdHourly;
import com.factory.hub.report.ProdHourlyRepository;
import com.factory.hub.service.DeviceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 产线与产线产量（ROADMAP DH-40/41）。
 * <p>
 * <b>唯一口径</b>：产线产量 = 成品计数设备的计数器增量，设备自身计数不变。
 * 产线上不存任何产量数字，全部从 {@code prod_hourly.output} 现算——
 * 这样换班清零、断线补记、改成品计数点都不会留下两份对不上的账。
 * <p>
 * 成品计数点：显式指定优先；没指定就取工位序号最大的一台（客户口径"最后一台机器"）；
 * 线上没有设备时返回 null，界面显示 "--" 而不是 0。
 */
@Service
public class ErpLineService {

    private static final long HOUR_MS = 3_600_000L;
    private static final long DAY_MS = 86_400_000L;
    private static final ZoneId ZONE = ZoneId.systemDefault();
    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("MM-dd");

    private final ErpProdLineRepository repo;
    private final DeviceService deviceService;
    private final ProdHourlyRepository prodRepo;
    private final ObjectMapper mapper;
    private final SnapshotStore store;
    private final com.factory.hub.report.ProdRollup rollup;

    public ErpLineService(ErpProdLineRepository repo, DeviceService deviceService,
                          ProdHourlyRepository prodRepo, ObjectMapper mapper,
                          SnapshotStore store, com.factory.hub.report.ProdRollup rollup) {
        this.repo = repo;
        this.deviceService = deviceService;
        this.prodRepo = prodRepo;
        this.mapper = mapper;
        this.store = store;
        this.rollup = rollup;
    }

    /* ==================== 查询 ==================== */

    /** 产线列表：每条线带设备数、在线数、成品计数点与今日/本月/今年产量 */
    @Transactional(readOnly = true)
    public Map<String, Object> list() {
        List<ErpProdLine> lines = repo.findAllByOrderBySortOrderAscCodeAsc();
        Map<Long, List<DeviceEntity>> byLine = devicesByLine();

        long now = System.currentTimeMillis();
        Map<String, DeviceEntity> finishedOf = new HashMap<>();
        for (ErpProdLine l : lines) {
            DeviceEntity d = resolveFinished(l, byLine.getOrDefault(l.getId(), List.of()));
            if (d != null) {
                finishedOf.put(l.getCode(), d);
            }
        }
        Map<String, List<ProdHourly>> rows = hourlyRows(finishedOf.values().stream()
                .map(DeviceEntity::getCode).collect(Collectors.toSet()), now);
        Map<String, Boolean> online = onlineMap();

        List<Map<String, Object>> out = new ArrayList<>();
        double sumToday = 0;
        double sumMonth = 0;
        int deviceTotal = 0;
        int onlineTotal = 0;
        int withFinished = 0;
        for (ErpProdLine l : lines) {
            List<DeviceEntity> devices = byLine.getOrDefault(l.getId(), List.of());
            DeviceEntity fin = finishedOf.get(l.getCode());
            boolean auto = fin != null && !fin.getCode().equals(l.getFinishedDeviceCode());

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", l.getId());
            m.put("code", l.getCode());
            m.put("name", l.getName());
            m.put("workshop", l.getWorkshop());
            m.put("sortOrder", l.getSortOrder());
            m.put("enabled", l.isEnabled());
            m.put("remark", l.getRemark());
            m.put("deviceCount", devices.size());
            int on = 0;
            for (DeviceEntity d : devices) {
                if (Boolean.TRUE.equals(online.get(d.getCode()))) {
                    on++;
                }
            }
            m.put("onlineCount", on);
            m.put("finishedCode", fin == null ? null : fin.getCode());
            m.put("finishedName", fin == null ? null : fin.getName());
            m.put("finishedAuto", auto);
            m.put("finishedHasCount", fin != null && hasCountPoint(fin));
            double[] st = fin == null ? new double[]{0, 0, 0}
                    : statOf(rows.get(fin.getCode()), fin.getProdResetHour(), now);
            m.put("today", round2(st[0]));
            m.put("month", round2(st[1]));
            m.put("year", round2(st[2]));
            out.add(m);

            deviceTotal += devices.size();
            onlineTotal += on;
            if (fin != null) {
                withFinished++;
                sumToday += st[0];
                sumMonth += st[1];
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("lines", lines.size());
        summary.put("devices", deviceTotal);
        summary.put("online", onlineTotal);
        summary.put("withFinished", withFinished);
        summary.put("today", round2(sumToday));
        summary.put("month", round2(sumMonth));

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("ok", true);
        res.put("rows", out);
        res.put("summary", summary);
        res.put("unassigned", deviceService.all().stream()
                .filter(d -> d.getProdLineId() == null).count());
        return res;
    }

    /** 产线详情：设备按工位排序 + 近 N 天产量 */
    @Transactional(readOnly = true)
    public Map<String, Object> detail(String code, int days) {
        ErpProdLine line = repo.findByCode(code).orElse(null);
        if (line == null) {
            return fail("产线不存在：" + code);
        }
        List<DeviceEntity> devices = deviceService.all().stream()
                .filter(d -> line.getId().equals(d.getProdLineId()))
                .sorted(Comparator.comparingInt(DeviceEntity::getLineSeq)
                        .thenComparing(DeviceEntity::getCode))
                .toList();
        DeviceEntity fin = resolveFinished(line, devices);
        boolean auto = fin != null && !fin.getCode().equals(line.getFinishedDeviceCode());
        long now = System.currentTimeMillis();
        Map<String, Boolean> online = onlineMap();

        Map<String, List<ProdHourly>> rows = hourlyRows(
                devices.stream().map(DeviceEntity::getCode).collect(Collectors.toSet()), now);

        List<Map<String, Object>> devs = new ArrayList<>();
        for (DeviceEntity d : devices) {
            double[] st = statOf(rows.get(d.getCode()), d.getProdResetHour(), now);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", d.getCode());
            m.put("name", d.getName());
            m.put("location", d.getLocation());
            m.put("seq", d.getLineSeq());
            m.put("online", Boolean.TRUE.equals(online.get(d.getCode())));
            m.put("hasCount", hasCountPoint(d));
            m.put("finished", fin != null && fin.getCode().equals(d.getCode()));
            m.put("today", round2(st[0]));
            m.put("month", round2(st[1]));
            m.put("prodSkuId", d.getProdSkuId());
            devs.add(m);
        }

        Map<String, Object> l = new LinkedHashMap<>();
        l.put("id", line.getId());
        l.put("code", line.getCode());
        l.put("name", line.getName());
        l.put("workshop", line.getWorkshop());
        l.put("sortOrder", line.getSortOrder());
        l.put("enabled", line.isEnabled());
        l.put("remark", line.getRemark());
        l.put("finishedCode", fin == null ? null : fin.getCode());
        l.put("finishedName", fin == null ? null : fin.getName());
        l.put("finishedAuto", auto);
        l.put("deviceCount", devices.size());

        double[] st = fin == null ? new double[]{0, 0, 0}
                : statOf(rows.get(fin.getCode()), fin.getProdResetHour(), now);

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("ok", true);
        res.put("line", l);
        res.put("devices", devs);
        res.put("daily", daily(fin, days, now));
        res.put("totals", Map.of(
                "today", round2(st[0]),
                "month", round2(st[1]),
                "year", round2(st[2])));
        return res;
    }

    /** 近 N 天产量（按成品计数设备的清零时刻切日） */
    private List<Map<String, Object>> daily(DeviceEntity fin, int days, long now) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (fin == null) {
            return out;
        }
        int n = Math.max(1, Math.min(120, days));
        int rh = fin.getProdResetHour();
        long todayKey = ProdDay.dayKey(now, rh);
        long from = ProdDay.startOfDayKey(todayKey - n + 1, rh);
        Map<Long, Double> byDay = new LinkedHashMap<>();
        for (long k = todayKey - n + 1; k <= todayKey; k++) {
            byDay.put(k, 0d);
        }
        for (ProdHourly p : prodRepo.findByDeviceCodeAndHourStartBetween(fin.getCode(), from, now)) {
            byDay.merge(ProdDay.dayKey(p.getHourStart(), rh), p.getOutput(), Double::sum);
        }
        for (Map.Entry<Long, Double> e : byDay.entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", LocalDate.ofEpochDay(e.getKey()).format(DAY_FMT));
            m.put("qty", round2(e.getValue()));
            out.add(m);
        }
        return out;
    }

    /** 给开放接口/大屏用：只要编码、名称、车间、成品设备与今日产量 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> brief() {
        List<ErpProdLine> lines = repo.findAllByOrderBySortOrderAscCodeAsc();
        Map<Long, List<DeviceEntity>> byLine = devicesByLine();
        long now = System.currentTimeMillis();
        Map<String, DeviceEntity> finishedOf = new HashMap<>();
        for (ErpProdLine l : lines) {
            DeviceEntity d = resolveFinished(l, byLine.getOrDefault(l.getId(), List.of()));
            if (d != null) {
                finishedOf.put(l.getCode(), d);
            }
        }
        Map<String, List<ProdHourly>> rows = hourlyRows(finishedOf.values().stream()
                .map(DeviceEntity::getCode).collect(Collectors.toSet()), now);
        Map<String, Boolean> online = onlineMap();

        List<Map<String, Object>> out = new ArrayList<>();
        for (ErpProdLine l : lines) {
            if (!l.isEnabled()) {
                continue;
            }
            List<DeviceEntity> devices = byLine.getOrDefault(l.getId(), List.of());
            DeviceEntity fin = finishedOf.get(l.getCode());
            double[] st = fin == null ? new double[]{0, 0, 0}
                    : statOf(rows.get(fin.getCode()), fin.getProdResetHour(), now);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", l.getCode());
            m.put("name", l.getName());
            m.put("workshop", l.getWorkshop());
            m.put("devices", devices.size());
            m.put("online", devices.stream()
                    .filter(d -> Boolean.TRUE.equals(online.get(d.getCode()))).count());
            m.put("finishedCode", fin == null ? null : fin.getCode());
            m.put("today", round2(st[0]));
            m.put("month", round2(st[1]));
            out.add(m);
        }
        return out;
    }

    /* ==================== 写入 ==================== */

    @Transactional
    public Map<String, Object> create(Map<String, Object> body) {
        String code = str(body.get("code")).toUpperCase();
        String name = str(body.get("name"));
        if (code.isBlank()) {
            return fail("产线编码不能为空");
        }
        if (name.isBlank()) {
            return fail("产线名称不能为空");
        }
        if (repo.existsByCode(code)) {
            return fail("产线编码已存在：" + code);
        }
        ErpProdLine l = new ErpProdLine();
        l.setCode(code);
        l.setName(name);
        l.setWorkshop(str(body.get("workshop")));
        l.setRemark(str(body.get("remark")));
        l.setEnabled(body.get("enabled") == null || Boolean.parseBoolean(String.valueOf(body.get("enabled"))));
        int sort = body.get("sortOrder") == null ? nextSort() : (int) num(body.get("sortOrder"), nextSort());
        l.setSortOrder(sort);
        repo.save(l);
        return ok("产线已创建：" + code);
    }

    @Transactional
    public Map<String, Object> update(String code, Map<String, Object> body) {
        ErpProdLine l = repo.findByCode(code).orElse(null);
        if (l == null) {
            return fail("产线不存在：" + code);
        }
        String name = str(body.get("name"));
        if (name.isBlank()) {
            return fail("产线名称不能为空");
        }
        l.setName(name);
        l.setWorkshop(str(body.get("workshop")));
        l.setRemark(str(body.get("remark")));
        if (body.get("enabled") != null) {
            l.setEnabled(Boolean.parseBoolean(String.valueOf(body.get("enabled"))));
        }
        if (body.get("sortOrder") != null) {
            l.setSortOrder((int) num(body.get("sortOrder"), l.getSortOrder()));
        }
        l.setUpdatedAt(System.currentTimeMillis());
        repo.save(l);
        return ok("已保存：" + code);
    }

    /** 删除产线：还有设备时拒绝，避免设备莫名其妙"消失"在产线里 */
    @Transactional
    public Map<String, Object> remove(String code) {
        ErpProdLine l = repo.findByCode(code).orElse(null);
        if (l == null) {
            return fail("产线不存在：" + code);
        }
        long count = deviceService.all().stream()
                .filter(d -> l.getId().equals(d.getProdLineId())).count();
        if (count > 0) {
            return fail("该产线还有 " + count + " 台设备，请先移出设备再删除");
        }
        repo.delete(l);
        return ok("产线已删除：" + code);
    }

    /**
     * 编排设备：传入完整的有序编码列表，序号按数组顺序重排。
     * 不在列表里的本线设备会被移出（prod_line_id 置空），在别的线上的会被接过归属。
     */
    @Transactional
    public Map<String, Object> assign(String code, List<String> codes) {
        ErpProdLine l = repo.findByCode(code).orElse(null);
        if (l == null) {
            return fail("产线不存在：" + code);
        }
        List<String> list = codes == null ? List.of() : codes.stream()
                .map(c -> str(c)).filter(s -> !s.isBlank()).distinct().toList();
        Map<String, DeviceEntity> all = new HashMap<>();
        for (DeviceEntity d : deviceService.all()) {
            all.put(d.getCode(), d);
        }
        for (String c : list) {
            if (!all.containsKey(c)) {
                return fail("设备不存在：" + c);
            }
        }
        long now = System.currentTimeMillis();
        int seq = 1;
        Set<String> wanted = new HashSet<>(list);
        for (DeviceEntity d : all.values()) {
            boolean onThisLine = l.getId().equals(d.getProdLineId());
            if (onThisLine && !wanted.contains(d.getCode())) {
                d.setProdLineId(null);
                d.setLineSeq(0);
                deviceService.save(d);
            }
        }
        for (String c : list) {
            DeviceEntity d = all.get(c);
            d.setProdLineId(l.getId());
            d.setLineSeq(seq++);
            deviceService.save(d);
        }
        // 指定的成品设备被移出 → 回到"自动取最后一台"
        if (l.getFinishedDeviceCode() != null && !wanted.contains(l.getFinishedDeviceCode())) {
            l.setFinishedDeviceCode(null);
        }
        l.setUpdatedAt(now);
        repo.save(l);
        return ok("已编排 " + list.size() + " 台设备");
    }

    /** 工位上下移：整条线重排序号，避免历史脏数据里序号重复 */
    @Transactional
    public Map<String, Object> move(String code, String deviceCode, String dir) {
        ErpProdLine l = repo.findByCode(code).orElse(null);
        if (l == null) {
            return fail("产线不存在：" + code);
        }
        List<DeviceEntity> devices = new ArrayList<>(deviceService.all().stream()
                .filter(d -> l.getId().equals(d.getProdLineId()))
                .sorted(Comparator.comparingInt(DeviceEntity::getLineSeq)
                        .thenComparing(DeviceEntity::getCode))
                .toList());
        int idx = -1;
        for (int i = 0; i < devices.size(); i++) {
            if (devices.get(i).getCode().equals(deviceCode)) {
                idx = i;
                break;
            }
        }
        if (idx < 0) {
            return fail("设备不在该产线上：" + deviceCode);
        }
        int target = "up".equalsIgnoreCase(dir) ? idx - 1 : idx + 1;
        if (target < 0 || target >= devices.size()) {
            return ok("已经在" + ("up".equalsIgnoreCase(dir) ? "最前" : "最后"));
        }
        DeviceEntity a = devices.get(idx);
        devices.set(idx, devices.get(target));
        devices.set(target, a);
        int seq = 1;
        for (DeviceEntity d : devices) {
            d.setLineSeq(seq++);
            deviceService.save(d);
        }
        return ok("已调整工位顺序");
    }

    /** 指定成品计数点；deviceCode 为空 = 回到"自动取最后一台" */
    @Transactional
    public Map<String, Object> setFinished(String code, String deviceCode) {
        ErpProdLine l = repo.findByCode(code).orElse(null);
        if (l == null) {
            return fail("产线不存在：" + code);
        }
        String dev = str(deviceCode);
        if (dev.isBlank() || "null".equalsIgnoreCase(dev)) {
            l.setFinishedDeviceCode(null);
            l.setUpdatedAt(System.currentTimeMillis());
            repo.save(l);
            return ok("已改为自动：取工位序号最大的一台作为成品计数点");
        }
        DeviceEntity d = deviceService.byCode(dev);
        if (d == null) {
            return fail("设备不存在：" + dev);
        }
        if (!l.getId().equals(d.getProdLineId())) {
            return fail("该设备不在本产线上，请先编排进来");
        }
        if (!hasCountPoint(d)) {
            return fail("设备 " + dev + " 没有计数点（role=count），不能作为成品计数点");
        }
        l.setFinishedDeviceCode(dev);
        l.setUpdatedAt(System.currentTimeMillis());
        repo.save(l);
        return ok("成品计数点已设为：" + dev);
    }

    /* ==================== 内部 ==================== */

    /**
     * 成品计数设备：显式指定优先；否则取<b>有计数点</b>的设备里工位序号最大的一台。
     * <p>
     * 为什么不是直接取最后一行：产线末端常有一台输送线/包装机没有 count 点，
     * 直接取最后一台会让整条线的产量永远是 0。只有全线都没有计数点时才退回最后一台，
     * 并在返回里标 finishedHasCount=false，让界面把"这条线采不到产量"说清楚。
     */
    private DeviceEntity resolveFinished(ErpProdLine l, List<DeviceEntity> devices) {
        if (devices == null || devices.isEmpty()) {
            return null;
        }
        String want = l.getFinishedDeviceCode();
        if (want != null && !want.isBlank()) {
            for (DeviceEntity d : devices) {
                if (want.equals(d.getCode())) {
                    return d;
                }
            }
        }
        List<DeviceEntity> withCount = devices.stream().filter(this::hasCountPoint).toList();
        List<DeviceEntity> pool = withCount.isEmpty() ? devices : withCount;
        return pool.stream()
                .max(Comparator.comparingInt(DeviceEntity::getLineSeq)
                        .thenComparing(DeviceEntity::getCode))
                .orElse(null);
    }

    private Map<Long, List<DeviceEntity>> devicesByLine() {
        Map<Long, List<DeviceEntity>> map = new HashMap<>();
        for (DeviceEntity d : deviceService.all()) {
            if (d.getProdLineId() != null) {
                map.computeIfAbsent(d.getProdLineId(), k -> new ArrayList<>()).add(d);
            }
        }
        for (List<DeviceEntity> list : map.values()) {
            list.sort(Comparator.comparingInt(DeviceEntity::getLineSeq)
                    .thenComparing(DeviceEntity::getCode));
        }
        return map;
    }

    /** 在线 = 采集线程最近一次读成功（enabled 只是"启用采集"，不代表通着） */
    private Map<String, Boolean> onlineMap() {
        Map<String, Boolean> map = new HashMap<>();
        for (DeviceEntity d : deviceService.all()) {
            map.put(d.getCode(), store.runtime(d.getCode()).online);
        }
        return map;
    }

    /**
     * 一次取回所有成品设备今年（往前提两天，覆盖清零时刻跨年）的小时产量。
     * 产线数量通常个位数到几十条，一次 IN 查询足够。
     */
    private Map<String, List<ProdHourly>> hourlyRows(Set<String> codes, long now) {
        if (codes.isEmpty()) {
            return Map.of();
        }
        long yearStart = LocalDate.now(ZONE).withDayOfYear(1).minusDays(2)
                .atStartOfDay(ZONE).toInstant().toEpochMilli();
        Map<String, List<ProdHourly>> byDev = prodRepo
                .findByDeviceCodeInAndHourStartBetween(codes, yearStart, now).stream()
                .collect(Collectors.groupingBy(ProdHourly::getDeviceCode));
        // 今日产量实时口径（2026-10-29）：设备 KPI（ErpProdController.effOutput）对
        // 当前小时用 ProdRollup 内存值（秒级），产线这里必须同口径，否则
        // 「产线今日」落后「设备今日」最多 60 秒的落库窗口，smoke 的一致性断言会挂。
        // 用脱离副本而不是改实体：OSIV 下查询出的实体被 dirty-checking 写回库，
        // 只读接口不能有写副作用
        long curHour = now - (now % HOUR_MS);
        Map<String, List<ProdHourly>> out = new java.util.HashMap<>(byDev.size());
        for (Map.Entry<String, List<ProdHourly>> e : byDev.entrySet()) {
            List<ProdHourly> list = new java.util.ArrayList<>(e.getValue().size());
            for (ProdHourly p : e.getValue()) {
                if (p.getHourStart() == curHour) {
                    double live = rollup.liveOutput(p.getDeviceCode(), p.getHourStart());
                    if (!Double.isNaN(live) && live != p.getOutput()) {
                        ProdHourly c = new ProdHourly(p.getDeviceCode(), p.getDeviceName(),
                                p.getHourStart());
                        c.setOutput(live);
                        list.add(c);
                        continue;
                    }
                }
                list.add(p);
            }
            out.put(e.getKey(), list);
        }
        return out;
    }

    /** [今日, 本月, 今年]，今日按设备自己的清零时刻切日（本地时区，别再用 UTC 天数） */
    private static double[] statOf(List<ProdHourly> rows, int resetHour, long now) {
        double today = 0;
        double month = 0;
        double year = 0;
        if (rows == null || rows.isEmpty()) {
            return new double[]{0, 0, 0};
        }
        long todayKey = ProdDay.dayKey(now, resetHour);
        LocalDate bizToday = ProdDay.bizDate(now, resetHour);
        for (ProdHourly p : rows) {
            double o = p.getOutput();
            // 今日先判：清零时刻在凌晨时，"今天"可能从前一天的 resetHour 开始
            if (ProdDay.dayKey(p.getHourStart(), resetHour) == todayKey) {
                today += o;
            }
            LocalDate d = ProdDay.bizDate(p.getHourStart(), resetHour);
            if (d.getYear() != bizToday.getYear()) {
                continue;                       // 为覆盖跨年多取的两天，不算进今年
            }
            year += o;
            if (d.getMonth() == bizToday.getMonth()) {
                month += o;
            }
        }
        return new double[]{today, month, year};
    }

    /** 设备有没有 role=count 的点（没有的话产量永远是 0，界面上要说清楚） */
    boolean hasCountPoint(DeviceEntity d) {
        try {
            List<Map<String, Object>> pts = mapper.readValue(
                    d.getPointsJson() == null ? "[]" : d.getPointsJson(),
                    mapper.getTypeFactory().constructCollectionType(List.class, Map.class));
            return pts.stream().anyMatch(p -> "count".equals(p.get("role")));
        } catch (Exception e) {
            return false;
        }
    }

    private int nextSort() {
        return repo.findAll().stream().mapToInt(ErpProdLine::getSortOrder).max().orElse(0) + 10;
    }

    private static double num(Object o, double def) {
        try {
            return Double.parseDouble(String.valueOf(o));
        } catch (Exception e) {
            return def;
        }
    }

    private static double round2(double v) {
        return Math.round(v * 100) / 100d;
    }

    private static String str(Object o) {
        return o == null || "null".equals(String.valueOf(o)) ? "" : String.valueOf(o).trim();
    }

    static Map<String, Object> ok(String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("message", msg);
        return m;
    }

    static Map<String, Object> fail(String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", false);
        m.put("message", msg);
        return m;
    }
}
