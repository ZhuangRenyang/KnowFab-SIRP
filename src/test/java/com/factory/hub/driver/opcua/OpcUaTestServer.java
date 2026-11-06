package com.factory.hub.driver.opcua;

import java.net.ServerSocket;
import java.security.KeyPair;
import java.security.cert.X509Certificate;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.google.common.collect.ImmutableSet;

import org.eclipse.milo.opcua.sdk.server.OpcUaServer;
import org.eclipse.milo.opcua.sdk.server.api.config.OpcUaServerConfig;
import org.eclipse.milo.opcua.sdk.server.identity.AnonymousIdentityValidator;
import org.eclipse.milo.opcua.sdk.server.nodes.UaVariableNode;
import org.eclipse.milo.opcua.stack.core.security.DefaultCertificateManager;
import org.eclipse.milo.opcua.stack.core.security.SecurityPolicy;
import org.eclipse.milo.opcua.stack.core.transport.TransportProfile;
import org.eclipse.milo.opcua.stack.core.types.builtin.DateTime;
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText;
import org.eclipse.milo.opcua.stack.core.types.enumerated.MessageSecurityMode;
import org.eclipse.milo.opcua.stack.core.types.structured.BuildInfo;
import org.eclipse.milo.opcua.stack.core.util.SelfSignedCertificateBuilder;
import org.eclipse.milo.opcua.stack.core.util.SelfSignedCertificateGenerator;
import org.eclipse.milo.opcua.stack.server.EndpointConfiguration;

/**
 * 本地 OPC UA 测试服务器（Eclipse Milo sdk-server 0.6.11）。
 * <p>
 * <b>存在的理由</b>：真机是西门子 S7-200，不支持 OPC UA，没法用它验驱动。
 * 这是一个<b>真的 OPC UA Server</b>（同样的 milo 协议栈、同样的二进制 UASC 通道），
 * 用它可以证明 {@link OpcUaDriver} 在 Spring Boot 4.1.2 下能真正建会话、读到点位值。
 * <p>
 * <b>几个刻意对齐现场的选择</b>：
 * <ul>
 *   <li>只暴露 {@code SecurityPolicy.None} + 匿名 —— 与 {@code OpcUaDriver} 第 74-76 行的过滤一致；</li>
 *   <li>endpoint 路径留空 → URL 形如 {@code opc.tcp://127.0.0.1:12345}，
 *       <b>不带路径</b>，因为驱动拼的就是 {@code opc.tcp://host:port}；</li>
 *   <li>端口随机取空闲端口，避免和别的进程（含同机器上的其他测试）撞车。</li>
 * </ul>
 * <p>
 * <b>注意</b>：milo 0.6.11 的服务端启动时要用 JAXB 2 的
 * {@code javax.xml.bind.DatatypeConverter} 读内置节点表。
 * Boot 4 把 jaxb 换成了 jakarta 命名空间，所以本项目在 pom 里显式补了
 * {@code javax.xml.bind:jaxb-api}（runtime 作用域）—— 不补的话，
 * 本地 Server 起不来，客户的 ByteString 节点号（{@code ns=2;b=...}）也会炸。
 * 见 {@code OpcUaDriverBoot4ClasspathTest}。
 */
final class OpcUaTestServer implements AutoCloseable {

    /** 会被客户端读到、且值随时间递增的节点。用来证明「读到的是实时值，不是缓存」。 */
    static final String LIVE_TAG = "LiveTag";

    /**
     * 节点号是 ByteString 的那个节点的标识字节（base64 后是 {@code AAEC}）。
     * 点表里对应写法：{@code ns=2;b=AAEC}
     */
    static final byte[] BYTE_STRING_TAG_ID = new byte[]{0, 1, 2};

    /** 点表里那个 ByteString 节点号的字符串写法。 */
    static final String BYTE_STRING_TAG_NODE_ID = "ns=2;b=AAEC";

    private final OpcUaServer server;
    private final OpcUaTestNamespace namespace;
    private final ScheduledExecutorService scheduler;
    private final int port;
    private final UaVariableNode liveNode;

    private OpcUaTestServer(OpcUaServer server,
                             OpcUaTestNamespace namespace,
                             ScheduledExecutorService scheduler,
                             int port,
                             UaVariableNode liveNode) {
        this.server = server;
        this.namespace = namespace;
        this.scheduler = scheduler;
        this.port = port;
        this.liveNode = liveNode;
    }

    /**
     * 起一个本地 OPC UA Server，并挂好测试节点（端口随机取空闲端口）。
     *
     * @return 已启动的 server（调用方负责 close）
     */
    static OpcUaTestServer start() throws Exception {
        return start(0);
    }

