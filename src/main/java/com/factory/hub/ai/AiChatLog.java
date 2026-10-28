package com.factory.hub.ai;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * AI 助手对话留痕（DH-47）。
 * <p>
 * 为什么存库：一是刷新/换设备对话不丢（按用户回放最近若干条）；
 * 二是出了"AI 答错了"的纠纷，有原始问答可查——AI 的每句话都带着工具溯源，
 * 存下来才对得上账。只存问和答与所用工具，不存任何密钥。
 * <p>
 * 隐私边界：只允许用户查/删<b>自己的</b>记录；管理员也不给全局界面（真要审计走 DBA）。
 * 保留 180 天，定时清理，别让它长成第二张日志大表。
 */
@Entity
@Table(name = "ai_chat_log", indexes = {
        @Index(name = "idx_chat_user_time", columnList = "username,created_at"),
        @Index(name = "idx_chat_created", columnList = "created_at")
})
public class AiChatLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "username", nullable = false, length = 64)
    private String username;

    /** 回答时的角色（提权/降权都能从历史里看出来） */
    @Column(name = "role", nullable = false, length = 16)
    private String role;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "question", nullable = false)
    private String question;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "answer", nullable = false)
    private String answer;

    /** 用了哪些工具（JSON 数组：name/args/ms），回答溯源用 */
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "tools_json")
    private String toolsJson;

    @Column(name = "tokens")
    private int tokens;

    @Column(name = "created_at", nullable = false)
    private long createdAt = System.currentTimeMillis();

    public Long getId() { return id; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }
    public String getAnswer() { return answer; }
    public void setAnswer(String answer) { this.answer = answer; }
    public String getToolsJson() { return toolsJson; }
    public void setToolsJson(String toolsJson) { this.toolsJson = toolsJson; }
    public int getTokens() { return tokens; }
    public void setTokens(int tokens) { this.tokens = tokens; }
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
}
