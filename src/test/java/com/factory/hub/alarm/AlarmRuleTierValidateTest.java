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
 * 规则体检的单元测试（DH-14 2.5）。
 * <p>
 * 重点是四种「配错档位」必须被当场拒掉，而且错误信息要说清是哪两档反了——
 * 只说「配置有问题」，用户根本不知道自己该改哪一行。
 */
class AlarmRuleTierValidateTest {

    private static AlarmRule rule(String op, AlarmRule.Tier... tiers) {
        AlarmRule r = new AlarmRule();
        r.setId("r-validate");
        r.setName("主轴温度");
        r.setType(AlarmRule.TYPE_THRESHOLD);
        r.setPointKey("temperature");
        r.setOp(op);
        r.setForSeconds(60);
        r.setEnabled(true);
        List<AlarmRule.Tier> list = new ArrayList<>();
        for (AlarmRule.Tier t : tiers) {
            list.add(t);
        }
        r.setTiers(list);
        return r;
    }

    @Test
    @DisplayName("正常的三档配置通过体检")
    void validTiers() {
        AlarmRule r = rule(">",
                new AlarmRule.Tier("MINOR", 80d),
                new AlarmRule.Tier("MAJOR", 85d),
                new AlarmRule.Tier("CRITICAL", 90d));
        assertNull(r.validate());
    }

    @Test
    @DisplayName("缺阈值被拒，并且说清是哪一档缺了")
    void missingThreshold() {
        AlarmRule r = rule(">",
                new AlarmRule.Tier("MINOR", null),
                new AlarmRule.Tier("MAJOR", 85d));
        String bad = r.validate();
        assertNotNull(bad);
        assertTrue(bad.contains("预警"), "错误信息要指出是哪一档：" + bad);
        assertTrue(bad.contains("阈值"), bad);
    }

    @Test
    @DisplayName("等级非法被拒")
    void illegalLevel() {
        AlarmRule r = rule(">",
                new AlarmRule.Tier("MINOR", 80d),
                new AlarmRule.Tier("FATAL", 85d));
        String bad = r.validate();
        assertNotNull(bad);
        assertTrue(bad.contains("等级"), bad);
    }

    @Test
    @DisplayName("同档重复被拒")
    void duplicatedLevel() {
        AlarmRule r = rule(">",
                new AlarmRule.Tier("MINOR", 80d),
                new AlarmRule.Tier("MINOR", 85d));
        String bad = r.validate();
        assertNotNull(bad);
        assertTrue(bad.contains("重复"), bad);
        assertTrue(bad.contains("预警"), bad);
    }

    @Test
    @DisplayName("阈值配反了被拒，并且说清是哪两档反了")
    void notMonotonicIncreasing() {
        // 预警 90 / 停机 65：等级越高阈值反而越小
        AlarmRule r = rule(">",
                new AlarmRule.Tier("MINOR", 90d),
                new AlarmRule.Tier("CRITICAL", 65d));
        String bad = r.validate();
        assertNotNull(bad);
        assertTrue(bad.contains("递增"), bad);
        assertTrue(bad.contains("预警"), bad);
        assertTrue(bad.contains("停机"), bad);
    }

    @Test
    @DisplayName("小于型的阈值必须递减，配反了被拒")
    void notMonotonicDecreasing() {
        // 预警 5 / 停机 10：等级越高阈值反而越大
        AlarmRule r = rule("<",
                new AlarmRule.Tier("MINOR", 5d),
                new AlarmRule.Tier("CRITICAL", 10d));
        String bad = r.validate();
        assertNotNull(bad);
        assertTrue(bad.contains("递减"), bad);
    }

    @Test
    @DisplayName("小于型配对了就通过（预警 10 / 告警 5 / 停机 0）")
    void monotonicDecreasingOk() {
        AlarmRule r = rule("<",
                new AlarmRule.Tier("MINOR", 10d),
                new AlarmRule.Tier("MAJOR", 5d),
                new AlarmRule.Tier("CRITICAL", 0d));
        assertNull(r.validate());
    }

    @Test
    @DisplayName("等值型配多档被拒")
    void equalsWithMultipleTiers() {
        AlarmRule r = rule("==",
                new AlarmRule.Tier("MINOR", 35d),
                new AlarmRule.Tier("CRITICAL", 65d));
        String bad = r.validate();
        assertNotNull(bad);
        assertTrue(bad.contains("等值"), bad);
    }

    @Test
    @DisplayName("不等型配多档也被拒")
    void notEqualsWithMultipleTiers() {
        AlarmRule r = rule("!=",
                new AlarmRule.Tier("MINOR", 35d),
                new AlarmRule.Tier("CRITICAL", 65d));
        assertNotNull(r.validate());
    }

    @Test
    @DisplayName("等值型只配一档是允许的")
    void equalsWithSingleTier() {
        AlarmRule r = rule("==", new AlarmRule.Tier("MAJOR", 0d));
        assertNull(r.validate());
    }

    @Test
    @DisplayName("老规则（无 tiers）走原来的校验分支：必须填阈值")
    void legacyRuleStillRequiresThreshold() {
        AlarmRule r = new AlarmRule();
        r.setName("老规则");
        r.setType(AlarmRule.TYPE_THRESHOLD);
        r.setPointKey("temperature");
        r.setOp(">");
        r.setForSeconds(60);
        String bad = r.validate();
        assertNotNull(bad);
        assertEquals("数值越限规则必须填阈值", bad);
    }

    @Test
    @DisplayName("老规则（无 tiers）填了阈值就通过，行为和以前一样")
    void legacyRulePasses() {
        AlarmRule r = new AlarmRule();
        r.setName("老规则");
        r.setType(AlarmRule.TYPE_THRESHOLD);
        r.setPointKey("temperature");
        r.setOp(">");
        r.setThreshold(75d);
        r.setForSeconds(60);
        assertNull(r.validate());
    }

    @Test
    @DisplayName("分级规则的规则级 threshold 可以不填，阈值由各档自己带")
    void tieredRuleDoesNotNeedRuleLevelThreshold() {
        AlarmRule r = rule(">",
                new AlarmRule.Tier("MINOR", 80d),
                new AlarmRule.Tier("MAJOR", 85d));
        assertNull(r.getThreshold());
        assertNull(r.validate());
    }
}
