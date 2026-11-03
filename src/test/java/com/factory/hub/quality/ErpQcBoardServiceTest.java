package com.factory.hub.quality;

import com.factory.hub.report.ProdHourlyRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 质量看板与帕累托测试。
 *
 * <p><b>最要紧的一条：两个口径必须分开给，不能合成一个数。</b>
 * 合成之后，质检口径与设备口径的差就看不见了——
 * 而那个差正是双通道对账要核对的东西（Spec §2.4）。
 *
 * <p>第二条：<b>待处置按件算</b>。8 件处置 3 件后，待处置是 5 件，
 * 不是"1 条待处置"，也不是"8 件待处置"。
 */
class ErpQcBoardServiceTest {

    private static final long FROM = 1_763_000_000_000L;
    private static final long TO = FROM + 30L * 24 * 3600_000L;

    private ErpQcBoardService svc(ErpQcDefectRepository defectRepo,
                                   ErpQcDispositionRepository dispRepo,
                                   ErpQcInspectionRepository insRepo,
                                   ProdHourlyRepository prodRepo) {
        return new ErpQcBoardService(defectRepo, dispRepo, insRepo, prodRepo);
    }

    private static void stub(ErpQcDefectRepository d, ErpQcDispositionRepository p,
                             ErpQcInspectionRepository i, ProdHourlyRepository h) {
        when(d.sumQtyInWindow(anyLong(), anyLong())).thenReturn(new BigDecimal("20"));
        // 看板的质检口径分子走 findInWindow + 有效通道口径（按工单分组去重），
        // 不走 sumQtyInWindow——同一工单的设备通道与人工通道相加就是重复计数。
        when(d.findInWindow(anyLong(), anyLong())).thenReturn(List.of(
                row(ErpQcDefect.SRC_MANUAL, "WO1", "20")));
        when(d.pendingQtyTotal()).thenReturn(new BigDecimal("5"));
        when(d.pendingRecordCount()).thenReturn(3L);
        when(d.untriagedQty()).thenReturn(new BigDecimal("8"));
        when(d.countUntriaged()).thenReturn(2L);
        when(i.sumInspectedQty(anyLong(), anyLong())).thenReturn(new BigDecimal("1000"));
        when(h.sumOutputAndDefect(anyLong(), anyLong()))
                .thenReturn(List.<Object[]>of(new Object[]{new BigDecimal("2000"), new BigDecimal("40")}));
        // 处置汇总：SCRAP / CONCESSION
        when(p.costByAction(anyLong(), anyLong())).thenReturn(List.of(
                new Object[]{"SCRAP", new BigDecimal("6"), new BigDecimal("300"), BigDecimal.ZERO},
                new Object[]{"CONCESSION", new BigDecimal("4"), BigDecimal.ZERO, new BigDecimal("150")}));
    }

    /** 造一条不良记录（默认未处置、未定责） */
    private static ErpQcDefect row(String source, String wo, String qty) {
        ErpQcDefect d = new ErpQcDefect();
        d.setSource(source);
        d.setWorkOrderNo(wo);
        d.setQty(new BigDecimal(qty));
        d.setStatus(ErpQcDefect.ST_PENDING);
        d.setFoundAt(FROM);
        return d;
    }

    @Test
    @DisplayName("同一工单的设备通道与人工通道不相加：质检口径只算设备（QA 静态验收 P0-2）")
    void sameWorkOrderChannelsAreNotAddedUp() {
        ErpQcDefectRepository d = mock(ErpQcDefectRepository.class);
        ErpQcDispositionRepository p = mock(ErpQcDispositionRepository.class);
        ErpQcInspectionRepository i = mock(ErpQcInspectionRepository.class);
        ProdHourlyRepository h = mock(ProdHourlyRepository.class);
        stub(d, p, i, h);
        // 同一工单：设备采到 6 件、人工补录 4 件——是同一批货的两次记录
        when(d.findInWindow(anyLong(), anyLong())).thenReturn(List.of(
                row(ErpQcDefect.SRC_DEVICE, "WO1", "6"),
                row(ErpQcDefect.SRC_MANUAL, "WO1", "4")));
        Map<String, Object> out = svc(d, p, i, h).dashboard(FROM, TO);

        // 相加 = 10 那是重复计数；设备优先，正确答案是 6
        assertEquals(0, new BigDecimal("6").compareTo((BigDecimal) out.get("qcDefectQty")));
        // 设备口径仍然是原始的 40/2000，不受定责影响
        assertEquals(2.0d, (Double) out.get("deviceDefectRate"), 0.001);
    }

