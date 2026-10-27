package com.factory.hub.system;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 已执行的数据库迁移记录。
 * <p>
 * 表由启动前的迁移器用裸 JDBC 建（见 {@link MigrationInitializer}），
 * 实体只负责读出来给页面看，所以字段要和那边的 DDL 一一对应，
 * 改了一边必须改另一边，否则 Hibernate 校验时会多出差异列。
 */
@Entity
@Table(name = "schema_migration")
public class SchemaMigration {

    /** 就是脚本文件名去掉 .sql，例如 1.1.0 */
    @Id
    @Column(name = "version", length = 32)
    private String version;

    @Column(name = "applied_at")
    private long appliedAt;

    @Column(name = "ok")
    private boolean ok;

    /** 失败原因，成功时为空 */
    @Column(name = "err_text", length = 1000)
    private String error;

    /** 实际执行了几条语句 */
    @Column(name = "stmt_count")
    private int statements;

    /** 脚本内容的 sha256，用来发现"文件换了但版本号没换" */
    @Column(name = "sha256", length = 64)
    private String sha256;

    /** 试了几次。超过 2 次还失败，说明不是偶发问题，得人看 */
    @Column(name = "attempts")
    private int attempts;

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public long getAppliedAt() {
        return appliedAt;
    }

    public void setAppliedAt(long appliedAt) {
        this.appliedAt = appliedAt;
    }

    public boolean isOk() {
        return ok;
    }

    public void setOk(boolean ok) {
        this.ok = ok;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public int getStatements() {
        return statements;
    }

    public void setStatements(int statements) {
        this.statements = statements;
    }

    public String getSha256() {
        return sha256;
    }

    public void setSha256(String sha256) {
        this.sha256 = sha256;
    }

    public int getAttempts() {
        return attempts;
    }

    public void setAttempts(int attempts) {
        this.attempts = attempts;
    }
}
