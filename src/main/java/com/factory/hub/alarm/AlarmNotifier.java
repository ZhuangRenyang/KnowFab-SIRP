package com.factory.hub.alarm;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 告警通知发送。
 * <p>
 * <b>为什么必须异步：</b>评估线程每秒要扫几百台设备，HTTP 请求一个就要几百毫秒，
 * 网络不好时能卡 5 秒。同步发就等于让一台发不出去的手机拖死整个告警系统——
 * 其他设备的告警全被堵住，这比不报警更糟。
 * <p>
 * <b>为什么单线程：</b>通知是低频事件，一秒几十条已经算事故了。
 * 单线程顺序发，天然不会把客户的群机器人刷限流，也不会占太多连接。
 * <p>
 * <b>队列满了怎么办：</b>丢最新的并记日志。宁可漏一条通知，
 * 也不能让内存被撑爆或者把评估线程阻塞。
 */
@Component
public class AlarmNotifier {

    private static final Logger log = LoggerFactory.getLogger(AlarmNotifier.class);

    private static final DateTimeFormatter TF = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss");

    private final NotifyStore notifyStore;
    private final ObjectMapper mapper;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** 队列满时丢弃的条数。必须先于 pool 声明，否则 lambda 里引用会构成非法前向引用 */
    private final AtomicInteger dropped = new AtomicInteger();

