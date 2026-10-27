package com.factory.hub.alarm;

/**
 * 一个通知出口。可以是企业微信群机器人、Server酱、钉钉、飞书，或者任何能收
 * POST 地址。
 */
public class NotifyChannel {

    /** 企业微信群机器人。工厂首选：免费、无限量、管理员扫码建群 1 分钟 */
    public static final String WECOM_BOT = "WECOM_BOT";
    /** Server酱，能推到个人微信（第三方服务，有免费额度） */
    public static final String SERVERCHAN = "SERVERCHAN";
    /** PushPlus，也是推个人微信，额度比 Server酱宽松 */
    public static final String PUSHPLUS = "PUSHPLUS";
    public static final String DINGTALK = "DINGTALK";
    public static final String FEISHU = "FEISHU";
    /** 自定义地址：发标准 JSON，方便对接客户自己的平台或 MES */
    public static final String WEBHOOK = "WEBHOOK";
    /** 只记到日志文件，不往外发。调试期用 */
    public static final String LOG = "LOG";

    private String id;

    private String name;

    private String type = WECOM_BOT;

    /** 机器人 webhook 地址。Server酱填完整地址（含 SENDKEY） */
    private String url = "";

    /** PushPlus 专用 token。其他类型留空 */
    private String token = "";

    private boolean enabled = true;

    /** 只推这个级别及以上：MINOR < MAJOR < CRITICAL */
    private String minLevel = "MINOR";

    public NotifyChannel() {
    }

    public NotifyChannel(String id, String name, String type, String url) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.url = url;
    }

    /** 配置是否完整。不完整就不发，免得每次失败都刷日志 */
    public String validate() {
        if (LOG.equals(type)) {
            return null;
        }
        if (url == null || url.isBlank()) {
            return "缺少地址";
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return "地址必须 http:// 或 https:// 开头";
        }
        if (PUSHPLUS.equals(type) && (token == null || token.isBlank())) {
            return "PushPlus 需要填 token";
        }
        return null;
    }

    public String typeName() {
        return switch (type == null ? "" : type) {
            case WECOM_BOT -> "企业微信群机器人";
            case SERVERCHAN -> "Server酱（个人微信）";
            case PUSHPLUS -> "PushPlus（个人微信）";
            case DINGTALK -> "钉钉群机器人";
            case FEISHU -> "飞书群机器人";
            case WEBHOOK -> "自定义 Webhook";
            case LOG -> "仅记录日志";
            default -> type;
        };
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name == null || name.isBlank() ? typeName() : name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getUrl() {
        return url == null ? "" : url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getToken() {
        return token == null ? "" : token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getMinLevel() {
        return minLevel == null ? "MINOR" : minLevel;
    }

    public void setMinLevel(String minLevel) {
        this.minLevel = minLevel;
    }
}
