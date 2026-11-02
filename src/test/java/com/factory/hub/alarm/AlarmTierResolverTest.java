package com.factory.hub.alarm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 档位解析的单元测试（DH-14）。
 * <p>
 * 这套逻辑是纯计算，不碰数据库也不起 Spring 容器，
 * 所以直接测 {@link AlarmTierResolver}——这是能把它做成静态方法的原因。
 */
class AlarmTierResolverTest {

    /* ==================== 辅助 ==================== */

    /** 造一条三档规则：预警>35 / 告警>50 / 停机>65 */
    private static AlarmRule tieredRule(String op) {
        AlarmRule r = new AlarmRule();
        r.setId("r-test");
        r.setName("分级测试");
        r.setType(AlarmRule.TYPE_THRESHOLD);
        r.setPointKey("temperature");
        r.setOp(op);
        r.setForSeconds(0);
        r.setEnabled(true);
        List<AlarmRule.Tier> tiers = new ArrayList<>();
        tiers.add(new AlarmRule.Tier("MINOR", 35d));
        tiers.add(new AlarmRule.Tier("MAJOR", 50d));
        tiers.add(new AlarmRule.Tier("CRITICAL", 65d));
        r.setTiers(tiers);
        return r;
    }

    /** 造一条「越低越严重」的三档规则：预警<65 / 告警<50 / 停机<35 */
    private static AlarmRule tieredRuleDown(String op) {
        AlarmRule r = new AlarmRule();
        r.setId("r-test-down");
        r.setName("分级测试-降");
        r.setType(AlarmRule.TYPE_THRESHOLD);
        r.setPointKey("temperature");
        r.setOp(op);
        r.setForSeconds(0);
        r.setEnabled(true);
        List<AlarmRule.Tier> tiers = new ArrayList<>();
        tiers.add(new AlarmRule.Tier("MINOR", 65d));
        tiers.add(new AlarmRule.Tier("MAJOR", 50d));
        tiers.add(new AlarmRule.Tier("CRITICAL", 35d));
        r.setTiers(tiers);
        return r;
    }

    /** 造一条老规则（不带 tiers，单值） */
    private static AlarmRule legacyRule(String op, Double threshold, String level) {
        AlarmRule r = new AlarmRule();
        r.setId("r-legacy");
        r.setName("老规则");
        r.setType(AlarmRule.TYPE_THRESHOLD);
        r.setPointKey("temperature");
        r.setOp(op);
        r.setThreshold(threshold);
        r.setLevel(level);
        r.setForSeconds(0);
        r.setEnabled(true);
        return r;
    }

    /* ==================== 各 op × 各取值 ==================== */

    @Test
    @DisplayName("大于型：只有超过阈值才算命中，等于阈值不算")
    void greaterThan() {
        AlarmRule r = tieredRule(">");
        assertNull(AlarmTierResolver.resolve(r, 30));
        assertNull(AlarmTierResolver.resolve(r, 35));
        assertEquals("MINOR", AlarmTierResolver.resolve(r, 35.1).level());
        assertEquals("MAJOR", AlarmTierResolver.resolve(r, 50.1).level());
        assertEquals("CRITICAL", AlarmTierResolver.resolve(r, 65.1).level());
    }

    @Test
    @DisplayName("大于等于型：等于阈值就算命中")
    void greaterOrEqual() {
        AlarmRule r = tieredRule(">=");
        assertNull(AlarmTierResolver.resolve(r, 34.9));
        assertEquals("MINOR", AlarmTierResolver.resolve(r, 35).level());
        assertEquals("MAJOR", AlarmTierResolver.resolve(r, 50).level());
        assertEquals("CRITICAL", AlarmTierResolver.resolve(r, 65).level());
    }

    @Test
    @DisplayName("小于型：低于阈值才算命中，等于阈值不算")
    void lessThan() {
        AlarmRule r = tieredRuleDown("<");
        assertNull(AlarmTierResolver.resolve(r, 70));
        assertNull(AlarmTierResolver.resolve(r, 65));
        assertEquals("MINOR", AlarmTierResolver.resolve(r, 64.9).level());
        assertEquals("MAJOR", AlarmTierResolver.resolve(r, 49.9).level());
        assertEquals("CRITICAL", AlarmTierResolver.resolve(r, 34.9).level());
    }

