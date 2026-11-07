package com.factory.hub.erp.quality.defect;

import com.factory.hub.erp.production.ErpWorkOrder;
import com.factory.hub.erp.production.ErpWorkOrderOutput;
import com.factory.hub.erp.production.ErpWorkOrderOutputRepository;
import com.factory.hub.erp.production.ErpWorkOrderRepository;
import com.factory.hub.model.DeviceEntity;
import com.factory.hub.report.ProdHourly;
import com.factory.hub.report.ProdHourlyRepository;
import com.factory.hub.service.DeviceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

/**
 * 设备通道同步：把 {@code prod_hourly} 采到的不良数变成待处置的不良记录（QC-SPEC §4.2）。
 * <p>
 * <b>幂等键必须是 {@code DEV:设备码:小时起点}</b>，这个键不能改：
 * <ul>
 *   <li><b>不能带工单号</b>：一小时里工单会切换（10:30 完工、10:40 开新单），
 *       而 {@code prod_hourly} 那一小时只有一行 defect。键里带工单号 → 同一小时两个键 →
 *       两条记录各拿整小时的 defect → <b>数量翻倍</b>。工单归属是事后指认，不是身份；</li>
 *   <li><b>不能绑检验单</b>：设备采的不良根本没人检、没抽样、没判定，绑了等于让
 *       大部分设备不良无处可去；</li>
 *   <li><b>不能按天</b>：丢时间分布，且跨天补记会算错。</li>
 * </ul>
 * <p>
 * <b>只处理封口 1 小时后的小时</b>（{@code hourStart + 2h <= now}）：
 * {@code ProdRollup} 会把断线增量一次性补记到已经过去的小时行上，
 * 刚封口就同步会漏掉补记部分，而补记的那部分永远不会再被捡回来。
 * <p>
 * <b>已存在的记录怎么处理</b>：
 * <ul>
 *   <li>{@code PENDING} → 用当前的 {@code prod_hourly.defect} <b>覆盖</b> qty（还没处置，覆盖没有副作用）；</li>
 *   <li>{@code DISPOSED} → <b>不改已处置的数量</b>，而是新建一条补记行
 *       （{@code source_key} 加 {@code :#n} 后缀，qty = 差额）。改已处置的数量等于篡改历史。</li>
 * </ul>
 * <p>
 * <b>{@code defect_code_id} 恒为 NULL</b>：设备只知道"坏了几件"，不知道为什么坏。
 * 分类要人分诊（看板上的"未分类"卡片就是它）。{@code sku_id} 允许为 NULL（设备没开工单时）。
 */
@Service
public class ErpDefectSyncService {

    private static final Logger log = LoggerFactory.getLogger(ErpDefectSyncService.class);

    /** 幂等键前缀 */
    public static final String KEY_PREFIX = "DEV:";
    /** 补记行的后缀分隔符（{@code DEV:设备:小时:#2}） */
    private static final String SUFFIX = ":#";

    /** 一个小时行封口后多久才同步：1 小时（断线补记最长补 1 小时） */
    private static final long SETTLE_MS = 2 * 3600_000L;
    /** 一次回溯多久（补数、修复历史数据时能用到） */
    private static final long LOOKBACK_MS = 7 * 24 * 3600_000L;

    private final ProdHourlyRepository prodRepo;
    private final ErpQcDefectRepository defectRepo;
    private final ErpWorkOrderOutputRepository outputRepo;
    private final ErpWorkOrderRepository workOrderRepo;
    private final DeviceService deviceService;

    public ErpDefectSyncService(ProdHourlyRepository prodRepo,
                                ErpQcDefectRepository defectRepo,
                                ErpWorkOrderOutputRepository outputRepo,
                                ErpWorkOrderRepository workOrderRepo,
                                DeviceService deviceService) {
        this.prodRepo = prodRepo;
        this.defectRepo = defectRepo;
        this.outputRepo = outputRepo;
        this.workOrderRepo = workOrderRepo;
        this.deviceService = deviceService;
    }

    /** 定时同步：每 5 分钟一次，回溯 7 天（覆盖补数场景） */
    @Scheduled(fixedDelay = 300_000L)
    public void syncScheduled() {
        try {
            int n = sync(System.currentTimeMillis());
            if (n > 0) {
                log.info("设备不良同步：新增/更新 {} 条", n);
            }
        } catch (Exception e) {
            // 同步失败不能影响采集与生产
            log.warn("设备不良同步失败（不影响采集）：{}", e.getMessage());
        }
    }

    /**
     * 同步一次。返回处理条数（新增 + 覆盖 + 补记）。
     *
     * @param nowTs 当前时间戳（测试可传固定值）
     */
    @Transactional
    public int sync(long nowTs) {
        long to = nowTs - SETTLE_MS;
        long from = to - LOOKBACK_MS;
        int n = 0;
        for (ProdHourly row : prodRepo.findInWindow(from, to)) {
            if (row.getDefect() <= 0) {
                continue;
            }
            if (syncOne(row)) {
                n++;
            }
        }
        return n;
    }

