package com.factory.hub.driver.opcua;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;

/**
 * 造一个「看不见 javax.xml.bind」的类加载器，用来模拟
 * <b>「万一这个依赖没进交付包」</b>的运行环境。
 * <p>
 * <b>为什么需要它</b>：milo 解析 ByteString 节点号（{@code ns=2;b=...}）时
 * 会调 {@code javax.xml.bind.DatatypeConverter}，而这个类在 Spring Boot 4 的
 * 类路径上曾经整个消失（Boot 4 把 jaxb 统一成了 jakarta 命名空间）。
 * 本项目的 pom 已经把它补在 runtime 作用域，所以<b>正常跑测试时它是存在的</b> ——
 * 也就是说，光跑正常用例<b>永远验证不了「缺了它会怎样」</b>。
 * <p>
 * <b>怎么做到的</b>：父加载器只给 JDK 平台类（{@code javax.xml.bind} 不在 JDK 里），
 * 应用类路径里把 {@code jaxb-api} 与 {@code jakarta.xml.bind} 剔掉。
 * 于是在这个加载器里加载的 milo 类看到的就是「缺依赖」的真实环境。
 */
final class NoJaxbClassLoader {

    private NoJaxbClassLoader() {
    }

    /**
     * @return 一个屏蔽了 javax.xml.bind / jakarta.xml.bind 的类加载器
     */
    static URLClassLoader create() throws Exception {
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
}