    /**
     * 起一个本地 OPC UA Server，并挂好测试节点。
     *
     * @param port 监听端口；传 0 表示由系统分配一个空闲端口
     * @return 已启动的 server（调用方负责 close）
     */
    static OpcUaTestServer start(int port) throws Exception {
        if (port <= 0) {
            port = findFreePort();
        }
        KeyPair keyPair = SelfSignedCertificateGenerator.generateRsaKeyPair(2048);
        X509Certificate certificate = new SelfSignedCertificateBuilder(keyPair)
                .setCommonName("KnowFab OPC UA Test Server")
                .setOrganization("KnowFab")
                .setApplicationUri("urn:knowfab:test:opcua:server")
                .addIpAddress("127.0.0.1")
                .build();

        // 路径留空：驱动的 URL 是 opc.tcp://host:port，服务端必须正好是同一个 URL
        EndpointConfiguration endpoint = EndpointConfiguration.newBuilder()
                .setTransportProfile(TransportProfile.TCP_UASC_UABINARY)
                .setBindAddress("127.0.0.1")
                .setBindPort(port)
                .setHostname("127.0.0.1")
                .setPath("")
                .setCertificate(certificate)
                .setSecurityPolicy(SecurityPolicy.None)
                .setSecurityMode(MessageSecurityMode.None)
                .addTokenPolicy(OpcUaServerConfig.USER_TOKEN_POLICY_ANONYMOUS)
                .build();

        OpcUaServerConfig config = OpcUaServerConfig.builder()
                .setApplicationName(LocalizedText.english("KnowFab OPC UA Test Server"))
                .setApplicationUri("urn:knowfab:test:opcua:server")
                .setProductUri("urn:knowfab:test:opcua")
                .setCertificateManager(new DefaultCertificateManager(keyPair, certificate))
                .setIdentityValidator(new AnonymousIdentityValidator())
                .setBuildInfo(new BuildInfo("urn:knowfab:test:opcua", "KnowFab", "OPC UA Test Server",
                        "1.0.0", "1", DateTime.now()))
                .setEndpoints(ImmutableSet.of(endpoint))
                .build();

        OpcUaServer server = new OpcUaServer(config);
        server.startup().get();
        awaitEndpointAdvertised(server, port);

        OpcUaTestNamespace namespace = new OpcUaTestNamespace(server);
        namespace.startup();

        namespace.addDouble("TestTag1", 42.5d, true);
        namespace.addDouble("TestTag2", 7.25d, false);
        namespace.addInt32("TestTag3", 123, false);
        namespace.addBoolean("TestTag4", true, false);
        // 节点号是 ByteString 的节点（nodeId = ns=2;b=AAEC）：
        // milo 解析这类节点号要走 javax.xml.bind.DatatypeConverter.parseBase64Binary，
        // 而那个类在 Boot 4 的类路径上曾经整个消失 —— 有这个节点才能端到端验它。
        namespace.addByteString(BYTE_STRING_TAG_ID, 66.5d);
        UaVariableNode liveNode = namespace.addDouble(LIVE_TAG, 0d, false);

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "opcua-test-live-tag");
            t.setDaemon(true);
            return t;
        });
        AtomicLong liveCounter = new AtomicLong();
        scheduler.scheduleAtFixedRate(() -> {
            namespace.updateValue(liveNode, liveCounter.incrementAndGet());
        }, 100L, 100L, TimeUnit.MILLISECONDS);

        return new OpcUaTestServer(server, namespace, scheduler, port, liveNode);
    }

    /** 服务端监听端口。 */
    int port() {
        return port;
    }

    /** 服务端暴露的 endpoint URL，驱动拼的 URL 必须与它一致。 */
    String endpointUrl() {
        return "opc.tcp://127.0.0.1:" + port;
    }

    /** 命名空间索引，测试节点都挂在它下面（正常是 2）。 */
    int namespaceIndex() {
        return namespace.getNamespaceIndex().intValue();
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
        try {
            server.shutdown().get(10, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // 关闭失败不影响测试结论
        }
    }

    /**
     * 等端点真的被 advertise 出来再返回。
     * <p>
     * <b>为什么必须等</b>：milo 的端点描述是<b>懒加载</b>的 ——
     * {@code startup()} 返回时，端点可能还没 advertise 完，客户端立刻连会收到
     * {@code Bad_TcpEndpointUrlInvalid}（"unrecognized endpoint url: …/discovery"）。
     * 那是<b>竞态</b>，不是被测代码的问题，但它会让测试变成 flaky：
     * 偶发红 ⇒ 大家开始忽略这个测试 ⇒ 测试就废了。
     * <p>
     * 这里最多等 30 秒；真的等不到就明确抛异常（而不是让后面的用例莫名失败）。
     */
    private static void awaitEndpointAdvertised(OpcUaServer server, int port) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            boolean advertised = server.getEndpointDescriptions().stream()
                    .anyMatch(e -> e.getEndpointUrl() != null && e.getEndpointUrl().contains(":" + port));
            if (advertised) {
                return;
            }
            Thread.sleep(50L);
        }
        throw new IllegalStateException("30 秒内没有 advertise 出端点（端口 " + port + "），"
                + "服务端没真正就绪，测试不能继续 —— 否则会变成 flaky 的假红");
    }

    private static int findFreePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }

    /** 供启动器打印用。 */
    static Set<String> endpointsOf(OpcUaServer server) {
        return server.getEndpointDescriptions().stream()
                .map(e -> e.getEndpointUrl())
                .collect(java.util.stream.Collectors.toSet());
    }
}
