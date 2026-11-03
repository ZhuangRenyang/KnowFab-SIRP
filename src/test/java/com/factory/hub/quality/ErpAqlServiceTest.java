package com.factory.hub.quality;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AQL 查表测试（AC-05 / QC-SPEC §6）。
 *
 * <p><b>最要紧的是三个锚点必须精确等于国标</b>：接收数算错一格，判合格的货可能本该拒收。
 * 锚点值直接对人 GB/T 2828.1-2012 表 2-A 抄，测试里不写任何"按公式推"的东西——
 * 用公式反推去验证公式，等于没验。
 *
 * <p>数据来源是 {@link ErpAqlSeeder#buildPlans()}，也就是真正要写进库的那份表，
 * 不是测试里另抄一份期望值（那样抄错了也是绿灯）。
 *
 * <p>第二条同样重要：<b>查不到必须抛异常</b>。返回一个默认 Ac 会让"方案漏配"
 * 变成"系统算出了一个看起来正常的数"，那是最难发现的一类错。
 */
class ErpAqlServiceTest {

    /** 用播种器那份真实数据造一个只认 (水平, 严格度, 批量区间, AQL) 的假仓储 */
    private ErpAqlService serviceWithStandardTable() {
        List<ErpQcAqlPlan> table = ErpAqlSeeder.buildPlans();
        ErpQcAqlPlanRepository repo = mock(ErpQcAqlPlanRepository.class);
        when(repo.findPlan(anyString(), anyString(), anyInt(), any())).thenAnswer(inv -> {
            String level = inv.getArgument(0);
            String strict = inv.getArgument(1);
            int lot = inv.getArgument(2);
            BigDecimal aql = inv.getArgument(3);
            return table.stream()
                    .filter(p -> p.getLevel().equals(level) && p.getStrictLevel().equals(strict))
                    .filter(p -> lot >= p.getLotFrom() && lot <= p.getLotTo())
                    .filter(p -> p.getAql().compareTo(aql) == 0)
                    .findFirst();
        });
        return new ErpAqlService(repo, mock(com.factory.hub.audit.AuditService.class));
    }

    /* ==================== 三个锚点（国标表 2-A 人工核对） ==================== */

    @Test
    @DisplayName("锚点①：批量 501~1200、水平 II、AQL 1.0 → 字码 J、n=80、Ac=2、Re=3")
    void anchorJ100() {
        ErpAqlService.Resolved r = serviceWithStandardTable()
                .resolve("GENERAL_II", "NORMAL", new BigDecimal("800"), new BigDecimal("1.0"));
        assertEquals("J", r.codeLetter());
        assertEquals(80, r.sampleSize());
        assertEquals(2, r.ac());
        assertEquals(3, r.re());
        assertFalse(r.fullInspection());
    }

    @Test
    @DisplayName("锚点②：批量 501~1200、水平 II、AQL 2.5 → 字码 J、n=80、Ac=5、Re=6")
    void anchorJ250() {
        ErpAqlService.Resolved r = serviceWithStandardTable()
                .resolve("II", "NORMAL", new BigDecimal("501"), new BigDecimal("2.5"));
        assertEquals("J", r.codeLetter());
        assertEquals(80, r.sampleSize());
        assertEquals(5, r.ac());
        assertEquals(6, r.re());
    }

    @Test
    @DisplayName("锚点③：批量 26~50、水平 II、AQL 0.65 → 字码 D、n=8、Ac=0、Re=1")
    void anchorD065() {
        ErpAqlService.Resolved r = serviceWithStandardTable()
                .resolve("GENERAL_II", null, new BigDecimal("50"), new BigDecimal("0.65"));
        assertEquals("D", r.codeLetter());
        assertEquals(8, r.sampleSize());
        assertEquals(0, r.ac());
        assertEquals(1, r.re());
        assertFalse(r.fullInspection());
    }

    /* ==================== 播种表本身 ==================== */

    @Test
    @DisplayName("播种 66 行：6 个 AQL × 11 个批量档，且没有重复的格子")
    void seedTableShape() {
        List<ErpQcAqlPlan> table = ErpAqlSeeder.buildPlans();
        assertEquals(66, table.size());
        assertEquals(ErpAqlSeeder.plannedRowCount(), table.size());
        long distinct = table.stream()
                .map(p -> p.getLevel() + "|" + p.getStrictLevel() + "|" + p.getLotFrom() + "|" + p.getAql())
                .distinct().count();
        assertEquals(66, distinct, "同一格不允许两行，否则查表会随机命中其中一行");
    }

    @Test
    @DisplayName("每一行都满足 Re = Ac + 1，且样本量与字码对应国标表 1")
    void seedTableInvariants() {
        for (ErpQcAqlPlan p : ErpAqlSeeder.buildPlans()) {
            assertEquals(p.getAc() + 1, p.getRe(), "Re 必须等于 Ac+1：" + p.getCodeLetter());
            assertEquals(ErpQcAqlPlan.LEVEL_II, p.getLevel());
            assertEquals(ErpQcAqlPlan.STRICT_NORMAL, p.getStrictLevel());
        }
        // 11 个批量档的样本量，国标表 1（一般检验水平 II）
        int[] expectedSample = {2, 3, 5, 8, 13, 20, 32, 50, 80, 125, 200};
        String[] expectedLetter = {"A", "B", "C", "D", "E", "F", "G", "H", "J", "K", "L"};
        for (int i = 0; i < expectedSample.length; i++) {
            assertEquals(expectedSample[i], ErpAqlSeeder.sampleOfBand(i));
            assertEquals(expectedLetter[i], ErpAqlSeeder.letterOfBand(i));
        }
    }

    @Test
    @DisplayName("AQL 2.5 整列（11 格）与人 GB/T 2828.1 表 2-A 完全一致")
    void aql25Column() {
        int col = ErpAqlSeeder.aqlIndexOf("2.5");
        assertTrue(col >= 0);
        int[] expectedAc = {0, 0, 0, 0, 1, 1, 2, 3, 5, 7, 10};
        for (int i = 0; i < expectedAc.length; i++) {
            assertEquals(expectedAc[i], ErpAqlSeeder.acOf(i, col),
                    "AQL 2.5 第 " + i + " 档（字码 " + ErpAqlSeeder.letterOfBand(i) + "）接收数不对");
        }
    }

    /* ==================== 退化情形 ==================== */

    @Test
    @DisplayName("批量小于最小档（1 件）→ 全检：n = 批量、Ac=0、Re=1")
    void tinyLotIsFullInspection() {
        ErpAqlService.Resolved r = serviceWithStandardTable()
                .resolve("II", "NORMAL", BigDecimal.ONE, new BigDecimal("1.0"));
        assertTrue(r.fullInspection());
        assertEquals(1, r.sampleSize());
        assertEquals(0, r.ac());
        assertEquals(1, r.re());
        assertNotNull(r.note(), "全检必须给一句人话说明原因");
    }

    @Test
    @DisplayName("样本量 >= 批量 → 全检，Ac/Re 沿用方案值不变")
    void sampleNotSmallerThanLot() {
        // 批量 40 落在 26~50 档（D，n=8）→ 8 < 40，不是全检
        ErpAqlService.Resolved normal = serviceWithStandardTable()
                .resolve("II", "NORMAL", new BigDecimal("40"), new BigDecimal("2.5"));
        assertFalse(normal.fullInspection());
        assertEquals(8, normal.sampleSize());
        // 批量 2 落在 2~8 档（A，n=2）→ 2 >= 2，转全检，Ac 不变（A 档 AQL 2.5 → 0/1）
        ErpAqlService.Resolved full = serviceWithStandardTable()
                .resolve("II", "NORMAL", new BigDecimal("2"), new BigDecimal("2.5"));
        assertTrue(full.fullInspection());
        assertEquals(2, full.sampleSize());
        assertEquals(0, full.ac());
        assertEquals(1, full.re());
        assertNotNull(full.note(), "转全检要给一句人话说明原因");
    }

    /* ==================== 查不到 = 抛异常（不给默认值） ==================== */

    @Test
    @DisplayName("方案缺失必须抛异常，且消息里说清缺哪一行、去哪里补")
    void missingPlanThrows() {
        ErpQcAqlPlanRepository emptyRepo = mock(ErpQcAqlPlanRepository.class);
        when(emptyRepo.findPlan(anyString(), anyString(), anyInt(), any())).thenReturn(Optional.empty());
        ErpAqlService svc = new ErpAqlService(emptyRepo, mock(com.factory.hub.audit.AuditService.class));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> svc.resolve("GENERAL_II", "NORMAL", new BigDecimal("800"), new BigDecimal("1.5")));
        String msg = ex.getMessage();
        assertTrue(msg.contains("未维护抽样方案"), msg);
        assertTrue(msg.contains("800"), "要带批量，现场才知道缺哪一行：" + msg);
        assertTrue(msg.contains("1.5"), "要带 AQL：" + msg);
        assertTrue(msg.contains("质量字典"), "要告诉人去哪里补：" + msg);
    }

    @Test
    @DisplayName("加严/放宽没有播种时同样抛异常，不会退化成正常检验")
    void tightenedNotSeededThrows() {
        // 播种器只写了 NORMAL，加严检验必须明确报错 ——
        // 悄悄按正常检验算，等于把"该加严"的一批货按正常标准放过去了
        assertThrows(IllegalArgumentException.class, () -> serviceWithStandardTable()
                .resolve("II", "TIGHTENED", new BigDecimal("800"), new BigDecimal("1.0")));
    }

    /* ==================== CR 零容忍 ==================== */

    @Test
    @DisplayName("CR 致命缺陷：任意 n 一律 Ac=-1 / Re=1，且与 Ac=0 明确区分")
    void criticalIsZeroTolerance() {
        ErpAqlService svc = serviceWithStandardTable();
        for (String lot : new String[]{"50", "800", "5000"}) {
            ErpAqlService.Resolved r = svc.resolveForSeverity("II", "NORMAL",
                    new BigDecimal(lot), new BigDecimal("1.0"), ErpQcDefectCode.SEV_CR);
            assertEquals(ErpAqlService.AC_ZERO_TOLERANCE, r.ac());
            assertEquals(ErpAqlService.RE_ZERO_TOLERANCE, r.re());
            assertEquals(-1, r.ac(), "内部统一用 -1 表示零容忍，不能用 0（0 是未配置）");
            assertTrue(r.note().contains("零容忍"));
        }
        // 非 CR 不改变方案
        ErpAqlService.Resolved ma = svc.resolveForSeverity("II", "NORMAL",
                new BigDecimal("800"), new BigDecimal("1.0"), ErpQcDefectCode.SEV_MA);
        assertEquals(2, ma.ac());
        assertEquals(3, ma.re());
    }

    /* ==================== verified 标记（哪些格是推出来的） ==================== */

    @Test
    @DisplayName("verified 标记：1.0/1.5/2.5 三列 + 4.0 列（除 C 档）为 true，其余 23 格为 false")
    void verifiedFlags() {
        List<ErpQcAqlPlan> table = ErpAqlSeeder.buildPlans();
        int verified = 0;
        int unverified = 0;
        for (int band = 0; band < 11; band++) {
            for (int col = 0; col < 6; col++) {
                boolean expect = ErpAqlSeeder.verifiedOf(band, col);
                boolean actual = table.get(band * 6 + col).isVerified();
                assertEquals(expect, actual,
                        "格（档 " + ErpAqlSeeder.letterOfBand(band)
                                + "，AQL " + ErpAqlSeeder.aqlTextOf(col) + "）的 verified 标记不对");
                if (actual) {
                    verified++;
                } else {
                    unverified++;
                }
            }
        }
        // 43 格逐格核对过公开资料；23 格是推算/有争议的
        assertEquals(43, verified, "已核对格数应为 43");
        assertEquals(23, unverified, "待核对格数应为 23（0.65 整列 + 6.5 整列 + C 档 @4.0）");
    }

    @Test
    @DisplayName("AQL 0.65 与 6.5 两列整列都是未核对（由格子规律推出，不能当国标用）")
    void derivedColumnsAreUnverified() {
        int col065 = ErpAqlSeeder.aqlIndexOf("0.65");
        int col65 = ErpAqlSeeder.aqlIndexOf("6.5");
        for (int band = 0; band < 11; band++) {
            assertFalse(ErpAqlSeeder.verifiedOf(band, col065), "0.65 列第 " + band + " 档应为未核对");
            assertFalse(ErpAqlSeeder.verifiedOf(band, col65), "6.5 列第 " + band + " 档应为未核对");
        }
        // C 档 @ 4.0：公开资料有 1/2 与 0/1 两种说法，一律标未核对让客户核
        assertFalse(ErpAqlSeeder.verifiedOf(2, ErpAqlSeeder.aqlIndexOf("4.0")));
        // 相邻的 D 档 @ 4.0 是核对过的，别一刀切
        assertTrue(ErpAqlSeeder.verifiedOf(3, ErpAqlSeeder.aqlIndexOf("4.0")));
    }

    @Test
    @DisplayName("确认核对后 verified 置 true 并写审计")
    void verifyPlanWritesAudit() {
        ErpQcAqlPlan row = ErpAqlSeeder.buildPlans().stream()
                .filter(p -> !p.isVerified()).findFirst().orElseThrow();
        row.setId(99L);
        ErpQcAqlPlanRepository repo = mock(ErpQcAqlPlanRepository.class);
        when(repo.findById(99L)).thenReturn(java.util.Optional.of(row));
        when(repo.save(any(ErpQcAqlPlan.class))).thenAnswer(inv -> inv.getArgument(0));
        com.factory.hub.audit.AuditService audit = mock(com.factory.hub.audit.AuditService.class);
        ErpAqlService svc = new ErpAqlService(repo, audit);

        ErpQcAqlPlan out = svc.verifyPlan(99L);
        assertTrue(out.isVerified(), "确认后必须变成已核对");
        assertNotNull(out.getVerifiedAt(), "要记核对时间");
        assertNotNull(out.getVerifiedBy(), "要记核对人（审计要能追到人）");
        org.mockito.Mockito.verify(audit).ok(
                org.mockito.ArgumentMatchers.eq(com.factory.hub.audit.AuditLog.CAT_QUALITY),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.contains("GB/T 2828.1"));
    }

    @Test
    @DisplayName("缺方案的异常类型是 NoPlanException，接口层据此给独立错误码")
    void missingPlanHasDedicatedType() {
        ErpQcAqlPlanRepository emptyRepo = mock(ErpQcAqlPlanRepository.class);
        when(emptyRepo.findPlan(anyString(), anyString(), anyInt(), any())).thenReturn(Optional.empty());
        ErpAqlService svc = new ErpAqlService(emptyRepo, mock(com.factory.hub.audit.AuditService.class));
        assertThrows(ErpAqlService.NoPlanException.class,
                () -> svc.resolve("II", "NORMAL", new BigDecimal("800"), new BigDecimal("1.5")));
        // 仍然是 IllegalArgumentException 的子类，老调用方不用改
        assertTrue(IllegalArgumentException.class.isAssignableFrom(ErpAqlService.NoPlanException.class));
    }

    /* ==================== 参数校验 ==================== */

    @Test
    @DisplayName("批量/AQL 非法直接拒绝，不会算出一个可疑的数")
    void invalidInputs() {
        ErpAqlService svc = serviceWithStandardTable();
        assertThrows(IllegalArgumentException.class,
                () -> svc.resolve("II", "NORMAL", BigDecimal.ZERO, new BigDecimal("1.0")));
        assertThrows(IllegalArgumentException.class,
                () -> svc.resolve("II", "NORMAL", new BigDecimal("100"), null));
        assertThrows(IllegalArgumentException.class,
                () -> svc.resolve("IV", "NORMAL", new BigDecimal("100"), new BigDecimal("1.0")));
    }

    @Test
    @DisplayName("检验水平 / 严格度的现场写法都能认（II / 2 / GENERAL_II）")
    void levelNormalization() {
        assertEquals(ErpQcAqlPlan.LEVEL_II, ErpAqlService.normalizeLevel("2"));
        assertEquals(ErpQcAqlPlan.LEVEL_II, ErpAqlService.normalizeLevel("ii"));
        assertEquals(ErpQcAqlPlan.LEVEL_II, ErpAqlService.normalizeLevel(null));
        assertEquals(ErpQcAqlPlan.LEVEL_I, ErpAqlService.normalizeLevel("GENERAL-I"));
        assertEquals(ErpQcAqlPlan.STRICT_NORMAL, ErpAqlService.normalizeStrict(""));
    }

    @Test
    @DisplayName("AQL 大于 10 直接拒绝：口径会从按件变成按项，两种口径能算出相反结论")
    void aqlAboveTenRejected() {
        ErpAqlService svc = serviceWithStandardTable();
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> svc.resolve("II", "NORMAL", new BigDecimal("800"), new BigDecimal("15")));
        assertTrue(ex.getMessage().contains("不能大于 10"), ex.getMessage());
        assertTrue(ex.getMessage().contains("每百单位不合格数"),
                "要说清为什么拒绝，否则客户以为是我们少输出了几档：" + ex.getMessage());
    }

    @Test
    @DisplayName("方案维护同样拒绝 AQL > 10；AQL = 10 是允许的边界")
    void planMaintenanceRejectsAqlAboveTen() {
        ErpQcAqlPlanRepository repo = mock(ErpQcAqlPlanRepository.class);
        when(repo.findByLevelAndStrictLevelOrderByAqlAscLotFromAsc(anyString(), anyString()))
                .thenReturn(java.util.List.of());
        when(repo.save(any(ErpQcAqlPlan.class))).thenAnswer(inv -> inv.getArgument(0));
        ErpAqlService svc = new ErpAqlService(repo, mock(com.factory.hub.audit.AuditService.class));

        assertThrows(IllegalArgumentException.class, () -> svc.savePlan(planWithAql("15")));
        ErpQcAqlPlan saved = svc.savePlan(planWithAql("10"));
        assertEquals(0, new BigDecimal("10").compareTo(saved.getAql()));
        assertEquals(3, saved.getRe(), "Re 由服务强制 = Ac + 1，不让用户手填");
    }

    private static ErpQcAqlPlan planWithAql(String aql) {
        ErpQcAqlPlan p = new ErpQcAqlPlan();
        p.setLevel(ErpQcAqlPlan.LEVEL_II);
        p.setStrictLevel(ErpQcAqlPlan.STRICT_NORMAL);
        p.setLotFrom(501);
        p.setLotTo(1200);
        p.setCodeLetter("J");
        p.setSampleSize(80);
        p.setAc(2);
        p.setAql(new BigDecimal(aql));
        return p;
    }
}
