package com.factory.hub.quality;

import com.factory.hub.erp.ErpWarehouse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 不良品「头 + 处置明细」模型测试。
 *
 * <p>核心是两条守恒/派生规则，算错了看板会一直显示错误的待处置量：
 * <ol>
 *   <li><b>已处置 = sum(qty − fail_qty) where 未撤销</b>。少减 fail_qty，"6 件返工 1 件失败"
 *       会被算成处置完毕，那 1 件永远不回到待处置队列——不是数量算错，是整件货物消失；</li>
 *   <li><b>待处置 = 不良件数 − 已处置</b>，且看板必须按件算，不能按条算。
 *       8 件的单处置 3 件后按条统计会显示"待处置 8 件"，车间会一直以为积压很多。</li>
 * </ol>
 *
 * <p>team-lead 给的例子直接钉在这里：8 件 → 报废 2 + 返工 5（其中失败 1）+ 报废 1，
 * 守恒 {@code (2+5)+1 = 8}，且全程<b>只追加不改数量</b>。
 */
class ErpQcDisposalModelTest {

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    private static ErpQcDisposition disp(long defectId, int seq, String action,
                                        String qty, String failQty) {
        ErpQcDisposition d = new ErpQcDisposition();
        d.setDefectId(defectId);
        d.setSeqNo(seq);
        d.setAction(action);
        d.setQty(bd(qty));
        d.setFailQty(bd(failQty));
        d.setOperator("test");
        return d;
    }

    /** 已处置量 = sum(有效消费量)，与仓储 sumActiveQty 的 JPQL 完全同构 */
    private static BigDecimal disposedOf(List<ErpQcDisposition> rows) {
        BigDecimal sum = BigDecimal.ZERO;
        for (ErpQcDisposition d : rows) {
            if (!d.isVoided()) {
                sum = sum.add(d.effectiveQty());
            }
        }
        return sum;
    }

    private static ErpQcDefect defect(String qty) {
        ErpQcDefect d = new ErpQcDefect();
        d.setQty(bd(qty));
        d.setDisposedQty(BigDecimal.ZERO);
        d.setStatus(ErpQcDefect.ST_PENDING);
        return d;
    }

    /* ==================== 守恒 ==================== */

    @Test
    @DisplayName("8 件：报废 2 + 返工 5 + 报废 1 → sum(qty)=8，其中返工失败 1 件留下 1 件待处置")
    void mixedDispositionConserves() {
        ErpQcDefect head = defect("8");
        List<ErpQcDisposition> rows = new ArrayList<>();
        rows.add(disp(1L, 1, ErpQcDisposition.ACT_SCRAP, "2", "0"));
        rows.add(disp(1L, 2, ErpQcDisposition.ACT_REWORK, "5", "1"));
        rows.add(disp(1L, 3, ErpQcDisposition.ACT_SCRAP, "1", "0"));

        // ① 账面处置件数守恒到批量：2 + 5 + 1 = 8
        BigDecimal sumQty = rows.stream().map(ErpQcDisposition::getQty)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, sumQty.compareTo(bd("8")), "sum(qty) 必须等于不良总数 8");

        // ② 但真正被"消费掉"的是 qty − failQty = 2 + 4 + 1 = 7，
        //    返工失败的 1 件退回待处置 —— 这正是 failQty 存在的意义。
        //    如果有人把 disposed_qty 写成 sum(qty)，这里会算出 8、
        //    把单子标成已处置，而实际还有 1 件躺在车间没人管。
        BigDecimal effective = disposedOf(rows);
        assertEquals(0, effective.compareTo(bd("7")), "有效处置量必须是 7（2 + (5-1) + 1）");

        head.setDisposedQty(effective);
        head.setStatus(ErpQcDefect.ST_PENDING);
        assertEquals(0, head.pendingQty().compareTo(bd("1")), "还有 1 件待处置");

