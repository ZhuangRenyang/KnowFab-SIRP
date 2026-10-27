package com.factory.hub.demand;

import com.factory.hub.alarm.AlarmEvent;
import com.factory.hub.alarm.AlarmEventRepository;
import com.factory.hub.alarm.AlarmNotifier;
import com.factory.hub.alarm.AlarmRule;
import com.factory.hub.web.WsHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 需量超限预警。
 * <p>
 * 基本电费是「本月最大值」，这个月一旦冲上去就改不回来了——不像电度电费，
 * 后面少用点还能省回来。所以这是唯一一个「必须当场提醒」的电费指标：
 * 客户下午冲高 10 分钟，当月多交几千块，事后才知道就晚了。
 * <p>
 * <b>为什么不走 AlarmEngine：</b>
 * 那个引擎的输入是「设备点位采样」，而需量是「一段时间窗口的聚合量」，
 * 没有对应点位。硬塞一个虚拟点进去，会让规则配置界面上多出一个客户看不懂的点。
 * 这里单独算、复用同一套 AlarmEvent（报警页能看到）+ AlarmNotifier（微信能收到），
 * 保持「一个报警入口」不变。
 * <p>
 * 去重靠 (ruleId, deviceCode, ACTIVE)：已经报过就不再报，回落后自动清除。
 */
@Component
public class DemandMonitor {

    private static final Logger log = LoggerFactory.getLogger(DemandMonitor.class);

    /** 全厂算一条告警，不按设备拆 */
    static final String RULE_ID = "r-demand-over";
    private static final String PLANT = "PLANT";

    /** 恢复阈值：低于合同需量的这个比例才算真回落，防止在 90% 附近反复报 */
    private static final double RECOVER_RATIO = 0.85;

    private final DemandService demandService;
    private final DemandStore demandStore;
    private final AlarmEventRepository alarmRepo;
    private final AlarmNotifier notifier;
    private final WsHandler ws;
    private final ObjectMapper mapper;

    public DemandMonitor(DemandService demandService, DemandStore demandStore,
                         AlarmEventRepository alarmRepo, AlarmNotifier notifier,
                         WsHandler ws, ObjectMapper mapper) {
        this.demandService = demandService;
        this.demandStore = demandStore;
        this.alarmRepo = alarmRepo;
        this.notifier = notifier;
        this.ws = ws;
        this.mapper = mapper;
    }

    @Scheduled(fixedDelay = 30_000L)
    public void check() {
        DemandConfig cfg;
        try {
            cfg = demandStore.current();
        } catch (Exception e) {
            return;
        }
        if (!cfg.isEnabled() || cfg.getContractDemandKw() <= 0) {
            return;
        }

        double peak;
        try {
            peak = demandService.monthPeakKw(YearMonth.now());
        } catch (Exception e) {
            log.debug("需量峰值计算失败: {}", e.getMessage());
            return;
        }
        double ratio = peak / cfg.getContractDemandKw();

        List<AlarmEvent> open = alarmRepo.findByRuleIdAndDeviceCodeAndStateIn(
                RULE_ID, PLANT, List.of(AlarmEvent.STATE_ACTIVE, AlarmEvent.STATE_ACKED));

        if (ratio >= cfg.getWarnRatio()) {
            if (!open.isEmpty()) {
                return;                            // 已经报过，别刷屏
            }
            fire(cfg, peak, ratio);
        } else if (ratio < RECOVER_RATIO && !open.isEmpty()) {
            for (AlarmEvent e : open) {
                e.setState(AlarmEvent.STATE_CLEARED);
                e.setClearedAt(System.currentTimeMillis());
                e.setClearReason("需量已回落到合同范围内");
                alarmRepo.save(e);
            }
            log.info("需量已回落到 {} kW（合同 {} kW），告警自动解除", Math.round(peak),
                    Math.round(cfg.getContractDemandKw()));
        }
    }

    private void fire(DemandConfig cfg, double peak, double ratio) {
        boolean over = ratio > 1.0;
        String message = over
                ? String.format("本月最大需量 %.0f kW，已超合同需量 %.0f kW（%.0f%%）。"
                        + "本月基本电费按 %.0f kW 计，超容部分有的地区按 2 倍加收。",
                peak, cfg.getContractDemandKw(), (ratio - 1) * 100, peak)
                : String.format("本月最大需量 %.0f kW，已到合同需量 %.0f kW 的 %.0f%%。"
                        + "再冲高这个月的电费就多交了，高峰时段把非关键设备错开。",
                peak, cfg.getContractDemandKw(), ratio * 100);

        AlarmEvent e = new AlarmEvent();
        e.setRuleId(RULE_ID);
        e.setRuleName(over ? "需量超合同" : "需量接近合同");
        e.setRuleType("DEMAND");
        e.setDeviceCode(PLANT);
        e.setDeviceName("全厂");
        e.setLocation("");
        e.setLevel(over ? "CRITICAL" : "MAJOR");
        e.setState(AlarmEvent.STATE_ACTIVE);
        e.setMessage(message);
        e.setValue(round1(peak));
        e.setThreshold(cfg.getContractDemandKw());
        e.setStartedAt(System.currentTimeMillis());
        e.setRuleDesc(String.format("15 分钟需量 ≥ 合同需量的 %.0f%%", cfg.getWarnRatio() * 100));
        try {
            AlarmEvent saved = alarmRepo.save(e);

            AlarmRule forNotify = new AlarmRule();
            forNotify.setId(RULE_ID);
            forNotify.setName(saved.getRuleName());
            forNotify.setNotify(true);
            if (notifier.fire(saved, forNotify)) {
                saved.setNotifyCount(saved.getNotifyCount() + 1);
                saved.setLastNotifyAt(System.currentTimeMillis());
                alarmRepo.save(saved);
            }
            push(saved);
            log.warn("需量告警：{}", message);
        } catch (Exception ex) {
            log.error("需量告警写库/发送失败: {}", ex.getMessage());
        }
    }

    private void push(AlarmEvent e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "alarm:new");
        m.put("id", e.getId());
        m.put("deviceCode", e.getDeviceCode());
        m.put("deviceName", e.getDeviceName());
        m.put("level", e.getLevel());
        m.put("state", e.getState());
        m.put("ruleName", e.getRuleName());
        m.put("message", e.getMessage());
        m.put("startedAt", e.getStartedAt());
        try {
            ws.broadcast(mapper.writeValueAsString(m));
        } catch (Exception ignored) {
        }
    }

    private static double round1(double v) {
        return Math.round(v * 10d) / 10d;
    }
}
