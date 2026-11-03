package com.factory.hub.quality;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 「有哪些检验水平可用」的测试。
 *
 * <p><b>为什么这个接口重要</b>：抽样界面的两个下拉如果列全 GB/T 2828.1 的
 * 8 个水平 × 3 个严格度，而方案表里只导入了一部分，用户选到没数据的组合
 * 必然撞上"没配方案"——<b>界面在制造必然的失败</b>。
 * 所以下拉要按"表里实际有什么"来列，主数据补齐后自动跟着多出来。
 *
 * <p>这条测试守的是：<b>返回的组合必须与表里真实存在的一致</b>，
 * 不能凭空多报或漏报——多报一个组合，用户选了照样失败。
 */
class ErpAqlAvailableCombosTest {

    private ErpAqlService serviceWith(Object[]... rows) {
        ErpQcAqlPlanRepository repo = mock(ErpQcAqlPlanRepository.class);
        when(repo.listAvailableCombos()).thenReturn(new ArrayList<>(List.of(rows)));
        return new ErpAqlService(repo, mock(com.factory.hub.audit.AuditService.class));
    }

    @Test
    @DisplayName("返回表里真实存在的组合，并带上方案数")
    void returnsCombosWithCounts() {
        ErpAqlService svc = serviceWith(
                new Object[]{"GENERAL_II", "NORMAL", 66L},
                new Object[]{"GENERAL_I", "NORMAL", 66L});

        List<Map<String, Object>> combos = svc.availableCombos();

        assertEquals(2, combos.size());
        assertEquals("GENERAL_II", combos.get(0).get("level"));
        assertEquals("NORMAL", combos.get(0).get("strictLevel"));
        assertEquals(66, combos.get(0).get("planCount"));
        assertEquals("GENERAL_I", combos.get(1).get("level"));
    }

    @Test
    @DisplayName("表是空的 → 返回空列表（前端据此退回列全枚举，不把界面卡死）")
    void emptyTableGivesEmptyList() {
        List<Map<String, Object>> combos = serviceWith().availableCombos();
        assertTrue(combos.isEmpty(), "表空就是空列表，不要塞一个假的默认组合");
    }

    @Test
    @DisplayName("播种器的真实数据：7 个检验水平 × 3 个严格度 = 21 个组合")
    void seededTableHasAllCombos() {
        Set<String> combos = new HashSet<>();
        for (ErpQcAqlPlan p : ErpAqlSeeder.buildPlans()) {
            combos.add(p.getLevel() + "/" + p.getStrictLevel());
        }
        // 国标表 1 的 7 个检验水平 × 表 2-A/2-B/2-C 的 3 个严格度
        assertEquals(21, combos.size(), "21 个组合都要有：" + combos);
        for (String level : ErpAqlLevelMap.LEVELS) {
            for (String strict : ErpAqlSeeder.STRICT_LEVELS) {
                assertTrue(combos.contains(level + "/" + strict),
                        "缺组合 " + level + "/" + strict);
            }
        }
    }
}
