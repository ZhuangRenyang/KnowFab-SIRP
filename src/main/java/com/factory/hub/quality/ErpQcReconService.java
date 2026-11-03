package com.factory.hub.quality;

import com.factory.hub.erp.ErpWorkOrder;
import com.factory.hub.erp.ErpWorkOrderRepository;
import com.factory.hub.report.ProdHourly;
import com.factory.hub.report.ProdHourlyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 双通道对账：设备采到的不良数 vs 质量域记录的不良数（QC-SPEC §4.2）。
 * <p>
 * <b>两个通道本来就是两套数</b>（Spec §2.4）：设备是"机床计数器说的"，质检是"人判的"。
 * 对账条把差值摆出来，让人决定"以哪个为准"，而不是让系统悄悄挑一个。
 * <p>
 * <b>校准只进质量域，绝不改 {@code prod_hourly.defect}</b>：
 * 那是 OEE 良率的唯一数据源，改了它，已出过的月报就再也对不上了。
 * 所以"以质检为准"是<b>标记</b>质量域里哪些通道算数，
 * <b>设备原始值仍然保留在 {@code prod_hourly} 里</b>，对账条上会写明这一点。
 *
 * <p><b>定责是「标记」不是「改数」</b>（QC-SPEC §2.5.1）：
 * <ul>
 *   <li>改数会让合计越校越大——质检口径本身含设备行，
 *       {@code DEVICE := qcQty} 之后 3+2 变成 5+2=7，再定一次 7+2=9；</li>
 *   <li>改数还会被同步撤销——设备通道的行是 {@code prod_hourly} 的镜像，
 *       改它的 qty，下一轮同步（每 5 分钟）会按镜像值把差额补回去。</li>
 * </ul>
 * 所以定责只改 {@code recon_state} 标记：哪个通道 {@code KEEP}（算数）、
 * 哪个 {@code DROP}（不计入）。<b>定责前后合计口径稳定</b>，重复定责结果相同。
 */
@Service
public class ErpQcReconService {

    /** 以质检为准：质量域向质检数校准 */
    public static final String CHOICE_TRUST_QC = "TRUST_QC";
    /** 以设备为准：质量域向设备采集数校准 */
    public static final String CHOICE_TRUST_DEVICE = "TRUST_DEVICE";

    private final ErpWorkOrderRepository workOrderRepo;
    private final ProdHourlyRepository prodRepo;
    private final ErpQcDefectRepository defectRepo;
    private final com.factory.hub.audit.AuditService audit;
    private final com.factory.hub.service.DeviceService deviceService;

    public ErpQcReconService(ErpWorkOrderRepository workOrderRepo,
                              ProdHourlyRepository prodRepo,
                              ErpQcDefectRepository defectRepo,
                              com.factory.hub.audit.AuditService audit,
                              com.factory.hub.service.DeviceService deviceService) {
        this.workOrderRepo = workOrderRepo;
        this.prodRepo = prodRepo;
        this.defectRepo = defectRepo;
        this.audit = audit;
        this.deviceService = deviceService;
    }

