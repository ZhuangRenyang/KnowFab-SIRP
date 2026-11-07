package com.factory.hub.driver;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.Socket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「本机源地址」的行为测试。
 *
 * <p><b>为什么这些点必须钉住</b>：这个字段的价值全在「填错时的报错」上 ——
 * 填一个本机没有的 IP，底层抛的是 {@code Cannot assign requested address}，
 * 长得像「网络不通」，现场会去查网线、查 PLC，实际是**本机没这个地址**。
 * 所以断言不只要断「抛了」，还要断「**说的是人话**」。
 */
class SourceAddressTest {

    @Test
    @DisplayName("不填源地址 ⇒ 不 bind，交给系统路由表决定（绝大多数情况这样就对）")
    void blankSourceAddressDoesNotBind() throws Exception {
        Socket s = new Socket();
        try {
            SourceAddress.bind(s, null);
            SourceAddress.bind(s, "");
            SourceAddress.bind(s, "   ");
            // ⚠️ 断「未绑定」这个**契约**，别断 getLocalPort() 的具体数值 ——
            //   未 bind 且未 connect 时它返回 -1（实测踩过：断言写成 0 直接红），
            //   那是实现细节，跨 JDK 版本不保证。
            assertEquals("0.0.0.0", s.getLocalAddress().getHostAddress(),
                    "没填源地址就不该 bind —— 否则等于强行指定了一条链路，"
                    + "而多网段场景下正确做法是让路由表自己挑");
        } finally {
            s.close();
        }
    }

    @Test
    @DisplayName("填了本机真实拥有的地址 ⇒ bind 到它")
    void bindToLocalAddress() throws Exception {
        Socket s = new Socket();
        try {
            SourceAddress.bind(s, "127.0.0.1");
            assertEquals("127.0.0.1", s.getLocalAddress().getHostAddress(),
                    "bind 之后本地地址应当就是指定的那个");
        } finally {
            s.close();
        }
    }

    @Test
    @DisplayName("填了本机没有的 IP ⇒ 报错必须说「本机没有这个 IP」，而不是让它长得像网络不通")
    void bindToAbsentAddressGivesReadableError() throws Exception {
        // 192.0.2.0/24 是 RFC 5737 的文档保留网段，保证任何机器上都不会有这个地址
        Socket s = new Socket();
        try {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> SourceAddress.bind(s, "192.0.2.1"),
                    "本机没有这个地址时必须失败 —— 静默忽略会让人以为配生效了");
            String msg = e.getMessage();
            assertNotNull(msg, "异常必须有消息");
            assertTrue(msg.contains("本机没有这个 IP"),
                    "消息要点明「本机没有这个 IP」，否则现场会当成网络不通去查网线。实际：" + msg);
            assertTrue(msg.contains("tools/net/plc-on"),
                    "消息要给出下一步怎么做（给本机加 IP 别名），实际：" + msg);
        } finally {
            s.close();
        }
    }

    @Test
    @DisplayName("填了主机名 ⇒ 明确说「只能填 IP」")
    void hostNameIsRejected() throws Exception {
        Socket s = new Socket();
        try {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> SourceAddress.bind(s, "no such host.invalid!!"));
            assertTrue(e.getMessage().contains("只能填本机自己的 IP"),
                    "消息要点明只能填 IP，实际：" + e.getMessage());
        } finally {
            s.close();
        }
    }

    @Test
    @DisplayName("协议不支持源地址 ⇒ 明确报错，不静默忽略（静默忽略 = 用户以为生效了）")
    void unsupportedProtocolIsRejectedExplicitly() {
        // 没填 ⇒ 不该有任何意见
        SourceAddress.rejectUnsupported("OPC UA", null);
        SourceAddress.rejectUnsupported("OPC UA", "  ");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> SourceAddress.rejectUnsupported("OPC UA", "192.168.10.100"),
                "填了却不生效 ⇒ 必须报错");
        assertTrue(e.getMessage().contains("不支持指定本机源地址"),
                "消息要说清「不支持」，实际：" + e.getMessage());
        assertTrue(e.getMessage().contains("tools/net/plc-on"),
                "消息要给出替代做法（加 IP 别名），实际：" + e.getMessage());
    }
}
