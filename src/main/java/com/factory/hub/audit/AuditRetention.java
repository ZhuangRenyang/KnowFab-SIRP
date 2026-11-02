package com.factory.hub.audit;

import com.factory.hub.system.SettingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 审计日志保留策略（DH-24）。
 * <p>
 * 审计表只增不改不删——这是"留痕"的前提：客户月底发现电费和上个月差很多，
 * 能一眼看到「10-28 有人把峰电价从 1.1 改成 1.3」，而不是互相怀疑。
 * <p>
 * 但只增不减也不能无限存：装在客户机柜里跑三年的设备，审计行数会到几十万，
 * 备份体积和查询延迟都上来。所以超期自动清理，默认 180 天。
 * <p>
 * 保留天数在「系统 → 审计」里可改（{@code audit.retain-days}）。
 * 要追溯的改动必须在这之前查清楚——清理掉就真的没有了。
 */
@Component
public class AuditRetention {

    private static final Logger log = LoggerFactory.getLogger(AuditRetention.class);

    private final AuditLogRepository repo;
    private final SettingStore settings;

    public AuditRetention(AuditLogRepository repo, SettingStore settings) {
        this.repo = repo;
        this.settings = settings;
    }

    /**
     * 每天凌晨 3:17 清一次。
     * <p>
     * 挑 3:17 是为了避开别的定时任务：AI 对话留痕清理在 3:40，
     * 备份一般也在 3 点附近，错开半小时。
     * <p>
     * cron 做成配置项（{@code audit.cleanup-cron}）：默认凌晨 3:17，
     * 客户机柜里跑久了想挪到别的空档期，或者交付测试时想让它跑快一点，
     * 改配置就行，不用改代码重新打包。
     */
    @Scheduled(cron = "${audit.cleanup-cron:0 17 3 * * ?}")
    public void purgeExpired() {
        try {
            int days = settings.getInt(SettingStore.AUDIT_RETAIN_DAYS);
            if (days <= 0) {
                return;
            }
            long before = System.currentTimeMillis() - days * 24L * 3_600_000L;
            int n = repo.deleteOlderThan(before);
            if (n > 0) {
                log.info("审计日志清理：删除 {} 条超过 {} 天的记录", n, days);
            }
        } catch (Exception e) {
            // 清理失败不影响业务——审计少几条远好过系统起不来
            log.warn("审计日志清理失败（不影响业务）：{}", e.getMessage());
        }
    }
}
