package com.factory.hub.quality;

import com.factory.hub.audit.AuditService;
import com.factory.hub.erp.ErpQualityReturnGate;
import com.factory.hub.erp.ErpSku;
import com.factory.hub.erp.ErpSkuRepository;
import com.factory.hub.erp.ErpWarehouse;
import com.factory.hub.erp.ErpWarehouseRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 质量退货分流的测试（QC-SPEC §3.5 / AC-13 / AC-14）。
 *
 * <p><b>这个类守的是最坏的那种 bug</b>：质量原因的退货按普通良品入库，
 * 重新发给下一个客户——<b>质量问题被彻底抹掉</b>，看板上也看不出来。
 * 而界面还显示着"退货原因：质量问题"：<b>界面说对了，账做错了</b>。
 *
 * <p>不报错、不影响任何测试，只能靠人眼发现。所以必须在这里钉死。
 */
class ErpQcReturnServiceTest {

    private static final Long BLOCKED_WH = 9L;
    private static final Long NORMAL_WH = 3L;

    private static final class Fx {
        ErpQcDefectRepository defectRepo = mock(ErpQcDefectRepository.class);
        ErpWarehouseRepository whRepo = mock(ErpWarehouseRepository.class);
        ErpSkuRepository skuRepo = mock(ErpSkuRepository.class);
        AuditService audit = mock(AuditService.class);
        ErpQcReturnService svc;
        final List<ErpQcDefect> saved = new ArrayList<>();
    }

    private Fx fx(boolean withBlockedWarehouse) {
        Fx f = new Fx();
        ErpWarehouse normal = new ErpWarehouse();
        normal.setId(NORMAL_WH);
        normal.setName("成品仓");
        normal.setKind("STANDARD");
        normal.setEnabled(true);

        List<ErpWarehouse> whs = new ArrayList<>();
        whs.add(normal);
        if (withBlockedWarehouse) {
            ErpWarehouse blocked = new ErpWarehouse();
            blocked.setId(BLOCKED_WH);
            blocked.setName("待处理仓");
            blocked.setKind("BLOCKED");
            blocked.setEnabled(true);
            whs.add(blocked);
        }
        when(f.whRepo.findAll()).thenReturn(whs);

        ErpSku sku = new ErpSku();
        sku.setCode("SKU-77");
        sku.setName("铸铁件");
        when(f.skuRepo.findById(any())).thenReturn(Optional.of(sku));

        when(f.defectRepo.maxNoByPrefix(any())).thenReturn("BLP000099");
        when(f.defectRepo.save(any())).thenAnswer(inv -> {
            ErpQcDefect d = inv.getArgument(0);
            f.saved.add(d);
            return d;
        });

        f.svc = new ErpQcReturnService(f.defectRepo, f.whRepo, f.skuRepo, f.audit);
        return f;
    }

    private static List<ErpQualityReturnGate.QualityReturnLine> lines() {
        return List.of(new ErpQualityReturnGate.QualityReturnLine(77L, new BigDecimal("3")));
    }

    @Test
    @DisplayName("质量原因退货 → 强制入禁发仓，不是前端选的那个仓")
    void qualityReasonGoesToBlockedWarehouse() {
        Fx f = fx(true);
        Long wh = f.svc.resolveReturnWarehouse("QUALITY", NORMAL_WH);
        assertEquals(BLOCKED_WH, wh,
                "问题货进普通仓 = 重新发给下一个客户，质量问题被彻底抹掉");
    }

    @Test
    @DisplayName("其它原因退货 → 正常回前端选的仓")
    void otherReasonGoesToRequestedWarehouse() {
        Fx f = fx(true);
        Long wh = f.svc.resolveReturnWarehouse("OTHER", NORMAL_WH);
        assertEquals(NORMAL_WH, wh);
    }

    @Test
    @DisplayName("原因缺失 → 拒绝，不落默认值")
    void missingReasonIsRejected() {
        Fx f = fx(true);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> f.svc.resolveReturnWarehouse(null, NORMAL_WH));
        assertTrue(e.getMessage().contains("退货必须选原因"), e.getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> f.svc.resolveReturnWarehouse("   ", NORMAL_WH));
    }

    @Test
    @DisplayName("原因值非法 → 拒绝，不当成 OTHER 处理")
    void illegalReasonIsRejected() {
        Fx f = fx(true);
        assertThrows(IllegalArgumentException.class,
                () -> f.svc.resolveReturnWarehouse("WHATEVER", NORMAL_WH));
    }

    @Test
    @DisplayName("质量原因但没有禁发仓 → 拒绝入库（fail-safe）")
    void qualityReturnWithoutBlockedWarehouseIsRejected() {
        Fx f = fx(false);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> f.svc.resolveReturnWarehouse("QUALITY", NORMAL_WH));
        assertTrue(e.getMessage().contains("禁发仓"), e.getMessage());
    }

    @Test
    @DisplayName("质量退货入库后 → 生成待处置的不良记录（in_stock=true，带入库单号）")
    void qualityReturnCreatesDefectRecord() {
        Fx f = fx(true);
        f.svc.registerReturnDefects("SO001", 5L, BLOCKED_WH, "SR20261103-001",
                lines(), "外包装破损", "zhang");

        assertEquals(1, f.saved.size(), "质量退货必须生成一条不良记录");
        ErpQcDefect d = f.saved.get(0);
        assertEquals(ErpQcDefect.SRC_RETURN, d.getSource());
        assertEquals(ErpQcDefect.ST_PENDING, d.getStatus(), "进待处置队列");
        assertTrue(d.isInStock(), "货已入库，处置报废时要出库扣库存");
        assertEquals(BLOCKED_WH, d.getWarehouseId());
        assertEquals("SR20261103-001", d.getSrcBillNo(), "要记入库单号，否则报废时不知道扣哪个仓");
        assertEquals(0, new BigDecimal("3").compareTo(d.getQty()));
        assertEquals("SKU-77", d.getSkuCode());
    }

    @Test
    @DisplayName("不良记录单号不重复：同一张退货单的同一 SKU 幂等键唯一")
    void returnDefectHasIdempotentKey() {
        Fx f = fx(true);
        f.svc.registerReturnDefects("SO001", 5L, BLOCKED_WH, "SR001", lines(), null, "zhang");
        assertEquals("RET:SO001:77", f.saved.get(0).getSourceKey(),
                "退货单可能被重复提交（网络重试），没有唯一键会建两条 → 数量翻倍");
        assertTrue(f.saved.get(0).getDefectNo().startsWith("BLP"), f.saved.get(0).getDefectNo());
    }

    @Test
    @DisplayName("非质量原因不生成不良记录")
    void nonQualityReturnCreatesNoDefect() {
        Fx f = fx(true);
        // 其它原因不走 registerReturnDefects（ERP 侧只在质量原因时调），
        // 这里验证服务本身对空明细是安全的（不建记录、不报错）。
        f.svc.registerReturnDefects("SO001", 5L, NORMAL_WH, "SR002",
                new ArrayList<>(), null, "zhang");
        assertEquals(0, f.saved.size());
        verify(f.defectRepo, never()).save(any());
    }
}
