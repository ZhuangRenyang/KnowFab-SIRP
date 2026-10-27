package com.factory.hub.system;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * 迁移脚本的解析与安全检查。
 * <p>
 * 为什么要自己切分号而不是 {@code sql.split(";")}：真实迁移里到处是
 * 字符串里的分号、`--` 行注释、`/* *&#47;` 块注释，按分号硬切会把一条语句切成两半，
 * 执行到一半报错，数据库停在半截状态——这比不升级糟得多。
 * <p>
 * 为什么要拦危险语句：升级包是供应商打的，但客户内网也可能有人替换文件。
 * `drop database`、不带 where 的 `delete` 这种一条就能把客户几个月的数据清空，
 * 不该由一段自动执行的脚本决定。真需要删库，让供应商远程、手工、当面做。
 */
public final class MigrationScript {

    private MigrationScript() {
    }

    /**
     * 把整段 SQL 切成单条语句。
     * <p>
     * 处理四种情况：单引号字符串（含 '' 转义）、双引号标识符、
     * `--` 行注释、`/* *&#47;` 块注释。注释内容丢弃，不留进语句里。
     */
    public static List<String> split(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        StringBuilder cur = new StringBuilder();
        boolean inSingle = false;
        boolean inDouble = false;
        boolean inLine = false;
        boolean inBlock = false;

        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            char n = i + 1 < raw.length() ? raw.charAt(i + 1) : '\0';

            if (inLine) {
                if (c == '\n') {
                    inLine = false;
                    cur.append('\n');
                }
                continue;
            }
            if (inBlock) {
                if (c == '*' && n == '/') {
                    inBlock = false;
                    i++;
                }
                continue;
            }
            if (inSingle) {
                cur.append(c);
                if (c == '\'') {
                    if (n == '\'') {          // '' 是转义的单引号，属于字符串内容
                        cur.append(n);
                        i++;
                    } else {
                        inSingle = false;
                    }
                }
                continue;
            }
            if (inDouble) {
                cur.append(c);
                if (c == '"') {
                    inDouble = false;
                }
                continue;
            }

            if (c == '-' && n == '-') {
                inLine = true;
                i++;
                continue;
            }
            if (c == '/' && n == '*') {
                inBlock = true;
                i++;
                continue;
            }
            if (c == '\'') {
                inSingle = true;
                cur.append(c);
                continue;
            }
            if (c == '"') {
                inDouble = true;
                cur.append(c);
                continue;
            }
            if (c == ';') {
                addIfReal(out, cur.toString());
                cur.setLength(0);
                continue;
            }
            cur.append(c);
        }
        addIfReal(out, cur.toString());
        return out;
    }

    private static void addIfReal(List<String> out, String s) {
        String t = s.trim();
        if (!t.isEmpty()) {
            out.add(t);
        }
    }

    /**
     * 检查一批语句里有没有不该自动执行的东西。
     *
     * @return 阻断原因；没问题返回 null
     */
    public static String blockingReason(List<String> statements) {
        for (String s : statements) {
            String low = s.toLowerCase().replaceAll("\\s+", " ");
            if (low.contains("drop database") || low.contains("drop schema")) {
                return "脚本里有 drop database / drop schema，这类操作不允许自动执行";
            }
            if (low.startsWith("delete ") && !low.contains(" where ")) {
                // 不带条件的 delete 一条就能清空客户几个月的产量和电费
                return "脚本里有不带条件的 delete（" + shortOf(s) + "），这类操作不允许自动执行";
            }
        }
        return null;
    }

    /** 需要人工确认但不阻断的操作，页面红字提示，让人看清楚再点应用 */
    public static List<String> warnings(List<String> statements) {
        List<String> out = new ArrayList<>();
        for (String s : statements) {
            String low = s.toLowerCase().replaceAll("\\s+", " ");
            if (low.startsWith("truncate ")) {
                out.add("含 truncate：" + shortOf(s));
            } else if (low.startsWith("alter table ") && low.contains(" drop column")) {
                out.add("删列（数据不可恢复）：" + shortOf(s));
            }
        }
        return out;
    }

    private static String shortOf(String s) {
        String one = s.replaceAll("\\s+", " ").trim();
        return one.length() > 70 ? one.substring(0, 70) + "…" : one;
    }

    public static String sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    public static String sha256(String text) {
        return sha256(text.getBytes(StandardCharsets.UTF_8));
    }
}
