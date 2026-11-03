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
 * 所以"以质检为准"是把质量域里那条设备通道记录的 qty 改成质检数，
 * <b>设备原始值仍然保留在 {@code prod_hourly} 里</b>，对账条上会写明这一点。
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

    public ErpQcReconService(ErpWorkOrderRepository workOrderRepo,
                              ProdHourlyRepository prodRepo,
                              ErpQcDefectRepository defectRepo,
                              com.factory.hub.audit.AuditService audit) {
        this.workOrderRepo = workOrderRepo;
        this.prodRepo = prodRepo;
        this.defectRepo = defectRepo;
        this.audit = audit;
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
            BigDecimal qcQty = qcQtyOf(o.getOrderNo());
            BigDecimal gap = qcQty.subtract(deviceQty);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("workOrderNo", o.getOrderNo());
            m.put("deviceCode", o.getDeviceCode());
            m.put("deviceQty", deviceQty);
            m.put("manualQty", qcQty);
            m.put("gap", gap);
            m.put("challenged", gap.signum() != 0);
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

        // 改的是质量域里那条设备通道记录（source=DEVICE 且属于这张工单）
        int changed = 0;
        for (ErpQcDefect d : defectRepo.findByWorkOrderNoOrderByIdAsc(workOrderNo)) {
            if (!ErpQcDefect.SRC_DEVICE.equals(d.getSource()) || d.isVoided()) {
                continue;
            }
            d.setQty(target);
            d.setUpdatedAt(System.currentTimeMillis());
            defectRepo.save(d);
            changed++;
        }
        audit.ok(com.factory.hub.audit.AuditLog.CAT_QUALITY, com.factory.hub.audit.AuditLog.ACT_UPDATE,
                workOrderNo, "对账定责 " + choice,
                "设备 " + deviceQty.toPlainString() + " / 质检 " + qcQty.toPlainString()
                        + " → 校准为 " + target.toPlainString() + "：" + reason);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("workOrderNo", workOrderNo);
        out.put("choice", choice);
        out.put("deviceQty", deviceQty);
        out.put("qcQty", qcQty);
        out.put("calibratedQty", target);
        out.put("changedRecords", changed);
        return out;
    }

    /** 设备采集的不良数（该工单时间窗内、该设备的小时行之和） */
    private BigDecimal deviceQtyOf(String deviceCode, long from, long to) {
        if (deviceCode == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (ProdHourly p : prodRepo.findByDeviceCodeAndHourStartBetween(deviceCode, from, to)) {
            sum = sum.add(BigDecimal.valueOf(p.getDefect()));
        }
        return sum.setScale(4, RoundingMode.HALF_UP);
    }

    /** 质量域记录的不良数（这张工单名下所有未作废的不良记录） */
    private BigDecimal qcQtyOf(String workOrderNo) {
        BigDecimal sum = BigDecimal.ZERO;
        for (ErpQcDefect d : defectRepo.findByWorkOrderNoOrderByIdAsc(workOrderNo)) {
            if (!d.isVoided()) {
                sum = sum.add(d.getQty());
            }
        }
        return sum.setScale(4, RoundingMode.HALF_UP);
    }
}
