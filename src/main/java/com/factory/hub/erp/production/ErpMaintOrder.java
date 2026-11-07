package com.factory.hub.erp.production;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

/**
 * 设备维修工单——AI 诊断的闭环载体。
 * <p>
 * <b>为什么单独建表，不复用 {@link ErpWorkOrder}</b>：
 * 生产工单关心「做多少件、领多少料、几时完工」；维修工单关心
 * 「什么故障、AI 说什么、实际什么原因、换了什么件」。两者字段几乎不重叠，
 * 硬塞进一张表会让两边都背上对方的一堆空列（生产工单永远没有 rootCause，
 * 维修工单永远没有 planQty）。
 * <p>
 * <b>三个字段的语义（最易做错）</b>：
 * <ol>
 *   <li>{@code aiSource}：必须标清 AI / LOCAL。AI 未启用时退回本地规则建议
 *       （{@code FaultAdvisor.localAdvice}），文本照样有——但把本地规则
 *       说成 AI 结论是骗现场。</li>
 *   <li>{@code causeCategory}：统计维度，不是装饰。没有分类就答不出
 *       「这台机主要坏在哪一类」，也就没法做备件储备和预防性维护。</li>
 *   <li>{@code alarmCode} / {@code deviceName} 独立存快照，不靠外键取：
 *       报警事件有保留期，到期就删；靠外键取的话半年后这条维修记录
 *       会变成「设备不明、报警不明」，样本价值归零。</li>
 * </ol>
 */
@Entity
@Table(name = "erp_maint_order", indexes = {
        @Index(name = "uk_maint_no", columnList = "order_no", unique = true),
        @Index(name = "idx_maint_sample", columnList = "device_code,alarm_code,status"),
        @Index(name = "idx_maint_status", columnList = "status,created_at"),
        @Index(name = "idx_maint_alarm", columnList = "alarm_event_id")
})
public class ErpMaintOrder {

    /** 已诊断（AI/本地规则给了建议），还没人开始修 */
    public static final String ST_ANALYZED = "ANALYZED";
    /** 维修中 */
    public static final String ST_IN_PROGRESS = "IN_PROGRESS";
    /** 已结案（已回填实际原因） */
    public static final String ST_RESOLVED = "RESOLVED";
    /** 已取消（误报、重复报、不需要修） */
    public static final String ST_CANCELLED = "CANCELLED";

    /** 原因分类：电气 */
    public static final String CAUSE_ELECTRIC = "ELECTRIC";
    /** 原因分类：机械 */
    public static final String CAUSE_MECHANICAL = "MECHANICAL";
    /** 原因分类：参数/程序 */
    public static final String CAUSE_PARAM = "PARAM";
    /** 原因分类：操作不当 */
    public static final String CAUSE_OPERATION = "OPERATION";
    /** 原因分类：来料/工件 */
    public static final String CAUSE_MATERIAL = "MATERIAL";
    /** 原因分类：其他 */
    public static final String CAUSE_OTHER = "OTHER";

    /** 诊断来源：AI（点按钮触发的模型分析） */
    public static final String SRC_AI = "AI";

    /** 诊断来源：AI Agent（对话里由模型判断并建单，DH-85。与 AI 分开记，事后能查清这单怎么来的） */
    public static final String SRC_AGENT = "AGENT";

    /** 诊断来源：本地规则（AI 未启用或失败） */
    public static final String SRC_LOCAL = "LOCAL";

    /** 原因分类的全部取值（前端下拉、校验共用一处） */
    public static final String[] CAUSE_CATEGORIES = {
            CAUSE_ELECTRIC, CAUSE_MECHANICAL, CAUSE_PARAM,
            CAUSE_OPERATION, CAUSE_MATERIAL, CAUSE_OTHER
    };

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_no", nullable = false, length = 32)
    private String orderNo;

    @Column(name = "alarm_event_id")
    private Long alarmEventId;

    @Column(name = "device_code", nullable = false, length = 64)
    private String deviceCode;

    @Column(name = "device_name", length = 128)
    private String deviceName;

    @Column(name = "alarm_code", length = 32)
    private String alarmCode;

    @Column(name = "alarm_text", length = 500)
    private String alarmText;

    /**
     * 诊断建议原文。Lob 而不是 varchar(255)：
     * AI 输出常有 400~800 字（原因 + 3~5 条排查步骤 + 风险提示），截断等于废掉一半信息。
     */
    @Lob
    @Column(name = "ai_advice", columnDefinition = "TEXT")
    private String aiAdvice;

    @Column(name = "ai_source", length = 16)
    private String aiSource;

    @Column(name = "ai_at")
    private Long aiAt;

