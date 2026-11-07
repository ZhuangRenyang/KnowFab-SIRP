package com.factory.hub.alarm;

import com.factory.hub.collect.SnapshotStore;
import com.factory.hub.service.DeviceService;
import com.factory.hub.system.SettingStore;
import com.factory.hub.service.Broadcaster;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 重启时未恢复告警的处理（DH-29）。
 *
 * <p><b>问题的本质是「关闭理由在说假话」。</b>
 * 需量超合同是系统告警：规则本来就不在 {@code alarm.rules} 里（它的配置在
 * {@code AppSetting("energy.demand")}）。以前 restore() 按规则表找规则，找不到就把告警关掉，
 * 理由写「规则已删除或停用」——规则根本没被删过。结果客户「昨天超合同需量」的告警
 * 一重启就没了，追溯时看到的理由还是假的。
 *
 * <p>这里守住两条：
 * <ol>
 *   <li>系统告警重启后要活着（状态、startedAt 都不能动）</li>
 *   <li>用户规则被删/停用时，告警照旧关闭，理由照旧（那句是真话）</li>
 * </ol>
 */
class AlarmEngineRestoreTest {

    private AlarmRuleStore ruleStore;
    private AlarmEventRepository repo;
    private AlarmEngine engine;

    @BeforeEach
    void setUp() {
        ruleStore = mock(AlarmRuleStore.class);
        repo = mock(AlarmEventRepository.class);
        SnapshotStore snapshot = mock(SnapshotStore.class);
        DeviceService deviceService = mock(DeviceService.class);
        AlarmNotifier notifier = mock(AlarmNotifier.class);
        SettingStore settings = mock(SettingStore.class);
        Broadcaster ws = mock(Broadcaster.class);

        when(settings.getInt(SettingStore.ALARM_STORM)).thenReturn(8);
        when(settings.getInt(SettingStore.ALARM_RETAIN_DAYS)).thenReturn(180);
        when(repo.save(any(AlarmEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        engine = new AlarmEngine(ruleStore, repo, snapshot, deviceService,
                notifier, ws, new ObjectMapper(), settings);
    }

    private static AlarmEvent openEvent(String ruleId, String ruleName) {
        AlarmEvent e = new AlarmEvent();
        e.setId(1L);
        e.setRuleId(ruleId);
        e.setRuleName(ruleName);
        e.setRuleType("DEMAND");
        e.setDeviceCode("PLANT");
        e.setDeviceName("全厂");
        e.setLevel("CRITICAL");
        e.setState(AlarmEvent.STATE_ACTIVE);
        e.setMessage("本月最大需量已超合同需量");
        e.setValue(620d);
        e.setThreshold(500d);
        e.setStartedAt(1_700_000_000_000L);
        return e;
    }

    private void rules(List<AlarmRule> rules) {
        when(ruleStore.current()).thenReturn(rules);
    }

    /* ==================== 系统告警 ==================== */

    @Test
    @DisplayName("系统告警（需量超合同）重启后保留：状态、理由、开始时间都不动")
    void systemAlarmSurvivesRestart() {
        AlarmEvent e = openEvent(SystemAlarms.DEMAND_OVER, "需量超合同");
        when(repo.findOpen()).thenReturn(List.of(e));
        rules(List.of());            // 规则表里当然没有它

        engine.restore();

        assertEquals(AlarmEvent.STATE_ACTIVE, e.getState(), "系统告警不能被重启关掉");
        assertNull(e.getClearReason(), "不能给系统告警编一个关闭理由");
        assertEquals(1_700_000_000_000L, e.getStartedAt(), "开始时间不能动，追溯靠它");
        verify(repo, never()).save(any(AlarmEvent.class));
    }

    @Test
    @DisplayName("系统告警重启后继续跟踪：进 open 表，不会被当孤儿收掉")
    void systemAlarmIsTrackedAfterRestart() {
        AlarmEvent e = openEvent(SystemAlarms.DEMAND_OVER, "需量超合同");
        when(repo.findOpen()).thenReturn(List.of(e));
        rules(List.of());

        engine.restore();

        List<AlarmEvent> open = engine.openEvents();
        assertEquals(1, open.size(), "重启后要继续跟踪这条告警");
        assertEquals(SystemAlarms.DEMAND_OVER, open.get(0).getRuleId());
    }

    @Test
    @DisplayName("系统告警的规则 ID 登记在 SystemAlarms 里，引擎靠它认出来")
    void systemAlarmIsRegistered() {
        assertTrue(SystemAlarms.isSystem(SystemAlarms.DEMAND_OVER));
        assertEquals("r-demand-over", SystemAlarms.DEMAND_OVER);
    }

    /* ==================== 用户规则：行为不变 ==================== */

    @Test
    @DisplayName("用户规则被删了：告警照旧关闭，理由照旧是「规则已删除或停用」")
    void userRuleMissingStillCloses() {
        AlarmEvent e = openEvent("r-temp", "主轴温度过高");
        when(repo.findOpen()).thenReturn(List.of(e));
        rules(List.of());            // 规则确实被删了

        engine.restore();

        assertEquals(AlarmEvent.STATE_CLEARED, e.getState());
        assertEquals("规则已删除或停用", e.getClearReason());
        verify(repo).save(e);
    }

    @Test
    @DisplayName("用户规则被停用：告警照旧关闭")
    void userRuleDisabledStillCloses() {
        AlarmEvent e = openEvent("r-temp", "主轴温度过高");
        when(repo.findOpen()).thenReturn(List.of(e));
        AlarmRule r = new AlarmRule();
        r.setId("r-temp");
        r.setEnabled(false);
        rules(List.of(r));

        engine.restore();

        assertEquals(AlarmEvent.STATE_CLEARED, e.getState());
        assertEquals("规则已删除或停用", e.getClearReason());
    }

    @Test
    @DisplayName("用户规则还在且启用：告警保留并继续跟踪")
    void userRuleEnabledKeepsAlarm() {
        AlarmEvent e = openEvent("r-temp", "主轴温度过高");
        when(repo.findOpen()).thenReturn(List.of(e));
        AlarmRule r = new AlarmRule();
        r.setId("r-temp");
        r.setEnabled(true);
        rules(List.of(r));

        engine.restore();

        assertEquals(AlarmEvent.STATE_ACTIVE, e.getState());
        assertEquals(1, engine.openEvents().size());
        verify(repo, never()).save(any(AlarmEvent.class));
    }
}
