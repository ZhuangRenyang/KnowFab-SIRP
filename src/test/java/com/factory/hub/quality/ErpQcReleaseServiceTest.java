package com.factory.hub.quality;

import com.factory.hub.erp.ErpWarehouse;
import com.factory.hub.erp.ErpWarehouseRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 禁发仓放行校验测试（QC-SPEC §2.11 / AC-07）。
 *
 * <p><b>这块的判定必须 fail-safe：查不到就拦，不许放行。</b>
 * 失效方向反了就是一次事故——缺陷品当良品发出去。
 * 三个分支各测一遍：
 * <pre>
 *   源仓 STANDARD → 放行
 *   源仓 BLOCKED + 有流水无凭证 → NO_ACK      （下一步：登记客户同意单号）
 *   源仓 BLOCKED + 无流水        → NO_RELEASE  （下一步：去不良品页处置）
 *   源仓 BLOCKED + 有流水有凭证  → 放行
 * </pre>
 */
class ErpQcReleaseServiceTest {

    private static final Long WH_BLOCKED = 9L;
    private static final Long WH_STANDARD = 1L;

    private ErpQcReleaseService svc(ErpQcDispositionRepository dispRepo,
                                     ErpQcDefectRepository defectRepo,
                                     ErpWarehouseRepository whRepo) {
        return new ErpQcReleaseService(whRepo, dispRepo, defectRepo);
    }

    private static ErpWarehouse wh(Long id, boolean blocked) {
        ErpWarehouse w = new ErpWarehouse();
        w.setId(id);
        w.setCode("WH" + id);
        w.setName(blocked ? "待处理仓" : "成品仓");
        w.setKind(blocked ? ErpWarehouse.KIND_BLOCKED : ErpWarehouse.KIND_STANDARD);
        return w;
    }

    private static ErpQcDisposition concession(Long defectId, String ackNo) {
        ErpQcDisposition d = new ErpQcDisposition();
        d.setId(defectId + 1000L);
        d.setDefectId(defectId);
        d.setAction(ErpQcDisposition.ACT_CONCESSION);
        d.setQty(new BigDecimal("2"));
        d.setConcessionAckBillNo(ackNo);
        d.setConcessionApprover("张工");
        d.setConcessionSoNo("SO20261101-0001");
        return d;
    }

    @Test
    @DisplayName("源仓是正常仓 → 放行，hits 为空")
    void standardWarehousePasses() {
        ErpWarehouseRepository whRepo = mock(ErpWarehouseRepository.class);
        when(whRepo.findById(WH_STANDARD)).thenReturn(Optional.of(wh(WH_STANDARD, false)));
        ErpQcReleaseService s = svc(mock(ErpQcDispositionRepository.class),
                mock(ErpQcDefectRepository.class), whRepo);
        Map<String, Object> out = s.check(WH_STANDARD, List.of(12L));
        assertEquals(false, out.get("blocked"));
        assertEquals(false, out.get("fromSpecialWh"));
        assertTrue(((List<?>) out.get("hits")).isEmpty());
    }

    @Test
    @DisplayName("禁发仓 + 无任何让步流水 → NO_RELEASE（必须拦）")
    void blockedWithoutConcessionIsNoRelease() {
        ErpWarehouseRepository whRepo = mock(ErpWarehouseRepository.class);
        when(whRepo.findById(WH_BLOCKED)).thenReturn(Optional.of(wh(WH_BLOCKED, true)));
        ErpQcDispositionRepository dispRepo = mock(ErpQcDispositionRepository.class);
        when(dispRepo.findActiveConcessionsBySkuIds(any())).thenReturn(List.of());
        ErpQcReleaseService s = svc(dispRepo, mock(ErpQcDefectRepository.class), whRepo);

        Map<String, Object> out = s.check(WH_BLOCKED, List.of(12L));
        assertEquals(true, out.get("blocked"));
        List<?> hits = (List<?>) out.get("hits");
        assertEquals(1, hits.size());
        Map<?, ?> hit = (Map<?, ?>) hits.get(0);
        assertEquals(ErpQcReleaseService.BLOCK_NO_RELEASE, hit.get("blockReason"));
        assertTrue(((List<?>) hit.get("records")).isEmpty(), "NO_RELEASE 时没有不良单号可给");
        assertNotNull(hit.get("actionHint"));
    }

