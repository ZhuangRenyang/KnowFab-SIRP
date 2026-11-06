package com.factory.hub.driver.opcua;

import java.util.concurrent.CountDownLatch;

/**
 * 单独起一个本地 OPC UA Server 的启动器（给「生产运行时类路径探针」用）。
 * <p>
 * <b>为什么要拆成两个进程</b>：{@link OpcUaRuntimeProbe} 要在
 * <b>只有生产运行时依赖</b>的类路径上跑（没有 milo sdk-server、没有 javax.xml.bind），
 * 才能证明「驱动在真实交付环境里能用」。而 milo 的 Server 端启动<b>必须</b>有
 * {@code javax.xml.bind}，所以 Server 必须跑在另一个进程里。
 * <p>
 * 用法：{@code java -cp <测试类路径> com.factory.hub.driver.opcua.OpcUaServerLauncher 14840}
 */
public final class OpcUaServerLauncher {

    private OpcUaServerLauncher() {
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0].trim()) : 14840;

        OpcUaTestServer server = OpcUaTestServer.start(port);
        System.out.println("ENDPOINT=" + server.endpointUrl());
        System.out.println("NAMESPACE_INDEX=" + server.namespaceIndex());
        System.out.println("READY");
        System.out.flush();

        CountDownLatch forever = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.close();
            forever.countDown();
        }));
        forever.await();
    }
}
