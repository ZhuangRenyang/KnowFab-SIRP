package com.factory.hub.system;

/**
 * 一条可在后台修改的配置项定义。
 * <p>
 * 为什么不用 Spring 的 {@code @Value} 直接读：{@code @Value} 是启动时注入的常量，
 * 客户在页面上改完必须重启才生效——而车间服务器重启一次要停采集几十秒，
 * 没人愿意为改个"更新源"去停机。所以运行期可变的配置一律走 {@link SettingStore}。
 * <p>
 * 但也不是所有配置都该搬到页面：数据库地址、端口、授权公钥、授权开关这几类
 * 一旦被误改成服务起不来，或者被客户关掉就等于白送软件，它们只能留在 yml。
 * 页面上的"只读项"区会把这些列出来并说明原因。
 *
 * @param key     后台标识，也是存库时的后缀（{@code sys.<key>}）
 * @param group   页面上归到哪个分组
 * @param label   中文名
 * @param type    TEXT / AREA / BOOL / INT，决定页面用输入框、开关还是数字框
 * @param ymlKey  对应 application.yml 里的键，作为"出厂默认值"来源
 * @param fallback yml 里也没写时的兜底值
 * @param scope   LIVE = 保存即生效；RESTART = 下次启动生效（页面会提示）
 * @param desc    说明，直接显示给现场人员看
 * @param min     INT 类型的最小值（含）
 * @param max     INT 类型的最大值（含）
 */
public record SettingDef(String key,
                         String group,
                         String label,
                         String type,
                         String ymlKey,
                         String fallback,
                         String scope,
                         String desc,
                         int min,
                         int max) {

    public static final String TEXT = "TEXT";
    public static final String AREA = "AREA";
    public static final String BOOL = "BOOL";
    public static final String INT = "INT";

    public static final String LIVE = "LIVE";
    public static final String RESTART = "RESTART";

    public static SettingDef text(String key, String group, String label, String ymlKey,
                                  String fallback, String desc) {
        return new SettingDef(key, group, label, TEXT, ymlKey, fallback, LIVE, desc, 0, 0);
    }

    public static SettingDef area(String key, String group, String label, String ymlKey,
                                  String fallback, String desc) {
        return new SettingDef(key, group, label, AREA, ymlKey, fallback, LIVE, desc, 0, 0);
    }

    /** 开关。scope 决定是立即生效还是要重启 */
    public static SettingDef bool(String key, String group, String label, String ymlKey,
                                  String fallback, String scope, String desc) {
        return new SettingDef(key, group, label, BOOL, ymlKey, fallback, scope, desc, 0, 0);
    }

    public static SettingDef number(String key, String group, String label, String ymlKey,
                                    String fallback, int min, int max, String desc) {
        return new SettingDef(key, group, label, INT, ymlKey, fallback, LIVE, desc, min, max);
    }

    public boolean isRestartScoped() {
        return RESTART.equals(scope);
    }
}
