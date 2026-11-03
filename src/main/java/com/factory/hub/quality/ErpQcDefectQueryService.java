package com.factory.hub.quality;

import com.factory.hub.erp.ErpWorkOrder;
import com.factory.hub.erp.ErpWorkOrderRepository;
import com.factory.hub.manual.ManualEntry;
import com.factory.hub.manual.ManualEntryRepository;
import com.factory.hub.report.ProdHourly;
import com.factory.hub.report.ProdHourlyRepository;
import com.factory.hub.service.DeviceService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.factory.hub.quality.ErpQcBody.longOf;
import static com.factory.hub.quality.ErpQcBody.ok;
import static com.factory.hub.quality.ErpQcBody.qtyOf;
import static com.factory.hub.quality.ErpQcBody.str;

/**
 * 不良品的读接口 + 人工补录 + 批量处置 + 设备基线对照。
 * <p>
 * 与 {@link ErpQcDefectService} 分开是按读写分：那边是处置流程（写），这边是查询与登记（读多写少）。
 * 两边都碰同一张表，职责不重叠。
 * <p>
 * <b>列表里"待处置"必须按件算</b>：{@code pendingQty = qty - disposedQty}，
 * 另给 {@code pendingRecords = count(status = PENDING)}。
 * 只给一个"待处置条数"的话，8 件的单处置 3 件后会显示"待处置 8 件"，
 * 车间会一直以为积压很多。
 */
@Service
public class ErpQcDefectQueryService {

    private final ErpQcDefectRepository defectRepo;
    private final ErpQcDispositionRepository dispRepo;
    private final ErpQcDefectCodeRepository codeRepo;
    private final com.factory.hub.erp.ErpSkuRepository skuRepo;
    private final ErpQcDefectService defectService;
    private final ErpWorkOrderRepository workOrderRepo;
    private final ProdHourlyRepository prodRepo;
    private final ManualEntryRepository manualRepo;
    private final DeviceService deviceService;

    public ErpQcDefectQueryService(ErpQcDefectRepository defectRepo,
                                   ErpQcDispositionRepository dispRepo,
                                   ErpQcDefectCodeRepository codeRepo,
                                   com.factory.hub.erp.ErpSkuRepository skuRepo,
                                   ErpQcDefectService defectService,
                                   ErpWorkOrderRepository workOrderRepo,
                                   ProdHourlyRepository prodRepo,
                                   ManualEntryRepository manualRepo,
                                   DeviceService deviceService) {
        this.defectRepo = defectRepo;
        this.dispRepo = dispRepo;
        this.codeRepo = codeRepo;
        this.skuRepo = skuRepo;
        this.defectService = defectService;
        this.workOrderRepo = workOrderRepo;
        this.prodRepo = prodRepo;
        this.manualRepo = manualRepo;
        this.deviceService = deviceService;
    }

    /* ==================== 列表 / 详情 ==================== */

