package com.factory.hub.erp.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.factory.hub.energy.EnergyRepository;
import com.factory.hub.energy.EnergyRollup;
import com.factory.hub.report.ProdHourlyRepository;
import com.factory.hub.report.ProdRollup;
import com.factory.hub.system.SettingStore;
import com.factory.hub.erp.ErpSkuRepository;
import com.factory.hub.erp.production.ErpWorkOrder;
import com.factory.hub.erp.production.ErpWorkOrderRepository;
import com.factory.hub.erp.stock.ErpStockBill;
import com.factory.hub.erp.stock.ErpStockBillRepository;

/**
 * 验收项 <b>AC-07：报废不重复进成本</b>。
 * <p>
 * <b>这条为什么必须钉死</b>：报废处置会生成一张 {@code SCRAP_OUT} 出库单。
 * 如果成本核算把所有出库单都当材料消耗，那每报废一次就<b>重复计一次材料费</b>——
 * 而材料费在<b>领料时（{@code MATERIAL_OUT}）已经计过了</b>。
 * <p>
 * 重复计的后果：产品成本虚高 → 毛利虚低 → 客户以为这个产品不赚钱，
 * 可能把真正赚钱的产品线砍掉。<b>而且账面看不出任何异常，只是数字偏大。</b>
 * <p>
 * <b>正确口径</b>（写在 {@code ErpCostService} 类注释里）：
 * 直接材料 = 该工单所有有效 {@code MATERIAL_OUT} 行的 |qty| × unit_price。
 * 只认 {@code MATERIAL_OUT} 一个类型。
 * <p>
 * 这条验收项在 v1.0 Spec 的 §8 里列了，但一直<b>没有测试</b>（2026-11-04 逐条核对 AC 时发现）。
 */
class ErpCostScrapNotCountedTest {

    /** 材料领料金额（应该进成本） */
    private static final BigDecimal MATERIAL_AMOUNT = new BigDecimal("1000");

    /** 报废出库金额（不该进成本） */
    private static final BigDecimal SCRAP_AMOUNT = new BigDecimal("500");

    @Test
    @DisplayName("AC-07 报废（SCRAP_OUT）不计入材料成本，也不计入总成本")
    void scrapOutIsNotCountedAsMaterial() {
        Fx f = new Fx();
        Map<String, Object> cost = f.svc().orderCost("WO1");

        // 材料成本 = 领料金额，不是 领料 + 报废
        assertEquals(0, MATERIAL_AMOUNT.compareTo((BigDecimal) cost.get("materialCost")),
                "材料成本只能是领料的 1000，不能把报废的 500 也算进来（那样就是 1500）");
        assertEquals(0, MATERIAL_AMOUNT.compareTo((BigDecimal) cost.get("totalCost")),
                "未开工工单总成本 = 材料，同样不能含报废");
    }

    @Test
    @DisplayName("AC-07 成本只查 MATERIAL_OUT 这一种单据，从不查 SCRAP_OUT")
    void onlyMaterialOutIsQueried() {
        Fx f = new Fx();
        f.svc().orderCost("WO1");

        // 这一条比金额断言更硬：就算有人把 SCRAP_OUT 的金额算成 0（看不出错），
        // 只要它开始查 SCRAP_OUT 就说明口径变了，这里会红。
        verify(f.billRepo, times(1))
                .amountByOrderAndType(eq("WO1"), eq(ErpStockBill.TYPE_MATERIAL_OUT));
        verify(f.billRepo, never())
                .amountByOrderAndType(anyString(), eq(ErpStockBill.TYPE_SCRAP_OUT));
    }

    @Test
    @DisplayName("AC-07 前提校验：mock 的 SCRAP_OUT 金额确实非 0（否则上面的断言是假通过）")
    void fixtureIsMeaningful() {
        // 如果 SCRAP_AMOUNT 是 0，那"材料成本 = 1000"这条断言就测不出任何东西。
        // 假通过比没有测试更危险 —— 它给人虚假的安全感。
        Fx f = new Fx();
        assertNotNull(SCRAP_AMOUNT);
        assertEquals(0, SCRAP_AMOUNT.compareTo(BigDecimal.ZERO) > 0 ? 0 : 1,
                "夹具里的报废金额必须大于 0，否则测不出重复计数");
        assertEquals(0, SCRAP_AMOUNT.compareTo(
                        f.billRepo.amountByOrderAndType("WO1", ErpStockBill.TYPE_SCRAP_OUT)),
                "mock 里 SCRAP_OUT 确实返回非 0（模拟真有一张报废出库单）");
    }

    @Test
    @DisplayName("未开工工单：人工与制造费用算不出来，返回 null/0 而不是编一个数")
    void notStartedHasNoLabor() {
        Fx f = new Fx();
        Map<String, Object> cost = f.svc().orderCost("WO1");
        // 没开工就没有工时，人工必须是 null（"算不出来"），不是 0（"零成本"）
        assertNull(cost.get("laborCost"), "未开工时人工成本应为 null");
        assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) cost.get("overheadCost")));
        assertNull(cost.get("unitCost"), "完成量为 0 时单位成本应为 null");
    }

    /** 测试夹具：一个未开工的工单 + 有领料有报废的单据表 */
    private static final class Fx {
        final ErpWorkOrderRepository woRepo = mock(ErpWorkOrderRepository.class);
        final ErpStockBillRepository billRepo = mock(ErpStockBillRepository.class);
        final SettingStore settings = mock(SettingStore.class);

        Fx() {
            ErpWorkOrder o = new ErpWorkOrder();
            o.setOrderNo("WO1");
            o.setProductSkuId(1L);
            o.setPlanQty(new BigDecimal("10"));
            o.setDoneQty(BigDecimal.ZERO);
            o.setDefectQty(BigDecimal.ZERO);
            // actualStart 保持 null → 未开工，只走材料分支（本测试只关心材料口径）
            when(woRepo.findByOrderNo("WO1")).thenReturn(Optional.of(o));

            // 按单据类型区分金额：领料 1000、报废 500。
            // 如果实现把两者相加，materialCost 会是 1500 —— 断言立刻红。
            when(billRepo.amountByOrderAndType(anyString(), anyString())).thenAnswer(inv -> {
                String type = inv.getArgument(1);
                if (ErpStockBill.TYPE_MATERIAL_OUT.equals(type)) {
                    return MATERIAL_AMOUNT;
                }
                if (ErpStockBill.TYPE_SCRAP_OUT.equals(type)) {
                    return SCRAP_AMOUNT;
                }
                return BigDecimal.ZERO;
            });

            // 成本参数没配：num(null, 0) = 0，人工/制造费用都为 0
            when(settings.get(anyString())).thenReturn(null);
        }

        ErpCostService svc() {
            return new ErpCostService(woRepo, billRepo,
                    mock(ProdHourlyRepository.class), mock(ProdRollup.class),
                    mock(EnergyRepository.class), mock(EnergyRollup.class),
                    mock(ErpSkuRepository.class), mock(ErpBomService.class), settings);
        }
    }
}