    /**
     * 同步一个小时行。返回是否产生了写操作。
     */
    private boolean syncOne(ProdHourly row) {
        String key = KEY_PREFIX + row.getDeviceCode() + ":" + row.getHourStart();
        BigDecimal qty = BigDecimal.valueOf(row.getDefect()).setScale(4, RoundingMode.HALF_UP);

        // 同一基键下的全部记录：基础行 + 所有 #n 补记行
        List<ErpQcDefect> rows = defectRepo.findBySourceKeyGroup(key);
        if (rows.isEmpty()) {
            defectRepo.save(newRecord(row, key, qty));
            return true;
        }
        // 差额基准 = 这一组记录的件数之和。
        // 用单条记录当基准会让每轮同步都补记一次（prod_hourly 不变时也一直补），
        // 补记行无限增殖——这是 QA 静态验收 B2 抓到的真 bug。
        BigDecimal already = BigDecimal.ZERO;
        ErpQcDefect base = null;
        for (ErpQcDefect r : rows) {
            already = already.add(r.getQty());
            if (key.equals(r.getSourceKey())) {
                base = r;
            }
        }
        BigDecimal diff = qty.subtract(already);
        if (diff.signum() == 0) {
            return false;
        }
        // 基础行还在待处置 → 差额并进它，不新建行（避免补记行越攒越多）
        if (base != null && ErpQcDefect.ST_PENDING.equals(base.getStatus()) && !base.isVoided()) {
            base.setQty(base.getQty().add(diff));
            base.setUpdatedAt(System.currentTimeMillis());
            defectRepo.save(base);
            return true;
        }
        // 基础行已处置/已作废：不能改它的数量（那是既成事实），差额另开一条补记行
        if (diff.signum() < 0) {
            return false;
        }
        ErpQcDefect memo = newRecord(row, nextMemoKey(key), diff);
        defectRepo.save(memo);
        return true;
    }

    /** 新建一条设备不良记录 */
    private ErpQcDefect newRecord(ProdHourly row, String key, BigDecimal qty) {
        ErpQcDefect d = new ErpQcDefect();
        d.setDefectNo(nextDefectNo());
        d.setSource(ErpQcDefect.SRC_DEVICE);
        d.setSourceKey(key);
        d.setDeviceCode(row.getDeviceCode());
        d.setQty(qty);
        // 设备采到的不良是在制品，<b>没有入过账</b> → 处置时不动库存
        d.setInStock(false);
        d.setStatus(ErpQcDefect.ST_PENDING);
        d.setDisposedQty(BigDecimal.ZERO);
        d.setFoundAt(row.getHourStart());
        d.setRegisteredBy("system");
        // 缺陷分类恒为空：设备只知道坏了几件，不知道为什么坏，要人分诊
        d.setDefectCodeId(null);
        d.setSeverity(null);
        d.setRemark("设备采集（" + row.getDeviceCode() + "，" + hourText(row.getHourStart()) + "）");
        d.setCreatedAt(System.currentTimeMillis());
        d.setUpdatedAt(d.getCreatedAt());

        applyOwnership(d, row);
        return d;
    }

    /**
     * 归属：优先取那一小时的产出流水（工单驱动），没有就取设备绑定的固定产品。
     * <p>
     * 用产出流水而不是"当前在产工单"，是因为一小时里可能换过工单：
     * 10:30 完工、10:40 开新单，这一小时的产出到底算给谁，要看流水而不是看现在。
     */
    private void applyOwnership(ErpQcDefect d, ProdHourly row) {
        long hourFrom = row.getHourStart();
        long hourTo = hourFrom + 3600_000L;
        List<ErpWorkOrderOutput> outs = outputRepo
                .findByDeviceCodeAndCreatedAtBetweenOrderByCreatedAtAsc(
                        row.getDeviceCode(), hourFrom, hourTo);
        if (!outs.isEmpty()) {
            ErpWorkOrderOutput last = outs.get(outs.size() - 1);
            d.setWorkOrderNo(last.getWorkOrderNo());
            workOrderRepo.findById(last.getWorkOrderId()).ifPresent(o -> {
                d.setSkuId(o.getProductSkuId());
                d.setSkuCode(o.getProductCode());
                d.setSkuName(o.getProductName());
            });
            return;
        }
        // 没有流水：设备固定产品
        DeviceEntity dev = deviceService.byCode(row.getDeviceCode());
        if (dev != null && dev.getProdSkuId() != null) {
            d.setSkuId(dev.getProdSkuId());
        }
    }

    /** 补记行的键：{@code DEV:设备:小时:#n}，n 从 1 开始找空位 */
    private String nextMemoKey(String base) {
        for (int n = 1; n < 1000; n++) {
            String key = base + SUFFIX + n;
            if (defectRepo.findBySourceKey(key).isEmpty()) {
                return key;
            }
        }
        throw new IllegalStateException("补记行过多，无法为 " + base + " 找到空位");
    }

    private String nextDefectNo() {
        String base = "BLP" + java.time.LocalDate.now().format(
                java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd")) + "-";
        String max = defectRepo.maxNoByPrefix(base);
        int n = 1;
        if (max != null && max.length() > base.length()) {
            try {
                n = Integer.parseInt(max.substring(base.length())) + 1;
            } catch (NumberFormatException ignored) {
                n = 1;
            }
        }
        return base + String.format("%04d", n);
    }

    private static String hourText(long hourStart) {
        return java.time.Instant.ofEpochMilli(hourStart)
                .atZone(java.time.ZoneId.systemDefault())
                .toLocalDateTime().toString().replace('T', ' ') + " 时";
    }
}