    @Test
    @DisplayName("小于等于型：等于阈值就算命中")
    void lessOrEqual() {
        AlarmRule r = tieredRuleDown("<=");
        assertNull(AlarmTierResolver.resolve(r, 65.1));
        assertEquals("MINOR", AlarmTierResolver.resolve(r, 65).level());
        assertEquals("MAJOR", AlarmTierResolver.resolve(r, 50).level());
        assertEquals("CRITICAL", AlarmTierResolver.resolve(r, 35).level());
    }

    @Test
    @DisplayName("等值型：命中就返回那一档")
    void equals() {
        AlarmRule r = tieredRule("==");
        assertNull(AlarmTierResolver.resolve(r, 36));
        assertNotNull(AlarmTierResolver.resolve(r, 35));
        assertEquals("MINOR", AlarmTierResolver.resolve(r, 35).level());
    }

    @Test
    @DisplayName("不等型：单档时命中就返回那一档")
    void notEquals() {
        // 不等型配多档没有意义（任何数都等于「不等于」某一档），校验会拒掉，
        // 这里只验单档的判定行为
        AlarmRule r = new AlarmRule();
        r.setType(AlarmRule.TYPE_THRESHOLD);
        r.setOp("!=");
        List<AlarmRule.Tier> tiers = new ArrayList<>();
        tiers.add(new AlarmRule.Tier("MINOR", 35d));
        r.setTiers(tiers);
        assertNull(AlarmTierResolver.resolve(r, 35));
        assertEquals("MINOR", AlarmTierResolver.resolve(r, 36).level());
    }

    /* ==================== 命中多档取最高档 ==================== */

    @Test
    @DisplayName("多档同时命中时取等级最高的那档，不取第一条命中的")
    void takeHighestRank() {
        // 阈值故意配得一样密：60 同时越过 35 和 50，只能报最严重的
        AlarmRule r = new AlarmRule();
        r.setType(AlarmRule.TYPE_THRESHOLD);
        r.setOp(">");
        List<AlarmRule.Tier> tiers = new ArrayList<>();
        tiers.add(new AlarmRule.Tier("MINOR", 35d));
        tiers.add(new AlarmRule.Tier("MAJOR", 50d));
        tiers.add(new AlarmRule.Tier("CRITICAL", 65d));
        r.setTiers(tiers);

        AlarmTierResolver.TierHit hit = AlarmTierResolver.resolve(r, 60);
        assertNotNull(hit);
        assertEquals("MAJOR", hit.level());
        assertEquals(50d, hit.threshold());

        // 档位在列表里的顺序不影响结果：反过来填也要取到 CRITICAL
        List<AlarmRule.Tier> reversed = new ArrayList<>(tiers);
        java.util.Collections.reverse(reversed);
        r.setTiers(reversed);
        AlarmTierResolver.TierHit hit2 = AlarmTierResolver.resolve(r, 60);
        assertNotNull(hit2);
        assertEquals("MAJOR", hit2.level());
    }

    @Test
    @DisplayName("一档都没命中就不报警（返回 null）")
    void noTierHit() {
        AlarmRule r = tieredRule(">");
        assertNull(AlarmTierResolver.resolve(r, 20));
        assertNull(AlarmTierResolver.resolve(r, -5));
    }

    @Test
    @DisplayName("命中档要带上自己的阈值，审计和页面上要显示这个数")
    void hitCarriesThreshold() {
        AlarmRule r = tieredRule(">");
        AlarmTierResolver.TierHit hit = AlarmTierResolver.resolve(r, 70);
        assertNotNull(hit);
        assertEquals("CRITICAL", hit.level());
        assertEquals(65d, hit.threshold());
    }

    /* ==================== 老规则兼容 ==================== */

    @Test
    @DisplayName("老规则（无 tiers）走隐式单档：等级取规则级 level，阈值取规则级 threshold")
    void legacyRuleUsesImplicitTier() {
        AlarmRule r = legacyRule(">", 75d, "MAJOR");
        AlarmTierResolver.TierHit hit = AlarmTierResolver.resolve(r, 80);
        assertNotNull(hit);
        assertEquals("MAJOR", hit.level());
        assertEquals(75d, hit.threshold());

        assertNull(AlarmTierResolver.resolve(r, 70));
    }

