package com.factory.hub;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * 全仓**唯一**的集成测试：真起 Spring 容器 + 真连 MySQL + 真查一次库。
 *
 * <h2>为什么必须有它</h2>
 * 全仓 409 个用例都是纯 Mockito 单测（`@SpringBootTest` 在此之前一个都没有）。
 * 纯单测**结构上证明不了**下面这些事，而框架升级时它们恰恰是最容易坏的地方：
 * <ul>
 *   <li>{@code @Entity} / Spring Data Repository 有没有被扫到（漏扫时启动照样成功，只有真查表才炸）</li>
 *   <li>Bean 能不能装配起来（大版本升级常改自动配置条件、构造器注入规则）</li>
 *   <li>bean 名有没有撞（Spring Data 按**简单类名**区分 Repository）</li>
 *   <li>数据库方言与 Hibernate 的映射还对不对得上</li>
 * </ul>
 * 2026-11-07 拆包改造时这条缺口暴露得很清楚：改完包结构 {@code mvn test} 全绿，
 * 但「容器能不能起来」只能靠人工跑 {@code tools/verify-boot-smoke.sh}。
 * 人工会忘，CI 不会。
 *
 * <h2>为什么用真 MySQL 而不是 H2 / Testcontainers</h2>
 * <ul>
 *   <li><b>H2</b>：项目 2026-10-27 已明确「H2 彻底移除，MySQL 是唯一目标库」，
 *       且原生 SQL 用了 {@code from_unixtime} / {@code date_format}、实体用了
 *       {@code @JdbcTypeCode(SqlTypes.JSON)}，回退 H2 等于在测另一个数据库。</li>
 *   <li><b>Testcontainers</b>：需要 Docker。CI 上有，但**开发机不一定有**
 *       （写这个测试的机器上就没有）⇒ 本地跑不了的东西，本地就不会跑，
 *       写了等于只有 CI 一半收益。</li>
 *   <li><b>真 MySQL</b>：CI 用 {@code services: mysql}；本地用已有的 MySQL。
 *       两边跑的是同一个数据库，测出来的结论可信。</li>
 * </ul>
 *
 * <h2>怎么跑</h2>
 * <pre>
 *   # 默认 mvn test **不跑它**（pom 里 excludedGroups=integration）⇒ 单测基线仍是 409
 *   # 要跑：
 *   mvn test -Dgroups=integration -DexcludedGroups=
 *   # 连哪个库：环境变量（本地默认 devicehub_verify）
 *   TEST_DB_URL / TEST_DB_USER / TEST_DB_PASSWORD
 * </pre>
 *
 * <h2>安全护栏</h2>
 * 库名**必须**含 {@code test} 或 {@code verify}，否则直接让上下文加载失败。
 * 这条护栏防的是「手滑把 spring.datasource.url 指向交付库 devicehub」——
 * 那会拿客户数据做实验。
 *
 * <h2>它验证不到什么（别高估）</h2>
 * 空库上跑时，迁移脚本的守卫（「表存在才建」）会走 {@code DO 0} 静默跳过
 * ⇒ **这个测试证明不了迁移脚本能跑通**（那是 DH-84 的已知行为）。
 * 迁移要单独验：全新空库 + 老库升级两条路径，见 {@code docs/UPGRADE-RUNBOOK.md} H3。
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApplicationBootSmokeTest {

    private static final String DEFAULT_URL =
            "jdbc:mysql://127.0.0.1:3306/devicehub_verify"
            + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"
            + "&useSSL=false&allowPublicKeyRetrieval=true&rewriteBatchedStatements=true";

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        String url = env("TEST_DB_URL", DEFAULT_URL);

        // ---- 安全护栏：绝不在非测试库上跑 ----
        String dbName = url.replaceAll("^[^/]*//[^/]+/([^?]+).*$", "$1");
        if (!dbName.toLowerCase().matches(".*(test|verify).*")) {
            // 抛异常 ⇒ 上下文加载失败 ⇒ 测试红（这正是想要的：宁可不跑，也别连错库）
            throw new IllegalStateException(
                    "拒绝在库 [" + dbName + "] 上跑集成测试：库名必须含 test 或 verify。"
                    + "当前 TEST_DB_URL=" + url);
        }

        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> env("TEST_DB_USER", "devicehub"));
        registry.add("spring.datasource.password", () -> env("TEST_DB_PASSWORD", "DeviceHub2026"));

        // ⚠️ 刻意**不用** create-drop：那会删掉测试库里的全部表。
        //   update 足够（表已存在就复用，缺列就补），且不会毁掉验证库里的既有结构。
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "update");
    }

    private static String env(String key, String fallback) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? fallback : v;
    }

    @Test
    @DisplayName("Spring 容器能起来（@Entity / Repository / Bean 装配全部就位）")
    void contextLoads() {
        // 能执行到这一行，就说明容器起来了：
        //   @SpringBootTest 起不来时，这个类的方法根本不会被执行（上下文加载失败 → 测试 error）。
        //   ⇒ 这个空方法不是凑数，它是「升级后容器还能起来」这条断言的载体。
        assertThat(port).isPositive();
    }

    @Test
    @DisplayName("查库端点返回 200 且是真数据（@Entity 漏扫的探针）")
    void screenSnapshotReadsDatabase() throws Exception {
        // 选 /api/screen/snapshot 的三个理由：
        //   ① SecurityConfig 里 /api/screen/** 是 permitAll ⇒ 不需要登录，测试不依赖账号数据
        //   ② 它会真的查库（大屏快照要读设备/产量）⇒ 是「@Entity 有没有被扫到」的天然探针
        //      漏扫时启动照样成功，只有真查表才炸 "Table ... doesn't exist"
        //   ③ 返回聚合 JSON，断言「非空且无 error 字段」就够，不锁具体业务数值
        //      （测试不该因为业务数据变了就红）
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/api/screen/snapshot"))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();

        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());

        // 失败时把响应体带上——否则 CI 上只看到「期望 200 实际 500」，查不出是哪张表的问题
        if (resp.statusCode() != 200) {
            fail("GET /api/screen/snapshot 返回 " + resp.statusCode()
                    + "（这个端点免登录且会查库；非 200 通常意味着 @Entity 没被扫到、"
                    + "表不存在或 Bean 装配失败）\n响应体："
                    + resp.body().substring(0, Math.min(800, resp.body().length())));
        }

        assertThat(resp.body()).as("大屏快照不应为空").isNotBlank();
        // 项目约定：业务失败返回 200 + {ok:false}，所以 200 不等于成功，还要看 ok 字段
        assertThat(resp.body()).as("大屏快照不应带 error 字段").doesNotContain("\"error\"");
    }
}