    /** AI 调用耗时：客户判断"这个模型够不够快"就看它 */
    @Column(name = "ai_elapsed_ms")
    private Integer aiElapsedMs;

    @Column(name = "cause_category", length = 24)
    private String causeCategory;

    @Lob
    @Column(name = "root_cause", columnDefinition = "TEXT")
    private String rootCause;

    @Lob
    @Column(name = "solution", columnDefinition = "TEXT")
    private String solution;

    @Column(name = "parts_replaced", length = 255)
    private String partsReplaced;

    @Column(name = "downtime_min")
    private Integer downtimeMin;

    @Column(name = "status", nullable = false, length = 16)
    private String status = ST_ANALYZED;

    @Column(name = "created_by", length = 64)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @Column(name = "started_at")
    private Long startedAt;

    @Column(name = "resolved_at")
    private Long resolvedAt;

    @Column(name = "resolved_by", length = 64)
    private String resolvedBy;

    // ---------- 派工（DH-85）：事前责任人，不是事后的 resolvedBy ----------
    // 注意：指派**不是状态机的一部分**（见 SPEC-DH85 §2.6）。
    // ANALYZED + 指派 仍是 ANALYZED，只是有了负责人；状态流转只由 start/resolve/cancel 控制。
    // 否则工单状态会出现两套口径。
    @Column(name = "assignee", length = 64)
    private String assignee;

    @Column(name = "assigned_at")
    private Long assignedAt;

    @Column(name = "assigned_by", length = 64)
    private String assignedBy;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public void setOrderNo(String orderNo) {
        this.orderNo = orderNo;
    }

    public Long getAlarmEventId() {
        return alarmEventId;
    }

    public void setAlarmEventId(Long alarmEventId) {
        this.alarmEventId = alarmEventId;
    }

    public String getDeviceCode() {
        return deviceCode;
    }

    public void setDeviceCode(String deviceCode) {
        this.deviceCode = deviceCode;
    }

    public String getDeviceName() {
        return deviceName;
    }

    public void setDeviceName(String deviceName) {
        this.deviceName = deviceName;
    }

    public String getAlarmCode() {
        return alarmCode;
    }

    public void setAlarmCode(String alarmCode) {
        this.alarmCode = alarmCode;
    }

    public String getAlarmText() {
        return alarmText;
    }

    public void setAlarmText(String alarmText) {
        this.alarmText = alarmText;
    }

    public String getAiAdvice() {
        return aiAdvice;
    }

    public void setAiAdvice(String aiAdvice) {
        this.aiAdvice = aiAdvice;
    }

    public String getAiSource() {
        return aiSource;
    }

    public void setAiSource(String aiSource) {
        this.aiSource = aiSource;
    }

    public Long getAiAt() {
        return aiAt;
    }

    public void setAiAt(Long aiAt) {
        this.aiAt = aiAt;
    }

    public Integer getAiElapsedMs() {
        return aiElapsedMs;
    }

    public void setAiElapsedMs(Integer aiElapsedMs) {
        this.aiElapsedMs = aiElapsedMs;
    }

    public String getCauseCategory() {
        return causeCategory;
    }

    public void setCauseCategory(String causeCategory) {
        this.causeCategory = causeCategory;
    }

    public String getRootCause() {
        return rootCause;
    }

    public void setRootCause(String rootCause) {
        this.rootCause = rootCause;
    }

    public String getSolution() {
        return solution;
    }

    public void setSolution(String solution) {
        this.solution = solution;
    }

    public String getPartsReplaced() {
        return partsReplaced;
    }

    public void setPartsReplaced(String partsReplaced) {
        this.partsReplaced = partsReplaced;
    }

    public Integer getDowntimeMin() {
        return downtimeMin;
    }

    public void setDowntimeMin(Integer downtimeMin) {
        this.downtimeMin = downtimeMin;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public Long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Long createdAt) {
        this.createdAt = createdAt;
    }

    public Long getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Long startedAt) {
        this.startedAt = startedAt;
    }

    public Long getResolvedAt() {
        return resolvedAt;
    }

    public void setResolvedAt(Long resolvedAt) {
        this.resolvedAt = resolvedAt;
    }

    public String getResolvedBy() {
        return resolvedBy;
    }

    public void setResolvedBy(String resolvedBy) {
        this.resolvedBy = resolvedBy;
    }

    public String getAssignee() {
        return assignee;
    }

    public void setAssignee(String assignee) {
        this.assignee = assignee;
    }

    public Long getAssignedAt() {
        return assignedAt;
    }

    public void setAssignedAt(Long assignedAt) {
        this.assignedAt = assignedAt;
    }

    public String getAssignedBy() {
        return assignedBy;
    }

    public void setAssignedBy(String assignedBy) {
        this.assignedBy = assignedBy;
    }
}