    @Test
    @DisplayName("禁发仓 + 有让步流水但没客户同意凭证 → NO_ACK")
    void blockedWithoutAckIsNoAck() {
        ErpWarehouseRepository whRepo = mock(ErpWarehouseRepository.class);
        when(whRepo.findById(WH_BLOCKED)).thenReturn(Optional.of(wh(WH_BLOCKED, true)));
        ErpQcDispositionRepository dispRepo = mock(ErpQcDispositionRepository.class);
        when(dispRepo.findActiveConcessionsBySkuIds(any()))
                .thenReturn(List.of(concession(1L, null)));
        ErpQcDefectRepository defectRepo = mock(ErpQcDefectRepository.class);
        ErpQcDefect d = new ErpQcDefect();
        d.setId(1L);
        d.setDefectNo("BLP20261103-0001");
        d.setSkuId(12L);
        when(defectRepo.findById(1L)).thenReturn(Optional.of(d));

        ErpQcReleaseService s = svc(dispRepo, defectRepo, whRepo);
        Map<String, Object> out = s.check(WH_BLOCKED, List.of(12L));
        List<?> hits = (List<?>) out.get("hits");
        assertEquals(1, hits.size());
        Map<?, ?> hit = (Map<?, ?>) hits.get(0);
        assertEquals(ErpQcReleaseService.BLOCK_NO_ACK, hit.get("blockReason"));
        List<?> records = (List<?>) hit.get("records");
        assertEquals(1, records.size());
        Map<?, ?> r = (Map<?, ?>) records.get(0);
        assertEquals("BLP20261103-0001", r.get("defectNo"));
        assertEquals("张工", r.get("approver"));
        assertEquals(null, r.get("customerAckNo"), "没有凭证时必须是 null，不能给个假值");
    }

    @Test
    @DisplayName("禁发仓 + 有客户同意凭证 → 该 SKU 放行（不进 hits）")
    void ackedConcessionPasses() {
        ErpWarehouseRepository whRepo = mock(ErpWarehouseRepository.class);
        when(whRepo.findById(WH_BLOCKED)).thenReturn(Optional.of(wh(WH_BLOCKED, true)));
        ErpQcDispositionRepository dispRepo = mock(ErpQcDispositionRepository.class);
        when(dispRepo.findActiveConcessionsBySkuIds(any()))
                .thenReturn(List.of(concession(1L, "ACK-2026-001")));
        ErpQcDefectRepository defectRepo = mock(ErpQcDefectRepository.class);
        ErpQcDefect d = new ErpQcDefect();
        d.setId(1L);
        d.setSkuId(12L);
        when(defectRepo.findById(1L)).thenReturn(Optional.of(d));

        ErpQcReleaseService s = svc(dispRepo, defectRepo, whRepo);
        Map<String, Object> out = s.check(WH_BLOCKED, List.of(12L));
        assertEquals(true, out.get("blocked"), "整单层面仍是禁发仓");
        assertTrue(((List<?>) out.get("hits")).isEmpty(), "有凭证的 SKU 不进 hits（它被放行了）");
    }

    @Test
    @DisplayName("多个 SKU：逐个判定，有凭证的放行、没凭证的进 hits")
    void multipleSkusAreJudgedSeparately() {
        ErpWarehouseRepository whRepo = mock(ErpWarehouseRepository.class);
        when(whRepo.findById(WH_BLOCKED)).thenReturn(Optional.of(wh(WH_BLOCKED, true)));
        ErpQcDispositionRepository dispRepo = mock(ErpQcDispositionRepository.class);
        when(dispRepo.findActiveConcessionsBySkuIds(any()))
                .thenReturn(List.of(concession(1L, "ACK-2026-001")));
        ErpQcDefectRepository defectRepo = mock(ErpQcDefectRepository.class);
        ErpQcDefect d = new ErpQcDefect();
        d.setId(1L);
        d.setSkuId(12L);
        when(defectRepo.findById(1L)).thenReturn(Optional.of(d));

        ErpQcReleaseService s = svc(dispRepo, defectRepo, whRepo);
        Map<String, Object> out = s.check(WH_BLOCKED, List.of(12L, 15L));
        List<?> hits = (List<?>) out.get("hits");
        assertEquals(1, hits.size(), "只有没凭证没流水的那个 SKU 被拦");
        assertEquals(15L, ((Map<?, ?>) hits.get(0)).get("skuId"));
    }

    @Test
    @DisplayName("返回里带已知限制说明（SKU 级匹配不做数量配平）")
    void noteExplainsKnownLimit() {
        ErpWarehouseRepository whRepo = mock(ErpWarehouseRepository.class);
        when(whRepo.findById(WH_STANDARD)).thenReturn(Optional.of(wh(WH_STANDARD, false)));
        ErpQcReleaseService s = svc(mock(ErpQcDispositionRepository.class),
                mock(ErpQcDefectRepository.class), whRepo);
        Map<String, Object> out = s.check(WH_STANDARD, List.of(12L));
        assertNotNull(out.get("note"));
        assertTrue(String.valueOf(out.get("note")).contains("SKU 级"), String.valueOf(out.get("note")));
    }
}
