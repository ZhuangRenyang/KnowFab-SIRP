package com.factory.hub.erp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 销售出库的质量放行硬拦测试（AC-15）。
 *
 * <p><b>这条是 P0：只有查询接口不算实现。</b>
 * 界面提示"这批货不能发"是可绕过的（界面关了照样提交）；
 * 拦在 {@code ErpSalesService.ship} 里才是硬的——绕过前端直接调 API 一样被拒。
 *
 * <p>四条：
 * <ol>
 *   <li>让步流水 + 无客户同意 → 抛异常，<b>库存一件都没动</b>；</li>
 *   <li>让步流水 + 有客户同意 → 放行；</li>
 *   <li>禁发仓有货但无任何让步流水 → 抛异常（NO_RELEASE）；</li>
 *   <li>普通仓 → 不受影响（<b>最容易漏的一条</b>：别把正常发货也拦了）。</li>
 * </ol>
 */
class ErpSalesShipQualityGateTest {

    private static final String ORDER_NO = "SO20261103-0001";
    private static final Long SKU = 12L;
    private static final Long WH_BLOCKED = 9L;
    private static final Long WH_STANDARD = 1L;

    /** 组一套替身：订单已确认、有一行 10 件未发 */
    private static final class Fx {
        ErpSalesOrderRepository orderRepo = mock(ErpSalesOrderRepository.class);
        ErpSalesOrderItemRepository itemRepo = mock(ErpSalesOrderItemRepository.class);
        ErpStockService stockService = mock(ErpStockService.class);
        ErpStockBillRepository billRepo = mock(ErpStockBillRepository.class);
        ErpStockBillHdrRepository hdrRepo = mock(ErpStockBillHdrRepository.class);
        ErpSkuRepository skuRepo = mock(ErpSkuRepository.class);
        ErpPartnerRepository partnerRepo = mock(ErpPartnerRepository.class);
        ErpQualityReleaseGate gate = mock(ErpQualityReleaseGate.class);
        ErpSalesService svc;
        ErpQualityReturnGate returnGate = mock(ErpQualityReturnGate.class);
    }

    private Fx fx() {
        Fx f = new Fx();
        f.svc = new ErpSalesService(f.orderRepo, f.itemRepo, f.stockService, f.billRepo,
                f.hdrRepo, f.skuRepo, f.partnerRepo, f.gate, f.returnGate);

        ErpSalesOrder o = new ErpSalesOrder();
        o.setOrderNo(ORDER_NO);
        o.setStatus(ErpSalesOrder.STATUS_CONFIRMED);
        when(f.orderRepo.findByOrderNo(ORDER_NO)).thenReturn(Optional.of(o));

        ErpSalesOrderItem it = new ErpSalesOrderItem();
        it.setOrderId(5L);
        it.setSkuId(SKU);
        it.setQty(new BigDecimal("10"));
        it.setShippedQty(BigDecimal.ZERO);
        when(f.itemRepo.findByOrderIdOrderByLineNoAsc(any())).thenReturn(List.of(it));
        when(f.billRepo.sumByOrder(anyString())).thenReturn(List.of());
        when(f.stockService.createManual(anyString(), any(), any(), any(), anyString(), anyString(),
                anyList(), anyString())).thenReturn(new ErpStockService.Created("XSCK20261103-0001", 1));
        return f;
    }

    private static Object lines() {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(Map.of("skuId", SKU, "qty", new BigDecimal("3")));
        return rows;
    }

    @Test
    @DisplayName("① 禁发仓 + 闸门拒绝 → ship 抛异常，库存一张单都没建")
    void gateBlocksBeforeAnyStockMovement() {
        Fx f = fx();
        doThrow(new IllegalArgumentException("商品「钢件」有让步记录但缺客户书面同意（不良单 BLP0001）"))
                .when(f.gate).assertShippable(eq(WH_BLOCKED), anyList());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> f.svc.ship(ORDER_NO, WH_BLOCKED, lines(), "发货", "sales01"));
        assertTrue(ex.getMessage().contains("客户书面同意"), ex.getMessage());
        verify(f.stockService, never()).createManual(anyString(), any(), any(), any(),
                anyString(), anyString(), anyList(), anyString());
    }

    @Test
    @DisplayName("② 闸门放行 → ship 正常发货")
    void gateAllowsShip() {
        Fx f = fx();
        Map<String, Object> out = f.svc.ship(ORDER_NO, WH_BLOCKED, lines(), "发货", "sales01");
        assertEquals("XSCK20261103-0001", out.get("billNo"));
        verify(f.gate).assertShippable(eq(WH_BLOCKED), eq(List.of(SKU)));
        verify(f.stockService).createManual(eq(ErpStockBill.TYPE_SO_OUT), any(), any(), any(),
                anyString(), anyString(), anyList(), eq(ORDER_NO));
    }

    @Test
    @DisplayName("③ 禁发仓里没做任何让步（NO_RELEASE）→ 同样被闸门拦住")
    void noReleaseIsBlocked() {
        Fx f = fx();
        doThrow(new IllegalArgumentException("商品「钢件」在禁发仓里，还没做处置判定（不良单 BLP0002）"))
                .when(f.gate).assertShippable(eq(WH_BLOCKED), anyList());
        assertThrows(IllegalArgumentException.class,
                () -> f.svc.ship(ORDER_NO, WH_BLOCKED, lines(), "发货", "sales01"));
        verify(f.stockService, never()).createManual(anyString(), any(), any(), any(),
                anyString(), anyString(), anyList(), anyString());
    }

    @Test
    @DisplayName("④ 普通仓 → 闸门被调用但不拦，发货照常（别把正常发货也拦了）")
    void standardWarehouseIsNotBlocked() {
        Fx f = fx();
        Map<String, Object> out = f.svc.ship(ORDER_NO, WH_STANDARD, lines(), "发货", "sales01");
        assertEquals("XSCK20261103-0001", out.get("billNo"));
        verify(f.gate).assertShippable(eq(WH_STANDARD), eq(List.of(SKU)));
        verify(f.stockService).createManual(eq(ErpStockBill.TYPE_SO_OUT), any(), any(), any(),
                anyString(), anyString(), anyList(), eq(ORDER_NO));
    }

    @Test
    @DisplayName("闸门在数量校验之后、出库建单之前被调用（顺序错了会先建单再拦）")
    void gateRunsAfterQuantityCheck() {
        Fx f = fx();
        // 数量超过可发量 → 先被数量校验拦下，闸门根本不该被调用
        List<Map<String, Object>> tooMany = new ArrayList<>();
        tooMany.add(Map.of("skuId", SKU, "qty", new BigDecimal("99")));
        assertThrows(IllegalArgumentException.class,
                () -> f.svc.ship(ORDER_NO, WH_BLOCKED, tooMany, "发货", "sales01"));
        verify(f.gate, never()).assertShippable(any(), anyList());
    }
}
