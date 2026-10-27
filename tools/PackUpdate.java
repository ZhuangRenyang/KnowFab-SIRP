import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 升级包打包工具（单文件，纯 JDK，不进交付包）。
 *
 * <pre>
 *   java tools/PackUpdate.java \
 *        --jar target/device-hub.jar \
 *        --version 1.1.0 \
 *        --notes "报表加 XLSX 导出；修复需量窗口在重启后错位" \
 *        --key tools/keys/private.key \
 *        --out device-hub-1.1.0.zip
 * </pre>
 *
 * 产出结构：
 * <pre>
 *   manifest.json   版本、说明、每个文件的 sha256
 *   manifest.sig    用私钥对 manifest.json 的签名（SHA256withRSA）
 *   device-hub.jar  程序本体
 *   migration.sql   可选，数据库增量脚本
 * </pre>
 *
 * 签名用的是签发授权那把私钥——私钥在你手上，客户手上只有公钥，
 * 所以他造不出可用的升级包，也能证明"这个包是你发的"。
 */
public class PackUpdate {

    public static void main(String[] args) throws Exception {
        Map<String, String> a = parse(args);
        if (a.containsKey("help") || args.length == 0) {
            usage();
            return;
        }
        Path jar = Paths.get(need(a, "jar"));
        String version = need(a, "version");
        String notes = a.getOrDefault("notes", "");
        String keyFile = a.getOrDefault("key", "tools/keys/private.key");
        String minFrom = a.getOrDefault("min-from", "1.0.0");
        String migration = a.get("migration");
        Path out = Paths.get(a.getOrDefault("out", "device-hub-" + version + ".zip"));

        if (!Files.isRegularFile(jar)) {
            die("找不到 jar：" + jar);
        }
        if (!Files.isRegularFile(Paths.get(keyFile))) {
            die("找不到私钥：" + keyFile + "（用 openssl 生成一对，见 README 10.4）");
        }

        List<Map<String, Object>> files = new ArrayList<>();
        files.add(fileEntry("device-hub.jar", Files.readAllBytes(jar)));
        byte[] migrationBytes = null;
        if (migration != null && !migration.isBlank()) {
            Path m = Paths.get(migration);
            if (!Files.isRegularFile(m)) {
                die("找不到迁移脚本：" + m);
            }
            migrationBytes = Files.readAllBytes(m);
            files.add(fileEntry("migration.sql", migrationBytes));
        }

        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"format\": 1,\n");
        sb.append("  \"version\": ").append(q(version)).append(",\n");
        sb.append("  \"minFrom\": ").append(q(minFrom)).append(",\n");
        sb.append("  \"notes\": ").append(q(notes)).append(",\n");
        sb.append("  \"createdAt\": ").append(System.currentTimeMillis()).append(",\n");
        if (migrationBytes != null) {
            sb.append("  \"migration\": \"migration.sql\",\n");
        }
        sb.append("  \"files\": [\n");
        for (int i = 0; i < files.size(); i++) {
            Map<String, Object> f = files.get(i);
            sb.append("    {")
              .append("\"path\": ").append(q((String) f.get("path"))).append(", ")
              .append("\"sha256\": ").append(q((String) f.get("sha256"))).append(", ")
              .append("\"size\": ").append(f.get("size"))
              .append("}").append(i == files.size() - 1 ? "" : ",").append("\n");
        }
        sb.append("  ]\n}");
        byte[] manifest = sb.toString().getBytes(StandardCharsets.UTF_8);

        byte[] sig = sign(manifest, loadPrivate(Paths.get(keyFile)));

        Files.deleteIfExists(out);
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(out))) {
            put(zos, "manifest.json", manifest);
            put(zos, "manifest.sig", sig);
            put(zos, "device-hub.jar", Files.readAllBytes(jar));
            if (migrationBytes != null) {
                put(zos, "migration.sql", migrationBytes);
            }
        }

        System.out.println("已生成升级包: " + out.toAbsolutePath());
        System.out.println("  版本    : " + version + "（可从 " + minFrom + " 及以上升级）");
        System.out.println("  jar     : " + Files.size(jar) / 1024 / 1024 + " MB");
        System.out.println("  sha256  : " + files.get(0).get("sha256"));
        System.out.println("  说明    : " + notes.replace("\n", " / "));
        System.out.println();
        System.out.println("安装方式：网页「系统」页上传，或 sudo bin/upgrade.sh " + out.getFileName());
    }

    private static Map<String, Object> fileEntry(String path, byte[] data) throws Exception {
        Map<String, Object> m = new HashMap<>();
        m.put("path", path);
        m.put("size", data.length);
        m.put("sha256", sha256(data));
        return m;
    }

    private static void put(ZipOutputStream zos, String name, byte[] data) throws IOException {
        ZipEntry e = new ZipEntry(name);
        e.setTime(Instant.now().toEpochMilli());
        zos.putNextEntry(e);
        zos.write(data);
        zos.closeEntry();
    }

    private static byte[] sign(byte[] data, PrivateKey key) throws Exception {
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(key);
        s.update(data);
        return s.sign();
    }

    private static PrivateKey loadPrivate(Path p) throws Exception {
        String raw = Files.readString(p, StandardCharsets.UTF_8)
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(raw);
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    private static String sha256(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] d = md.digest(data);
        StringBuilder sb = new StringBuilder();
        for (byte b : d) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /** JSON 字符串转义。够用就行，不做完整实现 */
    private static String q(String s) {
        if (s == null) {
            return "\"\"";
        }
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String k = args[i];
            if (!k.startsWith("--")) {
                continue;
            }
            k = k.substring(2);
            if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                m.put(k, args[++i]);
            } else {
                m.put(k, "");
            }
        }
        return m;
    }

    private static String need(Map<String, String> a, String k) {
        String v = a.get(k);
        if (v == null || v.isBlank()) {
            die("缺少参数 --" + k);
        }
        return v;
    }

    private static void usage() {
        System.out.println("""
                用法：java tools/PackUpdate.java --jar <jar> --version <版本> [选项]

                  --jar        必填，打包的 jar
                  --version    必填，版本号，如 1.1.0（必须比当前运行版本新）
                  --key        私钥文件，默认 tools/keys/private.key
                  --out        输出文件名，默认 device-hub-<version>.zip
                  --notes      变更说明，会显示在升级页面上
                  --min-from   最低可升级版本，默认 1.0.0
                  --migration  可选的数据库迁移脚本（migration.sql）

                例：
                  java tools/PackUpdate.java --jar target/device-hub.jar --version 1.1.0 \\
                       --notes "报表导出修中文乱码" --out dist/device-hub-1.1.0.zip
                """);
    }

    private static void die(String msg) {
        System.err.println("错误：" + msg);
        System.exit(1);
    }
}
