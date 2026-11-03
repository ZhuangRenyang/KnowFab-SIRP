package com.factory.hub.quality;

import com.factory.hub.audit.AuditService;
import com.factory.hub.erp.ErpStockBill;
import com.factory.hub.erp.ErpStockService;
import com.factory.hub.erp.ErpWarehouse;
import com.factory.hub.erp.ErpWarehouseRepository;
import com.factory.hub.erp.ErpWorkOrderService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 不良品处置服务测试（AC-01 / AC-02 的服务层）。
 *
 * <p>适配器的六个分支在 {@code ErpQualityStockAdapterTest} 里逐支钉死；
 * 这里盯的是<b>服务层的守恒与校验</b>：
 * <ul>
 *   <li>{@code disposed_qty} 必须是 {@code sum(qty - fail_qty) where voided_at is null}
 *       ——写成 {@code sum(qty)}，返工失败退回的那一件会永久失踪，而且账面完全正常；</li>
 *   <li>撤销的顺序：先冲销库存单据，再把处置行标作废。反过来的话中途失败会留下
 *       "处置行没了、库存单还在"的局面，账实从此对不上；</li>
 *   <li>处置一经执行不可原地修改，要改就撤销 + 重做。</li>
 * </ul>
 */
class ErpQcDefectServiceTest {

    private static final Long WH_STOCK = 1L;
    private static final Long WH_BLOCKED = 9L;

    /** 测试用的替身集合 */
    private static final class Fx {
        ErpQcDefectRepository defectRepo = mock(ErpQcDefectRepository.class);
        ErpQcDispositionRepository dispRepo = mock(ErpQcDispositionRepository.class);
        ErpStockService stockService = mock(ErpStockService.class);
        ErpWorkOrderService workOrderService = mock(ErpWorkOrderService.class);
        ErpWarehouseRepository whRepo = mock(ErpWarehouseRepository.class);
        AuditService audit = mock(AuditService.class);
        ErpQcDefectService svc;
        final List<ErpQcDisposition> saved = new ArrayList<>();
    }

