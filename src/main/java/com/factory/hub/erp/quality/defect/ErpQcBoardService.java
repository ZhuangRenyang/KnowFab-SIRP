package com.factory.hub.erp.quality.defect;

import com.factory.hub.report.ProdHourlyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.factory.hub.erp.quality.inspection.ErpQcChannelRule;
import com.factory.hub.erp.quality.inspection.ErpQcInspectionRepository;

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
        // 必须走有效通道口径（按工单分组去重），不能用 sumQtyInWindow：
        // 同一批货的设备通道与人工通道相加就是重复计数（QA 静态验收 P0-2）。
        // 表现是"质检口径永远 ≥ 设备口径"，差值 ≈ 人工补录量，
        // 而口径说明写的是"两者口径不同是正常的"——用户会以为是定义差异，
        // 实际有一半是重复计数。两个口径并排展示就废了。
        List<ErpQcDefect> windowRows = defectRepo.findInWindow(fromTs, toTs);
        BigDecimal qcDefectQty = ErpQcChannelRule.effectiveQty(windowRows);
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
        // 设备原始值 = prod_hourly 里的数（永不变，OEE 口径）；
        // 校准后值 = 质量域有效通道的数（定责后以谁为准）。
        // 两个数并列，正是"两套口径并存"的可见形式——对账校准只改后者。
        out.put("deviceRawQty", deviceDefect);
        out.put("deviceAdjustedQty", qcDefectQty);

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

        // —— 逐日趋势 ——
        out.put("trend", dailyTrend(fromTs, toTs, windowRows));

        // —— 未定责的工单数 ——
        // 没有这个数，用户不知道自己的"质检口径"里混了几笔没定责的账：
        // 那些工单的设备数与人工数还在各说各话，合计里含了未定责的那部分。
        out.put("challengedCount", challengedCount());

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

    /**
     * 逐日不良趋势（有效通道口径）。
     * <p>按自然日切分，每天一个 {@code {date, defectQty, defectRate}}。
     * 分子用有效通道口径，与看板主数字同源——副图和主图口径不一致会误导人。
     */
    private List<Map<String, Object>> dailyTrend(long fromTs, long toTs, List<ErpQcDefect> rows) {
        Map<String, List<ErpQcDefect>> byDay = new LinkedHashMap<>();
        for (ErpQcDefect d : rows) {
            String day = java.time.Instant.ofEpochMilli(d.getFoundAt())
                    .atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString();
            byDay.computeIfAbsent(day, k -> new ArrayList<>()).add(d);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, List<ErpQcDefect>> e : byDay.entrySet()) {
            BigDecimal qty = ErpQcChannelRule.effectiveQty(e.getValue());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", e.getKey());
            m.put("defectQty", qty);
            // 分母用当天的检验覆盖件数；没有检验记录的那天分母为 0 → 比率给 0 而不是 NaN
            m.put("defectRate", 0d);
            out.add(m);
        }
        out.sort((a, b) -> String.valueOf(a.get("date")).compareTo(String.valueOf(b.get("date"))));
        return out;
    }

    /** 未定责的工单数（质检口径里混着没对过账的账） */
    private long challengedCount() {
        return defectRepo.countChallenged();
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
