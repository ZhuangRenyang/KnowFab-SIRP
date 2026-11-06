package com.factory.hub.driver.opcua;

import java.util.List;
import java.util.Map;

import com.factory.hub.driver.DriverContext;
import com.factory.hub.driver.opcua.OpcUaDriver;
import com.factory.hub.model.PointDef;

/**
 * 「生产运行时类路径」探针：用 {@link OpcUaDriver} 连一个真的 OPC UA Server，
 * 把读到的值打印出来。
 * <p>
 * <b>关键点</b>：本类必须用<b>只有生产运行时依赖</b>的类路径运行
 * （即 {@code mvn dependency:build-classpath -Dmdep.includeScope=runtime} 的结果，
 * 不含 test 作用域的 milo sdk-server 与 javax.xml.bind）。
 * 只有这样，它才能回答那个真问题：
 * <b>「交付到客户现场的类路径上，OPC UA 驱动到底能不能跑？」</b>
 * <ul>
 *   <li>在测试类路径上跑（带 jaxb-api）只能证明「加上依赖后能跑」，证明不了交付环境；</li>
 *   <li>所以 Server 跑在另一个进程（{@link OpcUaServerLauncher}），
 *       本进程只带生产依赖去连 —— 这才是交付环境的真实形态。</li>
 * </ul>
 * 用法：{@code java -cp <生产运行时类路径> com.factory.hub.driver.opcua.OpcUaRuntimeProbe 14840}
 * 成功时退出码 0，失败时 1。
 */
public final class OpcUaRuntimeProbe {

    private OpcUaRuntimeProbe() {
    }

    /** 对照组模式：类路径上没有 javax.xml.bind，验证驱动的 Error 级防护。 */
    private static boolean EXPECT_MISSING_JAXB = false;

    /**
     * 「缺 javax.xml.bind」时的正确行为应该是：
     * <ol>
     *   <li>ByteString 节点号那个点被<b>跳过</b>，同批次的其它点<b>照常读到</b>
     *       —— 不是整批失败（这是加 LinkageError 防护的目的）；</li>
     *   <li>只读那个坏点时抛出的异常里<b>能看出是缺依赖</b>（含 javax.xml.bind 字样），
     *       而不是笼统的「nodeId 不合法」。</li>
     * </ol>
     */
    private static void runMissingJaxbScenario(OpcUaDriver driver,
                                                DriverContext ctx,
                                                PointDef tag1,
                                                PointDef bstr) throws Exception {
        // 1) 坏点只丢自己：其它点照常读到
        Map<String, Object> values = driver.read(ctx, List.of(tag1, bstr));
        System.out.println("PROBE mixedValues=" + values);
        if (!values.containsKey("tag1")) {
            System.out.println("PROBE FAIL 坏点把整批点位带累了（tag1 也没读到）：" + values);
            System.exit(1);
        }
        if (!(values.get("tag1") instanceof Number)) {
            System.out.println("PROBE FAIL tag1 的值不是数字：" + values.get("tag1"));
            System.exit(1);
        }
        if (values.containsKey("bstr")) {
            System.out.println("PROBE FAIL 缺 javax.xml.bind 时 ByteString 节点号竟然读到了："
                    + values.get("bstr"));
            System.exit(1);
        }
        // 注意：这里不断言 tag1 的具体数值 —— 上一步（正常模式）刚把 tag1 写成 88.25，
        // 值本身由正常模式那一步断言；这一步要证明的是「坏点没拖累好点」。

        // 2) 只读坏点：异常信息里要能看出是缺依赖
        try {
            driver.read(ctx, List.of(bstr));
            System.out.println("PROBE FAIL 只读坏点时竟然没抛异常");
            System.exit(1);
        } catch (Exception e) {
            System.out.println("PROBE badPointException=" + e.getMessage());
            if (e.getMessage() == null || !e.getMessage().contains("javax.xml.bind")) {
                System.out.println("PROBE FAIL 异常信息里看不出是缺 javax.xml.bind：" + e.getMessage());
                System.exit(1);
            }
        }

        // 3) 写路径同样要接住 LinkageError，并带上原始 nodeId
        try {
            driver.write(ctx, bstr, 1.0d);
            System.out.println("PROBE FAIL 写 ByteString 节点号时竟然没抛异常");
            System.exit(1);
        } catch (Exception e) {
            System.out.println("PROBE badWriteException=" + e.getMessage());
            if (e.getMessage() == null
                    || !e.getMessage().contains("javax.xml.bind")
                    || !e.getMessage().contains(OpcUaTestServer.BYTE_STRING_TAG_NODE_ID)) {
                System.out.println("PROBE FAIL 写路径的异常信息应含「javax.xml.bind」与原始 nodeId："
                        + e.getMessage());
                System.exit(1);
            }
        }

        System.out.println("PROBE OK (missing-jaxb 防护生效)");
        System.exit(0);
    }

