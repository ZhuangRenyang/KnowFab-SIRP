package com.factory.hub.system;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 启动时执行数据库迁移脚本，位置在 Spring 建 DataSource 之前、Hibernate 之前。
 * <p>
 * <b>为什么必须抢在 Hibernate 前面：</b>现在用的是 {@code ddl-auto=update}，
 * 它只会加列、改不了已有列。要是新版本给某个已有表加一个 {@code not null} 的列，
 * Hibernate 会先执行 {@code alter table ... add column x varchar(255) not null}，
 * 表里有数据时这句在 H2 和 MySQL 上都会直接失败，服务起不来——
 * 而且报的是一句 Hibernate 的方言错误，现场根本看不懂。
 * 迁移脚本先跑，就能把列加好、默认值填好，Hibernate 再来只会看到"已经对了"。
 * <p>
 * <b>为什么用裸 JDBC 而不用 JdbcTemplate：</b>这个类跑在任何 Bean 创建之前，
 * 那时候连 DataSource Bean 都还不存在。只能从 Environment 里读连接参数自己连。
 * <p>
 * <b>失败策略（和 apply-pending.sh 一致的思路）：</b>
 * 迁移失败不阻断启动，把错误记进 {@code schema_migration} 表并打 ERROR 日志。
 * 理由：车间服务器起不来 = 彻底没有监控，比"结构没迁完但还能看数据"严重得多。
 * 但页面上会红字显示失败原因，不会让它悄悄过去。
 */
