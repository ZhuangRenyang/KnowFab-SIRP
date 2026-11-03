package com.factory.hub.quality;

import com.factory.hub.erp.ErpWarehouse;
import com.factory.hub.erp.ErpWarehouseRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 禁发仓放行校验（QC-SPEC §2.11）——<b>fail-safe</b>。
 * <p>
 * 判定规则：
 * <pre>
 *   源仓 STANDARD → 放行（blocked = false）
 *   源仓 BLOCKED  → 默认拒绝，三个分支：
 *       该 SKU 有未作废让步流水 且 有客户同意凭证 → 放行
 *       有流水但无凭证                              → NO_ACK
 *       无流水                                      → NO_RELEASE
 * </pre>
 * <b>查不到必须拦，不许放行</b>。失效方向必须安全：查不到就放，
 * 那就是一次事故（缺陷品当良品发出去）。
 * <p>
 * <b>为什么用「仓 + SKU」而不是「销售单 + SKU」做匹配键</b>：禁发仓里的货可能是客户退回来的，
 * 压根没有销售单关联，用 SO 当查询键会全查不到。而且让步流水自己就记了 {@code concession_so_no}，
 * 用 SO 当键是循环依赖——先要知道哪张 SO 才能查让步记录。
 * <p>
 * <b>为什么用禁发仓这个仓维度，而不是"再查一张退货表"</b>：
 * ① 每加一种挂起原因（盘盈待判、客诉待查、召回）就要改一次查询，
 * 迟早变成第二个"两份白名单"（F3 那个毛病）；
 * ② 失效方向安全；
 * ③ 只依赖仓一个维度——仓是物理事实，不会因某条记录漏写而失效。
 */
@Service
public class ErpQcReleaseService {

    /** 有让步流水但没有客户同意凭证 */
    public static final String BLOCK_NO_ACK = "NO_ACK";
    /** 禁发仓里没有任何放行依据 */
    public static final String BLOCK_NO_RELEASE = "NO_RELEASE";

    /** 已知限制：SKU 级匹配不做数量配平 */
    public static final String NOTE = "让步匹配为 SKU 级，不做数量配平；"
            + "订单数量大于禁发仓存量时会整行拦截（多拦不少拦，方向安全）";

    private final ErpWarehouseRepository whRepo;
    private final ErpQcDispositionRepository dispRepo;
    private final ErpQcDefectRepository defectRepo;

    public ErpQcReleaseService(ErpWarehouseRepository whRepo,
                               ErpQcDispositionRepository dispRepo,
                               ErpQcDefectRepository defectRepo) {
        this.whRepo = whRepo;
        this.dispRepo = dispRepo;
        this.defectRepo = defectRepo;
    }

    /**
     * 校验某个仓、某些 SKU 能不能出库。
     *
     * @param whId   源仓 id
     * @param skuIds SKU 列表
     */
    @Transactional(readOnly = true)
    public Map<String, Object> check(Long whId, List<Long> skuIds) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("sourceWarehouseId", whId);
        ErpWarehouse wh = whId == null ? null : whRepo.findById(whId).orElse(null);
        out.put("sourceWarehouseName", wh == null ? null : wh.getName());
        out.put("fromSpecialWh", wh != null && wh.isBlocked());
        out.put("blocked", false);
        out.put("hits", new ArrayList<>());
        out.put("note", NOTE);

        // 正常仓：直接放行
        if (wh == null || !wh.isBlocked()) {
            return out;
        }
        out.put("blocked", true);
        List<Long> skus = skuIds == null ? List.of() : skuIds.stream()
                .filter(java.util.Objects::nonNull).distinct().toList();
        if (skus.isEmpty()) {
            return out;
        }
        List<ErpQcDisposition> concessions = dispRepo.findActiveConcessionsBySkuIds(skus);
        Map<Long, List<ErpQcDisposition>> bySku = concessions.stream()
                .collect(Collectors.groupingBy(this::skuIdOf));

        List<Map<String, Object>> hits = new ArrayList<>();
        for (Long skuId : skus) {
            List<ErpQcDisposition> rows = bySku.getOrDefault(skuId, List.of());
            if (rows.isEmpty()) {
                // NO_RELEASE：禁发仓里的货没有任何放行依据（可能就是刚退回来还没判）
                hits.add(hit(skuId, BLOCK_NO_RELEASE, List.of()));
                continue;
            }
            boolean acked = rows.stream().anyMatch(this::hasAck);
            if (acked) {
                // 有客户同意凭证 → 该 SKU 放行，不进 hits
                continue;
            }
            hits.add(hit(skuId, BLOCK_NO_ACK, rows));
        }
        out.put("hits", hits);
        return out;
    }

    private Map<String, Object> hit(Long skuId, String blockReason, List<ErpQcDisposition> rows) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("skuId", skuId);
        m.put("blockReason", blockReason);
        // 下一步要人做的事不同，不能混成一个"被拦了"：
        // NO_ACK → 登记客户同意单号；NO_RELEASE → 去不良品页处置
        m.put("actionHint", BLOCK_NO_ACK.equals(blockReason)
                ? "登记客户同意凭证后即可放行" : "请先到不良品页处置这批货");
        List<Map<String, Object>> records = new ArrayList<>();
        for (ErpQcDisposition d : rows) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("dispositionId", d.getId());
            r.put("defectNo", defectNoOf(d.getDefectId()));
            r.put("qty", d.effectiveQty());
            r.put("concessionSoNo", d.getConcessionSoNo());
            r.put("approver", d.getConcessionApprover());
            r.put("customerAckNo", d.getConcessionAckBillNo());
            records.add(r);
        }
        m.put("records", records);
        return m;
    }

    private boolean hasAck(ErpQcDisposition d) {
        return d.getConcessionAckBillNo() != null && !d.getConcessionAckBillNo().isBlank();
    }

    private Long skuIdOf(ErpQcDisposition d) {
        return defectRepo.findById(d.getDefectId())
                .map(ErpQcDefect::getSkuId).orElse(null);
    }

    private String defectNoOf(Long defectId) {
        return defectRepo.findById(defectId).map(ErpQcDefect::getDefectNo).orElse(null);
    }
}
