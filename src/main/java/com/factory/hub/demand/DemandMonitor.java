package com.factory.hub.demand;

import com.factory.hub.alarm.AlarmEvent;
import com.factory.hub.alarm.AlarmEventRepository;
import com.factory.hub.alarm.AlarmNotifier;
import com.factory.hub.alarm.AlarmRule;
import com.factory.hub.alarm.SystemAlarms;
import com.factory.hub.service.Broadcaster;
import tools.jackson.databind.ObjectMapper;
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

    /**
     * 全厂算一条告警，不按设备拆。
     * <p>这是系统告警：规则不在 alarm.rules 里，配置在 {@code AppSetting("energy.demand")}。
     * 规则 ID 登记在 {@link SystemAlarms}，告警引擎靠它认出「这不是被删了的规则」。
     */
    static final String RULE_ID = SystemAlarms.DEMAND_OVER;
    private static final String PLANT = "PLANT";

    /** 恢复阈值：低于合同需量的这个比例才算真回落，防止在 90% 附近反复报 */
    private static final double RECOVER_RATIO = 0.85;

    private final DemandService demandService;
    private final DemandStore demandStore;
    private final AlarmEventRepository alarmRepo;
    private final AlarmNotifier notifier;
    private final Broadcaster ws;
    private final ObjectMapper mapper;

    public DemandMonitor(DemandService demandService, DemandStore demandStore,
                         AlarmEventRepository alarmRepo, AlarmNotifier notifier,
                         Broadcaster ws, ObjectMapper mapper) {
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

        // 挂着的需量告警要先查出来：监控关掉时也得靠它收尾
        List<AlarmEvent> open = alarmRepo.findByRuleIdAndDeviceCodeAndStateIn(
                RULE_ID, PLANT, List.of(AlarmEvent.STATE_ACTIVE, AlarmEvent.STATE_ACKED));

        if (!cfg.isEnabled() || cfg.getContractDemandKw() <= 0) {
            // 监控关了或合同需量清零 → 挂着的告警要收掉。
            // 系统告警不在规则表里，告警引擎不会替它关，得这儿自己负责，
            // 否则它会永远挂着——挂着没人能确认，也没人说得清为什么还挂着。
            // 这两种情况是两件事，理由也要分开说：「监控关了」是人为关的，
            // 「合同需量填成 0」是没填基准——不能都写成「需量监控已关闭」。
            closeAll(open, cfg.getContractDemandKw() <= 0
                    ? "合同需量为 0，没有需量基准，告警自动关闭"
                    : "需量监控已关闭");
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

        if (ratio >= cfg.getWarnRatio()) {
            if (!open.isEmpty()) {
                // 已经报过了，但峰值还在涨：跨过合同线就要升级成「已超合同」。
                // 不升级的话客户永远看不到「已超合同」这个唯一有加收后果的事实——
                // 告警还挂着、文案却还停在「已到合同需量的 95%」。
                upgradeIfOverContract(open, cfg, peak, ratio);
                return;                            // 别刷屏
            }
            fire(cfg, peak, ratio);
        } else if (ratio < RECOVER_RATIO && !open.isEmpty()) {
            // 本月峰值只增不减（monthPeakKw 取窗口最大值），所以 ratio 掉下来只有两种可能：
            // ① 跨月了——上个月的需量已结算归档，本月从零重新算
            // ② 峰值被重算或清零了
            // 原来一律写成「需量已回落到合同范围内」——跨月时这句话是假的：
            // 上个月确实超了合同，这不是回落，是结算归档。关闭理由必须说真话。
            boolean rolledOver = false;
            for (AlarmEvent e : open) {
                YearMonth m = monthOf(e);
                if (m != null && !m.equals(YearMonth.now())) {
                    rolledOver = true;
                    break;
                }
            }
            closeAll(open, rolledOver
                    ? "上月需量已结算归档（本月重新计算）"
                    : "需量已回落到合同范围内");
            log.info("需量告警已收口：{}（当前峰值 {} kW，合同 {} kW）",
                    rolledOver ? "上月结算归档" : "回落到合同范围内",
                    Math.round(peak), Math.round(cfg.getContractDemandKw()));
        }
    }

    /** 告警是哪个月的事（用触发时间判断） */
    private static YearMonth monthOf(AlarmEvent e) {
        if (e.getStartedAt() <= 0) {
            return null;
        }
        return YearMonth.from(java.time.Instant.ofEpochMilli(e.getStartedAt())
                .atZone(java.time.ZoneId.systemDefault()));
    }

    /**
     * 峰值越过合同线，就把挂着的告警就地升级成「已超合同」。
     * <p>
     * 升级要发通知（情况变严重了，人得知道），跟 {@code AlarmEngine.switchTier}
     * 一个道理：升档通知、降档不通知。
     */
    private void upgradeIfOverContract(List<AlarmEvent> open, DemandConfig cfg,
                                        double peak, double ratio) {
        if (ratio <= 1.0) {
            return;                         // 还没超合同，不用升级
        }
        for (AlarmEvent e : open) {
            if (!RULE_ID.equals(e.getRuleId()) || "CRITICAL".equals(e.getLevel())) {
                continue;                  // 不是需量告警，或已经是最严重那一档
            }
            e.setLevel("CRITICAL");
            e.setRuleName("需量超合同");
            e.setMessage(String.format("本月最大需量 %.0f kW，已超合同需量 %.0f kW（%.0f%%）。"
                            + "本月基本电费按 %.0f kW 计，超容部分有的地区按 2 倍加收。",
                    peak, cfg.getContractDemandKw(), (ratio - 1) * 100, peak));
            e.setValue(round1(peak));
            try {
                alarmRepo.save(e);
            } catch (Exception ex) {
                log.warn("需量告警升级写库失败: {}", ex.getMessage());
                continue;
            }
            AlarmRule forNotify = new AlarmRule();
            forNotify.setId(RULE_ID);
            forNotify.setName(e.getRuleName());
            forNotify.setNotify(true);
            if (notifier.fire(e, forNotify)) {
                e.setNotifyCount(e.getNotifyCount() + 1);
                e.setLastNotifyAt(System.currentTimeMillis());
                alarmRepo.save(e);
            }
            push(e);
            log.warn("需量告警升级：{}", e.getMessage());
        }
    }

    /**
     * 把挂着的需量告警收掉。
     * <p>关闭理由必须说真话：以前这些告警在重启时被 {@code AlarmEngine.restore()} 关掉，
     * 理由写的是「规则已删除或停用」——规则根本没被删过。追溯的时候客户看到这句会以为
     * 当时确实回落了，或者有人动过规则，都是假的。
     */
    private void closeAll(List<AlarmEvent> open, String reason) {
        if (open.isEmpty()) {
            return;
        }
        for (AlarmEvent e : open) {
            e.setState(AlarmEvent.STATE_CLEARED);
            e.setClearedAt(System.currentTimeMillis());
            e.setClearReason(reason);
            alarmRepo.save(e);
        }
        log.info("需量告警已解除 {} 条：{}", open.size(), reason);
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