    /**
     * 对账条列表。
     * <p>
     * {@code gap} 与 {@code challenged} 在后端算好：差多少、算不算冲突是业务口径，
     * 散在前端两处就会不一致（这正是 F2/F3 那类"两处各写一份"的毛病）。
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(Long from, Long to) {
        long f = from == null ? 0L : from;
        long t = to == null ? System.currentTimeMillis() : to;
        List<Map<String, Object>> out = new ArrayList<>();
        for (ErpWorkOrder o : workOrderRepo.findByStatusIn(
                List.of(ErpWorkOrder.ST_RUNNING, ErpWorkOrder.ST_DONE))) {
            long start = o.getActualStart() != null ? o.getActualStart()
                    : (o.getPlanStart() != null ? o.getPlanStart() : 0L);
            long end = o.getActualEnd() != null ? o.getActualEnd() : t;
            if (start > t || end < f) {
                continue;
            }
            BigDecimal deviceQty = deviceQtyOf(o.getDeviceCode(), start, end);
            BigDecimal qcQty = qcQtyOf(o.getOrderNo());   // 人工判的数
            BigDecimal gap = qcQty.subtract(deviceQty);
            // 生效值：走统一口径（未定责时设备优先，人工不叠加；定责后以定责为准）
            BigDecimal effective = effectiveQtyOf(o.getOrderNo());
            // 这台设备到底能不能采：没配 reject 点位，"设备数"就是不存在的数
            boolean deviceConfigured = o.getDeviceCode() != null
                    && deviceService.hasRejectPoint(o.getDeviceCode());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("workOrderNo", o.getOrderNo());
            m.put("deviceCode", o.getDeviceCode());
            m.put("deviceQty", deviceQty);
            m.put("manualQty", qcQty);
            m.put("gap", gap);
            m.put("challenged", gap.signum() != 0);
            m.put("effectiveQty", effective);
            m.put("deviceConfigured", deviceConfigured);
            m.put("deviceCollected", deviceQty.signum() > 0);
            m.put("source", sourceCode(o.getDeviceCode(), deviceConfigured, deviceQty, qcQty));
            out.add(m);
        }
        out.sort((a, b) -> {
            BigDecimal ga = (BigDecimal) a.get("gap");
            BigDecimal gb = (BigDecimal) b.get("gap");
            return gb.abs().compareTo(ga.abs());
        });
        return out;
    }

    /**
     * 逐工单定"以哪个为准"并写审计。
     * <p>
     * 校准只改质量域，{@code prod_hourly} 一个字都不动——
     * 它是 OEE 良率的唯一数据源，改了已出过的月报就对不上了。
     */
    @Transactional
    public Map<String, Object> resolve(String workOrderNo, String choice, String reason,
                                       String operator) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("定责必须填原因（对账结论要能追溯谁定的、为什么）");
        }
        if (!CHOICE_TRUST_QC.equals(choice) && !CHOICE_TRUST_DEVICE.equals(choice)) {
            throw new IllegalArgumentException("choice 只能是 TRUST_QC（以质检为准）或 TRUST_DEVICE（以设备为准）");
        }
        ErpWorkOrder o = workOrderRepo.findByOrderNo(workOrderNo)
                .orElseThrow(() -> new IllegalArgumentException("工单不存在：" + workOrderNo));
        long start = o.getActualStart() != null ? o.getActualStart()
                : (o.getPlanStart() != null ? o.getPlanStart() : 0L);
        long end = o.getActualEnd() != null ? o.getActualEnd() : System.currentTimeMillis();

        BigDecimal deviceQty = deviceQtyOf(o.getDeviceCode(), start, end);
        BigDecimal qcQty = qcQtyOf(workOrderNo);
        BigDecimal target = CHOICE_TRUST_QC.equals(choice) ? qcQty : deviceQty;

        // 当前设备通道在质量域里的合计（这些行是"机器数"的镜像，不该被人改）
        BigDecimal mirrored = BigDecimal.ZERO;
        for (ErpQcDefect d : defectRepo.findByWorkOrderNoOrderByIdAsc(workOrderNo)) {
            if (ErpQcDefect.SRC_DEVICE.equals(d.getSource()) && !d.isVoided()) {
                mirrored = mirrored.add(d.getQty());
            }
        }
        BigDecimal diff = target.subtract(mirrored);
        BigDecimal before = effectiveQtyOf(workOrderNo);

        // 定责 = 标记哪个通道算数，<b>不改任何一行 qty</b>。
        // 理由两条（QC-SPEC §2.5.1）：
        //   ① 改数会让合计越校越大（qcQty 含 DEVICE 行，DEVICE := qcQty → 3+2 变 5+2）；
        //   ② 改数会被同步撤销（设备通道的行是 prod_hourly 的镜像，下一轮同步按镜像补差额）。
        // 已处置的记录一个字不动 —— 那是既成事实（可能已经报废出库了），
        // 所以只有未处置的行会被标记；已处置的仍计入统计（那是已发生的损失）。
        boolean trustQc = CHOICE_TRUST_QC.equals(choice);
        int changed = 0;
        for (ErpQcDefect d : defectRepo.findByWorkOrderNoOrderByIdAsc(workOrderNo)) {
            if (d.isVoided() || ErpQcDefect.SRC_RECON_ADJ.equals(d.getSource())) {
                continue;
            }
            if (!ErpQcDefect.ST_PENDING.equals(d.getStatus())) {
                continue;   // 已处置：既成事实，不参与定责标记
            }
            boolean isDevice = ErpQcDefect.SRC_DEVICE.equals(d.getSource());
            // 以质检为准 → 设备通道不计入；以设备为准 → 人工通道不计入
            String want = (trustQc != isDevice) ? ErpQcDefect.RECON_KEEP : ErpQcDefect.RECON_DROP;
            if (!want.equals(d.getReconState())) {
                d.setReconState(want);
                d.setUpdatedAt(System.currentTimeMillis());
                defectRepo.save(d);
                changed++;
            }
        }
        audit.ok(com.factory.hub.audit.AuditLog.CAT_QUALITY, com.factory.hub.audit.AuditLog.ACT_UPDATE,
                workOrderNo, "对账定责 " + choice,
                "设备 " + deviceQty.toPlainString() + " / 质检 " + qcQty.toPlainString()
                        + " → 校准为 " + target.toPlainString() + "：" + reason);

        BigDecimal after = effectiveQtyOf(workOrderNo);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("workOrderNo", workOrderNo);
        out.put("choice", choice);
        out.put("deviceQty", deviceQty);
        out.put("qcQty", qcQty);
        out.put("calibratedQty", target);
        out.put("changedRecords", changed);
        // 定责前后的有效通道合计：定责是标记不是改数，合计口径不会因为定责而膨胀。
        // 这两个数一起给，界面上能看出"定责改变的是谁说了算，不是总数"。
        out.put("effectiveQtyBefore", before);
        out.put("effectiveQtyAfter", after);
        return out;
    }

    /**
     * 对账条的来源代号（前端按它渲染，不给中文文案）。
     * <ul>
     *   <li>{@code NO_DEVICE}：工单没设备，或设备没配 reject 点位——没有设备数这一说</li>
     *   <li>{@code DEVICE_PRIORITY}：设备采到了数，按设备优先（人工不叠加）</li>
     *   <li>{@code MANUAL_ONLY}：设备没数，人工是唯一来源</li>
     * </ul>
     */
    private String sourceCode(String deviceCode, boolean deviceConfigured,
                              BigDecimal deviceQty, BigDecimal manualQty) {
        if (deviceCode == null || !deviceConfigured) {
            return "NO_DEVICE";
        }
        if (deviceQty.signum() > 0) {
            return "DEVICE_PRIORITY";
        }
        return "MANUAL_ONLY";
    }

    /**
     * 某张工单当前的有效通道合计（走统一口径 {@link ErpQcChannelRule}）。
     * <p>这是"定责后这个工单算几件不良"的唯一答案来源——
     * 不用 sum、也不自己判断通道，因为那正是各写一份、漏一处就重复计数的根源。
     */
    private BigDecimal effectiveQtyOf(String workOrderNo) {
        List<ErpQcDefect> rows = defectRepo.findByWorkOrderNoOrderByIdAsc(workOrderNo);
        List<ErpQcDefect> mine = new ArrayList<>();
        for (ErpQcDefect d : rows) {
            if (!ErpQcDefect.SRC_RECON_ADJ.equals(d.getSource())) {
                mine.add(d);
            }
        }
        return ErpQcChannelRule.effectiveQtyOfGroup(mine);
    }

    /** 设备采集的不良数（该工单时间窗内、该设备的小时行之和） */
    private BigDecimal deviceQtyOf(String deviceCode, long from, long to) {
        if (deviceCode == null) {
            return BigDecimal.ZERO;
        }
        // 走统一算法（ErpQcDeviceQty）：与人工补录基线用的是同一份，
        // 且按"小时与时间窗有交集"算，不会漏掉开工/完工那一小时
        return ErpQcDeviceQty.of(prodRepo, deviceCode, from, to);
    }

    /**
     * <b>人工判的</b>不良数：只算非设备来源的记录（MANUAL / INSPECTION / RETURN）。
     * <p>
     * 设备通道的记录是 {@code prod_hourly} 的镜像，不是"人的判断"，
     * 把它算进质检口径就变成自己跟自己比，gap 永远是 0。
     */
    private BigDecimal qcQtyOf(String workOrderNo) {
        BigDecimal sum = BigDecimal.ZERO;
        for (ErpQcDefect d : defectRepo.findByWorkOrderNoOrderByIdAsc(workOrderNo)) {
            if (d.isVoided() || ErpQcDefect.SRC_DEVICE.equals(d.getSource())
                    || ErpQcDefect.SRC_RECON_ADJ.equals(d.getSource())) {
                continue;
            }
            sum = sum.add(d.getQty());
        }
        return sum.setScale(4, RoundingMode.HALF_UP);
    }
}