        // ③ 那 1 件再报废一次，才真正结案
        rows.add(disp(1L, 4, ErpQcDisposition.ACT_SCRAP, "1", "0"));
        head.setDisposedQty(disposedOf(rows));
        assertEquals(0, head.pendingQty().signum(), "补处置后待处置归零");
        head.setStatus(ErpQcDefect.ST_DISPOSED);
        assertFalse(head.isDisposable());
    }

    @Test
    @DisplayName("没有返工失败时：已处置 = sum(qty)，8 件处置完直接结案")
    void simpleSplitConservesExactly() {
        ErpQcDefect head = defect("8");
        List<ErpQcDisposition> rows = new ArrayList<>();
        rows.add(disp(9L, 1, ErpQcDisposition.ACT_SCRAP, "3", "0"));
        rows.add(disp(9L, 2, ErpQcDisposition.ACT_REWORK, "5", "0"));
        head.setDisposedQty(disposedOf(rows));
        assertEquals(0, head.pendingQty().signum(), "3 + 5 = 8，一次处置完");
        head.setStatus(ErpQcDefect.ST_DISPOSED);
        assertFalse(head.isDisposable());
    }

    @Test
    @DisplayName("返工失败会让状态往回跳（DISPOSED → 待处置 1），这是正确行为不是 bug")
    void reworkFailPushesStatusBack() {
        ErpQcDefect head = defect("6");
        List<ErpQcDisposition> rows = new ArrayList<>();
        rows.add(disp(2L, 1, ErpQcDisposition.ACT_REWORK, "6", "0"));

        head.setDisposedQty(disposedOf(rows));
        assertEquals(0, head.pendingQty().signum(), "全都返工了，暂时没有待处置");

        // 复检 1 件不合格：不删行、不改数量，只回写 failQty
        rows.get(0).setFailQty(bd("1"));
        rows.get(0).setFailReason("同轴度仍超差");
        head.setDisposedQty(disposedOf(rows));
        assertEquals(0, head.pendingQty().compareTo(bd("1")),
                "失败 1 件退回待处置：待处置从 0 变回 1");
        assertTrue(head.isDisposable(), "那 1 件要能再次被处置");
        assertTrue(rows.get(0).hasReworkFail());
    }

    /* ==================== 作废 = 打标记，不减数量 ==================== */

    @Test
    @DisplayName("撤销一条处置：打 voidedAt 后不再计入，但 qty 一个字都不改")
    void voidingKeepsQtyIntact() {
        List<ErpQcDisposition> rows = new ArrayList<>();
        rows.add(disp(3L, 1, ErpQcDisposition.ACT_SCRAP, "3", "0"));
        rows.add(disp(3L, 2, ErpQcDisposition.ACT_SCRAP, "5", "0"));
        assertEquals(0, disposedOf(rows).compareTo(bd("8")));

        ErpQcDisposition v = rows.get(0);
        v.setVoidedAt(System.currentTimeMillis());
        v.setVoidedBy("manager");
        v.setVoidReason("报废单开错仓");
        assertEquals(0, disposedOf(rows).compareTo(bd("5")), "撤销的 3 件不再算已处置");
        assertEquals(0, v.getQty().compareTo(bd("3")), "原数量必须原样保留，审计要看");
        assertEquals("报废单开错仓", v.getVoidReason());
    }

    /* ==================== 待处置按件算 ==================== */

    @Test
    @DisplayName("8 件处置了 3 件：待处置是 5 件（按件算），不是「1 条待处置」")
    void pendingIsCountedByPieces() {
        ErpQcDefect head = defect("8");
        head.setDisposedQty(bd("3"));
        head.setStatus(ErpQcDefect.ST_PENDING);
        assertEquals(0, head.pendingQty().compareTo(bd("5")));
        assertTrue(head.isDisposable(), "部分处置后仍然可处置");
        // 状态不因为"处置了一部分"而变成第四种，靠 disposedQty 表达进度
        assertEquals(ErpQcDefect.ST_PENDING, head.getStatus());
    }

    @Test
    @DisplayName("处置量被写成超过不良数时，待处置按 0 兜底，不返回负数")
    void pendingNeverNegative() {
        ErpQcDefect head = defect("3");
        head.setDisposedQty(bd("5"));
        assertEquals(0, head.pendingQty().signum(), "脏数据也不能让待处置变成 -2");
    }

    @Test
    @DisplayName("作废/已取消的不良单不能再处置")
    void cancelledOrVoidedNotDisposable() {
        ErpQcDefect c = defect("2");
        c.setStatus(ErpQcDefect.ST_CANCELLED);
        assertFalse(c.isDisposable());
        ErpQcDefect v = defect("2");
        v.setVoidedAt(System.currentTimeMillis());
        assertFalse(v.isDisposable());
    }

    /* ==================== 仓类型 ==================== */

    @Test
    @DisplayName("禁发仓判定：kind=BLOCKED 才算，空值/未知值按正常仓（宁可不误伤）")
    void warehouseKind() {
        ErpWarehouse blocked = new ErpWarehouse();
        blocked.setKind(ErpWarehouse.KIND_BLOCKED);
        assertTrue(blocked.isBlocked());

        ErpWarehouse standard = new ErpWarehouse();
        standard.setKind(ErpWarehouse.KIND_STANDARD);
        assertFalse(standard.isBlocked());

        // 升级上来的老行 kind 为 null：必须能跑，且不能把正常仓误判成禁发仓
        // （误判成禁发 = 全厂发不出货且没人知道为什么；安全兜底在出库判定那一侧）
        ErpWarehouse legacy = new ErpWarehouse();
        legacy.setKind(null);
        assertFalse(legacy.isBlocked());
    }
}