    private Fx fx() {
        Fx f = new Fx();
        f.svc = new ErpQcDefectService(f.defectRepo, f.dispRepo, f.stockService,
                f.workOrderService, f.whRepo, f.audit);
        when(f.dispRepo.maxSeqNo(any())).thenReturn(0);
        when(f.dispRepo.sumActiveQty(any())).thenReturn(BigDecimal.ZERO);
        when(f.dispRepo.save(any())).thenAnswer(inv -> {
            ErpQcDisposition d = inv.getArgument(0);
            f.saved.add(d);
            return d;
        });
        when(f.defectRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(f.whRepo.findById(WH_STOCK)).thenReturn(Optional.of(wh(WH_STOCK, "成品仓", false)));
        when(f.whRepo.findById(WH_BLOCKED)).thenReturn(Optional.of(wh(WH_BLOCKED, "待处理仓", true)));
        when(f.whRepo.findAll()).thenReturn(List.of(wh(WH_STOCK, "成品仓", false),
                wh(WH_BLOCKED, "待处理仓", true)));
        when(f.stockService.createManual(anyString(), any(), any(), any(), anyString(), anyString(),
                any(), any())).thenReturn(new ErpStockService.Created("BILL20261103-0001", 1));
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("orderNo", "WO20261103-0009");
        when(f.workOrderService.create(any(), anyString())).thenReturn(order);
        return f;
    }

    private static ErpWarehouse wh(Long id, String name, boolean blocked) {
        ErpWarehouse w = new ErpWarehouse();
        w.setId(id);
        w.setCode("WH" + id);
        w.setName(name);
        w.setKind(blocked ? ErpWarehouse.KIND_BLOCKED : ErpWarehouse.KIND_STANDARD);
        return w;
    }

    private static ErpQcDefect defect(boolean inStock, String qty) {
        ErpQcDefect d = new ErpQcDefect();
        d.setId(100L);
        d.setDefectNo("BLP20261103-0001");
        d.setQty(new BigDecimal(qty));
        d.setDisposedQty(BigDecimal.ZERO);
        d.setStatus(ErpQcDefect.ST_PENDING);
        d.setInStock(inStock);
        d.setWarehouseId(inStock ? WH_STOCK : null);
        d.setSrcBillNo(inStock ? "CGRK20261101-0001" : null);
        d.setSkuId(12L);
        return d;
    }

    private static void given(Fx f, ErpQcDefect d) {
        when(f.defectRepo.findByDefectNo(d.getDefectNo())).thenReturn(Optional.of(d));
    }

    /* ==================== 三种动作 ==================== */

    @Test
    @DisplayName("报废 + 未入账 → 不建库存单，只写处置明细")
    void scrapNotInStock() {
        Fx f = fx();
        ErpQcDefect d = defect(false, "8");
        given(f, d);
        Map<String, Object> out = f.svc.dispose("BLP20261103-0001", ErpQcDisposition.ACT_SCRAP,
                Map.of(), "qc01");
        verify(f.stockService, never()).createManual(anyString(), any(), any(), any(),
                anyString(), anyString(), any(), any());
        assertEquals(1, f.saved.size());
        assertEquals(ErpQcDisposition.ACT_SCRAP, f.saved.get(0).getAction());
        assertEquals(0, f.saved.get(0).getQty().compareTo(new BigDecimal("8")));
        assertNotNull(out.get("pendingQty"));
    }

    @Test
    @DisplayName("报废 + 已入账 → 建 SCRAP_OUT 出库单（类型对、单号落到处置行）")
    void scrapInStock() {
        Fx f = fx();
        ErpQcDefect d = defect(true, "8");
        given(f, d);
        f.svc.dispose("BLP20261103-0001", ErpQcDisposition.ACT_SCRAP, Map.of(), "qc01");
        ArgumentCaptor<String> type = ArgumentCaptor.forClass(String.class);
        verify(f.stockService).createManual(type.capture(), any(), any(), any(), anyString(),
                anyString(), any(), any());
        assertEquals(ErpStockBill.TYPE_SCRAP_OUT, type.getValue());
        assertEquals("BILL20261103-0001", f.saved.get(0).getStockBillNo());
    }

    @Test
    @DisplayName("返工 → 建 REWORK 工单，不动库存，并给出人工领料提示")
    void reworkCreatesOrderOnly() {
        Fx f = fx();
        ErpQcDefect d = defect(true, "6");
        given(f, d);
        Map<String, Object> out = f.svc.dispose("BLP20261103-0001", ErpQcDisposition.ACT_REWORK,
                Map.of(), "qc01");
        verify(f.stockService, never()).createManual(anyString(), any(), any(), any(),
                anyString(), anyString(), any(), any());
        verify(f.workOrderService, times(1)).create(any(), anyString());
        assertEquals("WO20261103-0009", f.saved.get(0).getReworkOrderNo());
        assertEquals(ErpQualityStockAdapter.WARN_REWORK_NEED_ISSUE, out.get("warnCode"),
                "已入账的返工必须给提示代号（文案由前端写）");
        assertEquals("成品仓", ((Map<?, ?>) out.get("warnParams")).get("warehouseName"));
    }

    @Test
    @DisplayName("让步 + 未入账 → OTHER_IN 入待处理仓；已入账 → TRANSFER 原仓→待处理仓")
    void concessionBothBranches() {
        Fx f1 = fx();
        ErpQcDefect d1 = defect(false, "2");
        given(f1, d1);
        f1.svc.dispose("BLP20261103-0001", ErpQcDisposition.ACT_CONCESSION, Map.of(), "qc01");
        ArgumentCaptor<String> t1 = ArgumentCaptor.forClass(String.class);
        verify(f1.stockService).createManual(t1.capture(), any(), any(), any(), anyString(),
                anyString(), any(), any());
        assertEquals(ErpStockBill.TYPE_OTHER_IN, t1.getValue());

        Fx f2 = fx();
        ErpQcDefect d2 = defect(true, "2");
        given(f2, d2);
        f2.svc.dispose("BLP20261103-0001", ErpQcDisposition.ACT_CONCESSION, Map.of(), "qc01");
        ArgumentCaptor<String> t2 = ArgumentCaptor.forClass(String.class);
        verify(f2.stockService).createManual(t2.capture(), any(), any(), any(), anyString(),
                anyString(), any(), any());
        assertEquals(ErpStockBill.TYPE_TRANSFER, t2.getValue());
    }

    /* ==================== 校验 ==================== */

    @Test
    @DisplayName("处置件数超过待处置 → 拒绝（不能凭空多处置）")
    void qtyOverPendingRejected() {
        Fx f = fx();
        ErpQcDefect d = defect(false, "8");
        d.setDisposedQty(new BigDecimal("5"));
        given(f, d);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> f.svc.dispose("BLP20261103-0001", ErpQcDisposition.ACT_SCRAP,
                        Map.of("qty", new BigDecimal("5")), "qc01"));
        assertTrue(ex.getMessage().contains("超过待处置"), ex.getMessage());
    }