    /**
     * 列表。from/to 是毫秒时间戳，0 表示不限。
     * <p>
     * 筛选参数统一一套：{@code severity}（CR/MA/MI）、{@code source}、{@code status}
     * （PENDING/DISPOSED/CANCELLED）、{@code triaged}（true/false）、
     * {@code kw}、{@code from}、{@code to}。
     * <p>
     * <b>没有 {@code partial}</b>：「处置了一半」用 {@code disposedQty > 0 && disposedQty < qty} 表达，
     * 前端本地过滤即可，后端不再多一个参数（多一个参数就多一份两处口径）。
     */
    @Transactional(readOnly = true)
    public Map<String, Object> list(String status, String source, String severity, Boolean triaged,
                                    Boolean partial, Long from, Long to, String kw,
                                    int page, int size) {
        int p = Math.max(1, page);
        int s = Math.max(1, Math.min(200, size));
        Page<ErpQcDefect> res = defectRepo.search(
                blankToNull(source), blankToNull(status), blankToNull(severity), triaged, partial,
                from == null ? 0L : from, to == null ? 0L : to,
                blankToNull(kw), PageRequest.of(p - 1, s));

        // 一次取本页所有处置明细，避免 N+1
        List<Long> ids = res.getContent().stream().map(ErpQcDefect::getId).toList();
        Map<Long, List<ErpQcDisposition>> byDefect = ids.isEmpty() ? Map.of()
                : dispRepo.findByDefectIdInOrderByDefectIdAscSeqNoAsc(ids).stream()
                        .collect(Collectors.groupingBy(ErpQcDisposition::getDefectId));

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal pendingQty = BigDecimal.ZERO;
        int pendingRecords = 0;
        for (ErpQcDefect d : res.getContent()) {
            List<ErpQcDisposition> ds = byDefect.getOrDefault(d.getId(), List.of());
            Map<String, Object> row = defectService.toRow(d, ds);
            // dispositionSummary：前端契约要的是字符串（"报废 2 · 返工 1"）；
            // 同时给一份结构化的（items），前端要本地化时用后者
            row.put("dispositionSummary", summaryText(ds));
            row.put("dispositionSummaryItems", summary(ds));
            row.put("voidCount", dispRepo.countVoided(d.getId()));
            rows.add(row);
            if (ErpQcDefect.ST_PENDING.equals(d.getStatus())) {
                pendingQty = pendingQty.add(d.pendingQty());
                pendingRecords++;
            }
        }
        Map<String, Object> out = ok("共 " + res.getTotalElements() + " 条");
        out.put("total", res.getTotalElements());
        out.put("page", p);
        out.put("size", s);
        out.put("rows", rows);
        // 看板用：件数与条数必须分开给
        out.put("pendingQty", pendingQty);
        out.put("pendingRecords", pendingRecords);
        out.put("untriagedQty", defectRepo.untriagedQty());
        return out;
    }

