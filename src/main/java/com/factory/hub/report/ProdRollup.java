package com.factory.hub.report;

import com.factory.hub.collect.SnapshotStore;
import com.factory.hub.erp.ErpProdInboundService;
import com.factory.hub.erp.ProdDay;
import com.factory.hub.model.DeviceEntity;
import com.factory.hub.model.PointValue;
import com.factory.hub.service.DeviceService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 生产汇总引擎：把「每台设备当前是运行还是停机」「累计产量读数」变成
 * 「每小时各状态占多少秒、出了多少件」。
 * <p>
 * 和能耗统计一样，独立定时任务每秒扫一遍快照，不挂在采集线程里——
 * 采集线程只负责把数据读回来，统计逻辑一旦出错不能把采集带崩。
 * <p>
 * <b>这个模块最容易算错的地方，按踩坑顺序：</b>
 * <ol>
 *   <li><b>停机时间算成运行时间</b>——必须用「上一次状态持续到这一次采样」的区间归属，
 *       而不是「这次采到什么就记一秒」。设备 1 秒采样一次，靠 ts 差值算秒数，
 *       不然丢一次采样就少记一秒，一天下来差几个小时。</li>
 *   <li><b>掉线期间瞎记</b>——设备离线两小时再上线，两次采样 ts 差 7200 秒，
 *       直接记进某一小时会把那小时填满。所以间隔超过 {@link #MAX_GAP_MS} 一律不记时长，
 *       那段空白留在「未记录」里，看报表的人一眼能看出数据不连续。</li>
 *   <li><b>产量计数器清零</b>——换班清零、换表清零，读数变小当成「负数产量」会把
 *       整班产量抹掉。跟能耗一样处理：读数变小就记 0，只统计 resets 次数。</li>
 *   <li><b>产量算到错误的小时</b>——断线重连时一次性补记的产量，归到重连那一小时，
 *       并标记 backfill，让客户知道这小时的数字为什么突然高一截。</li>
 *   <li><b>重启把「上次读数」一起清掉，白丢一段产量（DH-81）</b>——计数器增量靠
 *       「本次读数 - 上次读数」算，而"上次读数"原先只在内存里（初值 -1）。
 *       重启后第一次采样判据不成立，只把当前读数记成基线：那一段产量不回补、
 *       也不报错，而且产量报表和 ERP 入库单据<b>同时少记</b>。
 *       现在水位落 {@code dev_counter_state}（见 {@link DevCounterState}），
 *       启动时 {@link #restore()} 灌回来，重启后第一拍就能算出正确增量。</li>
 * </ol>
 *
 * <b>本模块的两处「重启后必须继承的基线」，继承对象不一样，别搞混：</b>
 * <ol>
 *   <li>{@link #bucket} 继承的是<b>已落库的小时行</b>（{@code prod_hourly}）——
 *       因为 flush 是覆盖写，不继承就会被内存里的零值冲掉。</li>
 *   <li>{@link #restore()} 继承的是<b>已落库的计数器水位</b>（{@code dev_counter_state}）——
 *       因为增量 = 本次读数 - 上次读数，上次读数没了就只能重新起算。</li>
 * </ol>
 *
 * <b>DH-81 修不到、也不该由它修的窗口（写清边界，免得有人以为重启已经"零丢失"）：</b>
 * <ul>
 *   <li><b>硬崩（断电/OOM）仍会丢最后一个 checkpoint 窗口（≤60 秒）</b>——
 *       水位记的是"计数器处理到哪儿"，而 {@code prod_hourly} 是 60 秒一落库。
 *       崩在两次 checkpoint 之间时，那段产量在内存里，跟着进程一起没了。
 *       正常停服务（{@code @PreDestroy} 会 flush）不受影响。</li>
 *   <li><b>计数器清零/换表的同时重启</b>：持久化的是旧读数、新读数是 0，
 *       会按既有规则记 0 + resets++，那一段产量补不回来 —— 读数本身丢了，是物理限制。</li>
 * </ul>
 */
@Component
public class ProdRollup {

    private static final Logger log = LoggerFactory.getLogger(ProdRollup.class);

    private static final long HOUR_MS = 3_600_000L;

    /** 两次采样间隔超过这个值就认为中间断过，不补记时长（正常采集周期 1~5 秒） */
    private static final long MAX_GAP_MS = 15_000L;

    /** 数据保留天数 */
    private static final int RETAIN_DAYS = 400;

    private final SnapshotStore store;
    private final DeviceService deviceService;
    private final ProdHourlyRepository repo;
    private final ErpProdInboundService inbound;
    /** 计数器处理水位（DH-81）：重启后靠它接着上次读数算增量 */
    private final DevCounterStateRepository counterRepo;

    /** key = deviceCode|hourStart，值是该小时该设备的累计 */
    private final Map<String, ProdHourly> buckets = new ConcurrentHashMap<>();

    /**
     * 今日产量的<b>已落库部分</b>快照（deviceCode → 该设备今日已落库的产量和）。
     * <p>
     * <b>为什么需要它</b>（2026-11-07 的 1000 台压测发现）：
     * {@link #todayOutputOf(String)} 原来每台设备每轮都要查一次库
     * （{@code findByDeviceCodeAndHourStartBetween}），1000 台 × 1 秒 = 1000 次/秒，
     * 而它是<b>方法级 synchronized</b> ⇒ 1000 次全部串行在一把锁上 ⇒
     * 实测锁占用率 71.5%、最慢的设备等锁 883ms ⇒ <b>采集周期被拉长</b>。
     * <p>
     * <b>为什么是「不可变快照」而不是直接读 {@code buckets}</b>：
     * {@link ProdHourly} 是<b>可变 JPA 实体</b>（output/runSec 都是普通 double，非 volatile），
     * 而 {@code scan()} 线程会在锁内改它 ⇒ 无锁读者与写者之间<b>没有 happens-before 边</b>
     * ⇒ 可能读到「runSec 是新的、output 是旧的」这种不一致组合。
     * ⇒ 所以这里发布的是 {@code Map<String, Double}>（不可变值），不是可变实体。
     * <p>
     * <b>失效按「写事件」，不按时间</b>：{@code flush()} 写库成功、或
     * {@link #purgeByCode(String)} 删除设备时，立刻让快照作废。
     * ⇒ 不依赖任何关于时钟/落库周期的推理 ⇒ 不会算出过期值。
     */
    private final java.util.concurrent.atomic.AtomicReference<Map<String, TodayOut>> todayFromDb =
            new java.util.concurrent.atomic.AtomicReference<>(Map.of());

    /**
     * 某设备今日产量的<b>已落库部分</b>。
     * <p>
     * <b>为什么是三样而不是一个数</b>：{@link #todayOutputOf(String)} 命中快照后
     * <b>一次库都不能查</b>（连设备表的 {@code byCode} 也不能查 —— 它只是为了让
     * {@code prodResetHour} 拿到手）。所以当前小时那一行的 DB 值、有没有那一行，
     * 都必须提前算好放进快照 ⇒ 快照自己带齐，调用方就不用再问库。
     *
     * @param closedSum   当前小时<b>之前</b>的已落库产量和
     * @param curHourDb   当前小时那一行的 DB 产量（没查过 ⇒ 0）
     * @param hasCurHour  当前小时那一行在不在 DB 里
     */
    private record TodayOut(double closedSum, double curHourDb, boolean hasCurHour) {
    }

    /** 快照里还记着建快照时的小时：跨小时后「当前小时」那部分就不能用了 */
    private volatile long todaySnapshotHour = -1L;

    /** 快照待重建标记：flush/purge 写库后置位，下一拍 scan() 重建 */
    private volatile boolean todaySnapshotDirty = true;

    /** 每台设备的上一笔采样，用来算时间差和产量增量 */
    private final Map<String, DevState> states = new ConcurrentHashMap<>();

    /**
     * 水位是否已经尝试加载过（成功或失败都算）。
     * <p>
     * 用 CAS 而不是「只在 {@link #restore()} 里加载」：定时任务在容器 refresh 阶段就排上了，
     * 有可能比 {@code ApplicationReadyEvent} 先跑一拍。要是第一拍在加载之前处理了计数器，
     * 就会用「当前读数」当基线把水位覆盖掉 —— 那一段增量照样丢，而且这次丢得悄无声息。
     * 所以 {@link #scan()} 开头也会调一次 {@link #ensureRestored()}，谁先谁加载，只加载一次。
     */
    private final AtomicBoolean restored = new AtomicBoolean(false);

    private long lastCleanupDay = -1L;

    private static final class DevState {
        long statusTs = 0;
        int status = -1;
        long countTs = 0;
        /**
         * 上次已处理到的产量计数器读数。-1 = 还没有基线（首次采样只当基准，不推增量）。
         * <p>
         * DH-81：这个基线会同步落 {@code dev_counter_state}，重启后由 {@link #restore()} 灌回，
         * 所以「-1」只在「这台设备从来没采过」时出现，不再是「每次重启都出现」。
         */
        double count = -1;
        /**
         * 已经<b>写成功</b>的读数时间戳；0 = 还没写成功过（下一拍必写）。
         * <p>
         * 只在「读数变了」或「水位的 ts 已经陈旧超过 {@link #MAX_GAP_MS}」时才写库：
         * 计数器不变说明没有产量，写进去也是同一个数 —— 500 台设备每秒各写一次是白烧 IO。
         * 但也不能一直不写：ts 陈旧太久，重启后第一笔增量会被判成 backfill，
         * 报表上会多出一截"补记"（数字对，标注不准）。
         */
        long persistedTs = 0;
        long resets = 0;
        /** DH-15：不良品计数器的上一笔读数 */
        long rejectTs = 0;
        double rejectCount = -1;
        long rejectResets = 0;
    }

    public ProdRollup(SnapshotStore store, DeviceService deviceService, ProdHourlyRepository repo,
                      ErpProdInboundService inbound, DevCounterStateRepository counterRepo) {
        this.store = store;
        this.deviceService = deviceService;
        this.repo = repo;
        this.inbound = inbound;
        this.counterRepo = counterRepo;
    }

    /**
     * 每秒扫一次。fixedDelay 而不是 fixedRate：某次卡了不堆积。
     * <p>
     * 方法级 synchronized + 水位同步落库：产量统计这一条链路上，
     * 「内存状态」和「库里的水位」必须是同一拍的东西，不能一边走一边算。
     */
    @Scheduled(fixedDelay = 1000L)
    public synchronized void scan() {
        long now = System.currentTimeMillis();
        // 兜底：万一这一拍比 ApplicationReadyEvent 还早，就地加载水位（只加载一次）
        ensureRestored();

        // 本拍需要写回的水位（一台设备一行）。收集完一次性提交：
        // 逐台各开一个事务在车间机器上就是每秒几十次 fsync，
        // 而攒到本拍结束一次性写，语义上仍然是同步的（scan 返回时库里已是最新）。
        List<DevCounterState> dirty = new ArrayList<>();

        for (Map.Entry<String, Map<String, PointValue>> e : store.entries()) {
            String code = e.getKey();
            Map<String, PointValue> points = e.getValue();
            if (points == null || points.isEmpty()) {
                continue;
            }
            DevState st = states.computeIfAbsent(code, k -> new DevState());

            PointValue statusPv = pick(points, "status");
            if (statusPv != null) {
                long ts = statusPv.ts();
                Double sv = statusPv.asDouble();
                int cur = sv == null ? -1 : (int) Math.round(sv);
                if (st.statusTs > 0 && ts > st.statusTs && st.status >= 0) {
                    long dt = ts - st.statusTs;
                    if (dt > 0 && dt <= MAX_GAP_MS) {
                        // 用「上一笔状态」占住这段区间：状态是在这次采样才变的，
                        // 中间那段时间设备还是老状态
                        bucket(code, st.statusTs).addStatus(st.status, dt / 1000d);
                    }
                }
                st.status = cur;
                st.statusTs = ts;
            }

            PointValue countPv = pick(points, "count");
            Double cv = countPv == null ? null : countPv.asDouble();
            if (countPv != null && cv != null) {
                long ts = countPv.ts();
                double cur = cv;
                if (st.count >= 0 && ts > st.countTs) {
                    double delta = cur - st.count;
                    boolean backfill = (ts - st.countTs) > MAX_GAP_MS;
                    if (delta < 0) {
                        // 换班清零 / 换表。记 0，不能记负数
                        // （重启 + 清零同时发生时，库里是旧读数、新读数是 0，差值只能记 0，
                        //   那段产量补不回来 —— 读数本身丢了，是计数器的物理限制，
                        //   不是水位没持久化好，见 DevCounterState 的说明）
                        st.resets++;
                        delta = 0;
                    }
                    if (delta > 0) {
                        ProdHourly p = bucket(code, ts);
                        p.setOutput(p.getOutput() + delta);
                        if (backfill) {
                            p.setBackfill(p.getBackfill() + delta);
                        }
                        // DH-44 生产入库联动：增量交给 ERP（服务内部按设备配置过滤，
                        // 没启用联动的设备在这里是空操作）
                        inbound.onProduced(code, delta, ts, backfill);
                    }
                }
                // 只有「这一笔采样比上次新」才推进水位：
                // 乱序/重复的采样（设备时钟回跳、上游重发同一笔）把水位往回拨，
                // 下一拍就会把已经算过的增量再算一遍 —— 那是重复计数，比丢数更难查。
                if (ts > st.countTs) {
                    boolean moved = cur != st.count;        // 用更新前的旧值比，判断"读数变了"
                    st.count = cur;
                    st.countTs = ts;
                    // DH-81 水位同步落库：重启后从这里接着算，不会再把当前读数当基线。
                    // 只收集、本拍结束统一提交（见 persistCounters），失败也绝不中断采集。
                    if (moved || st.persistedTs == 0 || ts - st.persistedTs > MAX_GAP_MS) {
                        dirty.add(new DevCounterState(code, cur, ts, now));
                    }
                }
            }

            // DH-15 不良品计数（role=reject）。机床的不良计数器，很多设备有独立的一个。
            // 没配这个点位的设备这里整段跳过——「没采到」和「采到 0」是两回事，
            // 报表那边靠设备配没配这个点位来区分，不能拿 0 冒充「没有不良」
            PointValue rejectPv = pick(points, "reject");
            Double rv = rejectPv == null ? null : rejectPv.asDouble();
            if (rejectPv != null && rv != null) {
                long ts = rejectPv.ts();
                double cur = rv;
                if (st.rejectCount >= 0 && ts > st.rejectTs) {
                    double delta = cur - st.rejectCount;
                    if (delta < 0) {
                        // 计数器清零 / 换表。记 0，不能记负数
                        st.rejectResets++;
                        delta = 0;
                    }
                    if (delta > 0) {
                        bucket(code, ts).addDefect(delta);
                        // 不良数也要进 ERP（QC-SPEC §2.3/§7 F1）：
                        // 主计数器算的是总产出（含不良），入库时得把这一批的不良扣掉，
                        // 否则不良品会被当良品入成品仓。走独立入口而不是塞进 onProduced，
                        // 因为两个计数器是独立采样，增量常常不在同一拍上
                        inbound.onRejected(code, delta, ts);
                    }
                }
                st.rejectCount = cur;
                st.rejectTs = ts;
            }
        }

        // 水位在本拍结束前同步写回。滞后写（攒够再批量刷）会把已经处理过的增量
        // 在重启后重算一遍 —— 那变成重复计数，比丢数更难发现。
        persistCounters(dirty);

        cleanupStates();
        maybeCleanupOldData(now);

        // 刷新「今日产量」的批量快照（见 todayFromDb 字段的说明）。
        // 放在 scan() 末尾而不是新建 @Scheduled 任务：
        //   Spring 的 @Scheduled 默认**单线程** ⇒ 新增一个定时任务会跟 scan() 抢同一个线程，
        //   而 scan() 是每秒拍（生产链路的节拍），不能被别的任务拖慢。
        //   ⇒ 挂在这里：它本来就每秒跑、本来就遍历全量设备、本来就持这把锁。
        refreshTodaySnapshot();
    }

    /**
     * 刷新「今日产量已落库部分」的批量快照。
     * <p>
     * <b>为什么批量</b>：原来每台设备每轮查一次库 ⇒ 1000 台 = 1000 次/秒，
     * 而且都在 {@link #todayOutputOf(String)} 的 synchronized 里 ⇒ 全部串行。
     * ⇒ 现在改成<b>一次批量查询</b>（所有设备一次查回来）⇒ 1000 次/秒 → 1 次/秒。
     * <p>
     * <b>为什么放在锁内</b>：查出来的值要跟内存桶（{@code buckets}）里的当前小时实时值
     * 拼在一起才算今日产量，而内存桶正被 {@code scan()} 改 ⇒ 同一把锁下取值才自洽。
     * <p>
     * <b>失效按写事件</b>：{@code flush()} 写库成功、{@link #purgeByCode(String)} 删设备时
     * 都会让快照作废（{@link #invalidateTodaySnapshot()}）⇒
     * <b>不依赖任何关于时钟/落库周期的推理</b> ⇒ 不会算出过期值。
     */
    private void refreshTodaySnapshot() {
        // ⚠️⚠️ **必须先判断要不要刷**（这是本改造最容易埋的雷）：
        //   这个方法要查两次库（设备全表 + 批量小时行），而它跑在 {@code scan()} 的
        //   synchronized 里 ⇒ **每秒持锁做重查询** = 制造一个比原来更糟的锁瓶颈。
        // ⇒ 只在两种情况下刷：
        //   ① 有人写过库（flush/purge 置 dirty）—— 数据变了
        //   ② 跨了小时 —— 「当前小时」那部分的含义变了
        //   其它时候**直接返回**，一次库都不查。
        long nowMs = System.currentTimeMillis();
        long nowHour = nowMs - (nowMs % HOUR_MS);
        if (!todaySnapshotDirty && todaySnapshotHour == nowHour) {
            return;
        }
        try {
            List<DeviceEntity> devices = deviceService.all();
            if (devices.isEmpty()) {
                todayFromDb.set(Map.of());
                return;
            }
            long now = System.currentTimeMillis();
            long curHour = now - (now % HOUR_MS);

            // 每台设备的「今日起点」不同（prodResetHour），批量查询只能用一个统一窗口
            // ⇒ 取所有设备里最早的那个起点，保证一行都不漏；各设备再用自己的起点裁剪。
            long minStart = Long.MAX_VALUE;
            Map<String, Integer> resetHours = new HashMap<>();
            List<String> codes = new ArrayList<>(devices.size());
            for (DeviceEntity d : devices) {
                if (d.getCode() == null) {
                    continue;
                }
                codes.add(d.getCode());
                int rh = d.getProdResetHour();
                resetHours.put(d.getCode(), rh);
                minStart = Math.min(minStart, ProdDay.dayStart(now, rh));
            }
            if (codes.isEmpty()) {
                todayFromDb.set(Map.of());
                return;
            }

            // 一次批量查询拿到所有设备今天的小时行（仓库里已有这个方法）
            List<ProdHourly> rows = repo.findByDeviceCodeInAndHourStartBetween(codes, minStart, now);

            // 每台设备用自己的今日起点裁剪（prodResetHour 不同 ⇒ 起点不同）
            // 每台设备一份：closedSum / curHourDb / hasCurHour
            Map<String, TodayOut> snap = new HashMap<>(devices.size() * 2);
            Map<String, double[]> acc = new HashMap<>(devices.size() * 2);
            for (String code : codes) {
                acc.put(code, new double[]{0d, 0d, 0d});
            }
            for (ProdHourly p : rows) {
                String code = p.getDeviceCode();
                double[] a = acc.get(code);
                Integer rh = resetHours.get(code);
                if (a == null || rh == null) {
                    continue;
                }
                if (p.getHourStart() < ProdDay.dayStart(now, rh)) {
                    continue;
                }
                if (p.getHourStart() == curHour) {
                    // 当前小时这一行：单独记（只有内存没实时值时才用它）
                    a[1] = p.getOutput();
                    a[2] = 1d;
                } else {
                    a[0] += p.getOutput();
                }
            }
            for (Map.Entry<String, double[]> e : acc.entrySet()) {
                double[] a = e.getValue();
                snap.put(e.getKey(), new TodayOut(a[0], a[1], a[2] > 0d));
            }
            todayFromDb.set(Map.copyOf(snap));
            todaySnapshotHour = curHour;
            // ⚠️ **只在成功后清 dirty**：失败时保持 dirty ⇒ 下一拍重试
            todaySnapshotDirty = false;
        } catch (Exception e) {
            // 刷新失败**不能让推送挂掉**：保留旧快照，下一拍再试。
            // （旧快照可能略旧 ⇒ 今日产量最多差一个落库周期，客户看到的数字仍自洽。）
            log.warn("刷新今日产量快照失败（沿用旧快照）: {}", e.getMessage());
        }
    }

    /** 让快照作废：下一个 {@link #refreshTodaySnapshot()} 会重建 */
    private void invalidateTodaySnapshot() {
        todaySnapshotDirty = true;
    }

    /* ==================== DH-81 计数器水位：启动恢复 + 同步落库 ==================== */

    /**
     * 启动时把库里的计数器水位灌回内存，让重启后第一拍就能算出正确增量。
     * <p>
     * 用 {@code ApplicationReadyEvent}（和 {@code AlarmEngine.restore()} 一个路子）：
     * 这时数据源和 JPA 都就绪，但采集线程还没跑出第一轮真实数据。
     * <p>
     * <b>加载失败绝不阻断启动</b>（项目铁律：迁移/恢复失败不阻断启动）——
     * 车间服务器起不来等于彻底没有监控，比"水位没恢复、可能重复记一小段"严重得多。
     * 失败只记 WARN，并把后果写清楚，让人能从日志判断"那一段产量要不要去对账"。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void restore() {
        ensureRestored();
    }

    /**
     * 只加载一次。{@link #scan()} 开头也会调它——定时任务可能在
     * {@code ApplicationReadyEvent} 之前就先跑了一拍，谁先到谁加载，
     * 否则第一拍会拿「当前读数」当基线，把还没算的增量永久盖掉。
     */
    private synchronized void ensureRestored() {
        if (!restored.compareAndSet(false, true)) {
            return;
        }
        try {
            List<DevCounterState> rows = counterRepo.findAll();
            int n = 0;
            for (DevCounterState r : rows) {
                // 没有时间戳的水位用不了：增量是靠 ts 严格递增来判定的
                if (r.getDeviceCode() == null || r.getCountTs() <= 0) {
                    continue;
                }
                DevState st = states.computeIfAbsent(r.getDeviceCode(), k -> new DevState());
                st.count = r.getCountValue();
                st.countTs = r.getCountTs();
                st.persistedTs = r.getCountTs();
                n++;
            }
            if (n > 0) {
                log.info("产量计数器水位恢复：{} 台设备接着上次读数算增量（重启不再丢那一段产量）", n);
            }
        } catch (Exception e) {
            log.warn("加载计数器水位失败，不影响采集：本次启动按「首次读数」重新起算，"
                    + "重启那一小段产量会丢（可事后按 prod_hourly 与入库单据人工核对）: {}", e.getMessage());
        }
    }

    /**
     * 把本拍的水位写回 {@code dev_counter_state}。
     * <p>
     * <b>失败只记 WARN，绝不抛出</b>：产出采集是主链路（报表、ERP 入库、看板全靠它），
     * 不能被数据库抖动挡住。代价是"写失败后若立刻重启会重复计这一小段"——
     * 下一拍写成功即自愈，且日志里有设备号和读数，事后能核对。
     * <p>
     * 先整批一次提交（一个事务，一次 fsync）；整批失败再逐台重试，
     * 免得一台设备的坏行把其他设备的水位一起卡住 —— 那时重启就是全厂一起丢数。
     */
    private void persistCounters(List<DevCounterState> dirty) {
        if (dirty == null || dirty.isEmpty()) {
            return;
        }
        try {
            counterRepo.saveAll(dirty);
            markPersisted(dirty);
            return;
        } catch (Exception e) {
            log.warn("计数器水位整批写库失败（{} 台），改为逐台重试: {}", dirty.size(), e.getMessage());
        }
        for (DevCounterState s : dirty) {
            try {
                counterRepo.save(s);
                markPersisted(List.of(s));
            } catch (Exception e) {
                // 失败就把 persistedTs 打回 0：下一拍无论读数有没有变都会再写一次，
                // 不让一次抖动把"水位陈旧"拖成 15 秒（那 15 秒里重启就会重复计数）
                DevState st = states.get(s.getDeviceCode());
                if (st != null) {
                    st.persistedTs = 0;
                }
                log.warn("计数器水位写库失败：设备 {} 读数 {}（ts={}）。"
                                + "若此刻重启，这一小段产量会被重复计一次，下次采样写成功即自愈: {}",
                        s.getDeviceCode(), s.getCountValue(), s.getCountTs(), e.getMessage());
            }
        }
    }

    /** 只有真写成功了才认这个 ts，否则「陈旧判定」会把没写进去的当成写进去了 */
    private void markPersisted(List<DevCounterState> rows) {
        for (DevCounterState s : rows) {
            DevState st = states.get(s.getDeviceCode());
            if (st != null) {
                st.persistedTs = s.getCountTs();
            }
        }
    }

    /** 该设备的计数器清零/换表次数（resets）。排障与重启验收用，不对外提供接口 */
    long resetsOf(String deviceCode) {
        DevState st = states.get(deviceCode);
        return st == null ? 0 : st.resets;
    }

    /**
     * 每分钟落一次库。
     * <p>
     * 不做「整点才落」：客户在 10:59 打开报表看到 10 点那行是空的会以为系统坏了。
     * 提前落库，当前小时的数字也在，只是还会继续涨。
     */
    @Scheduled(fixedDelay = 60_000L)
    public synchronized void checkpoint() {
        flush(true);
    }

    /** 关机前把内存里的数据写掉，最多丢 60 秒 */
    @PreDestroy
    public synchronized void onShutdown() {
        flush(true);
    }

    /**
     * 单设备的今日产量：DB 已落库的小时行 + 当前小时的内存实时增量。
     * <p>
     * 推送用——点位一变就把今日产量一起推给前端，设备卡片上的数字才能秒级跟涨
     * （列表接口的 todayOutput 只有在页面刷新时才会变，WS 推送不带就一直是旧值）。
     * <p>
     * <b>⚠️ 这里<b>不再</b>每台设备查一次库</b>（2026-11-07 的 1000 台压测发现）：
     * 原来这里是 {@code synchronized} + 2 次查库，1000 台 × 1 秒 ⇒ 1000 次全部串行在一把锁上
     * ⇒ 锁占用率 71.5%、最慢的设备等锁 883ms ⇒ 采集周期被拉长。
     * ⇒ 现在：<b>已落库部分</b>读 {@link #todayFromDb} 批量快照（每秒由 {@code scan()} 刷一次），
     * <b>当前小时</b>仍读内存实时值（{@link #liveOutput}）⇒ 查库 1000 次/秒 → 1 次/秒。
     * <p>
     * <b>口径一个字节没改</b>（见 {@code ProdRollupTodayOutputTest} 的 6 条契约）：
     * <ol>
     *   <li>DB 多行 ⇒ 累加</li>
     *   <li>DB 行里当前小时那行 ⇒ 用内存实时值<b>替换</b>它</li>
     *   <li>DB <b>没有</b>当前小时行、但内存桶有值 ⇒ 把内存值<b>加</b>上去</li>
     *   <li>设备查不到 ⇒ 返回 0</li>
     * </ol>
     * ⇒ 快照只存「<b>当前小时之前</b>的已落库产量和」，当前小时一律由内存实时值决定，
     * 这样契约②③ 天然成立（DB 有没有当前小时行，都由 liveOutput 说了算）。
     */
    public double todayOutputOf(String code) {
        long now = System.currentTimeMillis();
        long curHour = now - (now % HOUR_MS);

        Map<String, TodayOut> snap = todayFromDb.get();
        TodayOut t = snap.get(code);
        if (t == null) {
            // 快照里还没有这台设备（新加的、或快照还没刷出来）⇒ 走原来的查库路径，
            // 保证「第一次推送就能拿到正确的今日产量」，而不是先推一个 0。
            return todayOutputOfByQuery(code, now, curHour);
        }

        double live = liveOutput(code, curHour);
        boolean hasLive = !Double.isNaN(live);

        if (hasLive) {
            // 契约②③：有内存实时值 ⇒ 当前小时由它说了算（DB 那行被**替换**，不是相加）
            return t.closedSum() + live;
        }
        // 契约④：没有内存实时值 ⇒ 当前小时若 DB 里有行就用它
        // ⚠️ 跨小时时快照里的「当前小时」是上一个小时的值 ⇒ 不能用（会重复算）
        if (t.hasCurHour() && todaySnapshotHour == curHour) {
            return t.closedSum() + t.curHourDb();
        }
        return t.closedSum();
    }

    /** 快照缺失时的兜底：按原来的逻辑查库算（口径与快照路径一致） */
    private double todayOutputOfByQuery(String code, long now, long curHour) {
        DeviceEntity d = deviceService.byCode(code);
        if (d == null) {
            return 0d;
        }
        int rh = d.getProdResetHour();
        long start = ProdDay.dayStart(now, rh);
        double sum = 0;
        boolean hasCur = false;
        for (ProdHourly p : repo.findByDeviceCodeAndHourStartBetween(code, start, now)) {
            double v = p.getOutput();
            if (p.getHourStart() == curHour) {
                hasCur = true;
                double live = liveOutput(code, curHour);
                if (!Double.isNaN(live)) {
                    v = live;
                }
            }
            sum += v;
        }
        if (!hasCur) {
            double live = liveOutput(code, curHour);
            if (!Double.isNaN(live)) {
                sum += live;
            }
        }
        return sum;
    }

    /**
     * 删除设备后清掉它的全部产量痕迹：
     * DB 的小时行（今日/本月/今年综合表都从这里聚合）+ 内存桶 + 统计基准 + 计数器水位。
     * 重建设备后产量从零开始；寄存器首读只当基准，不会把 PLC 现值算进产量。
     */
    public synchronized void purgeByCode(String code) {
        // 先清内存（防止下一次 checkpoint 把内存数据写回 DB），再删 DB 行
        buckets.keySet().removeIf(k -> k.startsWith(code + "|"));
        states.remove(code);
        repo.deleteByDeviceCode(code);
        // 设备被删了 ⇒ 快照里它的今日产量也作废（否则会一直推一个已删设备的产量）
        invalidateTodaySnapshot();
        // DH-81 水位也要删：留着的话，同编码设备重建后新表读数若比旧水位大，
        // 差值会被当成"这一拍生产的"计进产量和入库单据（凭空多出一批货）。
        try {
            counterRepo.deleteById(code);
        } catch (Exception e) {
            log.warn("清理设备 {} 的计数器水位失败（不影响删设备）: {}", code, e.getMessage());
        }
    }

    private void flush(boolean dropClosedHours) {
        if (buckets.isEmpty()) {
            return;
        }
        long currentHour = System.currentTimeMillis();
        currentHour -= currentHour % HOUR_MS;

        Map<Long, Map<String, ProdHourly>> byHour = new HashMap<>();
        for (Map.Entry<String, ProdHourly> e : buckets.entrySet()) {
            byHour.computeIfAbsent(e.getValue().getHourStart(), k -> new LinkedHashMap<>())
                    .put(e.getKey(), e.getValue());
        }

        for (Map.Entry<Long, Map<String, ProdHourly>> hourEntry : byHour.entrySet()) {
            long hour = hourEntry.getKey();
            Map<String, ProdHourly> existing = new HashMap<>();
            try {
                for (ProdHourly r : repo.findByHourStart(hour)) {
                    existing.put(r.getDeviceCode(), r);
                }
            } catch (Exception ex) {
                log.warn("读取小时生产数据失败（{}），本轮跳过这一小时", ex.getMessage());
                continue;
            }
            List<ProdHourly> rows = new ArrayList<>();
            for (Map.Entry<String, ProdHourly> e : hourEntry.getValue().entrySet()) {
                ProdHourly cur = e.getValue();
                if (cur.getDeviceCode() == null) {
                    continue;                 // 只有被写过的桶才有设备码
                }
                ProdHourly old = existing.get(cur.getDeviceCode());
                if (old != null) {
                    // 内存里是这一小时的全量累计，直接覆盖
                    old.setDeviceName(cur.getDeviceName());
                    old.setRunSec(cur.getRunSec());
                    old.setIdleSec(cur.getIdleSec());
                    old.setStopSec(cur.getStopSec());
                    old.setAlarmSec(cur.getAlarmSec());
                    old.setUnknownSec(cur.getUnknownSec());
                    old.setOutput(cur.getOutput());
                    old.setBackfill(cur.getBackfill());
                    rows.add(old);
                } else {
                    rows.add(cur);
                }
            }
            if (rows.isEmpty()) {
                continue;
            }
            try {
                repo.saveAll(rows);
                // ⚠️ **按写失效**（不按时间失效）：DB 里这些设备的今日产量变了 ⇒
                //   快照里的旧值不能再用 ⇒ 立刻作废，下一拍 scan() 会重建。
                //   ⇒ 不依赖任何关于时钟/落库周期的推理 ⇒ 不会算出过期值。
                invalidateTodaySnapshot();
            } catch (Exception ex) {
                log.error("小时生产数据写库失败（内存保留，下轮重试）: {}", ex.getMessage());
                continue;
            }
            if (dropClosedHours && hour < currentHour) {
                // 已经过去的小时不会再变，从内存里扔掉，防止长期运行越攒越多
                Iterator<Map.Entry<String, ProdHourly>> it = buckets.entrySet().iterator();
                while (it.hasNext()) {
                    if (it.next().getValue().getHourStart() < currentHour) {
                        it.remove();
                    }
                }
            }
        }
    }

    /** 取某个时刻所属小时、某台设备的累计对象，没有就建一个 */
    private ProdHourly bucket(String deviceCode, long ts) {
        long hour = ts - (ts % HOUR_MS);
        String key = deviceCode + "|" + hour;
        ProdHourly p = buckets.get(key);
        if (p != null) {
            return p;
        }
        return buckets.computeIfAbsent(key, k -> {
            ProdHourly n = new ProdHourly(deviceCode, deviceName(deviceCode), hour);
            // 服务重启后内存桶从零开始，而 flush 是覆盖写——若不继承已落库的
            // 当前小时累计，重启前的增量会被内存里的零值冲掉。
            // 从 DB 快照继续累计（产量 + 各状态时长全量继承）。
            ProdHourly saved = repo.findByDeviceCodeAndHourStart(deviceCode, hour);
            if (saved != null) {
                n.setOutput(saved.getOutput());
                n.setBackfill(saved.getBackfill());
                n.setRunSec(saved.getRunSec());
                n.setIdleSec(saved.getIdleSec());
                n.setStopSec(saved.getStopSec());
                n.setAlarmSec(saved.getAlarmSec());
                n.setUnknownSec(saved.getUnknownSec());
            }
            return n;
        });
    }

    /**
     * 该设备在 hourStart 所属小时的<b>内存实时产量</b>（秒级，未等 60s checkpoint 落库）。
     * <p>
     * 内存桶里存的是这一小时的累计<b>全量</b>（flush 是覆盖写，不是追加），
     * 所以报表接口对「当前小时」直接取这里的值，KPI/图表就能实时跟上按键；
     * 历史小时已从内存清掉（flush dropClosedHours），返回 {@code Double.NaN}，
     * 调用方回退到数据库行。
     */
    public double liveOutput(String deviceCode, long hourStart) {
        long hour = hourStart - (hourStart % HOUR_MS);
        ProdHourly p = buckets.get(deviceCode + "|" + hour);
        return p == null ? Double.NaN : p.getOutput();
    }

    /** 该设备当前小时的内存实时产量；没有内存桶（这一小时还没有任何采样）返回 NaN */
    public double liveOutputNow(String deviceCode) {
        long hour = System.currentTimeMillis();
        return liveOutput(deviceCode, hour);
    }

    /**
     * 该设备在 hourStart 所属小时的<b>内存实时运行秒数</b>（未等整点落库）。
     * <p>
     * 成本核算需要它：产量表是<b>整点才落库</b>的，一张上午 10:13 开工、
     * 10:40 完工的工单，查 10:00 那一行永远是空的——那个小时还没结束。
     * 不读内存桶的话，人工成本只能退化成「开工到完工的墙钟时间」，把停机也算进去。
     * <p>
     * 返回 {@code NaN} 表示这一小时还没有任何采样（设备没接/刚启动），调用方自行兜底。
     */
    public double liveRunSec(String deviceCode, long hourStart) {
        long hour = hourStart - (hourStart % HOUR_MS);
        ProdHourly p = buckets.get(deviceCode + "|" + hour);
        return p == null ? Double.NaN : p.getRunSec();
    }

    /**
     * 全部设备的「今日产量」，按<b>各自清零时刻</b>的统计日口径，
     * live（当前小时内存桶）+ DB（已落库小时行）混合 —— 与产量统计页 KPI 完全同源。
     * 设备卡片完成率、车间大屏目标红绿灯都用它。
     * <p>
     * 48 小时窗口覆盖任何清零时刻（跨天班次）：窗口外的行不可能属于今天的统计日。
     */
    public Map<String, Double> todayByDevice(List<DeviceEntity> devices) {
        long now = System.currentTimeMillis();
        LocalDate today = LocalDate.now();
        Map<String, Integer> reset = new HashMap<>();
        for (DeviceEntity d : devices) {
            reset.put(d.getCode(), d.getProdResetHour());
        }
        Map<String, Double> out = new HashMap<>();
        for (DeviceEntity d : devices) {
            out.put(d.getCode(), 0d);
        }
        if (devices.isEmpty()) {
            return out;
        }
        List<ProdHourly> rows = repo.findByHourStartBetween(now - 48L * HOUR_MS, now);
        long curHour = now - (now % HOUR_MS);
        Set<String> curHourRows = new HashSet<>();
        for (ProdHourly p : rows) {
            Integer rh = reset.get(p.getDeviceCode());
            if (rh == null) {
                continue;
            }
            if (!ProdDay.bizDate(p.getHourStart(), rh).equals(today)) {
                continue;
            }
            double v = p.getOutput();
            if (p.getHourStart() == curHour) {
                curHourRows.add(p.getDeviceCode());
                double live = liveOutput(p.getDeviceCode(), p.getHourStart());
                if (!Double.isNaN(live)) {
                    v = live;
                }
            }
            out.merge(p.getDeviceCode(), v, Double::sum);
        }
        // 内存桶兜底：当前小时已有增量但 checkpoint（60s）还没落库时，
        // 上面按 DB 行驱动的循环看不见它，今日产量会滞后最多 1 分钟。
        // 这里把「无 DB 行、但内存桶有数」的当前小时增量补上，
        // 让「按一下 +1」在设备卡片和车间大屏上秒级可见。
        for (DeviceEntity d : devices) {
            String code = d.getCode();
            if (curHourRows.contains(code)) {
                continue;
            }
            double live = liveOutput(code, curHour);
            if (!Double.isNaN(live) && live != 0d) {
                out.merge(code, live, Double::sum);
            }
        }
        return out;
    }

    private String deviceName(String code) {
        try {
            var d = deviceService.byCode(code);
            if (d != null && d.getName() != null) {
                return d.getName();
            }
        } catch (Exception ignored) {
        }
        return code;
    }

    private PointValue pick(Map<String, PointValue> points, String role) {
        PointValue fallback = null;
        for (PointValue pv : points.values()) {
            if (!pv.isGood()) {
                continue;
            }
            if (role.equals(pv.role())) {
                return pv;
            }
            if (fallback == null && pv.asDouble() != null) {
                fallback = pv;
            }
        }
        return null;   // 没有对应的角色就返回 null，绝不拿别的点凑数
    }

    /** 设备被删掉后状态还留在内存里，长期运行会涨，定期清 */
    private void cleanupStates() {
        Set<String> alive = new HashSet<>(store.deviceCodes());
        if (states.size() <= alive.size() * 3 + 32) {
            return;
        }
        states.keySet().removeIf(k -> !alive.contains(k));
    }

    private void maybeCleanupOldData(long now) {
        java.time.LocalDate today = java.time.LocalDate.now();
        long dayKey = today.toEpochDay();
        if (lastCleanupDay == dayKey) {
            return;
        }
        lastCleanupDay = dayKey;
        long before = now - RETAIN_DAYS * 24L * HOUR_MS;
        try {
            int n = repo.deleteOlderThan(before);
            if (n > 0) {
                log.info("清理 {} 天前的小时生产数据 {} 行", RETAIN_DAYS, n);
            }
        } catch (Exception e) {
            log.warn("清理历史生产数据失败: {}", e.getMessage());
        }
    }
}