    @Test
    @DisplayName("已处置完 / 已作废 的单不能再处置")
    void closedRecordsRejected() {
        Fx f = fx();
        ErpQcDefect done = defect(false, "8");
        done.setDisposedQty(new BigDecimal("8"));
        done.setStatus(ErpQcDefect.ST_DISPOSED);
        given(f, done);
        assertThrows(IllegalArgumentException.class,
                () -> f.svc.dispose("BLP20261103-0001", ErpQcDisposition.ACT_SCRAP, Map.of(), "qc01"));

        Fx f2 = fx();
        ErpQcDefect cancelled = defect(false, "8");
        cancelled.setStatus(ErpQcDefect.ST_CANCELLED);
        given(f2, cancelled);
        assertThrows(IllegalArgumentException.class,
                () -> f2.svc.dispose("BLP20261103-0001", ErpQcDisposition.ACT_SCRAP, Map.of(), "qc01"));
    }

    @Test
    @DisplayName("已入账却没有来源单据号 → 拒绝处置（账上没来源就没法对账）")
    void inStockWithoutSourceBillRejected() {
        Fx f = fx();
        ErpQcDefect d = defect(true, "8");
        d.setSrcBillNo(null);
        given(f, d);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> f.svc.dispose("BLP20261103-0001", ErpQcDisposition.ACT_SCRAP, Map.of(), "qc01"));
        assertTrue(ex.getMessage().contains("来源单据"), ex.getMessage());
        verify(f.stockService, never()).createManual(anyString(), any(), any(), any(),
                anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("已入账却没有所在仓 → 拒绝（不能静默不动库存）")
    void inStockWithoutWarehouseRejected() {
        Fx f = fx();
        ErpQcDefect d = defect(true, "8");
        d.setWarehouseId(null);
        given(f, d);
        assertThrows(IllegalArgumentException.class,
                () -> f.svc.dispose("BLP20261103-0001", ErpQcDisposition.ACT_SCRAP, Map.of(), "qc01"));
    }

    /* ==================== 镜像列重算 ==================== */

    @Test
    @DisplayName("disposed_qty 取 sum(qty - fail_qty)，并且状态随待处置量回退")
    void disposedQtyUsesEffectiveQty() {
        Fx f = fx();
        ErpQcDefect d = defect(false, "8");
        given(f, d);
        // 仓储给的是有效消费量 7（返工 5 失败 1 + 报废 2）
        when(f.dispRepo.sumActiveQty(100L)).thenReturn(new BigDecimal("7"));
        f.svc.dispose("BLP20261103-0001", ErpQcDisposition.ACT_SCRAP, Map.of(), "qc01");
        assertEquals(0, d.getDisposedQty().compareTo(new BigDecimal("7")));
        assertEquals(ErpQcDefect.ST_PENDING, d.getStatus(),
                "还有 1 件待处置，状态不能是 DISPOSED");
    }

    @Test
    @DisplayName("待处置归零 → status = DISPOSED（只有三态）")
    void statusBecomesDisposed() {
        Fx f = fx();
        ErpQcDefect d = defect(false, "8");
        given(f, d);
        when(f.dispRepo.sumActiveQty(100L)).thenReturn(new BigDecimal("8"));
        f.svc.dispose("BLP20261103-0001", ErpQcDisposition.ACT_SCRAP, Map.of(), "qc01");
        assertEquals(ErpQcDefect.ST_DISPOSED, d.getStatus());
    }

    /* ==================== 撤销 ==================== */

    @Test
    @DisplayName("撤销：先冲销库存单据，再把处置行标作废，最后重算镜像列")
    void revokeVoidsBillAndRow() {
        Fx f = fx();
        ErpQcDefect d = defect(true, "8");
        given(f, d);
        ErpQcDisposition disp = new ErpQcDisposition();
        disp.setId(7L);
        disp.setDefectId(100L);
        disp.setSeqNo(1);
        disp.setAction(ErpQcDisposition.ACT_SCRAP);
        disp.setQty(new BigDecimal("8"));
        disp.setStockBillNo("BFCK20261103-0001");
        when(f.dispRepo.findById(7L)).thenReturn(Optional.of(disp));
        when(f.dispRepo.findByDefectIdOrderBySeqNoAsc(100L)).thenReturn(List.of(disp));
        when(f.dispRepo.sumActiveQty(100L)).thenReturn(BigDecimal.ZERO);

        f.svc.revoke("BLP20261103-0001", 7L, "报废单开错仓", "mgr01");

        verify(f.stockService).voidBill(eq("BFCK20261103-0001"), eq("mgr01"));
        assertNotNull(disp.getVoidedAt());
        assertEquals("报废单开错仓", disp.getVoidReason());
        assertEquals(ErpQcDefect.ST_PENDING, d.getStatus(), "撤销后要退回待处置");
        assertEquals(0, d.getDisposedQty().signum());
    }

    @Test
    @DisplayName("撤销必须填原因；重复撤销被拒绝")
    void revokeRequiresReason() {
        Fx f = fx();
        ErpQcDefect d = defect(false, "8");
        given(f, d);
        ErpQcDisposition disp = new ErpQcDisposition();
        disp.setId(7L);
        disp.setDefectId(100L);
        when(f.dispRepo.findById(7L)).thenReturn(Optional.of(disp));
        assertThrows(IllegalArgumentException.class,
                () -> f.svc.revoke("BLP20261103-0001", 7L, "  ", "mgr01"));

        disp.setVoidedAt(System.currentTimeMillis());
        assertThrows(IllegalArgumentException.class,
                () -> f.svc.revoke("BLP20261103-0001", 7L, "又撤一次", "mgr01"));
    }

    @Test
    @DisplayName("撤销别人的处置行被拒绝（处置行必须属于这张不良单）")
    void revokeForeignRowRejected() {
        Fx f = fx();
        ErpQcDefect d = defect(false, "8");
        given(f, d);
        ErpQcDisposition disp = new ErpQcDisposition();
        disp.setId(7L);
        disp.setDefectId(999L);
        when(f.dispRepo.findById(7L)).thenReturn(Optional.of(disp));
        assertThrows(IllegalArgumentException.class,
                () -> f.svc.revoke("BLP20261103-0001", 7L, "撤错了", "mgr01"));
    }

    /* ==================== displayStatus 五态 ==================== */

    @Test
    @DisplayName("displayStatus 五态：CANCELLED > REWORK_FQC_FAIL > REWORK_WAIT_FQC > PENDING > DISPOSED")
    void displayStatusValues() {
        Fx f = fx();
        ErpQcDefect d = defect(false, "8");
        // CANCELLED
        d.setStatus(ErpQcDefect.ST_CANCELLED);
        assertEquals("CANCELLED", f.svc.displayStatus(d, List.of()));

        // PENDING
        d.setStatus(ErpQcDefect.ST_PENDING);
        assertEquals("PENDING", f.svc.displayStatus(d, List.of()));

        // DISPOSED
        d.setStatus(ErpQcDefect.ST_DISPOSED);
        assertEquals("DISPOSED", f.svc.displayStatus(d, List.of()));

        // REWORK_WAIT_FQC：有返工流水、还没复检
        d.setStatus(ErpQcDefect.ST_PENDING);
        ErpQcDisposition rw = new ErpQcDisposition();
        rw.setAction(ErpQcDisposition.ACT_REWORK);
        rw.setQty(new BigDecimal("5"));
        assertEquals("REWORK_WAIT_FQC", f.svc.displayStatus(d, List.of(rw)));

        // REWORK_FQC_FAIL：返工失败件数 > 0
        rw.setFailQty(BigDecimal.ONE);
        rw.setFqcInspectionNo("QC20261103-0001");
        assertEquals("REWORK_FQC_FAIL", f.svc.displayStatus(d, List.of(rw)));

        // 已作废的流水不算
        rw.setVoidedAt(System.currentTimeMillis());
        assertEquals("PENDING", f.svc.displayStatus(d, List.of(rw)));
    }
}
