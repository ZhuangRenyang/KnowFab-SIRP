package com.factory.hub.quality;

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
 * 质量看板与帕累托（QC-SPEC §4.2）。
 * <p>
 * <b>两个口径都要给，不能合成一个数</b>（Spec §2.4）：
 * <ul>
 *   <li>{@code defectRate}：<b>质检口径</b> —— 质检判出的不良件数 ÷ 质检覆盖件数。
 *       这是"人判的"质量，是客户认可的口径；</li>
 *   <li>{@code deviceDefectRate}：<b>设备口径</b> —— 设备计数器的不良数 ÷ 设备产出数。
 *       这是"机器说的"，它和质检口径的差就是双通道要核对的东西（对账条上的 gap）。</li>
 * </ul>
 * 合成一个数就等于把两套真相悄悄合并——差在哪、为什么差，全部看不见了。
 * <p>
 * <b>说明文字由前端 i18n 写</b>（后端不返回面向用户的文案）：塞中文则英文界面蹦中文，
 * 塞英文则中文界面蹦英文。
 * <p>
 * <b>待处置按件算</b>：{@code pendingQty}（件）与 {@code pendingRecords}（条）必须分开。
 * 只给条数看不出严重性：2 条记录 3000 件（设备批量采集）vs 3000 条各 1 件（人工乱填），
 * 治理手段完全不同。
 */
@Service
public class ErpQcBoardService {

    private final ErpQcDefectRepository defectRepo;
    private final ErpQcDispositionRepository dispRepo;
    private final ErpQcInspectionRepository insRepo;
    private final ProdHourlyRepository prodRepo;

    public ErpQcBoardService(ErpQcDefectRepository defectRepo,
                             ErpQcDispositionRepository dispRepo,
                             ErpQcInspectionRepository insRepo,
                             ProdHourlyRepository prodRepo) {
        this.defectRepo = defectRepo;
        this.dispRepo = dispRepo;
        this.insRepo = insRepo;
        this.prodRepo = prodRepo;
    }

    /**
     * 看板数据。
     *
     * @param fromTs 起始时间戳
     * @param toTs   结束时间戳
     */
    @Transactional(readOnly = true)
    public Map<String, Object> dashboard(long fromTs, long toTs) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("from", fromTs);
        out.put("to", toTs);

        // —— 质检口径 ——
        BigDecimal qcDefectQty = nz(defectRepo.sumQtyInWindow(fromTs, toTs));
        BigDecimal qcInspectedQty = nz(insRepo.sumInspectedQty(fromTs, toTs));
        double defectRate = pct(qcDefectQty, qcInspectedQty);
        out.put("qcDefectQty", qcDefectQty);
        out.put("qcInspectedQty", qcInspectedQty);
        out.put("defectRate", defectRate);
        out.put("passRate", round1(100d - defectRate));

        // —— 设备口径 ——
        BigDecimal deviceOutput = BigDecimal.ZERO;
        BigDecimal deviceDefect = BigDecimal.ZERO;
        for (Object[] row : prodRepo.sumOutputAndDefect(fromTs, toTs)) {
            deviceOutput = deviceOutput.add(nz(row[0]));
            deviceDefect = deviceDefect.add(nz(row[1]));
        }
        out.put("deviceOutputQty", deviceOutput);
        out.put("deviceDefectQty", deviceDefect);
        out.put("deviceDefectRate", pct(deviceDefect, deviceOutput));

        // —— 待处置（件 / 条分开）——
        out.put("pendingQty", nz(defectRepo.pendingQtyTotal()));
        out.put("pendingRecords", defectRepo.pendingRecordCount());

        // —— 未分类（件 / 条分开）——
        out.put("untriagedQty", nz(defectRepo.untriagedQty()));
        out.put("untriagedRecords", defectRepo.countUntriaged());

        // —— 处置结果：让步率与报废损失 ——
        BigDecimal scrapQty = BigDecimal.ZERO;
        BigDecimal scrapLoss = BigDecimal.ZERO;
        BigDecimal concessionQty = BigDecimal.ZERO;
        BigDecimal concessionAmount = BigDecimal.ZERO;
        for (Object[] row : dispRepo.costByAction(fromTs, toTs)) {
            String action = String.valueOf(row[0]);
            BigDecimal qty = nz(row[1]);
            BigDecimal cost = nz(row[2]);
            BigDecimal concession = nz(row[3]);
            if (ErpQcDisposition.ACT_SCRAP.equals(action)) {
                scrapQty = scrapQty.add(qty);
                scrapLoss = scrapLoss.add(cost);
            } else if (ErpQcDisposition.ACT_CONCESSION.equals(action)) {
                concessionQty = concessionQty.add(qty);
                concessionAmount = concessionAmount.add(concession);
            }
        }
        out.put("scrapQty", scrapQty);
        out.put("scrapLoss", scrapLoss);
        out.put("concessionQty", concessionQty);
        out.put("concessionAmount", concessionAmount);
        out.put("concessionRate", pct(concessionQty, qcDefectQty));
        return out;
    }

    /**
     * 帕累托：按缺陷分类或按设备。
     * <p>
     * <b>未分类固定占一行</b>：未分类的记录不许被静默丢掉——
     * 前几名会因为漏掉一大块而失真，而"该去分诊了"这件事也就没人知道。
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> pareto(String by, long fromTs, long toTs) {
        boolean byDevice = "device".equalsIgnoreCase(by);
        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        if (byDevice) {
            for (Object[] r : defectRepo.paretoByDevice(fromTs, toTs)) {
                BigDecimal qty = nz(r[1]);
                total = total.add(qty);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("key", r[0]);
                m.put("name", r[0] == null ? null : r[0]);
                m.put("qty", qty);
                rows.add(m);
            }
        } else {
            for (Object[] r : defectRepo.paretoByCode(fromTs, toTs)) {
                BigDecimal qty = nz(r[4]);
                total = total.add(qty);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("key", r[1]);
                m.put("defectCodeId", r[0]);
                m.put("name", r[2]);
                m.put("severity", r[3]);
                m.put("qty", qty);
                rows.add(m);
            }
        }
        // 占比与累计占比（帕累托的关键是"前几类占多少"）
        BigDecimal acc = BigDecimal.ZERO;
        for (Map<String, Object> m : rows) {
            BigDecimal qty = (BigDecimal) m.get("qty");
            acc = acc.add(qty);
            m.put("pct", pct(qty, total));
            m.put("cumPct", pct(acc, total));
        }
        return rows;
    }

    /* ==================== 内部 ==================== */

    private static double pct(BigDecimal part, BigDecimal whole) {
        if (whole == null || whole.signum() <= 0) {
            return 0d;
        }
        return part.multiply(BigDecimal.valueOf(100))
                .divide(whole, 4, RoundingMode.HALF_UP).doubleValue();
    }

    private static double round1(double v) {
        return Math.round(v * 10d) / 10d;
    }

    private static BigDecimal nz(Object o) {
        if (o == null) {
            return BigDecimal.ZERO;
        }
        if (o instanceof BigDecimal b) {
            return b;
        }
        if (o instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        try {
            return new BigDecimal(String.valueOf(o));
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }
}
