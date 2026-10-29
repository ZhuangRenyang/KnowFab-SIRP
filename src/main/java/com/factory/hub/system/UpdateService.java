package com.factory.hub.system;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 升级包：校验 → 暂存 → 登记待应用 → 重启时替换。
 * <p>
 * <b>为什么不在网页上直接替换 jar：</b>
 * Linux 上可以让 jar 文件被覆盖（inode 换了，进程还跑着老代码），
 * 但那一瞬间内存里的类和磁盘上的不一致，数据库结构也已经变了，
 * 出问题时你完全不知道服务跑的是哪个版本。所以：
 * 网页只负责「收包 + 验签 + 摆好」，替换交给停机的那几秒（apply-pending.sh）。
 * <p>
 * <b>为什么用私钥签名：</b>
 * 升级通道是系统里权限最大的入口——能升级就能换成任何代码。
 * 用签发授权那把 RSA 私钥签 manifest，客户手里只有公钥，
 * 内网被人放一个恶意包上来也装不进去。这不是防客户，是防内网投毒。
 */
@Service
public class UpdateService {

    private static final Logger log = LoggerFactory.getLogger(UpdateService.class);

    /**
     * 默认更新源（2026-10-29 用户口径：国内镜像固定 Gitee，不再让现场填输入框）。
     * 主源走 Gitee Releases（国内直连快），GitHub 仅作兜底；两者都连不上就用「上传升级包」。
     * 发版流程见 docs/RELEASE.md —— 打 tag、上传 device-hub-*.zip 附件即可。
     */
    public static final String DEFAULT_SOURCE = "gitee://ZhuangRenyang/KnowFab-SIRP";

    /** 兜底源：Gitee 不可用（未发版/被限流）时再试 GitHub */
    private static final String FALLBACK_SOURCE = "github://ZhuangRenyang/KnowFab-SIRP";

    /** 升级包绝对不允许碰的东西：客户数据和授权 */
    private static final List<String> FORBIDDEN_PREFIX = List.of("data/", "license/", "../");

    private final ObjectMapper mapper;
    private final VersionService versionService;

    @Value("${system.update.dir:./updates}")
    private String updateDir;

    /**
     * 升级包验签公钥。授权功能移除后，这把钥匙只服务于一件事：确认升级包是你签的。
     * 键名从 license.public-key 改成 system.update.public-key，
     * 但保留旧键名兜底——已经装过的机器配置里还是老名字，升级不该因为它们拒绝启动。
     */
    @Value("${system.update.public-key:${license.public-key:}}")
    private String publicKeyB64;

    /** 更新源、网页重启开关都是客服现场要改的，走 SettingStore 才能在页面上改完立即生效 */
    private final SettingStore settings;

    /** 升级包里带数据库脚本时，要在登记阶段就把"会执行什么"讲清楚 */
    private final MigrationService migrationService;

    public UpdateService(ObjectMapper mapper, VersionService versionService,
                         SettingStore settings, MigrationService migrationService) {
        this.mapper = mapper;
        this.versionService = versionService;
        this.settings = settings;
        this.migrationService = migrationService;
    }

    /** 每次现读，不缓存：管理员在页面上关掉开关必须立刻生效 */
    private boolean allowWebRestart() {
        return settings.getBool(SettingStore.UPDATE_ALLOW_WEB_RESTART);
    }

    /* ==================== 目录 ==================== */

    private Path base() {
        return Paths.get(updateDir).toAbsolutePath().normalize();
    }

    private Path inbox() {
        return base().resolve("inbox");
    }

    private Path staged() {
        return base().resolve("staged");
    }

    private Path pendingFile() {
        return base().resolve("pending.env");
    }

    private Path rollbackFlag() {
        return base().resolve("rollback.flag");
    }

    private Path backups() {
        return Paths.get(updateDir).toAbsolutePath().normalize().getParent()
                .resolve("backups");
    }

    /* ==================== 状态 ==================== */

    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("version", versionService.version());
        out.put("buildTime", versionService.buildTime());
        out.put("applied", versionService.appliedInfo());
        out.put("schemaVersion", versionService.schemaVersion());
        out.put("allowWebRestart", allowWebRestart());
        out.put("updateDir", base().toString());

