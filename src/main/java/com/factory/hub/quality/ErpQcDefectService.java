package com.factory.hub.quality;

import com.factory.hub.erp.ErpWarehouse;
import com.factory.hub.erp.ErpWarehouseRepository;
import com.factory.hub.erp.ErpWorkOrderService;
import com.factory.hub.erp.ErpStockService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.factory.hub.quality.ErpQcBody.longOf;
import static com.factory.hub.quality.ErpQcBody.ok;
import static com.factory.hub.quality.ErpQcBody.qtyOf;
import static com.factory.hub.quality.ErpQcBody.str;

/**
 * 不良品处置：报废 / 返工 / 让步接收，以及撤销（QC-SPEC §3.4 / §2.7）。
 * <p>
 * <b>处置一经执行不可原地修改</b>：撤销 + 重做是唯一路径，两步都留痕。
 * 想改数量就撤销再重做——直接改数字的话，"这批货到底怎么处置的"永远说不清，
 * 而质量记录说不清等于没有。
 * <p>
 * <b>六分支（动不动库存）由 {@link ErpQualityStockAdapter} 决定</b>，本类只负责：
 * 校验 → 算计划 → 执行 → 写处置明细 → 重算镜像列。
 * <p>
 * <b>{@code disposed_qty} 必须是 {@code sum(qty - fail_qty) where voided_at is null}</b>：
 * 写成 {@code sum(qty)}，返工失败退回的那一件会永久失踪，而且账面完全正常。
 * <p>
 * <b>{@code status} 只有三态</b>（PENDING / DISPOSED / CANCELLED），没有 PARTIAL：
 * "处置了一半"用 {@code disposed_qty} 这个数字表达，不新增状态值。
 * 界面上的五态显示值由 {@link #displayStatus} 派生，不落库。
 */
@Service
public class ErpQcDefectService {

    private final ErpQcDefectRepository defectRepo;
    private final ErpQcDispositionRepository dispRepo;
    private final ErpStockService stockService;
    private final ErpWorkOrderService workOrderService;
    private final ErpWarehouseRepository whRepo;
    private final com.factory.hub.audit.AuditService audit;

    public ErpQcDefectService(ErpQcDefectRepository defectRepo,
                              ErpQcDispositionRepository dispRepo,
                              ErpStockService stockService,
                              ErpWorkOrderService workOrderService,
                              ErpWarehouseRepository whRepo,
                              com.factory.hub.audit.AuditService audit) {
        this.defectRepo = defectRepo;
        this.dispRepo = dispRepo;
        this.stockService = stockService;
        this.workOrderService = workOrderService;
        this.whRepo = whRepo;
        this.audit = audit;
    }

    /* ==================== 处置 ==================== */

