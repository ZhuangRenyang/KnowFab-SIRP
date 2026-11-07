package com.factory.hub.erp.production;

import com.factory.hub.report.ProdHourlyRepository;
import com.factory.hub.service.DeviceService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.factory.hub.erp.ErpSkuRepository;
import com.factory.hub.erp.ErpWarehouseRepository;
import com.factory.hub.erp.analysis.ErpBomRepository;
import com.factory.hub.erp.analysis.ErpBomService;
import com.factory.hub.erp.stock.ErpStockBillRepository;
import com.factory.hub.erp.stock.ErpStockService;

/**
 * 返工工单下达的回归测试。
 *
 * <p><b>守的是这个 bug</b>：{@code release()} 里原来先调 {@code bomService.expand()}，
 * 再判断 {@code isRework()}。而 {@code expand()} 在产品没配 BOM 时会直接抛
 * "这个产品还没配 BOM，无法下达工单" —— 返工单永远走不到那句 {@code mats = List.of()}。
 *
 * <p><b>后果</b>：返工单下达不了。而返工单恰恰是最不该要求配 BOM 的单子
 * （它复用的是已经出过一次的那批料）。用户看到的报错是"没配 BOM"，
 * 与真实原因完全不符——他要去配 BOM，配完发现还是不行（因为返工单本来就不该展开）。
 *
 * <p><b>连带后果</b>：处置选「返工」时建出的返工工单下达就炸 → 返工闭环走不通。
 */
class ErpWorkOrderReworkReleaseTest {

    private static final String ORDER_NO = "WO20261103001";

    private static final class Fx {
        ErpWorkOrderRepository woRepo = mock(ErpWorkOrderRepository.class);
        ErpWorkOrderMaterialRepository womRepo = mock(ErpWorkOrderMaterialRepository.class);
        ErpWorkOrderOutputRepository wooRepo = mock(ErpWorkOrderOutputRepository.class);
        ErpBomRepository bomRepo = mock(ErpBomRepository.class);
        ErpBomService bomService = mock(ErpBomService.class);
        ErpBackflushService backflushService = mock(ErpBackflushService.class);
        ErpStockService stockService = mock(ErpStockService.class);
        ErpStockBillRepository billRepo = mock(ErpStockBillRepository.class);
        ErpSkuRepository skuRepo = mock(ErpSkuRepository.class);
        ErpProdLineRepository lineRepo = mock(ErpProdLineRepository.class);
        ErpWarehouseRepository whRepo = mock(ErpWarehouseRepository.class);
        DeviceService deviceService = mock(DeviceService.class);
        ErpWorkOrderService svc;
        ErpWorkOrder order;
    }

    private Fx fx(String orderType) {
        Fx f = new Fx();
        ErpWorkOrder o = new ErpWorkOrder();
        o.setId(1L);
        o.setOrderNo(ORDER_NO);
        o.setStatus(ErpWorkOrder.ST_DRAFT);
        o.setOrderType(orderType);
        o.setProductSkuId(77L);
        o.setPlanQty(new BigDecimal("10"));
        f.order = o;

        when(f.woRepo.findByOrderNo(ORDER_NO)).thenReturn(Optional.of(o));
        when(f.woRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        f.svc = new ErpWorkOrderService(f.woRepo, f.womRepo, f.wooRepo, f.bomRepo,
                f.bomService, f.backflushService, f.stockService, f.billRepo,
                f.skuRepo, f.lineRepo, f.whRepo, f.deviceService);
        return f;
    }

    @Test
    @DisplayName("返工单下达：不展开 BOM，产品没配 BOM 也能下达")
    void reworkOrderReleasesWithoutBom() {
        Fx f = fx(ErpWorkOrder.TYPE_REWORK);

        // bomService.expand() 在真实现里会抛"没配 BOM"——
        // 用 mock 把它设成会抛，验证返工单根本不会走到它。
        when(f.bomService.expand(any(), any(), any())).thenThrow(
                new IllegalArgumentException("这个产品还没配 BOM，无法下达工单。请先到「基础资料 → 物料清单」里配好用料"));

        var r = f.svc.release(ORDER_NO, "tester");

        assertEquals(Boolean.TRUE, r.get("ok"), "返工单必须能下达：" + r.get("message"));
        verify(f.bomService, never()).expand(any(), any(), any());
        assertEquals(ErpWorkOrder.ST_RELEASED, f.order.getStatus());
    }

    @Test
    @DisplayName("普通工单下达：照常展开 BOM（返工的例外不能扩大到所有单）")
    void normalOrderStillExpandsBom() {
        Fx f = fx(ErpWorkOrder.TYPE_PROD);
        when(f.bomService.expand(any(), any(), any())).thenReturn(java.util.List.of());

        var r = f.svc.release(ORDER_NO, "tester");

        assertEquals(Boolean.TRUE, r.get("ok"), "普通工单必须能下达：" + r.get("message"));
        verify(f.bomService).expand(any(), any(), any());
    }

    @Test
    @DisplayName("AC-06 返工单没有用料清单 → 领料必须抛异常（不生成 MATERIAL_OUT）")
    void reworkOrderCannotIssueMaterial() {
        // 这是「返工不重复计料」的**执行侧**保证：
        // 返工单下达时不展开 BOM（上面两条用例守的是下达侧），所以用料清单是空的。
        // 空清单 → 任何 skuId 都查不到用料行 → issue() 直接拒绝。
        //
        // 如果不拒绝会怎样：返工单也能领料 → 那批料被重复扣一次 →
        // 材料成本凭空多计，而报表上一切正常（没有任何异常信号）。
        Fx f = fx(ErpWorkOrder.TYPE_REWORK);
        f.svc.release(ORDER_NO, "tester");
        // 用料表里确实没有这张工单的任何行（下达时写的就是空快照）
        org.mockito.Mockito.when(f.womRepo.findByWorkOrderIdOrderBySkuIdAsc(1L))
                .thenReturn(java.util.List.of());

        IllegalArgumentException ex = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> f.svc.issue(ORDER_NO,
                        java.util.List.of(new ErpWorkOrderService.IssueLine(77L, BigDecimal.ONE)),
                        1L, "tester"),
                "返工单没有用料清单，领料必须被拒绝——否则同一批料会被扣两次");
        assertTrue(ex.getMessage().contains("用料清单"),
                "报错要说清原因（不在用料清单里），不能只说\"领料失败\"：" + ex.getMessage());

        // 而且**没有建出任何 MATERIAL_OUT 单据** —— 这才是 AC-06 要的那个结果
        verify(f.stockService, never()).createManual(any(), any(), any(), any(), any(), any(),
                any(), any());
    }
}
