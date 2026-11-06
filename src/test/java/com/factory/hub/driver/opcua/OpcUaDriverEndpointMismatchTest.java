package com.factory.hub.driver.opcua;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eclipse.milo.opcua.stack.core.UaException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.factory.hub.driver.DriverContext;
import com.factory.hub.driver.opcua.OpcUaDriver;

/**
 * 端点不匹配（服务端把 endpoint 挂在子路径下）的报错形态。
 * <p>
 * <b>守的是什么坑</b>：milo 抛的是 {@code Bad_TpcEndpointUrlInvalid} /
 * {@code "unrecognized endpoint url"}，最外层还包着 Netty 的 {@code DecoderException}
 * —— <b>长得像「网络不通」</b>，现场会去查网线、查防火墙、怀疑 PLC，
 * 而根因是「我们拼的端点 URL 和服务端 advertise 的不是同一个」。
 * {@link OpcUaEndpointErrors} 把它翻译成人话；这条用例守住那段翻译。
 * <p>
 * <b>为什么这条不能删</b>：{@code OpcUaEndpointUrl} 的路径校验只能拦住
 * 「用户主动在主机栏填了路径」；<b>拦不住「服务端有路径而用户不知道」</b> ——
 * 而后者恰恰是那段翻译的<b>唯一</b>触发方式。
 * 删掉这条用例 = 那段翻译没有回归保护，将来谁把它改坏了会静默退化成
 * 「又变成看着像网络不通的 DecoderException」，<b>而没有任何测试会红</b>。
 * <p>
 * 单独一个类：它要自己起一个<b>带子路径</b>的 server，与其它用例的 server 不同。
 */
class OpcUaDriverEndpointMismatchTest {

    @Test
    @DisplayName("主机栏干净但服务端带子路径时，报「端点不匹配」而不是网络不通（6b）")
    void reportsEndpointMismatchWhenServerHasSubPath() throws Exception {
        try (OpcUaTestServer subPathServer = OpcUaTestServer.start(0, "/subpath")) {
            DriverContext ctx = new DriverContext();
            ctx.setHost("127.0.0.1");           // 干净：客户不知道自己 server 有子路径
            ctx.setPort(subPathServer.port());
            ctx.setTimeoutMs(3000);

            OpcUaDriver d = new OpcUaDriver();
            try {
                IllegalStateException ex = assertThrows(IllegalStateException.class,
                        () -> d.connect(ctx),
                        "端点不匹配必须报成 IllegalStateException（人话），不能是 milo 的原始异常");
                assertTrue(ex.getMessage().contains("端点 URL 与服务端不匹配"),
                        "异常信息应说明是端点 URL 不匹配，实际：" + ex.getMessage());
                assertTrue(ex.getMessage().contains("不是网络不通"),
                        "异常信息必须点明「不是网络不通」，否则现场还是会去查网线，实际：" + ex.getMessage());
                assertNotNull(ex.getCause(),
                        "原始 milo 异常要保留在 cause 里（不能被翻译时丢掉根因）");
                assertTrue(ex.getCause() instanceof UaException,
                        "cause 应是 milo 的 UaException，实际：" + ex.getCause());
            } finally {
                d.disconnect();
            }
        }
    }
}
