package com.factory.hub.system;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 数据库账号密码的页面化修改（管理员）。
 * <p>
 * 背景：datasource 密码在 application.yml 里（默认值内置 + 环境变量可覆盖），
 * 现场要改密码就得登服务器敲 SQL + 改文件，客户做不到。这里收成一个接口：
 * 页面上填「当前密码 + 新密码」→ 后端直连 MySQL 验证旧密码 → ALTER USER 改自己
 * 的密码 → 用新密码回连验证（失败自动回滚）→ 把新密码写入外部覆盖文件
 * {@code ./config/application.yml}（Spring Boot 启动时自动加载，优先级高于 jar 内默认值，
 * 低于环境变量 DEVICEHUB_DB_PASSWORD —— 现场如果显式设了环境变量，以环境变量为准）。
 * <p>
 * 为什么不能立即生效：连接池（Hikari）建池时已持有旧密码，MySQL 改完密码后
 * 池里的老连接还活着、但新物理连接会失败 —— 所以必须重启服务，页面上会明确提示。
 * 这也是改密码是低频操作的原因：改一次重启一次，值不值由管理员判断。
 * <p>
 * 为什么只改当前连接的账号：devicehub 可能存在多个 host 条目
 * （localhost / 127.0.0.1 / %），ALTER CURRENT_USER() 只动后端实际使用的那个。
 * 其余条目若存在，需要 DBA 手动同步（页面提示里说明）。
 * <p>
 * 安全：挂 /api/system/settings/** 下，SecurityConfig 已锁 ADMIN；
 * 新密码做字符白名单（防 SQL 注入——ALTER USER 不支持参数绑定）；
 * 不回显当前密码。
 */
@RestController
@RequestMapping("/api/system/settings")
public class DbPasswordController {

    private static final Logger log = LoggerFactory.getLogger(DbPasswordController.class);

    /** 新密码白名单：字母数字 + 常见安全符号，6~64 位。排除引号/反斜杠/空白，保证拼进 ALTER USER 是安全的 */
    private static final Pattern NEW_PWD = Pattern.compile("^[A-Za-z0-9!@#$%^&*._+\\-]{6,64}$");

    private final Environment env;

    public DbPasswordController(Environment env) {
        this.env = env;
    }

    public record DbPasswordChange(String oldPassword, String newPassword) {
    }

    @PostMapping("/db-password")
    public Map<String, Object> change(@RequestBody DbPasswordChange req) throws IOException {
        String oldPwd = req.oldPassword() == null ? "" : req.oldPassword();
        String newPwd = req.newPassword() == null ? "" : req.newPassword();

        if (oldPwd.isBlank()) {
            return Map.of("ok", false, "message", "请填写当前密码");
        }
        if (!NEW_PWD.matcher(newPwd).matches()) {
            return Map.of("ok", false, "message",
                    "新密码 6~64 位，只能包含字母、数字和 !@#$%^&*._+- 这些符号（不含空格和引号）");
        }
        if (newPwd.equals(oldPwd)) {
            return Map.of("ok", false, "message", "新密码不能和当前密码相同");
        }

        String url = env.getProperty("spring.datasource.url");
        String username = env.getProperty("spring.datasource.username", "devicehub");
        if (url == null || url.isBlank()) {
            return Map.of("ok", false, "message", "读不到数据源配置（spring.datasource.url），无法修改");
        }

        // 1. 验证当前密码（直连，不走连接池）
        try (Connection c = DriverManager.getConnection(url, username, oldPwd)) {
            // 2. 用当前账号改自己的密码（MySQL 允许无特权用户 ALTER 自己）
            try (Statement st = c.createStatement()) {
                st.execute("ALTER USER CURRENT_USER() IDENTIFIED BY '" + newPwd + "'");
            }
        } catch (Exception e) {
            log.warn("数据库密码修改失败（验证/ALTER 阶段）: {}", e.getMessage());
            String msg = e.getMessage() == null ? "数据库连接失败" : e.getMessage();
            if (msg.contains("Access denied")) {
                return Map.of("ok", false, "message", "当前密码不正确");
            }
            return Map.of("ok", false, "message", "修改失败：" + msg);
        }

        // 3. 用新密码回连验证
        boolean verified;
        try (Connection c = DriverManager.getConnection(url, username, newPwd)) {
            verified = c.isValid(3);
        } catch (Exception e) {
            verified = false;
        }
        if (!verified) {
            // 4. 验证失败必须回滚，否则系统直接瘫掉
            try (Connection c = DriverManager.getConnection(url, username, oldPwd);
                 Statement st = c.createStatement()) {
                st.execute("ALTER USER CURRENT_USER() IDENTIFIED BY '" + oldPwd + "'");
            } catch (Exception rollbackErr) {
                log.error("密码回滚失败！系统可能无法重启，请立即用 DBA 账号修复: {}", rollbackErr.getMessage());
                return Map.of("ok", false, "message",
                        "新密码验证失败且回滚出错，请立即用 DBA 账号把 " + username + " 的密码改回去");
            }
            return Map.of("ok", false, "message", "新密码验证未通过（已自动改回原密码），请检查后重试");
        }

        // 5. 写外部覆盖文件，下次启动生效
        writeOverride(username, newPwd);

        log.info("数据库密码已通过页面修改（账号 {}），重启后完全生效", username);
        return Map.of("ok", true, "restartRequired", true, "message",
                "数据库密码已修改，并写入启动配置（config/application.yml）。"
                        + "请尽快重启系统：重启前老连接还能用，但新建数据库连接会失败。"
                        + "若数据库存在 " + username + "@其他host 的同名账号，需要手动同步新密码。");
    }

    /**
     * 把新密码写进 ./config/application.yml（只动 spring.datasource.password 一个键，
     * 其余内容原样保留；文件不存在则创建最小骨架）。
     * 这个位置 Spring Boot 启动时自动加载，优先级高于 jar 内 application.yml 的默认值。
     */
    private synchronized void writeOverride(String username, String newPwd) throws IOException {
        Path file = Path.of("config", "application.yml");
        Yaml yaml = new Yaml();
        Map<String, Object> root;
        if (Files.exists(file)) {
            try (var in = Files.newInputStream(file)) {
                Object loaded = yaml.load(in);
                root = loaded instanceof Map ? castMap(loaded) : new LinkedHashMap<>();
            }
        } else {
            root = new LinkedHashMap<>();
        }
        Map<String, Object> spring = castMap(root.computeIfAbsent("spring", k -> new LinkedHashMap<String, Object>()));
        Map<String, Object> ds = castMap(spring.computeIfAbsent("datasource", k -> new LinkedHashMap<String, Object>()));
        ds.put("password", newPwd);
        // username 不动：现场 install.sh 生成的文件里可能有自己的用户名，别覆盖

        DumperOptions opt = new DumperOptions();
        opt.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        String out = "# 由系统页面「修改数据库密码」自动生成/更新，只管 spring.datasource.password。\n"
                + "# 手动改密码请直接改这里，或用环境变量 DEVICEHUB_DB_PASSWORD（优先级更高）。\n"
                + new Yaml(opt).dump(root);
        Files.createDirectories(file.getParent());
        Files.writeString(file, out, StandardCharsets.UTF_8);
        log.info("已写入数据库密码覆盖文件: {}", file.toAbsolutePath());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object o) {
        return (Map<String, Object>) o;
    }
}