    @Test
    @DisplayName("老规则没有阈值时判定不了，不当成命中")
    void legacyRuleWithoutThreshold() {
        AlarmRule r = legacyRule(">", null, "MAJOR");
        assertNull(AlarmTierResolver.resolve(r, 80));
    }

    @Test
    @DisplayName("老规则不配置档位，isTiered 为 false")
    void legacyIsNotTiered() {
        assertTrue(!legacyRule(">", 75d, "MAJOR").isTiered());
        assertTrue(tieredRule(">").isTiered());
    }

    /* ==================== 换档 ==================== */

    @Test
    @DisplayName("升档：就地改等级、阈值、文案，并返回「要发通知」")
    void applyTierUpgrade() {
        AlarmEvent ev = new AlarmEvent();
        ev.setLevel("MAJOR");
        ev.setThreshold(50d);
        ev.setMessage("一号加工中心 主轴温度 = 60℃，达到 > 50");

        boolean up = AlarmTierResolver.applyTier(ev,
                new AlarmTierResolver.TierHit("CRITICAL", 65d), 80d,
                "一号加工中心 主轴温度 = 80℃，达到 > 65（停机档）");

        assertTrue(up, "等级升高了要发通知");
        assertEquals("CRITICAL", ev.getLevel());
        assertEquals(65d, ev.getThreshold());
        assertEquals(80d, ev.getValue(), "实测值要跟等级、阈值、文案是同一轮的结果");
        assertTrue(ev.getMessage().contains("停机档"));
    }

    @Test
    @DisplayName("降档：照样就地改，但不返回要发通知")
    void applyTierDowngrade() {
        AlarmEvent ev = new AlarmEvent();
        ev.setLevel("CRITICAL");
        ev.setThreshold(65d);

        boolean up = AlarmTierResolver.applyTier(ev,
                new AlarmTierResolver.TierHit("MINOR", 35d), 40d,
                "一号加工中心 主轴温度 = 40℃，达到 > 35（预警档）");

        assertTrue(!up, "降级不是紧急信息，不发通知");
        assertEquals("MINOR", ev.getLevel());
        assertEquals(35d, ev.getThreshold());
        assertEquals(40d, ev.getValue());
    }

    @Test
    @DisplayName("换档改的是同一条事件，不会凭空多出一条")
    void applyTierMutatesSameEvent() {
        AlarmEvent ev = new AlarmEvent();
        ev.setLevel("MINOR");
        AlarmTierResolver.applyTier(ev, new AlarmTierResolver.TierHit("MAJOR", 50d), 55d, "x");
        AlarmTierResolver.applyTier(ev, new AlarmTierResolver.TierHit("CRITICAL", 65d), 70d, "y");
        assertEquals("CRITICAL", ev.getLevel());
        assertEquals(65d, ev.getThreshold());
        assertEquals(70d, ev.getValue());
    }

    /* ==================== 等级权重与文案 ==================== */
    @Test
    @DisplayName("等级权重：MINOR < MAJOR < CRITICAL")
    void levelRank() {
        assertTrue(AlarmTierResolver.rank("MINOR") < AlarmTierResolver.rank("MAJOR"));
        assertTrue(AlarmTierResolver.rank("MAJOR") < AlarmTierResolver.rank("CRITICAL"));
        assertEquals(0, AlarmTierResolver.rank("WHAT"));
        assertEquals(0, AlarmTierResolver.rank(null));
    }

    @Test
    @DisplayName("等级中文名：预警 / 告警 / 停机")
    void levelText() {
        assertEquals("预警", AlarmTierResolver.levelText("MINOR"));
        assertEquals("告警", AlarmTierResolver.levelText("MAJOR"));
        assertEquals("停机", AlarmTierResolver.levelText("CRITICAL"));
        assertEquals("预警", AlarmTierResolver.levelText("minor"));
    }

    @Test
    @DisplayName("档位描述按等级从低到高排，和用户填的顺序无关")
    void describeTiers() {
        AlarmRule r = tieredRule(">");
        List<AlarmRule.Tier> reversed = new ArrayList<>(r.getTiers());
        java.util.Collections.reverse(reversed);
        r.setTiers(reversed);
        assertEquals("预警>35 / 告警>50 / 停机>65", AlarmTierResolver.describeTiers(r));    }
}
