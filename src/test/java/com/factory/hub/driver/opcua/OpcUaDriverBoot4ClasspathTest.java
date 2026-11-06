package com.factory.hub.driver.opcua;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;

/**
 * OPC UA 驱动在 <b>Spring Boot 4.1.2</b> 运行时类路径上的「能不能活」体检。
 * <p>
 * <b>守的是什么坑</b>：Boot 3 → 4 把 Jackson 2 换成了 Jackson 3
 * （{@code com.fasterxml.jackson} → {@code tools.jackson}），
 * 又把 JAXB 统一到 jakarta 命名空间。milo 0.6.11 是 Boot 3 时代的老库，
 * <b>只要它内部还在用被换掉的那套包，驱动就会在运行时炸 NoClassDefFoundError</b>。
 * 升级后必须把这件事查清楚，而不是「编译通过就没事」。
 * <p>
 * <b>逐条对应升级实测里已知的两个大风险</b>：
 * <ul>
 *   <li><b>Jackson</b>：milo 一个 Jackson 依赖都没有 → 风险不成立（本类第 2 条钉死）。</li>
 *   <li><b>JAXB / javax.xml.bind</b>：milo 的 {@code NodeId}、{@code ExpandedNodeId}
 *       硬引用了 {@code javax.xml.bind.DatatypeConverter}（JAXB 2），
 *       而 Boot 4 把 jaxb 换成 jakarta → <b>生产运行时类路径上这个类整个消失</b>。
 *       本类第 3、4 条把「哪种 nodeId 会炸、哪种不会」钉死。</li>
 * </ul>
 */
class OpcUaDriverBoot4ClasspathTest {

    @Test
    @DisplayName("驱动依赖的 milo 核心类在 Boot 4 类路径上都能加载（不是编译通过，是真的能加载）")
    void miloClientApiIsLoadable() throws Exception {
        String[] classes = {
                "org.eclipse.milo.opcua.sdk.client.OpcUaClient",
                "org.eclipse.milo.opcua.sdk.client.api.config.OpcUaClientConfig",
                "org.eclipse.milo.opcua.sdk.client.api.config.OpcUaClientConfigBuilder",
                "org.eclipse.milo.opcua.stack.core.AttributeId",
                "org.eclipse.milo.opcua.stack.core.security.SecurityPolicy",
                "org.eclipse.milo.opcua.stack.core.types.builtin.DataValue",
                "org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText",
                "org.eclipse.milo.opcua.stack.core.types.builtin.NodeId",
                "org.eclipse.milo.opcua.stack.core.types.builtin.QualifiedName",
                "org.eclipse.milo.opcua.stack.core.types.builtin.StatusCode",
                "org.eclipse.milo.opcua.stack.core.types.builtin.Variant",
                "org.eclipse.milo.opcua.stack.core.types.enumerated.TimestampsToReturn",
                "org.eclipse.milo.opcua.stack.core.types.structured.EndpointDescription",
                "org.eclipse.milo.opcua.stack.core.types.structured.ReadValueId",
        };
        for (String name : classes) {
            Class<?> c = Class.forName(name);
            assertNotNull(c, name + " 应能加载");
        }
    }

    @Test
    @DisplayName("milo 0.6.11 完全不依赖 Jackson 2（Boot 4 的 Jackson 3 替换对它无影响）")
    void miloDoesNotDependOnJackson2() throws Exception {
        // Boot 4 用的是 Jackson 3；Jackson 2 已经不在类路径上
        assertNotNull(Class.forName("tools.jackson.databind.ObjectMapper"),
                "Boot 4 应带 Jackson 3（tools.jackson）");
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("com.fasterxml.jackson.databind.ObjectMapper"),
                "Boot 4 类路径上不应再有 Jackson 2（com.fasterxml.jackson.databind）");

