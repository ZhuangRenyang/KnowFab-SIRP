package com.factory.hub.erp.quality.defect;

import com.factory.hub.erp.ErpSku;
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
public class ErpQcReleaseService implements com.factory.hub.erp.ErpQualityReleaseGate {

    /** 有让步流水但没有客户同意凭证 */
    public static final String BLOCK_NO_ACK = "NO_ACK";
    /** 禁发仓里没有任何放行依据 */
    public static final String BLOCK_NO_RELEASE = "NO_RELEASE";

    /**
     * 已知限制的<b>代号</b>（不给中文原文）。
     * <p>
     * 后端不返回面向用户的文案：塞中文则英文界面蹦中文，塞英文则中文界面蹦英文。
     * 只有"后端给码、前端给文案"是干净的。限制说明由前端 i18n 写。
     */
    public static final String LIMIT_SKU_LEVEL_MATCH = "SKU_LEVEL_MATCH";

    private final ErpWarehouseRepository whRepo;
    private final ErpQcDispositionRepository dispRepo;
    private final ErpQcDefectRepository defectRepo;
    private final com.factory.hub.erp.ErpSkuRepository skuRepo;

    public ErpQcReleaseService(ErpWarehouseRepository whRepo,
                               ErpQcDispositionRepository dispRepo,
                               ErpQcDefectRepository defectRepo,
                               com.factory.hub.erp.ErpSkuRepository skuRepo) {
        this.whRepo = whRepo;
        this.dispRepo = dispRepo;
        this.defectRepo = defectRepo;
        this.skuRepo = skuRepo;
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
        // 已知限制只给代号，说明文字由前端 i18n 写（后端不返回面向用户的文案）
        out.put("limitCode", LIMIT_SKU_LEVEL_MATCH);

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

    /**
     * 硬拦：不能出库就抛异常（AC-15）。
     * <p>
     * <b>这是"查不到就拦"落地的那一步</b>：查询接口只是给界面提示，
     * 界面关了照样能提交；拦在出库路径上才是硬的。
     * <p>
     * 两种拦法给两种提示（要用户做的事完全不同，不能混成一个"被拦了"）：
     * <ul>
     *   <li>{@code NO_ACK}：有让步记录但缺客户书面同意 → 去登记同意凭证；</li>
     *   <li>{@code NO_RELEASE}：禁发仓里没有任何放行依据 → 去不良品页处置。</li>
     * </ul>
     */
    @Override
    public void assertShippable(Long whId, List<Long> skuIds) {
        Map<String, Object> out = check(whId, skuIds);
        if (!Boolean.TRUE.equals(out.get("blocked"))) {
            return;
        }
        List<?> hits = (List<?>) out.get("hits");
        // 禁发仓 + 一个 SKU 都没给：无法核验有没有放行依据 → 拒（fail-safe 方向）
        if (skuIds == null || skuIds.isEmpty()) {
            throw new IllegalArgumentException("这次出库的源仓是禁发仓，但没能核验出库的货物，"
                    + "不能直接发货。请先到「不良品」页处置这批货，或改从正常仓发货");
        }
        if (hits == null || hits.isEmpty()) {
            // hits 为空 = 本次出库的每个 SKU 都有放行依据（有客户同意凭证）→ 放行
            return;
        }
        for (Object o : hits) {
            if (!(o instanceof Map<?, ?> hit)) {
                continue;
            }
            Long skuId = hit.get("skuId") instanceof Number n ? n.longValue() : null;
            String reason = String.valueOf(hit.get("blockReason"));
            String nos = defectNosOf(hit.get("records"));
            String skuText = skuId == null ? "这批货" : "商品「" + skuName(skuId) + "」";
            if (BLOCK_NO_ACK.equals(reason)) {
                throw new IllegalArgumentException(skuText + "有让步记录但缺客户书面同意"
                        + (nos.isEmpty() ? "" : "（不良单 " + nos + "）")
                        + "，请先登记客户同意凭证再发货");
            }
            throw new IllegalArgumentException(skuText + "在禁发仓里，还没做处置判定"
                    + (nos.isEmpty() ? "" : "（不良单 " + nos + "）")
                    + "，请先处置完再发货");
        }
    }

    /** 命中记录里的不良单号（给用户看是哪几张单卡住了） */
    private String defectNosOf(Object records) {
        if (!(records instanceof List<?> list)) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) {
                continue;
            }
            Object no = m.get("defectNo");
            if (no == null) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append("、");
            }
            sb.append(no);
        }
        return sb.toString();
    }

    private String skuName(Long skuId) {
        return skuRepo.findById(skuId).map(ErpSku::getName).orElse("商品#" + skuId);
    }

    private Map<String, Object> hit(Long skuId, String blockReason, List<ErpQcDisposition> rows) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("skuId", skuId);
        m.put("blockReason", blockReason);
        // 下一步要人做的事不同，不能混成一个"被拦了"：
        // NO_ACK → 登记客户同意单号；NO_RELEASE → 去不良品页处置
        m.put("actionHint", BLOCK_NO_ACK.equals(blockReason)
                ? "REGISTER_ACK" : "DISPOSE_FIRST");
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