    @Test
    @DisplayName("两个口径分开给：质检不良率 2% / 设备不良率 2%，不能合成一个数")
    void twoCalibersAreSeparate() {
        ErpQcDefectRepository d = mock(ErpQcDefectRepository.class);
        ErpQcDispositionRepository p = mock(ErpQcDispositionRepository.class);
        ErpQcInspectionRepository i = mock(ErpQcInspectionRepository.class);
        ProdHourlyRepository h = mock(ProdHourlyRepository.class);
        stub(d, p, i, h);
        Map<String, Object> out = svc(d, p, i, h).dashboard(FROM, TO);

        // 质检口径：20 / 1000 = 2%
        assertEquals(2.0d, (Double) out.get("defectRate"), 0.001);
        // 设备口径：40 / 2000 = 2%
        assertEquals(2.0d, (Double) out.get("deviceDefectRate"), 0.001);
        // 合格率 = 100 - 质检不良率
        assertEquals(98.0d, (Double) out.get("passRate"), 0.001);
        // 两个口径的原值也给出，前端要能自己算差
        assertEquals(0, new BigDecimal("20").compareTo((BigDecimal) out.get("qcDefectQty")));
        assertEquals(0, new BigDecimal("2000").compareTo((BigDecimal) out.get("deviceOutputQty")));
    }

    @Test
    @DisplayName("待处置/未分类都按件与条分开给")
    void piecesAndRecordsSeparated() {
        ErpQcDefectRepository d = mock(ErpQcDefectRepository.class);
        ErpQcDispositionRepository p = mock(ErpQcDispositionRepository.class);
        ErpQcInspectionRepository i = mock(ErpQcInspectionRepository.class);
        ProdHourlyRepository h = mock(ProdHourlyRepository.class);
        stub(d, p, i, h);
        Map<String, Object> out = svc(d, p, i, h).dashboard(FROM, TO);

        assertEquals(0, new BigDecimal("5").compareTo((BigDecimal) out.get("pendingQty")));
        assertEquals(3L, out.get("pendingRecords"));
        assertEquals(0, new BigDecimal("8").compareTo((BigDecimal) out.get("untriagedQty")));
        assertEquals(2L, out.get("untriagedRecords"));
    }

    @Test
    @DisplayName("让步率与报废损失：让步率 = 让步件数 ÷ 不良件数")
    void concessionRateAndScrapLoss() {
        ErpQcDefectRepository d = mock(ErpQcDefectRepository.class);
        ErpQcDispositionRepository p = mock(ErpQcDispositionRepository.class);
        ErpQcInspectionRepository i = mock(ErpQcInspectionRepository.class);
        ProdHourlyRepository h = mock(ProdHourlyRepository.class);
        stub(d, p, i, h);
        Map<String, Object> out = svc(d, p, i, h).dashboard(FROM, TO);

        assertEquals(0, new BigDecimal("4").compareTo((BigDecimal) out.get("concessionQty")));
        assertEquals(0, new BigDecimal("150").compareTo((BigDecimal) out.get("concessionAmount")));
        assertEquals(20.0d, (Double) out.get("concessionRate"), 0.001);   // 4 / 20
        assertEquals(0, new BigDecimal("6").compareTo((BigDecimal) out.get("scrapQty")));
        assertEquals(0, new BigDecimal("300").compareTo((BigDecimal) out.get("scrapLoss")));
    }

    @Test
    @DisplayName("看板返回里没有面向用户的文案（口径说明由前端 i18n 写）")
    void noUserFacingText() {
        ErpQcDefectRepository d = mock(ErpQcDefectRepository.class);
        ErpQcDispositionRepository p = mock(ErpQcDispositionRepository.class);
        ErpQcInspectionRepository i = mock(ErpQcInspectionRepository.class);
        ProdHourlyRepository h = mock(ProdHourlyRepository.class);
        stub(d, p, i, h);
        Map<String, Object> out = svc(d, p, i, h).dashboard(FROM, TO);
        assertTrue(!out.containsKey("caliberNote"), "后端不返回口径说明文案");
        assertTrue(!out.containsKey("note"), "后端不返回面向用户的说明文字");
    }

    @Test
    @DisplayName("帕累托：占比与累计占比，未分类固定占一行")
    void paretoPctAndCumPct() {
        ErpQcDefectRepository d = mock(ErpQcDefectRepository.class);
        when(d.paretoByCode(anyLong(), anyLong())).thenReturn(List.of(
                new Object[]{1L, "XC01", "划伤", "MA", new BigDecimal("60")},
                new Object[]{null, null, null, null, new BigDecimal("40")}));
        ErpQcBoardService s = svc(d, mock(ErpQcDispositionRepository.class),
                mock(ErpQcInspectionRepository.class), mock(ProdHourlyRepository.class));
        List<Map<String, Object>> rows = s.pareto("code", FROM, TO);
        assertEquals(2, rows.size());
        assertEquals(60.0d, (Double) rows.get(0).get("pct"), 0.001);
        assertEquals(60.0d, (Double) rows.get(0).get("cumPct"), 0.001);
        assertEquals(40.0d, (Double) rows.get(1).get("pct"), 0.001);
        assertEquals(100.0d, (Double) rows.get(1).get("cumPct"), 0.001);
        // 未分类那行的 key 为 null —— 前端要把它渲染成"未分类"这一行，不能跳过
        assertEquals(null, rows.get(1).get("key"));
    }
}