    private final ThreadPoolExecutor pool = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(500),
            r -> {
                Thread t = new Thread(r, "alarm-notify");
                t.setDaemon(true);
                return t;
            },
            (r, executor) -> {
                // 丢一条通知比让队列无限增长好。日志限频，否则地址不通时
                // 每丢弃一条就打一行，几秒钟就能把日志文件刷满
                long n = dropped.incrementAndGet();
                if (n == 1 || n % 100 == 0) {
                    log.warn("通知队列已满（500），已丢弃 {} 条告警通知。"
                            + "检查通知地址是否不通、或者告警规则是不是配得太敏感", n);
                }
            });

    public AlarmNotifier(NotifyStore notifyStore, ObjectMapper mapper) {
        this.notifyStore = notifyStore;
        this.mapper = mapper;
    }

    @PreDestroy
    public void shutdown() {
        pool.shutdown();
    }

    /* ==================== 对外入口 ==================== */

    /** 未恢复的告警多久重复提醒一次（分钟），0 = 不重复 */
    public int repeatMinutes() {
        return notifyStore.current().getRepeatMinutes();
    }

    /**
     * 新告警产生。
     *
     * @return 是否真的投递出去了（至少有一个渠道收下）。
     *         返回 false 的含义是「没发」，调用方不许写 notifyCount——
     *         客户看到的「已通知 1 次」必须是真发生过的事，
     *         否则事后对账时没人解释了「为什么我没收到微信」。
     */
    public boolean fire(AlarmEvent e, AlarmRule rule) {
        NotifyConfig cfg = notifyStore.current();
        if (!cfg.isEnabled() || (rule != null && !rule.isNotify())) {
            return false;
        }
        if (suppressByQuietHours(cfg, e.getLevel())) {
            log.info("免打扰时段，{} 级告警「{}」不发通知", e.getLevel(), e.getMessage());
            return false;
        }
        String title = "[" + levelText(e.getLevel()) + "] " + e.getRuleName();
        String content = markdown(e, false);
        return enqueue(cfg, title, content, e.getLevel(), e);
    }

    /** 告警恢复。返回是否真的投递出去 */
    public boolean recover(AlarmEvent e) {
        NotifyConfig cfg = notifyStore.current();
        if (!cfg.isEnabled()) {
            return false;
        }
        if (suppressByQuietHours(cfg, "MINOR")) {
            return false;                 // 恢复消息优先级最低，免打扰时段直接压住
        }
        String title = "[已恢复] " + e.getRuleName();
        String content = markdown(e, true);
        return enqueue(cfg, title, content, "MINOR", e);
    }

    /**
     * 合并通知。用于告警风暴：交换机一挂全厂离线，
     * 三百条通知发出去会把群淹没，也没人看得出问题在哪。
     * 这时候发一条「12 台设备同时离线，可能是网络问题」，比 300 条有用得多。
     */
    public boolean digest(List<AlarmEvent> events, String reason) {
        NotifyConfig cfg = notifyStore.current();
        if (!cfg.isEnabled() || events.isEmpty()) {
            return false;
        }
        if (suppressByQuietHours(cfg, "CRITICAL")) {
            return false;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("### 批量告警：").append(events.size()).append(" 台设备\n");
        sb.append("> ").append(reason).append("\n\n");
        int n = 0;
        for (AlarmEvent e : events) {
            if (n++ >= 15) {
                sb.append("> … 还有 ").append(events.size() - 15).append(" 台\n");
                break;
            }
            sb.append("> - **").append(e.getDeviceName()).append("**（").append(e.getDeviceCode())
                    .append("）").append(e.getMessage()).append("\n");
        }
        sb.append("\n时间：").append(now());
        if (events.size() >= 5) {
            sb.append("\n\n这么多设备同时出问题，先查交换机和网关电源，别一台台去现场看。");
        }
        return enqueue(cfg, "[批量告警] " + events.size() + " 台设备", sb.toString(),
                "CRITICAL", events.get(0));
    }
    /**
     * 测试发送。客户配完地址必须能点一下看到效果，
     * 否则永远不知道是「没告警」还是「配置错了收不到」。
     */
    public Map<String, Object> test(NotifyChannel ch) {
        Map<String, Object> out = new LinkedHashMap<>();
        String bad = ch.validate();
        if (bad != null) {
            out.put("ok", false);
            out.put("message", bad);
            return out;
        }
        String content = "### 这是一条测试消息\n"
                + "> 收到这条说明告警通知配置正确。\n"
                + "> 以后设备离线、停机超时、数值越限会推到这里。\n\n"
                + "时间：" + now();
        try {
            String err = send(ch, "设备监控 - 告警通知测试", content);
            if (err == null) {
                out.put("ok", true);
                out.put("message", "已发送，去群里看看有没有收到");
            } else {
                out.put("ok", false);
                out.put("message", err);
            }
        } catch (Exception e) {
            out.put("ok", false);
            out.put("message", "发送失败：" + e.getMessage());
        }
        return out;
    }

    /**
     * 定时简报（早报）推送。
     * <p>
     * 和告警通知的区别：<b>不受免打扰时段限制</b>。
     * 免打扰是为了防止半夜被报警铃吵醒，而早报本来就是固定 7 点发的东西，
     * 被免打扰压住等于这个功能不存在。
     * 仍然尊重"通知总开关"和渠道的最小级别。
     */
    public boolean sendBulletin(String title, String content) {
        NotifyConfig cfg = notifyStore.current();
        if (!cfg.isEnabled()) {
            return false;
        }
        return enqueue(cfg, title, content, "MINOR", null);
    }

    /* ==================== 内部 ==================== */

    /** 返回 true 表示至少有一个渠道收下了这条消息 */
    private boolean enqueue(NotifyConfig cfg, String title, String content,
                            String level, AlarmEvent event) {
        List<NotifyChannel> targets = new ArrayList<>();
        for (NotifyChannel ch : cfg.getChannels()) {
            if (!ch.isEnabled()) {
                continue;
            }
            if (levelRank(level) < levelRank(ch.getMinLevel())) {
                continue;                 // 这个渠道只收更严重的
            }
            if (ch.validate() != null) {
                continue;                 // 配置不全，跳过（启动时已经警告过）
            }
            targets.add(ch);
        }
        if (targets.isEmpty()) {
            return false;
        }
        pool.execute(() -> {
            for (NotifyChannel ch : targets) {
                String err = sendWithRetry(ch, title, content);
                if (err != null) {
                    log.warn("告警通知发送失败（{}）：{}", ch.getName(), err);
                }
            }
        });
        return true;
    }

    private String sendWithRetry(NotifyChannel ch, String title, String content) {
        String err = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                err = send(ch, title, content);
                if (err == null) {
                    return null;
                }
            } catch (Exception e) {
                err = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
            if (attempt == 1) {
                try {
                    Thread.sleep(1200);   // 网络抖动，等一下再来一次，不要死循环重试
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return err;
                }
            }
        }
        return err;
    }

    /** 返回 null 表示成功，否则返回失败原因 */
    private String send(NotifyChannel ch, String title, String content) throws Exception {
        if (NotifyChannel.LOG.equals(ch.getType())) {
            log.info("[告警通知/日志渠道] {}\n{}", title, content);
            return null;
        }

        String url = ch.getUrl().trim();
        String body = switch (ch.getType()) {
            case NotifyChannel.WECOM_BOT -> json(Map.of(
                    "msgtype", "markdown",
                    "markdown", Map.of("content", content)));
            case NotifyChannel.DINGTALK -> json(Map.of(
                    "msgtype", "markdown",
                    "markdown", Map.of("title", title, "text", content)));
            case NotifyChannel.FEISHU -> json(Map.of(
                    "msgtype", "text",
                    "content", Map.of("text", title + "\n" + stripMarkdown(content))));
            case NotifyChannel.SERVERCHAN -> json(Map.of(
                    "title", title,
                    "desp", content));
            case NotifyChannel.PUSHPLUS -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("token", ch.getToken());
                m.put("title", title);
                m.put("content", content);
                m.put("template", "markdown");
                yield json(m);
            }
            default -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("title", title);
                m.put("content", content);
                m.put("time", now());
                yield json(m);
            }
        };

        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(6))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            return "HTTP " + resp.statusCode() + " " + brief(resp.body());
        }
        // 这里必须查返回值：企业微信、钉钉配错了地址也可能返回 200，
        // 真正的错误在 body 的 errcode 里。不查的话客户一直以为发出去了。
        return checkBody(ch.getType(), resp.body());
    }

    private String checkBody(String type, String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            Map<?, ?> m = mapper.readValue(body, Map.class);
            Object code = m.containsKey("errcode") ? m.get("errcode") : m.get("code");
            if (code instanceof Number n && n.intValue() != 0) {
                Object msg = m.containsKey("errmsg") ? m.get("errmsg") : m.get("msg");
                return "接口返回错误 " + n.intValue() + "：" + msg
                        + hint(n.intValue());
            }
        } catch (Exception ignored) {
            // 返回的不是 JSON（比如 Server酱 成功时返回一些文本），忽略
        }
        return null;
    }

    /** 把常见错误码翻译成人话，客户自己就能改好，不用打电话问 */
    private String hint(int code) {
        return switch (code) {
            case 93000 -> "（机器人地址无效，检查 key 是否复制完整）";
            case 45009 -> "（发送太频繁被限流了，把重复提醒间隔调大）";
            case 40001 -> "（token 无效）";
            case 310000 -> "（钉钉机器人安全设置不匹配，检查加签或 IP 白名单）";
            default -> "";
        };
    }

    private String brief(String s) {
        if (s == null) {
            return "";
        }
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() > 120 ? t.substring(0, 120) + "…" : t;
    }

    private String json(Object o) throws Exception {
        return mapper.writeValueAsString(o);
    }

    /** 飞书 text 不认 markdown，去掉标记免得看到一堆 # 和 > */
    private String stripMarkdown(String s) {
        return s.replaceAll("(?m)^#{1,6}\\s*", "")
                .replaceAll("(?m)^>\\s?", "")
                .replace("**", "");
    }

    private boolean suppressByQuietHours(NotifyConfig cfg, String level) {
        String q = cfg.getQuietHours();
        if (q == null || q.isBlank()) {
            return false;
        }
        // 免打扰时段只压住非严重告警。停机、报警这类真事故该叫醒还得叫醒
        if (levelRank(level) >= levelRank("CRITICAL")) {
            return false;
        }
        try {
            String[] p = q.split("-");
            LocalTime start = LocalTime.parse(p[0].trim());
            LocalTime end = LocalTime.parse(p[1].trim());
            LocalTime now = LocalTime.now();
            boolean in = start.isAfter(end)
                    ? (!now.isBefore(start) || now.isBefore(end))
                    : (!now.isBefore(start) && now.isBefore(end));
            return in;
        } catch (Exception e) {
            return false;                 // 配置写错了就当没配，别因此漏掉告警
        }
    }

    private String markdown(AlarmEvent e, boolean recover) {
        StringBuilder sb = new StringBuilder();
        String color = switch (e.getLevel()) {
            case "CRITICAL" -> "warning";
            case "MAJOR" -> "warning";
            default -> "comment";
        };
        sb.append("### <font color=\"").append(color).append("\">")
                .append(recover ? "已恢复" : levelText(e.getLevel()))
                .append("</font> ")
                .append(escape(e.getRuleName())).append("\n");

        sb.append("> 设备：**").append(escape(e.getDeviceName())).append("**（")
                .append(escape(e.getDeviceCode())).append("）\n");
        if (e.getLocation() != null && !e.getLocation().isBlank()) {
            sb.append("> 位置：").append(escape(e.getLocation())).append("\n");
        }
        if (e.getMessage() != null && !e.getMessage().isBlank()) {
            sb.append("> 详情：").append(escape(e.getMessage())).append("\n");
        }
        sb.append("> 触发：").append(fmtTime(e.getStartedAt())).append("\n");
        if (recover) {
            sb.append("> 恢复：").append(fmtTime(e.getClearedAt()))
                    .append("（持续 ").append(fmtDuration(e.durationMs())).append("）\n");
        } else {
            sb.append("> 已经持续：").append(fmtDuration(e.durationMs())).append("\n");
        }
        if (e.getRuleDesc() != null && !e.getRuleDesc().isBlank()) {
            sb.append("> 判定条件：").append(escape(e.getRuleDesc())).append("\n");
        }
        sb.append("\n<font color=\"comment\">告警编号 #").append(e.getId())
                .append(" · 到监控页面可以确认处理</font>");
        return sb.toString();
    }

    /** 企业微信 markdown 里 & < > 要转义，设备名带这些符号会显示成乱码 */
    private String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    public static int levelRank(String level) {
        return switch (level == null ? "" : level) {
            case "CRITICAL" -> 3;
            case "MAJOR" -> 2;
            case "MINOR" -> 1;
            default -> 0;
        };
    }

    public static String levelText(String level) {
        return switch (level == null ? "" : level) {
            case "CRITICAL" -> "严重";
            case "MAJOR" -> "重要";
            case "MINOR" -> "提示";
            default -> "告警";
        };
    }

    /** 中文时长，「12 分 30 秒」比「750000 毫秒」好读得多 */
    public static String fmtDuration(long ms) {
        long s = Math.max(0, ms / 1000);
        if (s < 60) {
            return s + " 秒";
        }
        long m = s / 60;
        if (m < 60) {
            long r = s % 60;
            return r == 0 ? m + " 分钟" : m + " 分 " + r + " 秒";
        }
        long h = m / 60;
        long rm = m % 60;
        if (h < 24) {
            return rm == 0 ? h + " 小时" : h + " 小时 " + rm + " 分";
        }
        long d = h / 24;
        return d + " 天 " + (h % 24) + " 小时";
    }

    private String fmtTime(long ts) {
        if (ts <= 0) {
            return "--";
        }
        return TF.format(Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()));
    }

    private String now() {
        return TF.format(Instant.now().atZone(ZoneId.systemDefault()));
    }
}
