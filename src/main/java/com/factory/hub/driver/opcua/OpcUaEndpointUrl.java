package com.factory.hub.driver.opcua;

import com.factory.hub.driver.DriverContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OPC UA 端点 URL 的拼装 + 主机地址消毒。
 * <p>
 * <b>为什么单独拆一个类</b>：这段逻辑看起来只有两行，但它守的是一个
 * <b>现场极难定位</b>的坑 —— 客户把主机地址填歪了，报错却是
 * {@code Bad_TcpEndpointUrlInvalid}，长得像「网络不通」，实际是「地址填错了」。
 * 把它拆出来单独测：拼 URL 的逻辑一旦被改坏，测试能一眼看出是哪一步坏了，
 * 而不是只能靠「连不上」这种二值信号去猜。
 * <p>
 * <b>为什么必须消毒</b>：{@code DeviceEntity.host} 是前端输入框，客户会随手填成
 * {@code 192.168.1.10/UA}、{@code 192.168.1.10:4840}、{@code opc.tcp://192.168.1.10}。
 * 直接拼接会得到 {@code opc.tcp://192.168.1.10/UA:4840} 这种非法 URL。
 * <p>
 * <b>消毒规则</b>（只保留纯 IP / 主机名）：
 * <ol>
 *   <li>去掉协议前缀：{@code opc.tcp://}、{@code opc.tcp:}、以及任意 {@code xxx://}；</li>
 *   <li>去掉末尾路径：第一个 {@code /} 之后全部丢掉；</li>
 *   <li>去掉已有端口：{@code host:port} 里的 port 丢弃，
 *       <b>端口统一取连接参数里的值</b>（OPC UA 端口是单独一个配置项，
 *       客户在主机里填的端口会被丢掉，日志里会写清楚这件事）。</li>
 * </ol>
 * 消毒后为空 → 抛明确异常，绝不拼出 {@code opc.tcp://:4840} 这种废 URL。
 */
final class OpcUaEndpointUrl {

    private static final Logger log = LoggerFactory.getLogger(OpcUaEndpointUrl.class);

    /** OPC UA 标准端口。连接参数里端口没填或填了非正数时用它。 */
    static final int DEFAULT_PORT = 4840;

    private OpcUaEndpointUrl() {
    }

    /**
     * 拼 OPC UA 端点 URL。
     *
     * @param ctx 连接参数
     * @return 形如 {@code opc.tcp://192.168.1.10:4840} 的端点 URL
     * @throws IllegalArgumentException 主机地址为空或无法识别
     */
    static String build(DriverContext ctx) {
        String raw = ctx.getHost() == null ? "" : ctx.getHost().trim();
        String host = sanitizeHost(raw);
        int port = ctx.getPort() <= 0 ? DEFAULT_PORT : ctx.getPort();
        if (host.isEmpty()) {
            throw new IllegalArgumentException(
                    "OPC UA 主机地址为空或无法识别（原始输入：" + raw + "），请填纯 IP 或主机名");
        }
        if (!raw.equals(host)) {
            log.debug("OPC UA 主机地址已消毒：{} -> {}（端口取连接参数里的 {}）", raw, host, port);
        }
        return "opc.tcp://" + host + ":" + port;
    }

    /**
     * 把用户填的主机地址洗成纯 IP / 主机名。
     *
     * @param raw 用户原始输入，允许为 null
     * @return 清洗后的主机名；无法识别时返回空串
     */
    static String sanitizeHost(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty()) {
            return "";
        }
        // 1) 去掉协议前缀
        int scheme = s.indexOf("://");
        if (scheme >= 0) {
            s = s.substring(scheme + 3);
        } else if (s.regionMatches(true, 0, "opc.tcp:", 0, "opc.tcp:".length())) {
            s = s.substring("opc.tcp:".length());
        }
        // 2) 去掉路径
        int slash = s.indexOf('/');
        if (slash >= 0) {
            s = s.substring(0, slash);
        }
        s = s.trim();
        if (s.isEmpty()) {
            return "";
        }
        // 3) 去掉端口
        //    IPv6 字面量 [::1]:4840：端口在 ']' 之后，不能按 ':' 切
        if (s.startsWith("[")) {
            int close = s.indexOf(']');
            return close < 0 ? "" : s.substring(0, close + 1);
        }
        int firstColon = s.indexOf(':');
        int lastColon = s.lastIndexOf(':');
        if (firstColon >= 0 && firstColon != lastColon) {
            // 多个冒号 ⇒ 裸 IPv6（没有端口可剥），原样保留
            return s;
        }
        if (lastColon >= 0) {
            s = s.substring(0, lastColon).trim();
        }
        return s;
    }
}
