package com.factory.hub.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * 操作审计（DH-24）。谁在什么时候改了设备 / 电价 / 班次 / 规则，留痕可查。
 * <p>
 * <b>只增不改不删</b>：没有任何 update/delete 接口，报表里查到的就是当时写下的。
 * 客户月底发现电费和上个月差很多，能一眼看到"10-28 有人把峰电价从 1.1 改成 1.3"，
 * 而不是互相怀疑。
 * <p>
 * 不存密码、API Key 明文——detail 里只写"重置了 Key"，值本身不落审计表。
 */
@Entity
@Table(name = "audit_log",
        indexes = {
                @Index(name = "idx_audit_at", columnList = "at"),
                @Index(name = "idx_audit_cat_at", columnList = "category,at"),
                @Index(name = "idx_audit_operator", columnList = "operator,at")
        })
public class AuditLog {

    public static final String CAT_DEVICE = "DEVICE";
    public static final String CAT_ENERGY = "ENERGY";
    public static final String CAT_ALARM = "ALARM";
    public static final String CAT_REPORT = "REPORT";
    public static final String CAT_SYSTEM = "SYSTEM";
    public static final String CAT_MANUAL = "MANUAL";
    public static final String CAT_USER = "USER";
    /** 质量域：缺陷分类、抽样方案（含"确认已核对纸本国标"这类需要留痕的动作） */
    public static final String CAT_QUALITY = "QUALITY";

    public static final String ACT_CREATE = "CREATE";
    public static final String ACT_UPDATE = "UPDATE";
    public static final String ACT_DELETE = "DELETE";
    public static final String ACT_WRITE = "WRITE";
    public static final String ACT_PURGE = "PURGE";
    public static final String ACT_REVOKE = "REVOKE";
    public static final String ACT_RESET = "RESET";
    public static final String ACT_LOGIN = "LOGIN";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 发生时间（epoch ms） */
    @Column(name = "at", nullable = false)
    private long at;

    @Column(name = "category", nullable = false, length = 24)
    private String category;

    @Column(name = "action", nullable = false, length = 24)
    private String action;

    /** 对象标识：设备编码 / 设置 key / 规则 id / 用户名 */
    @Column(name = "target", length = 128)
    private String target;

    /** 对象显示名：设备名 / 方案名 / 规则名 */
    @Column(name = "target_name", length = 160)
    private String targetName;

    /** 人话摘要，给客户看的，不存 JSON */
    @Column(name = "detail", length = 2000)
    private String detail;

    @Column(name = "operator", length = 64)
    private String operator;

    @Column(name = "ip", length = 64)
    private String ip;

    /** 是否成功。失败也要留：有人反复试密码、下发写失败都是线索 */
    @Column(name = "ok")
    private boolean ok;

    /** 失败原因 */
    @Column(name = "message", length = 300)
    private String message;

    public AuditLog() {
    }

    public AuditLog(long at, String category, String action, String target, String targetName,
                    String detail, String operator, String ip, boolean ok, String message) {
        this.at = at;
        this.category = category;
        this.action = action;
        this.target = target;
        this.targetName = targetName;
        this.detail = detail;
        this.operator = operator;
        this.ip = ip;
        this.ok = ok;
        this.message = message;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public long getAt() {
        return at;
    }

    public void setAt(long at) {
        this.at = at;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getTarget() {
        return target;
    }

    public void setTarget(String target) {
        this.target = target;
    }

    public String getTargetName() {
        return targetName;
    }

    public void setTargetName(String targetName) {
        this.targetName = targetName;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public String getOperator() {
        return operator;
    }

    public void setOperator(String operator) {
        this.operator = operator;
    }

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }

    public boolean isOk() {
        return ok;
    }

    public void setOk(boolean ok) {
        this.ok = ok;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