    /**
     * 执行一次处置。
     *
     * @param defectNo 不良单号
     * @param action   SCRAP / REWORK / CONCESSION
     * @param body     可选字段：{@code qty}（本次处置件数，默认全部待处置）、
     *                 {@code concessionSoNo / concessionPartnerId / concessionApprover /
     *                 concessionAmount / concessionAckBillNo}、{@code costAmount}、{@code remark}
     */
    @Transactional
    public Map<String, Object> dispose(String defectNo, String action, Map<String, Object> body,
                                       String operator) {
        ErpQcDefect defect = load(defectNo);
        if (!ErpQcDisposition.ACTIONS.contains(action)) {
            throw new IllegalArgumentException("不支持的处置方式：" + action
                    + "（可用 SCRAP / REWORK / CONCESSION）");
        }
        if (defect.isVoided() || ErpQcDefect.ST_CANCELLED.equals(defect.getStatus())) {
            throw new IllegalArgumentException("不良单 " + defectNo + " 已作废，不能再处置");
        }
        BigDecimal pending = defect.pendingQty();
        if (pending.signum() <= 0) {
            throw new IllegalArgumentException("不良单 " + defectNo + " 已全部处置完毕，没有待处置件数");
        }
        BigDecimal qty = body == null || body.get("qty") == null ? pending : qtyOf(body.get("qty"));
        if (qty.signum() <= 0) {
            throw new IllegalArgumentException("处置件数必须大于 0");
        }
        if (qty.compareTo(pending) > 0) {
            throw new IllegalArgumentException("本次处置 " + qty.stripTrailingZeros().toPlainString()
                    + " 件超过待处置 " + pending.stripTrailingZeros().toPlainString() + " 件");
        }
        // 已入账却说不出来源单号：处置要动库存（报废出库/调拨），账上没来源就没法对账。
        // 这里必须拒绝保存，而不是"先处置再说"
        if (defect.isInStock() && defect.isSourceMissing()) {
            throw new IllegalArgumentException("不良单 " + defectNo + " 标记为已入账，但没有来源单据号，"
                    + "不能处置。请先补齐来源单据（退货单/入库单号）");
        }

        ErpWarehouse wh = defect.getWarehouseId() == null ? null
                : whRepo.findById(defect.getWarehouseId()).orElse(null);
        ErpWarehouse blocked = blockedWarehouse();
        ErpQualityStockAdapter.Ctx ctx = new ErpQualityStockAdapter.Ctx(
                defect.isInStock(), action, qty, defect.getWarehouseId(),
                blocked == null ? null : blocked.getId(), defectNo,
                wh == null ? null : wh.getName());

        ErpQualityStockAdapter.Plan plan = ErpQualityStockAdapter.plan(ctx);
        List<String> created = ErpQualityStockAdapter.execute(plan, defect.getSkuId(), operator,
                stockService, workOrderService, defect.getWorkOrderNo());

        ErpQcDisposition disp = new ErpQcDisposition();
        disp.setDefectId(defect.getId());
        disp.setSeqNo(dispRepo.maxSeqNo(defect.getId()) + 1);
        disp.setAction(action);
        disp.setQty(qty);
        disp.setFailQty(BigDecimal.ZERO);
        disp.setOperator(operator);
        disp.setDisposedAt(System.currentTimeMillis());
        disp.setRemark(str(body == null ? null : body.get("remark")));
        // 单据号：库存动作第一个是库存单，返工动作第一个是工单
        for (String no : created) {
            if (ErpQcDisposition.ACT_REWORK.equals(action)) {
                disp.setReworkOrderNo(no);
            } else if (disp.getStockBillNo() == null) {
                disp.setStockBillNo(no);
            }
        }
        if (ErpQcDisposition.ACT_CONCESSION.equals(action)) {
            fillConcession(disp, body);
            // B5：让步之后这批货换了仓（进禁发仓），头表要跟着改：
            // 不改的话，"这批货在哪"还是原仓，出库校验按仓判断时会拿错仓，
            // 追溯也查不到那笔调拨/入库单。
            // inStock 不用改：让步后货确实还在库里（只是换了仓），"已入过账"仍然成立。
            if (blocked != null) {
                defect.setWarehouseId(blocked.getId());
            }
            if (disp.getStockBillNo() != null) {
                defect.setSrcBillNo(disp.getStockBillNo());
            }
        }
        if (body != null && body.get("costAmount") != null) {
            disp.setCostAmount(qtyOf(body.get("costAmount")));
        }
        dispRepo.save(disp);

        recompute(defect);
        audit.ok(com.factory.hub.audit.AuditLog.CAT_QUALITY, com.factory.hub.audit.AuditLog.ACT_WRITE,
                defectNo, action + " " + qty.stripTrailingZeros().toPlainString() + " 件",
                "处置：" + action + "，单据 " + created + "，处置行 " + disp.getSeqNo());

        Map<String, Object> out = ok("已处置 " + qty.stripTrailingZeros().toPlainString() + " 件");
        out.put("defectNo", defectNo);
        out.put("action", action);
        out.put("dispositionSeq", disp.getSeqNo());
        out.put("stockBillNo", disp.getStockBillNo());
        out.put("reworkOrderNo", disp.getReworkOrderNo());
        out.put("disposedQty", defect.getDisposedQty());
        out.put("pendingQty", defect.pendingQty());
        out.put("status", defect.getStatus());
        out.put("displayStatus", displayStatus(defect, dispRepo.findByDefectIdOrderBySeqNoAsc(defect.getId())));
        if (plan.warnCode() != null) {
            out.put("warnCode", plan.warnCode());
            out.put("warnParams", plan.warnParams());
        }
        return out;
    }

    /* ==================== 撤销 ==================== */

