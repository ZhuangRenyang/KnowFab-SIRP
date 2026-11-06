package com.factory.hub.driver.opcua;

import com.factory.hub.driver.DriverContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OPC UA 端点 URL 的拼装 + 主机地址校验。
 * <p>
 * <b>为什么单独拆一个类</b>：这段逻辑看起来只有几行，但它守的是一个
 * <b>现场极难定位</b>的坑 —— 客户把主机地址填歪了，报错却是
 * {@code Bad_TcpEndpointUrlInvalid}，长得像「网络不通」，实际是「地址填错了」。
 * 把它拆出来单独测：拼 URL 的逻辑一旦被改坏，测试能一眼看出是哪一步坏了，
 * 而不是只能靠「连不上」这种二值信号去猜。
 * <p>
 * <b>三种输入，三种处理</b>（不能一刀切地「消毒」，也不能一刀切地「报错」）：
 * <ol>
 *   <li><b>协议前缀</b>（{@code opc.tcp://}、{@code xxx://}）
 *       → <b>静默去掉</b>。这是常见无害写法（从别的组态软件复制过来的），
 *         报错只会让客户莫名其妙。debug 日志留痕。</li>
 *   <li><b>末尾路径</b>（{@code /UA}、{@code /milo}）
 *       → <b>必须报错</b>。OPC UA 端点带子路径是真实存在的形态（milo 原生客户端
 *         URL 带 path 时能连上），静默丢掉等于把客户指向<b>另一台</b>服务端。
 *         报错的修复方法在现场是确定的（改一个输入框）。</li>
 *   <li><b>主机栏里的端口</b>（{@code 192.168.1.10:4840}）
 *       → 与连接参数的端口<b>不一致就报错</b>。静默替换更危险：
 *         可能连到<b>另一台设备</b>（端口不同 ⇒ 不同的服务）。一致则去掉（debug）。</li>
 * </ol>
 * <p>
 * <b>为什么主机栏里的端口以连接参数为准</b>：端口在本系统里是<b>独立的一个配置项</b>，
 * 主机栏的语义是「主机」。两个地方都能填端口，客户一定会填错一处，
 * 与其猜，不如当场报错让他改对。
 * <p>
 * 消毒后为空 → 抛明确异常，绝不拼出 {@code opc.tcp://:4840} 这种废 URL。
 * <p>
 * <b>端点匹配规则</b>（哪部分会被归一、哪部分不会 —— 实测，2026-11-06）：
 * milo 0.6.x 客户端只尝试「我们给它的 URL」与「那个 URL + /discovery」两个入口，
 * 而服务端只认自己 advertise 出来的 endpointUrl。对比结果：
 * <ul>
 *   <li><b>尾斜杠</b>：<b>归一</b>。{@code opc.tcp://host:port} 能匹配 advertise 的
 *       {@code opc.tcp://host:port/}。</li>
 *   <li><b>主机名</b>：<b>归一</b>（会解析）。服务端 advertise {@code 127.0.0.1} 时，
 *       客户端连 {@code localhost} 照样连得上。</li>
 *   <li><b>路径</b>：<b>不归一</b>。服务端 advertise {@code opc.tcp://host:port/subpath} 时，
 *       客户端按 {@code opc.tcp://host:port} 连过去会被拒（Bad_TcpEndpointUrlInvalid）。</li>
 * </ul>
 * ⇒ 准确说法：<b>「你给客户端的 URL 的『路径部分』必须和服务端 advertise 的 endpointUrl
 * 的路径部分一致；主机部分和尾斜杠都会被归一。」</b>
 * ⇒ <b>端点错位的唯一现实来源就是「服务端把 endpoint 挂在子路径下」</b>。
 * <p>
 * <b>默认端口</b>：端口没填（{@code ctx} 里是 0）时用本协议的标准端口 4840。
 * 「默认端口是多少」是协议知识，所以由协议自己的驱动/URL 构造器定义，
 * 不写在实体、控制器这些协议无关的地方（那边只表达「未填」）。
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
     * @throws IllegalArgumentException 主机地址为空、带路径、或端口与连接参数不一致
     */
    static String build(DriverContext ctx) {
        String raw = ctx.getHost() == null ? "" : ctx.getHost().trim();
        int port = ctx.getPort();
        if (port <= 0) {
            // 防御性兜底：正常路径上游（DeviceService.contextOf）已经按协议解析好端口，
            // 走不到这里。走到这里 = 上游漏了解析 —— 所以要**打 warn 而不是静默**：
            // 静默的兜底比没有兜底更危险（看着有兜底、其实上游的洞被盖住了，没人知道）。
            log.warn("OPC UA 端口未指定（ctx.getPort()={}），已回落到本协议默认端口 {}。"
                    + "正常路径应由上游按协议解析好端口 —— 走到这里说明上游漏了解析，请检查设备配置链路",
                    ctx.getPort(), DEFAULT_PORT);
            port = DEFAULT_PORT;
        }

        // 1) 路径 —— 确定性错误，当场报错，不静默丢
        String authority = stripScheme(raw);
        int slash = authority.indexOf('/');
        if (slash >= 0) {
            throw new IllegalArgumentException(
                    "OPC UA 主机地址不能带路径（本次输入带了 \"" + authority.substring(slash)
                            + "\"）。本系统只支持 opc.tcp://<host>:<port> 形态的端点："
                            + "服务端若把 endpoint 挂在子路径下（如 /milo），"
                            + "客户端按 opc.tcp://<host>:<port> 连过去会被服务端拒绝"
                            + "（Bad_TcpEndpointUrlInvalid，看着像网络不通，其实是端点形态对不上）。"
                            + "请只填 IP 或主机名。原始输入：" + raw);
        }

        // 2) 主机栏里夹带的端口 —— 与连接参数不一致就报错（静默替换可能连到别的设备）
        String embeddedPort = splitHostPort(authority)[1];
        if (embeddedPort != null) {
            if (!embeddedPort.matches("\\d+")) {
                throw new IllegalArgumentException(
                        "OPC UA 主机栏里的端口 \"" + embeddedPort + "\" 不是数字。"
                                + "请只填 IP 或主机名，端口在连接参数里设。原始输入：" + raw);
            }
            if (!embeddedPort.equals(String.valueOf(port))) {
                throw new IllegalArgumentException(
                        "OPC UA 主机栏里的端口 " + embeddedPort + " 与连接参数的端口 " + port
                                + " 不一致。请只填 IP 或主机名，端口在连接参数里设（以连接参数为准）。"
                                + "原始输入：" + raw);
            }
        }

        String host = sanitizeHost(raw);
        if (host.isEmpty()) {
            throw new IllegalArgumentException(
                    "OPC UA 主机地址为空或无法识别（原始输入：" + raw + "），请填纯 IP 或主机名");
        }
        if (!raw.equals(host)) {
            log.debug("OPC UA 主机地址已规范化：{} -> {}（端口取连接参数里的 {}）", raw, host, port);
        }
        return "opc.tcp://" + host + ":" + port;
    }

    /**
     * 把用户填的主机地址洗成纯 IP / 主机名。
     * <p>
     * 只做「无害的规范化」：去协议前缀、去路径、去端口、给裸 IPv6 补方括号。
     * <b>路径与端口的歧义判断在 {@link #build} 里</b>（那里要报错，不能静默）。
     *
     * @param raw 用户原始输入，允许为 null
     * @return 清洗后的主机名；无法识别时返回空串
     */
    static String sanitizeHost(String raw) {
        String s = stripScheme(raw == null ? "" : raw.trim()).trim();
        int slash = s.indexOf('/');
        if (slash >= 0) {
            s = s.substring(0, slash);
        }
        s = s.trim();
        if (s.isEmpty()) {
            return "";
        }
        String host = splitHostPort(s)[0];
        if (host.isEmpty()) {
            return "";
        }
        // 裸 IPv6（多个冒号、没方括号）必须补方括号：
        // RFC 3986 要求 URL 里的 IPv6 字面量带方括号，否则 java.net.URI 的
        // getHost() 返回 null、getPort() 返回 -1（实测 opc.tcp://::1:4840 就是这样），
        // 后面任何按 host 解析的逻辑都会拿到 null。
        if (!host.startsWith("[") && host.indexOf(':') >= 0) {
            host = "[" + host + "]";
        }
        return host;
    }

    /** 去掉协议前缀：{@code opc.tcp://}、{@code opc.tcp:}、任意 {@code xxx://}。 */
    private static String stripScheme(String s) {
        int scheme = s.indexOf("://");
        if (scheme >= 0) {
            return s.substring(scheme + 3);
        }
        if (s.regionMatches(true, 0, "opc.tcp:", 0, "opc.tcp:".length())) {
            return s.substring("opc.tcp:".length());
        }
        return s;
    }

    /**
     * 把 authority 拆成「主机」与「端口」两部分。
     *
     * @return {主机, 端口}；端口为 null 表示没写端口
     */
    private static String[] splitHostPort(String authority) {
        String a = authority.trim();
        if (a.startsWith("[")) {
            // IPv6 字面量 [::1]:4840 —— 端口在 ']' 之后，不能按 ':' 切
            int close = a.indexOf(']');
            if (close < 0) {
                return new String[]{a, null};
            }
            String host = a.substring(0, close + 1);
            String rest = a.substring(close + 1);
            if (rest.startsWith(":") && rest.length() > 1) {
                return new String[]{host, rest.substring(1).trim()};
            }
            return new String[]{host, null};
        }
        int firstColon = a.indexOf(':');
        int lastColon = a.lastIndexOf(':');
        if (firstColon >= 0 && firstColon != lastColon) {
            // 多个冒号 ⇒ 裸 IPv6（本身没有端口可剥）
            return new String[]{a, null};
        }
        if (lastColon >= 0) {
            String host = a.substring(0, lastColon).trim();
            String port = a.substring(lastColon + 1).trim();
            return new String[]{host, port.isEmpty() ? null : port};
        }
        return new String[]{a, null};
    }
}
