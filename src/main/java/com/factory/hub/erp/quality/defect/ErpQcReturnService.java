package com.factory.hub.erp.quality.defect;

import com.factory.hub.audit.AuditService;
import com.factory.hub.erp.ErpQualityReturnGate;
import com.factory.hub.erp.ErpWarehouse;
import com.factory.hub.erp.ErpWarehouseRepository;
import com.factory.hub.erp.production.ErpWorkOrderRepository;
import com.factory.hub.erp.ErpSkuRepository;
import com.factory.hub.erp.ErpSku;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 质量退货闸门的实现（QC-SPEC §3.5 / AC-13 / AC-14）。
 *
 * <p><b>退货必须按原因分流</b>：
 * <ul>
 *   <li>{@code QUALITY}（质量问题）→ <b>强制入禁发仓</b>，并生成一条待处置的不良记录。
 *       这批货等着质检处置：报废、返工、或者让步接收（让步要走禁发仓 + 客户凭证）；</li>
 *   <li>{@code OTHER}（其它原因）→ 正常回良品仓，不生成不良记录。</li>
 * </ul>
 *
 * <p><b>不分流的最坏后果</b>：质量退货按普通良品入库，重新发给下一个客户——
 * 质量问题被彻底抹掉，看板上还看不出来。而界面显示着"退货原因：质量问题"，
 * <b>界面说对了，账做错了</b>。这类 bug 不报错、不影响任何测试，只能靠人眼发现。
 *
 * <p><b>原因必填</b>：不传原因就抛异常，不落默认值。
 * 落默认值等于把"忘了填"悄悄变成"按非质量处理"，那正是要防的那件事。
 */
@Service
public class ErpQcReturnService implements ErpQualityReturnGate {

    private final ErpQcDefectRepository defectRepo;
    private final ErpWarehouseRepository whRepo;
    private final ErpSkuRepository skuRepo;
    private final AuditService audit;

    public ErpQcReturnService(ErpQcDefectRepository defectRepo,
                              ErpWarehouseRepository whRepo,
                              ErpSkuRepository skuRepo,
                              AuditService audit) {
        this.defectRepo = defectRepo;
        this.whRepo = whRepo;
        this.skuRepo = skuRepo;
        this.audit = audit;
    }

    /**
     * 决定这批货实际入哪个仓。
     * <p>
     * 质量原因时<b>不用前端选的仓</b>，强制用禁发仓——前端选的那个多半是普通良品仓，
     * 让退货进普通仓就等于把问题货放回流通渠道。
     */
    @Override
    @Transactional(readOnly = true)
    public Long resolveReturnWarehouse(String returnReason, Long requestedWhId) {
        String reason = reason(returnReason);
        if (REASON_QUALITY.equals(reason)) {
            ErpWarehouse blocked = blockedWarehouse();
            if (blocked == null) {
                // fail-safe：找不到禁发仓就不能放这批货进普通仓，
                // 否则等于把这批问题货放回流通渠道，而且看板上还看不出来。
                throw new IllegalArgumentException(
                        "质量原因退货必须入禁发仓，但系统里没有启用的禁发仓（待处理仓）——"
                                + "请先在「仓库管理」里把待处理仓的类型设为「禁发仓」，否则这批货只能先不入库");
            }
            return blocked.getId();
        }
        if (requestedWhId == null) {
            throw new IllegalArgumentException("请选择退货入库的仓库");
        }
        return requestedWhId;
    }

    /**
     * 入库完成后补记不良记录。
     * <p>
     * {@code in_stock = true}：货已经通过退货单进了禁发仓，是实打实的库存。
     * 之后处置"报废"才会建出库单把货扣掉（否则禁发仓里那批已报废的货数量不减，库存虚高）。
     */
    @Override
    @Transactional
    public void registerReturnDefects(String orderNo, Long customerId, Long whId, String billNo,
                                       List<QualityReturnLine> lines, String remark, String operator) {
        if (lines == null || lines.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (QualityReturnLine line : lines) {
            if (line == null || line.qty() == null || line.qty().signum() <= 0) {
                continue;
            }
            ErpQcDefect d = new ErpQcDefect();
            d.setSource(ErpQcDefect.SRC_RETURN);
            // 幂等键：同一张退货单 + 同一个 SKU 只建一条。
            // 退货单可能被重复提交（网络重试），没有唯一键就会建两条 → 数量翻倍。
            d.setSourceKey("RET:" + orderNo + ":" + line.skuId());
            d.setDefectNo(nextDefectNo());
            d.setSkuId(line.skuId());
            ErpSku sku = line.skuId() == null ? null : skuRepo.findById(line.skuId()).orElse(null);
            if (sku != null) {
                d.setSkuCode(sku.getCode());
                d.setSkuName(sku.getName());
            }
            d.setQty(line.qty());
            d.setInStock(true);
            d.setWarehouseId(whId);
            d.setSrcBillNo(billNo);
            d.setFoundAt(now);
            d.setRegisteredBy(operator);
            d.setStatus(ErpQcDefect.ST_PENDING);
            d.setDisposedQty(BigDecimal.ZERO);
            d.setSeverity(null);       // 要人分诊才知道是什么缺陷
            d.setDefectCodeId(null);
            d.setRemark("客户质量退货（订单 " + orderNo + "）"
                    + (remark == null || remark.isBlank() ? "" : "；" + remark));
            d.setCreatedAt(now);
            d.setUpdatedAt(now);
            defectRepo.save(d);
        }
        audit.ok(com.factory.hub.audit.AuditLog.CAT_QUALITY, com.factory.hub.audit.AuditLog.ACT_WRITE, orderNo,
                "客户质量退货入库并生成不良记录",
                "退货单 " + billNo + "，入禁发仓 " + whId + "，共 "
                        + lines.size() + " 个 SKU 待处置");
    }

    private String reason(String raw) {
        String s = raw == null ? "" : raw.trim().toUpperCase();
        if (s.isEmpty()) {
            throw new IllegalArgumentException(
                    "退货必须选原因（质量问题 / 其它原因）——不选的话系统不知道这批货该不该进待处理仓");
        }
        if (!REASON_QUALITY.equals(s) && !REASON_OTHER.equals(s)) {
            throw new IllegalArgumentException("退货原因只能是 QUALITY（质量问题）或 OTHER（其它原因），收到：" + s);
        }
        return s;
    }

    private ErpWarehouse blockedWarehouse() {
        for (ErpWarehouse w : whRepo.findAll()) {
            if (w.isBlocked() && w.isEnabled()) {
                return w;
            }
        }
        return null;
    }

    private String nextDefectNo() {
        String max = defectRepo.maxNoByPrefix("BLP");
        int n = 0;
        if (max != null && max.length() > 3) {
            try {
                n = Integer.parseInt(max.substring(3));
            } catch (NumberFormatException ignored) {
                // 编号不是数字格式就当没有，从 1 开始
            }
        }
        return String.format("BLP%06d", n + 1);
    }
}