        Map<String, Object> pending = new LinkedHashMap<>();
        Path p = pendingFile();
        if (Files.isRegularFile(p)) {
            Map<String, String> kv = readEnv(p);
            pending.put("present", true);
            pending.put("toVersion", kv.getOrDefault("TO_VERSION", ""));
            pending.put("fromVersion", kv.getOrDefault("FROM_VERSION", ""));
            pending.put("notes", kv.getOrDefault("NOTES", ""));
            String staged = kv.get("STAGED");
            Map<String, Object> mig = migrationService.staged(
                    staged == null || staged.isBlank() ? null : Paths.get(staged));
            pending.put("migration", mig);
            boolean hasMig = Boolean.TRUE.equals(mig.get("present"));
            pending.put("hasMigration", hasMig);
            if (hasMig && mig.get("blocked") != null) {
                pending.put("blocked", mig.get("blocked"));
            }
        } else {
            pending.put("present", false);
        }
        out.put("pending", pending);

        List<Map<String, Object>> backups = new ArrayList<>();
        try {
            Path b = backups();
            if (Files.isDirectory(b)) {
                try (var s = Files.list(b)) {
                    s.filter(Files::isDirectory)
                            .sorted(Comparator.comparing((Path x) -> x.getFileName().toString()).reversed())
                            .limit(5)
                            .forEach(x -> {
                                Map<String, Object> m = new LinkedHashMap<>();
                                m.put("dir", x.getFileName().toString());
                                m.put("hasJar", Files.isRegularFile(x.resolve("device-hub.jar")));
                                backups.add(m);
                            });
                }
            }
        } catch (Exception ignored) {
        }
        out.put("backups", backups);
        out.put("rollbackRequested", Files.isRegularFile(rollbackFlag()));
        return out;
    }

    /* ==================== 上传 ==================== */

    public Map<String, Object> upload(MultipartFile file) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (file == null || file.isEmpty()) {
            return fail(out, "没收到文件");
        }
        Path saved = null;
        try {
            Files.createDirectories(inbox());
            String name = file.getOriginalFilename() == null ? "update.zip" : file.getOriginalFilename();
            saved = inbox().resolve(System.currentTimeMillis() + "-"
                    + name.replaceAll("[^A-Za-z0-9._\\-\\u4e00-\\u9fa5]", "_"));
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, saved, StandardCopyOption.REPLACE_EXISTING);
            }
            return register(out, saved);
        } catch (Exception e) {
            log.error("升级包处理失败", e);
            return fail(out, "处理失败：" + e.getMessage());
        } finally {
            try {
                if (saved != null) {
                    Files.deleteIfExists(saved);
                }
            } catch (Exception ignored) {
            }
        }
    }

    /** 校验 + 解压 + 登记待应用。上传和联网下载共用这一套，避免两条路行为不一致 */
    private Map<String, Object> register(Map<String, Object> out, Path zip) throws Exception {
        VerifyResult vr = verify(zip);
        if (!vr.ok) {
            return fail(out, vr.message);
        }
        Path dest = staged().resolve(vr.manifest.getVersion());
        deleteRecursively(dest);
        Files.createDirectories(dest);
        extract(zip, dest);

        String jarSha = sha256(dest.resolve("device-hub.jar"));
        Map<String, String> kv = new LinkedHashMap<>();
        kv.put("TO_VERSION", vr.manifest.getVersion());
        kv.put("FROM_VERSION", versionService.version());
        kv.put("STAGED", dest.toString());
        kv.put("SHA256", jarSha);
        kv.put("NOTES", oneLine(vr.manifest.getNotes()));

        // 包里带 migration.sql 时，把会执行什么写进登记文件并回给页面。
        // 让管理员在点「应用」之前就知道"这次要动表结构"，而不是重启完才发现。
        Map<String, Object> mig = migrationService.staged(dest);
        if (Boolean.TRUE.equals(mig.get("present"))) {
            kv.put("MIGRATION", dest.resolve("migration.sql").toString());
        }
        writeEnv(pendingFile(), kv);

        out.put("ok", true);
        out.put("migration", mig);
        out.put("message", Boolean.TRUE.equals(mig.get("present"))
                ? "包已校验并登记。这个版本带数据库变更，重启时先执行脚本再启动服务"
                : "包已校验并登记，重启服务后生效");
        out.put("version", vr.manifest.getVersion());
        out.put("currentVersion", versionService.version());
        out.put("notes", vr.manifest.getNotes());
        out.put("fileCount", vr.manifest.getFiles() == null ? 0 : vr.manifest.getFiles().size());
        out.put("pending", true);
        log.info("升级包已登记：{} -> {}", versionService.version(), vr.manifest.getVersion());
        return out;
    }

    /* ==================== 联网检查更新 ==================== */

    /**
     * 依次问每个更新源有没有新版本。
     * <p>
     * 只做"有没有"，不做"自动装上"——装不装、什么时候装必须人点头。
     * 车间服务器半夜自动升级然后起不来，这个责任没人担得起。
     */
    public Map<String, Object> check() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("currentVersion", versionService.version());
        List<String> list = sourceList();
        if (list.isEmpty()) {
            out.put("ok", false);
            out.put("reachable", false);
            out.put("message", "没有可用更新源（内置 " + DEFAULT_SOURCE + "）。请检查网络，或用「上传并校验」离线升级。");
            return out;
        }

        List<Map<String, Object>> tried = new ArrayList<>();
        Map<String, Object> hit = null;
        String hitSource = null;
        for (String base : list) {
            Map<String, Object> r;
            try {
                r = probeSource(base);
            } catch (Exception e) {
                r = new LinkedHashMap<>();
                r.put("source", base);
                r.put("ok", false);
                r.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            if (Boolean.TRUE.equals(r.get("ok"))) {
                r.put("newer", VersionService.compare(String.valueOf(r.get("latest")),
                        versionService.version()) > 0);
                if (hit == null && Boolean.TRUE.equals(r.get("newer"))) {
                    hit = new LinkedHashMap<>(r);
                    hitSource = base;
                }
            }
            tried.add(r);
        }
        out.put("sources", tried);
        out.put("ok", true);
        out.put("reachable", tried.stream().anyMatch(x -> Boolean.TRUE.equals(x.get("ok"))));
        if (hit != null) {
            out.put("hasUpdate", true);
            out.put("latest", hit.get("latest"));
            out.put("notes", hit.get("notes"));
            out.put("url", hit.get("url"));
            out.put("sha256", hit.get("sha256"));
            out.put("source", hitSource);
            out.put("message", "有新版本 " + hit.get("latest") + "（当前 " + versionService.version() + "）");
        } else {
            out.put("hasUpdate", false);
            // 区分三种情况：已是新版 / 连得上但仓库还没发版 / 真连不上
            boolean answeredNoRelease = tried.stream().anyMatch(x -> {
                String e = String.valueOf(x.get("error"));
                return e.contains("还没有发布过版本");
            });
            if (Boolean.TRUE.equals(out.get("reachable"))) {
                out.put("message", "已是最新版本 " + versionService.version());
            } else if (answeredNoRelease) {
                out.put("message", "已连上 Gitee 更新源，但仓库还没有发布升级包"
                        + "（发版后这里会自动提示；现在可用「上传并校验」离线升级）");
            } else {
                out.put("message", "更新源都连不上（内置 Gitee，失败兜底 GitHub）。"
                        + "可稍后重试，或用「上传并校验」离线升级。");
            }
        }
        return out;
    }

    /**
     * 探测一个更新源。
     * <ul>
     *   <li>{@code github://owner/repo}：走 GitHub Releases API 的 latest——
     *       tag_name 去掉 v 前缀当版本号，assets 里找 {@code device-hub-*.zip} 当升级包。
     *       404（还没发过版）转成友好提示；api.github.com 需要请求带 User-Agent。</li>
     *   <li>其余：version.json 模式（version/notes/url/sha256），自建镜像走这条。</li>
     * </ul>
     * 返回的 Map 无论如何都带 source 字段；失败时 ok=false + error。
     */
    private Map<String, Object> probeSource(String base) throws Exception {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("source", base);
        boolean gitee = base.startsWith("gitee://");
        if (gitee || base.startsWith("github://")) {
            String repo = base.substring((gitee ? "gitee://" : "github://").length()).trim();
            // Gitee 的 Releases API 与 GitHub 字段基本一致（tag_name/body/assets.name/
            // assets.browser_download_url），解析逻辑共用
            String url = gitee
                    ? "https://gitee.com/api/v5/repos/" + repo + "/releases/latest"
                    : "https://api.github.com/repos/" + repo + "/releases/latest";
            try {
                com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(httpGet(url, 8000));
                String tag = root.path("tag_name").asText("");
                if (tag.isBlank()) {
                    throw new IllegalStateException("响应里没有 tag_name");
                }
                String latest = (tag.startsWith("v") || tag.startsWith("V")) ? tag.substring(1) : tag;
                r.put("ok", true);
                r.put("latest", latest);
                r.put("notes", root.path("body").asText(null));
                String asset = null;
                for (com.fasterxml.jackson.databind.JsonNode a : root.path("assets")) {
                    String name = a.path("name").asText("");
                    if (name.startsWith("device-hub-") && name.endsWith(".zip")) {
                        asset = a.path("browser_download_url").asText(null);
                        break;
                    }
                }
                r.put("url", asset);
                if (asset == null) {
                    r.put("error", "Release " + tag + " 里没有 device-hub-*.zip 升级包附件");
                }
            } catch (IllegalStateException e) {
                if (String.valueOf(e.getMessage()).contains("404")) {
                    r.put("error", "仓库还没有发布过版本（" + (gitee ? "Gitee" : "GitHub") + " Releases 为空）");
                } else {
                    throw e;
                }
            }
            return r;
        }

        String u = base.endsWith(".json") ? base
                : (base.endsWith("/") ? base + "version.json" : base + "/version.json");
        String json = httpGet(u, 6000);
        Map<?, ?> m = mapper.readValue(json, Map.class);
        r.put("ok", true);
        r.put("latest", String.valueOf(m.get("version")));
        r.put("notes", m.get("notes"));
        r.put("url", m.get("url"));
        r.put("sha256", m.get("sha256"));
        return r;
    }

    /** 从更新源下载升级包，验签后登记待应用 */
    public Map<String, Object> download(String url) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (url == null || url.isBlank()) {
            return fail(out, "没给下载地址");
        }
        if (!url.startsWith("https://")) {
            return fail(out, "只允许 https 地址");
        }
        Path tmp = null;
        try {
            Files.createDirectories(inbox());
            tmp = Files.createTempFile(inbox(), "dl-", ".zip");
            byte[] data = httpGetBytes(url, 300_000);
            Files.write(tmp, data);
            Map<String, Object> r = register(out, tmp);
            if (Boolean.TRUE.equals(r.get("ok"))) {
                r.put("message", "已下载并校验通过，重启服务后生效");
            }
            return r;
        } catch (Exception e) {
            log.warn("从更新源下载失败: {}", e.getMessage());
            return fail(out, "下载失败：" + e.getMessage());
        } finally {
            try {
                if (tmp != null) {
                    Files.deleteIfExists(tmp);
                }
            } catch (Exception ignored) {
            }
        }
    }

    private List<String> sourceList() {
        // 固定主源 + 兜底（2026-10-29）：国内现场优先 Gitee，连不上再试 GitHub，
        // 两个都不可用时页面提示用「上传并校验」离线升级。不再读「更新源」设置项。
        return List.of(DEFAULT_SOURCE, FALLBACK_SOURCE);
    }

    private String httpGet(String url, int timeoutMs) throws Exception {
        return new String(httpGetBytes(url, timeoutMs), StandardCharsets.UTF_8);
    }

    private byte[] httpGetBytes(String url, int timeoutMs) throws Exception {
        java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofMillis(Math.min(timeoutMs, 8000)))
                .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                .build();
        // GitHub API 没有 User-Agent 直接 403；Accept 声明 vnd.github+json，对其他源无害
        java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                .timeout(java.time.Duration.ofMillis(timeoutMs))
                .header("User-Agent", "KnowFab-SIRP-Updater")
                .header("Accept", "application/vnd.github+json")
                .GET().build();
        java.net.http.HttpResponse<byte[]> resp = client.send(req,
                java.net.http.HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() / 100 != 2) {
            throw new IllegalStateException("HTTP " + resp.statusCode());
        }
        return resp.body();
    }

    /* ==================== 校验 ==================== */

    private static final class VerifyResult {
        boolean ok;
        String message;
        UpdateManifest manifest;
    }

    private VerifyResult verify(Path zip) throws Exception {
        VerifyResult r = new VerifyResult();
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            ZipEntry me = zf.getEntry("manifest.json");
            ZipEntry sig = zf.getEntry("manifest.sig");
            if (me == null || sig == null) {
                r.message = "包里缺 manifest.json 或 manifest.sig，不是合格的升级包";
                return r;
            }
            byte[] manifestBytes = readAll(zf, me);
            byte[] sigBytes = readAll(zf, sig);

            if (publicKeyB64 != null && !publicKeyB64.isBlank()) {
                if (!verifySignature(manifestBytes, sigBytes)) {
                    r.message = "签名校验失败：这个包不是我们签的，或者被改过。拒绝接收";
                    return r;
                }
            } else {
                r.message = "服务端没配公钥，无法验签，拒绝接收";
                return r;
            }

            UpdateManifest m = mapper.readValue(manifestBytes, UpdateManifest.class);
            if (m.getVersion() == null || m.getVersion().isBlank()) {
                r.message = "manifest 里没有版本号";
                return r;
            }
            if (VersionService.compare(m.getVersion(), versionService.version()) <= 0) {
                r.message = "包版本 " + m.getVersion() + " 不比当前版本 "
                        + versionService.version() + " 新，没必要升级";
                return r;
            }
            if (m.getMinFrom() != null && !m.getMinFrom().isBlank()
                    && VersionService.compare(versionService.version(), m.getMinFrom()) < 0) {
                r.message = "这个包要求从 " + m.getMinFrom() + " 及以上升级，"
                        + "当前是 " + versionService.version() + "，请先升到中间版本";
                return r;
            }

            // 逐个文件核对 sha256 + 路径安全
            if (m.getFiles() != null) {
                for (UpdateManifest.FileEntry f : m.getFiles()) {
                    String p = f.getPath();
                    if (p == null || p.isBlank()) {
                        continue;
                    }
                    String lower = p.toLowerCase().replace('\\', '/');
                    for (String bad : FORBIDDEN_PREFIX) {
                        if (lower.startsWith(bad) || lower.contains("/" + bad)) {
                            r.message = "升级包试图写入受保护路径 " + p
                                    + "（客户数据和授权不能被升级包覆盖）";
                            return r;
                        }
                    }
                    ZipEntry e = zf.getEntry(p);
                    if (e == null) {
                        r.message = "manifest 里列了 " + p + "，但包里没这个文件";
                        return r;
                    }
                    String actual = sha256(readAll(zf, e));
                    if (f.getSha256() != null && !actual.equalsIgnoreCase(f.getSha256())) {
                        r.message = "文件 " + p + " 校验不通过（可能传输损坏）";
                        return r;
                    }
                }
            }
            zf.getEntry("device-hub.jar");
            if (zf.getEntry("device-hub.jar") == null) {
                r.message = "包里没有 device-hub.jar";
                return r;
            }

            r.ok = true;
            r.manifest = m;
            return r;
        }
    }

    private boolean verifySignature(byte[] data, byte[] sig) {
        try {
            byte[] der = Base64.getDecoder().decode(publicKeyB64.replaceAll("\\s", ""));
            PublicKey key = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
            Signature s = Signature.getInstance("SHA256withRSA");
            s.initVerify(key);
            s.update(data);
            return s.verify(sig);
        } catch (Exception e) {
            log.warn("验签异常（按失败处理）: {}", e.getMessage());
            return false;
        }
    }

    /* ==================== 应用 / 回滚 ==================== */

    public Map<String, Object> apply() {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!Files.isRegularFile(pendingFile())) {
            return fail(out, "没有待应用的升级包");
        }
        out.put("ok", true);
        if (allowWebRestart()) {
            out.put("message", "已提交，服务将在约 3 秒后重启并自动应用新版本，"
                    + "页面 30 秒后刷新即可（升级期间采集中断）");
            out.put("restarting", true);
            restartLater();
        } else {
            out.put("message", "升级包已就绪。执行下面这条命令完成替换（会中断采集约 10-30 秒）：\n"
                    + "sudo systemctl restart device-hub\n"
                    + "或者：sudo /opt/device-hub/bin/upgrade.sh --apply");
            out.put("restarting", false);
            out.put("command", "sudo systemctl restart device-hub");
        }
        return out;
    }

    public Map<String, Object> rollback() {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            Files.createDirectories(base());
            Files.writeString(rollbackFlag(), String.valueOf(System.currentTimeMillis()),
                    StandardCharsets.UTF_8);
            out.put("ok", true);
            out.put("message", allowWebRestart()
                    ? "已登记回滚，服务重启后恢复上一版本"
                    : "已登记回滚。执行 sudo systemctl restart device-hub 生效");
            if (allowWebRestart()) {
                restartLater();
            }
            return out;
        } catch (Exception e) {
            return fail(out, "登记回滚失败：" + e.getMessage());
        }
    }

    public Map<String, Object> cancel() {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            Files.deleteIfExists(pendingFile());
            out.put("ok", true);
            out.put("message", "已取消待应用的升级（已解包的文件留在了 updates/staged，不影响运行）");
            return out;
        } catch (Exception e) {
            return fail(out, "取消失败：" + e.getMessage());
        }
    }

    /** 给页面一点时间把响应发出去，再退出；systemd 会把服务重新拉起 */
    private void restartLater() {
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(2500);
            } catch (InterruptedException ignored) {
            }
            log.warn("按网页请求重启服务以应用升级包");
            System.exit(0);
        }, "self-restart");
        t.setDaemon(false);
        t.start();
    }

    /* ==================== 工具 ==================== */

    private Map<String, String> readEnv(Path p) {
        Map<String, String> m = new LinkedHashMap<>();
        try {
            for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                int i = line.indexOf('=');
                if (i > 0) {
                    m.put(line.substring(0, i).trim(), line.substring(i + 1).trim());
                }
            }
        } catch (Exception ignored) {
        }
        return m;
    }

    private void writeEnv(Path p, Map<String, String> kv) throws IOException {
        StringBuilder sb = new StringBuilder();
        kv.forEach((k, v) -> sb.append(k).append('=').append(v == null ? "" : v).append('\n'));
        Files.createDirectories(p.getParent());
        Files.writeString(p, sb.toString(), StandardCharsets.UTF_8);
    }

    private static byte[] readAll(ZipFile zf, ZipEntry e) throws IOException {
        try (InputStream in = zf.getInputStream(e)) {
            return in.readAllBytes();
        }
    }

    private static String sha256(byte[] data) throws Exception {
        var md = java.security.MessageDigest.getInstance("SHA-256");
        byte[] d = md.digest(data);
        StringBuilder sb = new StringBuilder();
        for (byte b : d) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static String sha256(Path p) throws Exception {
        return sha256(Files.readAllBytes(p));
    }

    private static String oneLine(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\r", "").replace("\n", " / ");
    }

    /** 解压，逐条防 zip slip：条目必须落在目标目录内 */
    private void extract(Path zip, Path dest) throws IOException {
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            var en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) {
                    continue;
                }
                Path target = dest.resolve(e.getName()).normalize();
                if (!target.startsWith(dest)) {
                    throw new IOException("包内路径非法：" + e.getName());
                }
                Files.createDirectories(target.getParent());
                try (InputStream in = zf.getInputStream(e);
                     OutputStream out = Files.newOutputStream(target)) {
                    in.transferTo(out);
                }
            }
        }
    }

    private void deleteRecursively(Path p) {
        if (p == null || !Files.exists(p)) {
            return;
        }
        try (var s = Files.walk(p)) {
            s.sorted(Comparator.reverseOrder()).forEach(x -> {
                try {
                    Files.deleteIfExists(x);
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ignored) {
        }
    }

    private Map<String, Object> fail(Map<String, Object> out, String msg) {
        out.put("ok", false);
        out.put("message", msg);
        return out;
    }
}