    /** 详情：单头 + 处置明细 + 撤销次数 */
    @Transactional(readOnly = true)
    public Map<String, Object> detail(String defectNo) {
        ErpQcDefect d = defectService.load(defectNo);
        List<ErpQcDisposition> ds = dispRepo.findByDefectIdOrderBySeqNoAsc(d.getId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("defect", defectService.toRow(d, ds));
        out.put("dispositions", dispositions(ds));
        out.put("voidedCount", dispRepo.countVoided(d.getId()));
        out.put("pendingQty", d.pendingQty());
        out.put("displayStatus", defectService.displayStatus(d, ds));
        return out;
    }

    /* ==================== 人工补录 ==================== */

    /**
     * 人工补录不良记录（来源 MANUAL）。
     * <p>
     * 幂等键 {@code MANUAL:设备码:日期:序号}——同一天同一台设备的第 N 条补录，
     * 重复提交不会产生两条。
     */
    @Transactional
    public Map<String, Object> registerManual(Map<String, Object> body, String operator) {
        String deviceCode = str(body.get("deviceCode"));
        if (deviceCode == null) {
            throw new IllegalArgumentException("请选择设备");
        }
        BigDecimal qty = qtyOf(body.get("qty"));
        if (qty.signum() <= 0) {
            throw new IllegalArgumentException("不良件数必须大于 0");
        }
        Long defectCodeId = longOf(body.get("defectCodeId"));
        ErpQcDefectCode code = defectCodeId == null ? null
                : codeRepo.findById(defectCodeId).orElse(null);
        if (defectCodeId != null && code == null) {
            throw new IllegalArgumentException("缺陷分类不存在：" + defectCodeId);
        }
        ErpQcDefect d = new ErpQcDefect();
        d.setDefectNo(nextNo());
        d.setSource(ErpQcDefect.SRC_MANUAL);
        d.setSourceKey("MANUAL:" + deviceCode + ":" + LocalDate.now() + ":"
                + System.currentTimeMillis());
        d.setDeviceCode(deviceCode);
        d.setQty(qty);
        d.setInStock(false);
        d.setStatus(ErpQcDefect.ST_PENDING);
        d.setDisposedQty(BigDecimal.ZERO);
        d.setFoundAt(System.currentTimeMillis());
        d.setRegisteredBy(operator);
        d.setRemark(str(body.get("remark")));
        d.setCreatedAt(System.currentTimeMillis());
        d.setUpdatedAt(d.getCreatedAt());
        // 产品与来源：skuId 一定要有——让步放行校验是按 SKU 匹配的，
        // 没有 SKU 的人工补录在禁发仓放行时查不到依据，会被当成"无流水"拦掉
        Long skuId = longOf(body.get("skuId"));
        if (skuId != null) {
            d.setSkuId(skuId);
            skuRepo.findById(skuId).ifPresent(s -> {
                d.setSkuCode(s.getCode());
                d.setSkuName(s.getName());
            });
        }
        d.setLotNo(str(body.get("lotNo")));
        d.setWorkOrderNo(str(body.get("workOrderNo")));
        d.setInspectionNo(str(body.get("inspectionNo")));
        if (code != null) {
            d.setDefectCodeId(code.getId());
            d.setDefectCode(code.getCode());
            d.setDefectName(code.getName());
            d.setSeverity(code.getSeverity());
        }
        defectRepo.save(d);
        return ok("已登记不良记录 " + d.getDefectNo());
    }

    /* ==================== 批量处置 ==================== */

    /**
     * 批量处置：逐条处理，失败的进 {@code skipped}，<b>不整体回滚</b>。
     * <p>
     * 车间一次可能有两百件不良要处置，前端循环 200 次请求不可接受（慢 + 半成功 + 判不了要不要回滚）。
     * <p>
     * {@code skipped} 的原因码要能区分，因为它们要用户做的事不同：
     * {@code DISPOSED} = 已处置（重试没用，得自己去处理）、
     * {@code CONFLICT} = 状态冲突（刷新后重试即可）、
     * {@code ERROR} = 其他错误（消息里带原因）。
     */
    @Transactional
    public Map<String, Object> batchDispose(List<String> recordNos, String action,
                                            Map<String, Object> body, String operator) {
        List<Map<String, Object>> skipped = new ArrayList<>();
        int updated = 0;
        for (String no : recordNos == null ? List.<String>of() : recordNos) {
            try {
                defectService.dispose(no, action, body, operator);
                updated++;
            } catch (IllegalArgumentException e) {
                String msg = e.getMessage() == null ? "" : e.getMessage();
                String reason = msg.contains("已全部处置") || msg.contains("已作废")
                        ? "DISPOSED" : "CONFLICT";
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("recordNo", no);
                s.put("reason", reason);
                s.put("message", msg);
                skipped.add(s);
            } catch (Exception e) {
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("recordNo", no);
                s.put("reason", "ERROR");
                s.put("message", e.getMessage() == null ? "处置失败" : e.getMessage());
                skipped.add(s);
            }
        }
        Map<String, Object> out = ok("已处置 " + updated + " 条，跳过 " + skipped.size() + " 条");
        out.put("updated", updated);
        out.put("skipped", skipped);
        return out;
    }

    /* ==================== 设备基线对照 ==================== */

    /**
     * 双通道对照：设备采了多少、人工补了多少、这台设备到底有没有 reject 点位。
     * <p>
     * 补录弹窗顶上常驻这三行，是三层冲突防线的第一层（也是最有效的一层）：
     * 让补录的人当场看到"设备已采 3 件 · 你登记 2 件"，而不是事后对账才发现多登记了。
     */
    @Transactional(readOnly = true)
    public Map<String, Object> deviceBaseline(String workOrderNo) {
        ErpWorkOrder o = workOrderRepo.findByOrderNo(workOrderNo)
                .orElseThrow(() -> new IllegalArgumentException("工单不存在：" + workOrderNo));
        String deviceCode = o.getDeviceCode();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("workOrderNo", workOrderNo);
        out.put("deviceCode", deviceCode);
        if (deviceCode == null) {
            out.put("deviceCollectedQty", BigDecimal.ZERO);
            out.put("existingManualQty", BigDecimal.ZERO);
            out.put("deviceHasRejectPoint", false);
            out.put("source", "NO_DEVICE");
            return out;
        }
        long from = o.getActualStart() != null ? o.getActualStart()
                : (o.getPlanStart() != null ? o.getPlanStart() : 0L);
        long to = o.getActualEnd() != null ? o.getActualEnd() : System.currentTimeMillis();
        BigDecimal deviceQty = BigDecimal.ZERO;
        for (ProdHourly p : prodRepo.findByDeviceCodeAndHourStartBetween(deviceCode, from, to)) {
            deviceQty = deviceQty.add(BigDecimal.valueOf(p.getDefect()));
        }
        BigDecimal manualQty = BigDecimal.ZERO;
        LocalDate d1 = Instant.ofEpochMilli(from).atZone(ZoneId.systemDefault()).toLocalDate();
        LocalDate d2 = Instant.ofEpochMilli(to).atZone(ZoneId.systemDefault()).toLocalDate();
        for (ManualEntry m : manualRepo.findByRevokedFalseAndDayBetweenOrderByDayAscIdAsc(d1, d2)) {
            if (deviceCode.equals(m.getDeviceCode()) && m.getDefect() != null) {
                manualQty = manualQty.add(BigDecimal.valueOf(m.getDefect()));
            }
        }
        boolean hasReject = deviceService.hasRejectPoint(deviceCode);
        out.put("deviceCollectedQty", deviceQty.setScale(4, java.math.RoundingMode.HALF_UP));
        out.put("existingManualQty", manualQty.setScale(4, java.math.RoundingMode.HALF_UP));
        out.put("deviceHasRejectPoint", hasReject);
        out.put("projectedTotal", deviceQty.add(manualQty)
                .setScale(4, java.math.RoundingMode.HALF_UP));
        // 只给代号，文案由前端 i18n 写
        out.put("source", hasReject ? "DEVICE_PRIORITY" : "MANUAL_ONLY");
        return out;
    }

    /* ==================== 内部 ==================== */

    /** 处置摘要的字符串形式："报废 2 · 返工 1"（前端契约明确要的格式） */
    private String summaryText(List<ErpQcDisposition> ds) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> m : summary(ds)) {
            if (!sb.isEmpty()) {
                sb.append(" · ");
            }
            sb.append(actionLabel(String.valueOf(m.get("action"))))
                    .append(" ").append(((BigDecimal) m.get("qty")).stripTrailingZeros().toPlainString());
        }
        return sb.toString();
    }

    private static String actionLabel(String action) {
        if (ErpQcDisposition.ACT_SCRAP.equals(action)) {
            return "报废";
        }
        if (ErpQcDisposition.ACT_REWORK.equals(action)) {
            return "返工";
        }
        if (ErpQcDisposition.ACT_CONCESSION.equals(action)) {
            return "让步";
        }
        return action;
    }

    private List<Map<String, Object>> summary(List<ErpQcDisposition> ds) {
        Map<String, BigDecimal> byAction = new LinkedHashMap<>();
        for (ErpQcDisposition d : ds) {
            if (d.isVoided()) {
                continue;
            }
            byAction.merge(d.getAction(), d.effectiveQty(), BigDecimal::add);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> e : byAction.entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("action", e.getKey());
            m.put("qty", e.getValue());
            out.add(m);
        }
        return out;
    }

    private List<Map<String, Object>> dispositions(List<ErpQcDisposition> ds) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ErpQcDisposition d : ds) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", d.getId());
            m.put("seqNo", d.getSeqNo());
            m.put("action", d.getAction());
            m.put("qty", d.getQty());
            m.put("failQty", d.getFailQty());
            m.put("effectiveQty", d.effectiveQty());
            m.put("stockBillNo", d.getStockBillNo());
            m.put("reworkOrderNo", d.getReworkOrderNo());
            m.put("fqcInspectionNo", d.getFqcInspectionNo());
            m.put("concessionSoNo", d.getConcessionSoNo());
            m.put("concessionApprover", d.getConcessionApprover());
            m.put("concessionAckBillNo", d.getConcessionAckBillNo());
            m.put("concessionAmount", d.getConcessionAmount());
            m.put("costAmount", d.getCostAmount());
            m.put("costPartial", d.isCostPartial());
            m.put("operator", d.getOperator());
            m.put("disposedAt", d.getDisposedAt());
            m.put("remark", d.getRemark());
            m.put("voidedAt", d.getVoidedAt());
            m.put("voidedBy", d.getVoidedBy());
            m.put("voidReason", d.getVoidReason());
            out.add(m);
        }
        return out;
    }

    private String nextNo() {
        String base = "BLP" + LocalDate.now().format(
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

    private static String blankToNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return s.trim();
    }
}