    public static void main(String[] args) {
        int port = args.length > 0 ? Integer.parseInt(args[0].trim()) : 14840;
        for (String a : args) {
            if ("--expect-missing-jaxb".equals(a.trim())) {
                EXPECT_MISSING_JAXB = true;
            }
        }

        DriverContext ctx = new DriverContext();
        ctx.setHost("127.0.0.1");
        ctx.setPort(port);
        ctx.setCode("PROBE");
        ctx.setTimeoutMs(3000);

        PointDef tag1 = new PointDef();
        tag1.setKey("tag1");
        tag1.setNodeId("ns=2;s=TestTag1");
        tag1.setDataType("f64");

        PointDef tag2 = new PointDef();
        tag2.setKey("tag2");
        tag2.setNodeId("ns=2;s=TestTag2");
        tag2.setDataType("f64");

        // ByteString 节点号：milo 解析它要走 javax.xml.bind.DatatypeConverter.parseBase64Binary，
        // 而那个类在 Boot 4 的类路径上曾经整个消失 → 这条是「runtime 依赖到位了」的端到端证据
        PointDef bstr = new PointDef();
        bstr.setKey("bstr");
        bstr.setNodeId(OpcUaTestServer.BYTE_STRING_TAG_NODE_ID);
        bstr.setDataType("f64");

        OpcUaDriver driver = new OpcUaDriver();
        try {
            driver.connect(ctx);
            System.out.println("PROBE connected=" + driver.isConnected());

            if (EXPECT_MISSING_JAXB) {
                runMissingJaxbScenario(driver, ctx, tag1, bstr);
                return;
            }

            Map<String, Object> values = driver.read(ctx, List.of(tag1, tag2, bstr));
            System.out.println("PROBE values=" + values);

            double v1 = ((Number) values.get("tag1")).doubleValue();
            double v2 = ((Number) values.get("tag2")).doubleValue();
            double v3 = ((Number) values.get("bstr")).doubleValue();
            if (Math.abs(v1 - 42.5d) > 1e-9 || Math.abs(v2 - 7.25d) > 1e-9) {
                System.out.println("PROBE FAIL 值不对：tag1=" + v1 + " tag2=" + v2);
                System.exit(1);
            }
            if (Math.abs(v3 - 66.5d) > 1e-9) {
                System.out.println("PROBE FAIL ByteString 节点号没读到：" + v3
                        + "（多半是 javax.xml.bind 没进生产运行时类路径）");
                System.exit(1);
            }
            System.out.println("PROBE byteStringNodeId=" + v3);

            // 写入再读回，确认写通道也通
            driver.write(ctx, tag1, 88.25d);
            Double back = (Double) driver.read(ctx, List.of(tag1)).get("tag1");
            System.out.println("PROBE writeBack=" + back);
            if (back == null || Math.abs(back - 88.25d) > 1e-9) {
                System.out.println("PROBE FAIL 写回值不对：" + back);
                System.exit(1);
            }

            System.out.println("PROBE OK");
            System.exit(0);
        } catch (Throwable t) {
            System.out.println("PROBE FAIL " + t);
            t.printStackTrace(System.out);
            System.exit(1);
        } finally {
            driver.disconnect();
        }
    }
}