    /**
     * 撤销一条处置：先冲销它产生的库存单据，再把处置行标记作废，最后重算镜像列。
     * <p>
     * <b>顺序不能反</b>：先作废处置行再去冲销单据，中途失败会留下"处置行没了、库存单还在"的局面，
     * 账实从此对不上，而且没有任何异常提示。
     */
    @Transactional
    public Map<String, Object> revoke(String defectNo, Long dispositionId, String reason,
                                      String operator) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("撤销必须填原因（审计要的就是这句人话）");
        }
        ErpQcDefect defect = load(defectNo);
        ErpQcDisposition disp = dispRepo.findById(dispositionId)
                .orElseThrow(() -> new IllegalArgumentException("处置记录不存在：" + dispositionId));
        if (!disp.getDefectId().equals(defect.getId())) {
            throw new IllegalArgumentException("处置记录不属于不良单 " + defectNo);
        }
        if (disp.isVoided()) {
            throw new IllegalArgumentException("这条处置已经撤销过了（撤销时间 "
                    + disp.getVoidedAt() + "），别重复操作");
        }
        // ① 先冲销库存单据
        if (disp.getStockBillNo() != null && !disp.getStockBillNo().isBlank()) {
            stockService.voidBill(disp.getStockBillNo(), operator);
        }
        // ② 返工工单也一并取消（取消失败会让整个撤销回滚，不留半截状态）
        String orderNote = null;
        if (disp.getReworkOrderNo() != null && !disp.getReworkOrderNo().isBlank()) {
            workOrderService.cancel(disp.getReworkOrderNo(), operator, "不良品处置撤销：" + reason);
            orderNote = "返工工单 " + disp.getReworkOrderNo() + " 已取消";
        }
        // ③ 再把处置行标作废（不改数量、不删行）
        disp.setVoidedAt(System.currentTimeMillis());
        disp.setVoidedBy(operator);
        disp.setVoidReason(reason);
        dispRepo.save(disp);

        recompute(defect);
        audit.ok(com.factory.hub.audit.AuditLog.CAT_QUALITY, com.factory.hub.audit.AuditLog.ACT_REVOKE,
                String.valueOf(dispositionId), "处置行 " + disp.getSeqNo() + " 撤销",
                "撤销处置：" + reason);

        Map<String, Object> out = ok("已撤销该笔处置");
        out.put("defectNo", defectNo);
        out.put("dispositionSeq", disp.getSeqNo());
        out.put("disposedQty", defect.getDisposedQty());
        out.put("pendingQty", defect.pendingQty());
        out.put("status", defect.getStatus());
        out.put("displayStatus", displayStatus(defect,
                dispRepo.findByDefectIdOrderBySeqNoAsc(defect.getId())));
        if (orderNote != null) {
            out.put("orderNote", orderNote);
        }
        return out;
    }

    /* ==================== 镜像列重算 ==================== */

    /**
     * 重算 {@code disposed_qty} 与 {@code status}。
     * <p>
     * {@code disposed_qty = sum(qty - fail_qty) where voided_at is null}；
     * 待处置 = 不良件数 − 已处置，待处置为 0 → DISPOSED，否则 PENDING。
     * <b>status 只三态</b>：返工失败退回的那件会让状态从 DISPOSED 退回 PENDING，这是正确的。
     */
    @Transactional
    public void recompute(ErpQcDefect defect) {
        BigDecimal disposed = dispRepo.sumActiveQty(defect.getId());
        if (disposed == null) {
            disposed = BigDecimal.ZERO;
        }
        defect.setDisposedQty(disposed);
        if (!ErpQcDefect.ST_CANCELLED.equals(defect.getStatus())) {
            defect.setStatus(defect.pendingQty().signum() <= 0
                    ? ErpQcDefect.ST_DISPOSED : ErpQcDefect.ST_PENDING);
        }
        defect.setUpdatedAt(System.currentTimeMillis());
        defectRepo.save(defect);
    }

    /**
     * 界面显示用的五态（<b>派生，不落库</b>）：
     * {@code CANCELLED > REWORK_FQC_FAIL > REWORK_WAIT_FQC > PENDING > DISPOSED}。
     * <p>
     * 为什么不落库：落库就多一份真值，撤销一条处置时它和 {@code disposed_qty} 必然有一份不同步。
     */
    public String displayStatus(ErpQcDefect defect, List<ErpQcDisposition> rows) {
        if (ErpQcDefect.ST_CANCELLED.equals(defect.getStatus())) {
            return "CANCELLED";
        }
        boolean waitFqc = false;
        for (ErpQcDisposition d : rows) {
            if (d.isVoided() || !ErpQcDisposition.ACT_REWORK.equals(d.getAction())) {
                continue;
            }
            if (d.hasReworkFail()) {
                return "REWORK_FQC_FAIL";
            }
            if (d.isReworkWaitingFqc()) {
                waitFqc = true;
            }
        }
        if (waitFqc) {
            return "REWORK_WAIT_FQC";
        }
        return ErpQcDefect.ST_PENDING.equals(defect.getStatus())
                ? "PENDING" : "DISPOSED";
    }

    /** 分级中文名 */
    static String severityLabel(String severity) {
        if (severity == null) {
            return "";
        }
        return switch (severity) {
            case ErpQcDefectCode.SEV_CR -> "致命";
            case ErpQcDefectCode.SEV_MI -> "轻微";
            default -> "严重";
        };
    }

    /* ==================== 内部 ==================== */

    ErpQcDefect load(String defectNo) {
        return defectRepo.findByDefectNo(defectNo)
                .orElseThrow(() -> new IllegalArgumentException("不良单不存在：" + defectNo));
    }

    /** 禁发仓（待处理仓）：让步要把货放进去 */
    private ErpWarehouse blockedWarehouse() {
        for (ErpWarehouse w : whRepo.findAll()) {
            if (w.isBlocked() && w.isEnabled()) {
                return w;
            }
        }
        return null;
    }

    private static void fillConcession(ErpQcDisposition disp, Map<String, Object> body) {
        if (body == null) {
            return;
        }
        disp.setConcessionSoNo(str(body.get("concessionSoNo")));
        disp.setConcessionPartnerId(longOf(body.get("concessionPartnerId")));
        disp.setConcessionApprover(str(body.get("concessionApprover")));
        disp.setConcessionAckBillNo(str(body.get("concessionAckBillNo")));
        if (body.get("concessionAmount") != null) {
            disp.setConcessionAmount(qtyOf(body.get("concessionAmount")));
        }
        if (disp.getConcessionAckBillNo() != null) {
            disp.setConcessionAckAt(System.currentTimeMillis());
            disp.setConcessionAckBy(disp.getOperator());
        }
    }

    /** 列表行用的字段（前端要的 pendingQty / displayStatus / 处置摘要） */
    public Map<String, Object> toRow(ErpQcDefect d, List<ErpQcDisposition> rows) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("defectNo", d.getDefectNo());
        m.put("source", d.getSource());
        m.put("status", d.getStatus());
        // —— 追溯链字段（前端 QcDefectRow 早就声明了，后端一直没给）——
        // 缺了这几个，用户在不良品列表上**看不出这条是哪张检验单/哪张工单来的**，
        // 追溯链断在这里（不良 → 检验单 → 工单 → 产品 → 客户）。
        // 「这条不良是谁判出来的、对应哪批货」是质量追溯的基本要求，
        // 不是锦上添花的字段。
        m.put("sourceKey", d.getSourceKey());
        m.put("inspectionNo", d.getInspectionNo());
        m.put("workOrderNo", d.getWorkOrderNo());
        m.put("skuId", d.getSkuId());
        m.put("defectCodeId", d.getDefectCodeId());
        m.put("srcBillNo", d.getSrcBillNo());
        m.put("warehouseId", d.getWarehouseId());
        m.put("registeredBy", d.getRegisteredBy());
        m.put("remark", d.getRemark());
        m.put("voidedAt", d.getVoidedAt());
        m.put("voidedBy", d.getVoidedBy());
        m.put("voidReason", d.getVoidReason());
        m.put("displayStatus", displayStatus(d, rows));
        m.put("qty", d.getQty());
        m.put("disposedQty", d.getDisposedQty());
        m.put("pendingQty", d.pendingQty());
        m.put("inStock", d.isInStock());
        m.put("severity", d.getSeverity());
        // severityLabel：中文名，前端直接显示（这是前端契约明确要的字段）
        m.put("severityLabel", severityLabel(d.getSeverity()));
        m.put("defectCode", d.getDefectCode());
        m.put("defectName", d.getDefectName());
        // codeMissing：还没分诊（缺缺陷代码）
        m.put("codeMissing", d.getDefectCodeId() == null);
        m.put("deviceCode", d.getDeviceCode());
        m.put("skuCode", d.getSkuCode());
        m.put("skuName", d.getSkuName());
        m.put("lotNo", d.getLotNo());
        m.put("foundAt", d.getFoundAt());
        return m;
    }
}
