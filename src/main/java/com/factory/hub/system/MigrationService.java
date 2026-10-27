package com.factory.hub.system;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 数据库迁移状态的查询与展示。
 * <p>
 * 执行在 {@link MigrationInitializer}（启动最早阶段），这里只负责"把发生了什么讲清楚"：
 * 哪些脚本执行过、哪些还在等、最后一个为什么失败。
 * 分开的原因是执行器不能用任何 Bean，而查询要漂亮地序列化成 JSON，职责不同。
 */
@Component
public class MigrationService {

    private final SchemaMigrationRepository repo;
    private final VersionService versionService;

    @Value("${system.migration.dir:./migrations}")
    private String migrationDir;

    public MigrationService(SchemaMigrationRepository repo, VersionService versionService) {
        this.repo = repo;
        this.versionService = versionService;
    }

    public Path dir() {
        return Paths.get(migrationDir);
    }

    /**
     * 页面要的全部信息。
     * <p>
     * 特意把「脚本在目录里但没执行成功」单独列出来：这种情况最容易被忽略——
     * jar 升上去了、页面也能开，但表结构是旧的，过两天某个功能才开始报奇怪的错。
     */
    public Map<String, Object> status() {
        List<Map<String, Object>> applied = new ArrayList<>();
        List<String> doneVersions = new ArrayList<>();
        String failedVersion = null;
        String failedError = null;

        for (SchemaMigration m : repo.findAllByOrderByVersionDesc()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("version", m.getVersion());
            row.put("ok", m.isOk());
            row.put("at", fmt(m.getAppliedAt()));
            row.put("statements", m.getStatements());
            row.put("attempts", m.getAttempts());
            row.put("error", m.getError());
            applied.add(row);
            if (m.isOk()) {
                doneVersions.add(m.getVersion());
            } else if (failedVersion == null) {
                // 列表按版本倒序，第一个失败就是最近的那次。
                // 这里不能 break：一旦某天有个脚本失败，后面的成功记录全不显示了，
                // 页面上会以为"只执行过一个还失败了"，反而更难判断。
                failedVersion = m.getVersion();
                failedError = m.getError();
            }
        }

        List<Map<String, Object>> pending = new ArrayList<>();
        for (Path f : files()) {
            String ver = versionOf(f);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("version", ver);
            row.put("file", f.getFileName().toString());
            row.put("size", sizeOf(f));
            boolean isDone = doneVersions.contains(ver);
            row.put("applied", isDone);
            if (!isDone) {
                List<String> stmts = safeParse(f);
                row.put("statements", stmts.size());
                row.put("blocked", MigrationScript.blockingReason(stmts));
                row.put("warnings", MigrationScript.warnings(stmts));
            }
            pending.add(row);
        }

        // 失败的也算"没完成"，一起提示
        long waiting = pending.stream().filter(p -> !Boolean.TRUE.equals(p.get("applied"))).count();

        // 脚本已被删掉（比如换个包重发）时，历史失败记录还留在表里。
        // 那种情况不该继续红灯报警吓人，只在"已执行"表里留个失败痕迹。
        if (failedVersion != null) {
            String fv = failedVersion;
            boolean stillThere = pending.stream().anyMatch(p -> fv.equals(p.get("version")));
            if (!stillThere) {
                failedVersion = null;
                failedError = null;
            }
        }

        // 结构版本取"已成功执行的最大版本"，顺手纠正存库的值。
        // 之前这个值只靠升级包写，一旦有人手工换 jar 就会一直是旧的。
        String maxOk = doneVersions.stream().max(VersionService::compare).orElse("0");
        if (VersionService.compare(maxOk, versionService.schemaVersion()) > 0) {
            versionService.schemaVersion(maxOk);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("dir", dir().toAbsolutePath().toString());
        out.put("schemaVersion", maxOk.equals("0") ? versionService.schemaVersion() : maxOk);
        out.put("applied", applied);
        out.put("files", pending);
        out.put("waiting", waiting);
        out.put("failedVersion", failedVersion);
        out.put("failedError", failedError);
        out.put("lastBootError", MigrationInitializer.lastError());
        out.put("lastBootCount", MigrationInitializer.lastRunCount());
        return out;
    }

    /** 某个脚本会被拆成哪几条语句——上传后可以让人先看一眼再应用 */
    public Map<String, Object> preview(String version) {
        Map<String, Object> out = new LinkedHashMap<>();
        Path f = dir().resolve(version + ".sql").normalize();
        // 防止 version 里带 ../ 去读别的文件
        if (!f.getParent().equals(dir().toAbsolutePath().normalize())
                && !f.startsWith(dir().toAbsolutePath().normalize())) {
            out.put("ok", false);
            out.put("message", "版本号不合法");
            return out;
        }
        if (!Files.isRegularFile(f)) {
            out.put("ok", false);
            out.put("message", "找不到迁移脚本 " + version + ".sql");
            return out;
        }
        List<String> stmts = safeParse(f);
        out.put("ok", true);
        out.put("version", version);
        out.put("statements", stmts);
        out.put("blocked", MigrationScript.blockingReason(stmts));
        out.put("warnings", MigrationScript.warnings(stmts));
        return out;
    }

    /** 新版本装上但还没重启时，升级包目录里的迁移脚本也要能提前看到 */
    public Map<String, Object> staged(Path stagedDir) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (stagedDir == null) {
            return out;
        }
        Path f = stagedDir.resolve("migration.sql");
        if (!Files.isRegularFile(f)) {
            return out;
        }
        List<String> stmts = safeParse(f);
        out.put("present", true);
        out.put("statements", stmts.size());
        out.put("blocked", MigrationScript.blockingReason(stmts));
        out.put("warnings", MigrationScript.warnings(stmts));
        out.put("preview", stmts.subList(0, Math.min(5, stmts.size())));
        return out;
    }

    /* ==================== 内部 ==================== */

    private List<Path> files() {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(dir())) {
            return out;
        }
        try (Stream<Path> s = Files.list(dir())) {
            out.addAll(s.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".sql"))
                    .sorted(Comparator.comparing(this::versionOf, VersionService::compare))
                    .toList());
        } catch (Exception ignored) {
        }
        return out;
    }

    private List<String> safeParse(Path f) {
        try {
            return MigrationScript.split(Files.readString(f, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return List.of();
        }
    }

    private long sizeOf(Path f) {
        try {
            return Files.size(f);
        } catch (Exception e) {
            return 0;
        }
    }

    private String versionOf(Path p) {
        String name = p.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private String fmt(long ts) {
        if (ts <= 0) {
            return "--";
        }
        return java.time.Instant.ofEpochMilli(ts)
                .atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
    }
}