public class MigrationInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    private static final Logger log = LoggerFactory.getLogger(MigrationInitializer.class);

    /** 最近一次启动的迁移错误，给页面兜底用（表都读不出来时还有这句） */
    private static volatile String lastError;

    /** 本次启动执行了几个脚本，供启动日志和页面显示 */
    private static volatile int lastRunCount;

    public static String lastError() {
        return lastError;
    }

    public static int lastRunCount() {
        return lastRunCount;
    }

    @Override
    public void initialize(ConfigurableApplicationContext ctx) {
        Environment env = ctx.getEnvironment();
        if (!Boolean.parseBoolean(env.getProperty("system.migration.enabled", "true"))) {
            log.info("数据库迁移已在配置里关闭（system.migration.enabled=false）");
            return;
        }

        Path dir = Paths.get(env.getProperty("system.migration.dir", "./migrations"));
        if (!Files.isDirectory(dir)) {
            return;                                  // 没有迁移目录就是没升级过，正常情况
        }

        List<Path> files;
        try (Stream<Path> s = Files.list(dir)) {
            files = s.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".sql"))
                    .sorted(Comparator.comparing(p -> versionOf(p), VersionService::compare))
                    .toList();
        } catch (Exception e) {
            log.warn("读取迁移目录失败 {}：{}", dir, e.getMessage());
            return;
        }
        if (files.isEmpty()) {
            return;
        }

        String url = env.getProperty("spring.datasource.url");
        String user = env.getProperty("spring.datasource.username", "");
        String pass = env.getProperty("spring.datasource.password", "");
        if (url == null || url.isBlank()) {
            log.warn("没有配置 spring.datasource.url，跳过数据库迁移");
            return;
        }

        try {
            DriverManager.setLoginTimeout(5);
            try (Connection c = DriverManager.getConnection(url, user, pass)) {
                ensureTable(c);
                Set<String> done = appliedVersions(c);
                int ran = 0;
                for (Path f : files) {
                    String ver = versionOf(f);
                    if (done.contains(ver)) {
                        continue;
                    }
                    if (runOne(c, f, ver)) {
                        ran++;
                    }
                }
                lastRunCount = ran;
                if (ran > 0) {
                    log.info("数据库迁移完成：本次执行 {} 个脚本（目录 {}）", ran, dir.toAbsolutePath());
                }
            }
        } catch (Exception e) {
            // 连不上库：让 JPA 去报它那条更具体的错，这里只留一条线索
            lastError = "连接数据库失败，迁移未执行：" + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : "：" + e.getMessage());
            log.error("数据库迁移无法执行：{}", e.getMessage());
        }
    }

    /** 表用裸 DDL 建，和 SchemaMigration 实体保持一致 */
    private void ensureTable(Connection c) throws Exception {
        String ddl = "create table if not exists schema_migration ("
                + "version varchar(32) not null primary key,"
                + "applied_at bigint,"
                + "ok boolean,"
                + "err_text varchar(1000),"
                + "stmt_count int,"
                + "sha256 varchar(64),"
                + "attempts int)";
        try (Statement st = c.createStatement()) {
            st.execute(ddl);
        }
    }

    /** 只有成功过的才算"已完成"；失败的要重试，否则改了脚本也永远进不去 */
    private Set<String> appliedVersions(Connection c) throws Exception {
        Set<String> out = new HashSet<>();
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("select version from schema_migration where ok = true")) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }

    private boolean runOne(Connection c, Path file, String version) {
        String sql;
        try {
            sql = Files.readString(file, StandardCharsets.UTF_8);
        } catch (Exception e) {
            record(c, version, false, "读取脚本失败：" + e.getMessage(), 0, "", 1);
            lastError = version + " 读取失败：" + e.getMessage();
            log.error("迁移脚本 {} 读取失败：{}", file, e.getMessage());
            return false;
        }

        List<String> statements = MigrationScript.split(sql);
        if (statements.isEmpty()) {
            log.info("迁移脚本 {} 里没有可执行语句，跳过", version);
            record(c, version, true, null, 0, MigrationScript.sha256(sql), 1);
            return false;
        }

        String blocked = MigrationScript.blockingReason(statements);
        if (blocked != null) {
            // 不执行也不记成"成功"，每次启动都会再提示一遍，直到人把脚本改对
            record(c, version, false, blocked, 0, MigrationScript.sha256(sql), 1);
            lastError = version + "：" + blocked;
            log.error("拒绝执行迁移脚本 {}：{}", version, blocked);
            return false;
        }

        int done = 0;
        try {
            for (String s : statements) {
                try (Statement st = c.createStatement()) {
                    st.execute(s);
                }
                done++;
            }
            record(c, version, true, null, statements.size(), MigrationScript.sha256(sql), 1);
            log.info("迁移脚本 {} 执行成功（{} 条语句）", version, statements.size());
            for (String w : MigrationScript.warnings(statements)) {
                log.warn("迁移 {} 含高风险操作：{}", version, w);
            }
            return true;
        } catch (Exception e) {
            String msg = "第 " + (done + 1) + " 条语句失败：" + e.getMessage();
            if (msg.length() > 900) {
                msg = msg.substring(0, 900);
            }
            int attempts = attemptsOf(c, version) + 1;
            record(c, version, false, msg, done, MigrationScript.sha256(sql), attempts);
            lastError = version + " " + msg;
            log.error("迁移脚本 {} 执行失败（已完成 {} 条）：{}", version, done, e.getMessage());
            // 后面的脚本不再执行：前一个没成功，后一个大概率依赖它的结果
            return false;
        }
    }

    private int attemptsOf(Connection c, String version) {
        try (PreparedStatement ps = c.prepareStatement(
                "select attempts from schema_migration where version = ?")) {
            ps.setString(1, version);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (Exception e) {
            return 0;
        }
    }

    /** 先 UPDATE 再 INSERT：H2/MySQL/PG 的 upsert 语法各不相同，这样两边都通用 */
    private void record(Connection c, String version, boolean ok, String error,
                        int stmtCount, String sha, int attempts) {
        try {
            int updated;
            try (PreparedStatement ps = c.prepareStatement(
                    "update schema_migration set applied_at = ?, ok = ?, err_text = ?,"
                            + " stmt_count = ?, sha256 = ?, attempts = ? where version = ?")) {
                ps.setLong(1, System.currentTimeMillis());
                ps.setBoolean(2, ok);
                ps.setString(3, error);
                ps.setInt(4, stmtCount);
                ps.setString(5, sha);
                ps.setInt(6, attempts);
                ps.setString(7, version);
                updated = ps.executeUpdate();
            }
            if (updated == 0) {
                try (PreparedStatement ps = c.prepareStatement(
                        "insert into schema_migration (version, applied_at, ok, err_text,"
                                + " stmt_count, sha256, attempts) values (?,?,?,?,?,?,?)")) {
                    ps.setString(1, version);
                    ps.setLong(2, System.currentTimeMillis());
                    ps.setBoolean(3, ok);
                    ps.setString(4, error);
                    ps.setInt(5, stmtCount);
                    ps.setString(6, sha);
                    ps.setInt(7, attempts);
                    ps.executeUpdate();
                }
            }
        } catch (Exception e) {
            log.warn("写迁移记录失败（不影响迁移本身）：{}", e.getMessage());
        }
    }

    private String versionOf(Path p) {
        String name = p.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** 供页面显示：目录里有哪些脚本 */
    static List<String> filesIn(Path dir) {
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".sql"))
                    .forEach(p -> out.add(p.getFileName().toString()));
        } catch (Exception ignored) {
        }
        return out;
    }
}
