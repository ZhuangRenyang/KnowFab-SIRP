package com.factory.hub.alarm;

import com.factory.hub.collect.SnapshotStore;
import com.factory.hub.model.DeviceEntity;
import com.factory.hub.model.PointValue;
import com.factory.hub.service.DeviceService;
import com.factory.hub.system.SettingStore;
import com.factory.hub.service.Broadcaster;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 引擎的分级行为测试（DH-14 2.3 / 2.4）。
 * <p>
 * <b>最要紧的一条：换档绝不产生第二条告警事件。</b>
 * 一台温度慢慢爬升的机器会先后越过预警线、告警线、停机线，
 * 如果每次越线都新开一条告警，用户又会看到三条——那就等于没做分级。
 * 这里用 mock 把整个评估回路跑起来，直接数「一共落库了几条」。
 */
class AlarmEngineTierSwitchTest {

    private AlarmRuleStore ruleStore;
    private AlarmEventRepository repo;
    private SnapshotStore snapshot;
    private DeviceService deviceService;
    private AlarmNotifier notifier;
    private SettingStore settings;
    private AlarmEngine engine;

    /** 当前点位值。每次 evaluate 前改它，模拟温度变化 */
    private double currentValue;

    @BeforeEach
    void setUp() {
        ruleStore = mock(AlarmRuleStore.class);
        repo = mock(AlarmEventRepository.class);
        snapshot = mock(SnapshotStore.class);
        deviceService = mock(DeviceService.class);
        notifier = mock(AlarmNotifier.class);
        settings = mock(SettingStore.class);
        Broadcaster ws = mock(Broadcaster.class);

        DeviceEntity dev = new DeviceEntity();
        dev.setCode("CNC-01");
        dev.setName("一号加工中心");
        dev.setLocation("一车间 A 区");
        dev.setProtocol("sim");

        when(deviceService.all()).thenReturn(List.of(dev));
        when(settings.getInt(SettingStore.ALARM_STORM)).thenReturn(8);
        when(settings.getInt(SettingStore.ALARM_RETAIN_DAYS)).thenReturn(180);
        when(repo.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(repo.save(any(AlarmEvent.class))).thenAnswer(inv -> inv.getArgument(0));
        when(notifier.fire(any(), any())).thenReturn(true);
        when(notifier.escalate(any(), any())).thenReturn(true);
        when(notifier.repeatMinutes()).thenReturn(0);

        engine = new AlarmEngine(ruleStore, repo, snapshot, deviceService,
                notifier, ws, new ObjectMapper(), settings);
    }

    /** 让采集器"采到"一个温度值 */
    private void feedTemperature(double v) {
        this.currentValue = v;
        Map<String, PointValue> vals = new HashMap<>();
        vals.put("temperature", new PointValue("CNC-01", "temperature", "主轴温度",
                v, "℃", PointValue.GOOD, "value", System.currentTimeMillis()));
        when(snapshot.latest("CNC-01")).thenReturn(vals);
    }

    /** 三档规则：预警>35 / 告警>50 / 停机>65 */
    private static AlarmRule tieredRule() {
        AlarmRule r = new AlarmRule();
        r.setId("r-tier");
        r.setName("主轴温度分级");
        r.setType(AlarmRule.TYPE_THRESHOLD);
        r.setPointKey("temperature");
        r.setOp(">");
        r.setForSeconds(0);
        r.setRecoverSeconds(0);
        r.setCooldownSeconds(0);
        r.setEnabled(true);
        List<AlarmRule.Tier> tiers = new ArrayList<>();
        tiers.add(new AlarmRule.Tier("MINOR", 35d));
        tiers.add(new AlarmRule.Tier("MAJOR", 50d));
        tiers.add(new AlarmRule.Tier("CRITICAL", 65d));
        r.setTiers(tiers);
        return r;
    }

    private void useRule(AlarmRule rule) {
        when(ruleStore.current()).thenReturn(List.of(rule));
    }

    /* ==================== 首次触发 ==================== */

    @Test
    @DisplayName("分级规则首次触发：等级和阈值取命中的那一档")
    void firstFireUsesHitTier() {
        useRule(tieredRule());
        feedTemperature(60);          // 越过 35 和 50，最高命中档是「告警」

        engine.evaluate();

        List<AlarmEvent> open = engine.openEvents();
        assertEquals(1, open.size(), "同一设备同一测点只能有一条告警");
        AlarmEvent ev = open.get(0);
        assertEquals("MAJOR", ev.getLevel());
        assertEquals(50d, ev.getThreshold());
        verify(repo, times(1)).saveAll(anyList());
    }

    @Test
    @DisplayName("一档都没命中就不报警")
    void noTierHitNoAlarm() {
        useRule(tieredRule());
        feedTemperature(20);

        engine.evaluate();

        assertEquals(0, engine.openEvents().size());
        verify(repo, never()).saveAll(anyList());
    }

    /* ==================== 换档 ==================== */

    @Test
    @DisplayName("升档：就地改等级，不产生第二条告警，并发一条升级通知")
    void upgradeSwitchesInPlace() {
        useRule(tieredRule());
        feedTemperature(60);
        engine.evaluate();
        assertEquals(1, engine.openEvents().size());
        AlarmEvent ev = engine.openEvents().get(0);
        assertEquals("MAJOR", ev.getLevel());

        // 温度继续爬，越过停机线
        feedTemperature(80);
        engine.evaluate();

        assertEquals(1, engine.openEvents().size(), "换档不能多出第二条告警");
        assertEquals("CRITICAL", ev.getLevel(), "等级要就地升到停机");
        assertEquals(65d, ev.getThreshold(), "阈值要跟着换成该档的阈值");
        assertEquals(80d, ev.getValue(), "实测值也要一起换，否则行内自相矛盾");
        assertTrue(ev.getMessage().contains("停机档"), "文案要说明是停机档：" + ev.getMessage());
        verify(repo, times(1)).saveAll(anyList());   // 整轮只落库过一条事件
        verify(notifier, times(1)).escalate(any(), any());
    }

    @Test
    @DisplayName("降档：就地改等级，但不发通知")
    void downgradeSwitchesInPlaceSilently() {
        useRule(tieredRule());
        feedTemperature(80);
        engine.evaluate();
        AlarmEvent ev = engine.openEvents().get(0);
        assertEquals("CRITICAL", ev.getLevel());
        verify(notifier, never()).escalate(any(), any());

        // 温度降回来，只剩预警档命中
        feedTemperature(40);
        engine.evaluate();

        assertEquals(1, engine.openEvents().size(), "换档不能多出第二条告警");
        assertEquals("MINOR", ev.getLevel(), "等级要就地降到预警");
        assertEquals(35d, ev.getThreshold());
        verify(repo, times(1)).saveAll(anyList());
        verify(notifier, never()).escalate(any(), any());
    }

    @Test
    @DisplayName("反复升档降档，事件始终只有一条")
    void repeatedSwitchKeepsOneEvent() {
        useRule(tieredRule());
        feedTemperature(60);
        engine.evaluate();

        // 每个值都还在预警线以上，条件一直成立，只是档位在变
        for (double v : new double[]{80, 40, 70, 45, 90}) {
            feedTemperature(v);
            engine.evaluate();
            assertEquals(1, engine.openEvents().size(), "换来换去也只能有一条告警");
        }
        verify(repo, times(1)).saveAll(anyList());
    }

    /* ==================== 老规则兼容 ==================== */

    @Test
    @DisplayName("老规则（不带 tiers）行为不变：等级取规则级 level，阈值取规则级 threshold")
    void legacyRuleBehavesAsBefore() {
        AlarmRule r = new AlarmRule();
        r.setId("r-legacy");
        r.setName("主轴温度");
        r.setType(AlarmRule.TYPE_THRESHOLD);
        r.setPointKey("temperature");
        r.setOp(">");
        r.setThreshold(75d);
        r.setLevel("MAJOR");
        r.setForSeconds(0);
        r.setRecoverSeconds(0);
        r.setCooldownSeconds(0);
        r.setEnabled(true);
        useRule(r);

        feedTemperature(80);
        engine.evaluate();

        List<AlarmEvent> open = engine.openEvents();
        assertEquals(1, open.size());
        AlarmEvent ev = open.get(0);
        assertEquals("MAJOR", ev.getLevel());
        assertEquals(75d, ev.getThreshold());
        assertNotNull(ev.getMessage());
        // 老规则不该冒出档位字样，消息和升级前一致
        assertTrue(!ev.getMessage().contains("档）"), "老规则文案不该变：" + ev.getMessage());
        verify(notifier, never()).escalate(any(), any());
    }

    @Test
    @DisplayName("老规则没采到数时不判断，不误报")
    void legacyRuleNoDataNoAlarm() {
        AlarmRule r = new AlarmRule();
        r.setId("r-legacy");
        r.setName("主轴温度");
        r.setType(AlarmRule.TYPE_THRESHOLD);
        r.setPointKey("temperature");
        r.setOp(">");
        r.setThreshold(75d);
        r.setLevel("MAJOR");
        r.setForSeconds(0);
        r.setEnabled(true);
        useRule(r);

        Map<String, PointValue> empty = new HashMap<>();
        when(snapshot.latest("CNC-01")).thenReturn(empty);

        engine.evaluate();

        assertEquals(0, engine.openEvents().size());
        verify(repo, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("坏值（quality=BAD）不判断，不误报")
    void badQualityNoAlarm() {
        useRule(tieredRule());
        Map<String, PointValue> vals = new HashMap<>();
        vals.put("temperature", new PointValue("CNC-01", "temperature", "主轴温度",
                null, "℃", PointValue.BAD, "value", System.currentTimeMillis()));
        when(snapshot.latest("CNC-01")).thenReturn(vals);
        engine.evaluate();

        assertEquals(0, engine.openEvents().size());
        verify(repo, never()).saveAll(anyList());
        verify(repo, never()).save(any(AlarmEvent.class));
        verify(notifier, never()).fire(any(), any());
        verify(notifier, never()).escalate(any(), any());
    }

    @Test
    @DisplayName("告警全部恢复后重新触发，才会再落一条新事件")
    void newEventAfterRecovery() {
        useRule(tieredRule());
        feedTemperature(60);
        engine.evaluate();
        assertEquals(1, engine.openEvents().size());

        // 条件恢复（recoverSeconds=0），事件关闭
        feedTemperature(10);
        engine.evaluate();
        assertEquals(0, engine.openEvents().size());

        // 又越线了：这次才允许再产生一条新事件
        feedTemperature(60);
        engine.evaluate();
        assertEquals(1, engine.openEvents().size());
        // 落库三次：第一次触发、恢复时关掉、再次触发
        verify(repo, times(3)).saveAll(anyList());
    }
}
