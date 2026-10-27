package com.factory.hub.system;

import java.util.ArrayList;
import java.util.List;

/**
 * 升级包清单（manifest.json）。
 * <p>
 * 包里必须有这个文件和它的签名（manifest.sig），服务端先验签再解压。
 * 用同一个 RSA 私钥签，客户手上只有公钥，伪造不出可用的包——
 * 这不是防客户，是防止升级通道被人拿来投毒（工控机在内网，但内网也会中招）。
 */
public class UpdateManifest {

    /** 包内格式版本，将来格式变了用它做兼容 */
    private int format = 1;

    /** 目标版本号，必须大于当前版本 */
    private String version;

    /** 最低可从哪个版本升上来。跨大版本（结构库改动大）时用来拦住直接升级 */
    private String minFrom = "1.0.0";

    /** 变更说明，给客户看的，一行一条 */
    private String notes = "";

    /** 打包时间（epoch ms） */
    private long createdAt;

    /** 包内文件清单。path 是相对包根的路径，禁止绝对路径和 .. */
    private List<FileEntry> files = new ArrayList<>();

    /** 可选的数据库迁移脚本文件名 */
    private String migration;

    public static class FileEntry {
        private String path;
        private String sha256;
        private long size;

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public String getSha256() {
            return sha256;
        }

        public void setSha256(String sha256) {
            this.sha256 = sha256;
        }

        public long getSize() {
            return size;
        }

        public void setSize(long size) {
            this.size = size;
        }
    }

    public int getFormat() {
        return format;
    }

    public void setFormat(int format) {
        this.format = format;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getMinFrom() {
        return minFrom;
    }

    public void setMinFrom(String minFrom) {
        this.minFrom = minFrom;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public List<FileEntry> getFiles() {
        return files;
    }

    public void setFiles(List<FileEntry> files) {
        this.files = files;
    }

    public String getMigration() {
        return migration;
    }

    public void setMigration(String migration) {
        this.migration = migration;
    }
}
