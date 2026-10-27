package com.factory.hub.ai;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import jakarta.persistence.Table;

/**
 * 每日早报。存下来有两个用处：客户想回看上周三说了什么；出问题时能证明"系统当天报过"。
 */
@Entity
@Table(name = "daily_digest", indexes = {
        @Index(name = "idx_digest_date", columnList = "digest_date", unique = true)
})
public class DailyDigest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** yyyy-MM-dd，唯一。同一天重复生成会覆盖而不是堆一堆 */
    @Column(name = "digest_date", nullable = false, length = 10)
    private String date;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "content", nullable = false)
    private String content;

    /** AI = 模型写的；LOCAL = 本地模板（没配 key 或调用失败） */
    @Column(name = "source", length = 16)
    private String source = "LOCAL";

    @Column(name = "model", length = 64)
    private String model;

    @Column(name = "tokens")
    private int tokens;

    @Column(name = "ok")
    private boolean ok = true;

    @Column(name = "error", length = 500)
    private String error;

    /** 生成时的数字快照（JSON）。刷新页面后 KPI 还要能显示，不能只活在内存里 */
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "facts_json")
    private String factsJson;

    @Column(name = "created_at")
    private long createdAt = System.currentTimeMillis();

    public DailyDigest() {
    }

    public DailyDigest(String date, String content, String source) {
        this.date = date;
        this.content = content;
        this.source = source;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getDate() {
        return date;
    }

    public void setDate(String date) {
        this.date = date;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public int getTokens() {
        return tokens;
    }

    public void setTokens(int tokens) {
        this.tokens = tokens;
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

    public String getFactsJson() {
        return factsJson;
    }

    public void setFactsJson(String factsJson) {
        this.factsJson = factsJson;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }
}
