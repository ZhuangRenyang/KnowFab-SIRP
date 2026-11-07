package com.factory.hub.driver;

import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;

/**
 * 「本机源地址」（{@code DriverContext.sourceAddress}）的公共处理。
 *
 * <p><b>它解决什么</b>：本机挂了多条<b>同网段</b>的物理链路时（两条网线各接一条产线、
 * 或一台 PLC 双网卡），源地址由路由表挑，可能挑错那条链路。
 * 指定了源地址，驱动就 bind 到它，精确从那条链路出去。
 *
 * <p><b>⚠️ 多网段本身不需要它</b>：本机缺某个网段的地址时，
 * 正确做法是<b>给本机加 IP 别名</b>（{@code tools/net/plc-on}），
 * 加完之后路由表自己会挑对源地址 ⇒ 这个字段仍然留空。
 * 本机 IP 的数量 = <b>网段数量</b>，不是设备数量。
 *
 * <p><b>⚠️ 支持程度因协议而异</b>：
 * <ul>
 *   <li>Modbus TCP / RTU-over-TCP：用 {@link Socket}，<b>可以</b> bind ⇒ 支持</li>
 *   <li>OPC UA：走 milo，其 {@code UaStackClientConfigBuilder} <b>没有</b>
 *       {@code setLocalAddress} ⇒ <b>无法</b>指定源地址</li>
 * </ul>
 * ⇒ 不支持的协议必须<b>明确报出来</b>，不能静默忽略
 * （静默忽略 = 用户填了、看着像生效了、实际没生效 ⇒ 排查成本极高）。
 */
public final class SourceAddress {

    private SourceAddress() {
    }

    /**
     * 把 socket 绑到指定的本机地址，然后才 connect。
     *
     * @param src 本机源地址；{@code null}/空白 = 不 bind，交给系统路由表决定
     * @throws Exception 源地址不存在 / 不属于本机时，抛出<b>说人话</b>的异常
     */
    public static void bind(Socket socket, String src) throws Exception {
        if (src == null || src.trim().isEmpty()) {
            return;
        }
        String addr = src.trim();
        InetAddress local;
        try {
            local = InetAddress.getByName(addr);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException(
                    "本机源地址填的是主机名而不是 IP（" + addr + "）：这里只能填本机自己的 IP，"
                    + "例如 192.168.10.100", e);
        }
        try {
            // 端口填 0 = 由系统分配；关键是「用哪个本机 IP」，端口无所谓
            socket.bind(new InetSocketAddress(local, 0));
        } catch (BindException e) {
            // ⚠️ 这是现场最容易看不懂的一个错：
            //   底层是 "Cannot assign requested address"，看起来像「网络不通」，
            //   实际是「**本机没有这个 IP**」⇒ 必须翻译，否则客户会去查网线。
            throw new IllegalArgumentException(
                    "本机没有这个 IP：" + addr + "（Cannot assign requested address）。"
                    + "请先在本机上配置该地址（例如用 tools/net/plc-on 给网卡加 IP 别名），"
                    + "或把「本机源地址」清空，改由系统路由表自动选择。", e);
        }
    }

    /**
     * 协议不支持指定源地址时调用。
     *
     * @param protocol 协议名（用于提示）
     * @param src      用户填的源地址
     * @throws IllegalArgumentException 总是抛 —— 让「填了却不生效」变成一个明确的错误
     */
    public static void rejectUnsupported(String protocol, String src) {
        if (src == null || src.trim().isEmpty()) {
            return;
        }
        throw new IllegalArgumentException(
                protocol + " 目前**不支持指定本机源地址**（你填了 " + src.trim() + "）。"
                + "原因：底层库（milo）的客户端配置没有暴露本地地址设置项。"
                + "本机源地址只对 Modbus 生效；OPC UA 请改用「给本机加 IP 别名」"
                + "（tools/net/plc-on）让路由表自己选源地址。");
    }
}
