package com.factory.hub.alarm;

import java.util.Set;

/**
 * 系统告警：规则<strong>不在</strong> {@code alarm.rules} 里，生命周期由产生它的模块自己管。
 *
 * <p>用户配的规则都在规则表里，告警引擎按规则表就能判断「这条规则还在不在」。
 * 但有些告警是系统模块直接产生的——比如需量超合同，它的配置在
 * {@code AppSetting("energy.demand")}（合同需量、预警比例、开关），跟「规则阈值」是两回事，
 * 塞进规则表只会让用户看到一个改了却不生效的条目。
 *
 * <p>这些告警登记在这里，告警引擎遇到它们就知道：
 * <b>「规则不在表里」是正常的，不是规则被删了</b>。
 *
 * <p>为什么以前会出事：{@code AlarmEngine.restore()} 重启时按规则表找规则，找不到就把未恢复的告警
 * 自动关掉，理由还写「规则已删除或停用」——规则根本没被删过，那是句假话。
 * 结果就是客户「昨天超合同需量」的告警一重启就查不到了，正好是审计要解决的追溯场景。
 *
 * <p>以后再有系统告警，在这里加一行就行，告警引擎不用改。
 */
public final class SystemAlarms {

    /** 需量超合同 / 接近合同（由 demand 模块写，规则不在规则表里） */
    public static final String DEMAND_OVER = "r-demand-over";

    private static final Set<String> SYSTEM_RULE_IDS = Set.of(DEMAND_OVER);

    private SystemAlarms() {
    }

    /**
     * 是不是系统告警。
     *
     * @param ruleId 告警关联的规则 ID
     * @return true = 系统告警，生命周期归产生它的模块管，引擎别去关它
     */
    public static boolean isSystem(String ruleId) {
        return ruleId != null && SYSTEM_RULE_IDS.contains(ruleId);
    }
}