        // 更硬的一条：直接扫 milo 的 jar，确认字节码里一次都没提过 com/fasterxml/jackson
        List<String> offenders = miloJarsReferencing("com/fasterxml/jackson");
        assertTrue(offenders.isEmpty(),
                "milo 的类里出现了 Jackson 2 引用，Boot 4 下会 NoClassDefFoundError：" + offenders);
    }

    @Test
    @DisplayName("Boot 4 只带 jakarta 版 JAXB；javax 版是本项目在 test 作用域补的（生产运行时没有）")
    void javaxXmlBindIsGoneFromRuntimeClasspath() throws Exception {
        // Boot 4 带来的 jakarta 版
        assertNotNull(Class.forName("jakarta.xml.bind.DatatypeConverter"),
                "Boot 4 应带 jakarta 版 JAXB");

        // javax 版：测试类路径上有 —— 是 pom 里 test 作用域补的，
        // 「生产运行时没有」这件事由 mvn dependency:tree 证明（整棵树里 0 个 javax.*）
        assertNotNull(Class.forName("javax.xml.bind.DatatypeConverter"),
                "test 作用域补的 jaxb-api 应可用（milo 服务端启动要它；"
                        + "若这条红了，多半是 pom 里那个 test 依赖被删了，server 会起不来）");

        // 两个命名空间是两个不同的类，不能互相替代
        assertNotSame(Class.forName("jakarta.xml.bind.DatatypeConverter"),
                Class.forName("javax.xml.bind.DatatypeConverter"),
                "jakarta 版与 javax 版是两个不同的类，milo 只会认 javax 版");
    }

    @Test
    @DisplayName("字符串节点号（ns=2;s=xxx）在没有 javax.xml.bind 的类路径上照样能解析")
    void stringNodeIdParsesWithoutJavaxXmlBind() throws Exception {
        try (URLClassLoader noJaxb = classLoaderWithoutJaxb()) {
            Class<?> nodeIdClass = noJaxb.loadClass(
                    "org.eclipse.milo.opcua.stack.core.types.builtin.NodeId");
            Method parse = nodeIdClass.getMethod("parse", String.class);

            Object parsed = parse.invoke(null, "ns=2;s=TestTag1");
            assertNotNull(parsed, "字符串节点号不该依赖 javax.xml.bind");
            assertTrue(parsed.toString().contains("ns=2"), "解析结果应是 ns=2 的节点，实际：" + parsed);

            // 驱动本体也要能在没有 javax.xml.bind 的类路径上加载并完成类初始化
            Class<?> driverClass = noJaxb.loadClass("com.factory.hub.driver.opcua.OpcUaDriver");
            assertNotNull(driverClass.getDeclaredConstructor().newInstance(),
                    "OpcUaDriver 应能在没有 javax.xml.bind 的类路径上实例化");
        }
    }

    @Test
    @DisplayName("ByteString 节点号（ns=2;b=xxx）会踩 javax.xml.bind 缺失的坑（生产风险点）")
    void byteStringNodeIdNeedsJavaxXmlBind() throws Exception {
        try (URLClassLoader noJaxb = classLoaderWithoutJaxb()) {
            Class<?> nodeIdClass = noJaxb.loadClass(
                    "org.eclipse.milo.opcua.stack.core.types.builtin.NodeId");
            Method parse = nodeIdClass.getMethod("parse", String.class);

            InvocationTargetException ex = assertThrows(InvocationTargetException.class,
                    () -> parse.invoke(null, "ns=2;b=AAEC"),
                    "ByteString 节点号在缺 javax.xml.bind 时应当失败");
            assertTrue(ex.getCause() instanceof NoClassDefFoundError,
                    "应因 javax.xml.bind.DatatypeConverter 缺失而失败，实际：" + ex.getCause());

            // 同时把「milo 确实硬依赖 javax.xml.bind」这件事钉在字节码上：
            // 将来 milo 升级换了实现，这条会提醒我们重新评估生产要不要补依赖
            assertTrue(classBytesContain(NodeId.class, "javax/xml/bind/DatatypeConverter"),
                    "milo 的 NodeId 应当仍然引用 javax.xml.bind.DatatypeConverter；"
                            + "若这里变成 false，说明 milo 换了实现，要重新评估生产依赖");
        }
    }

    @Test
    @DisplayName("驱动只接受 SecurityPolicy.None（与代码里的过滤条件一致）")
    void driverOnlyAcceptsNoneSecurityPolicy() {
        assertEquals("http://opcfoundation.org/UA/SecurityPolicy#None",
                org.eclipse.milo.opcua.stack.core.security.SecurityPolicy.None.getUri(),
                "驱动第 74-76 行按这个 URI 过滤端点，URI 变了过滤就会失效");
        assertFalse("http://opcfoundation.org/UA/SecurityPolicy#Basic256Sha256"
                        .equals(org.eclipse.milo.opcua.stack.core.security.SecurityPolicy.None.getUri()),
                "None 与 Basic256Sha256 必须是不同的策略");
    }

    /**
     * 造一个「看不见 javax.xml.bind」的类加载器：父加载器只给 JDK 平台类，
     * 应用类路径里的 jaxb-api 被剔掉。用来模拟 Spring Boot 4 的生产运行时类路径。
     */
    private static URLClassLoader classLoaderWithoutJaxb() throws Exception {
        List<URL> urls = new ArrayList<>();
        for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
            if (entry.contains("jaxb-api") || entry.contains("jakarta.xml.bind")) {
                continue;
            }
            urls.add(new File(entry).toURI().toURL());
        }
        return new URLClassLoader(urls.toArray(new URL[0]),
                ClassLoader.getPlatformClassLoader());
    }

    /** 扫类路径上所有 milo 的 jar，看有没有哪个类引用了给定的内部名。 */
    private static List<String> miloJarsReferencing(String internalName) throws Exception {
        List<String> offenders = new ArrayList<>();
        for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
            if (!entry.contains("org" + File.separator + "eclipse" + File.separator + "milo")) {
                continue;
            }
            File jar = new File(entry);
            if (!jar.isFile()) {
                continue;
            }
            try (ZipFile zip = new ZipFile(jar)) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry ze = entries.nextElement();
                    if (!ze.getName().endsWith(".class")) {
                        continue;
                    }
                    try (InputStream in = zip.getInputStream(ze)) {
                        String content = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
                        if (content.contains(internalName)) {
                            offenders.add(jar.getName() + "!" + ze.getName());
                        }
                    }
                }
            }
        }
        return offenders;
    }

    /** 某个类的字节码里是否出现过给定的内部名。 */
    private static boolean classBytesContain(Class<?> clazz, String internalName) throws Exception {
        String resource = clazz.getName().replace('.', '/') + ".class";
        try (InputStream in = clazz.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in, "应能读到 " + resource + " 的字节码");
            String content = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
            return content.contains(internalName);
        }
    }
}
