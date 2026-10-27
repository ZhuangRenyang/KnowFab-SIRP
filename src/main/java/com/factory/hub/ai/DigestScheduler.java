package com.factory.hub.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * 早报定时器：每分钟看一次"该不该发"，而不是死写一个 cron。
 * <p>
 * 为什么不用 {@code @Scheduled(cron="0 0 7 * * ?")}：
 * cron 是启动时固定死的，客户在页面上把时间改成 7:30 就得重启服务；
 * 而且重启错过 7:00 那一天就永久漏了。这里用「每分钟判断 + 记录最后运行日期」：
 * <ul>
 *   <li>改时间立即生效</li>
 *   <li>7:00 服务正好在重启，10:00 起来也会补发（补发的是昨天，日期不会错）</li>
 *   <li>同一天只发一次，重启不会重复轰炸</li>
 * </ul>
 */
@Component
public class DigestScheduler {

    private static final Logger log = LoggerFactory.getLogger(DigestScheduler.class);
    private static final ZoneId ZONE = ZoneId.systemDefault();

    private final AiConfigStore configStore;
    private final DigestService digestService;

    public DigestScheduler(AiConfigStore configStore, DigestService digestService) {
        this.configStore = configStore;
        this.digestService = digestService;
    }

    @Scheduled(fixedDelay = 60_000L)
    public void tick() {
        LocalDateTime now = LocalDateTime.now(ZONE);
        String today = now.toLocalDate().toString();
        if (today.equals(digestService.lastRun())) {
            return;                                  // 今天已经发过
        }

        AiConfig cfg = configStore.current();
        LocalTime due = LocalTime.of(Math.max(0, Math.min(23, cfg.getHour())),
                Math.max(0, Math.min(59, cfg.getMinute())));
        if (now.toLocalTime().isBefore(due)) {
            return;                                  // 还没到点
        }

        try {
            // 早上发的是「昨天」的总结，不是今天
            LocalDate target = now.toLocalDate().minusDays(1);
            var r = digestService.generate(target, cfg.isPushToWechat());
            digestService.markRun(now.toLocalDate());
            log.info("早报已生成（{}）：{} {}", target, r.get("source"),
                    Boolean.TRUE.equals(r.get("pushed")) ? "已推送" : "未推送");
        } catch (Exception e) {
            // 失败不 markRun：下一分钟会重试；连续失败也不会刷屏，因为 generate 内部已降级
            log.error("早报生成失败，下一分钟重试: {}", e.getMessage());
        }
    }
}
