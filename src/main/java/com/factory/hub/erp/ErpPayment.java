package com.factory.hub.erp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;

/**
 * 收付款单（ROADMAP DH-54）。
 * <p>
 * 刻意<b>不做总账</b>：没有会计科目、没有借贷分录、没有凭证。工厂要的只有三件事——
 * "客户还欠我多少、我欠供应商多少、这个月收进来/付出去多少"。
 * 这张表就是这三件事的唯一事实来源。
 * <p>
 * 两种用法：
 * <ul>
 *   <li><b>核销</b>：{@code orderNo} 指向一张销售/采购订单，金额冲抵该订单的应收/应付；
 *       金额不能超过该订单的未收/未付（防止手滑多收）。</li>
 *   <li><b>预收 / 预付</b>：{@code orderNo} 为空，挂在往来单位上，等以后有单再核销。</li>
 * </ul>
 * 作废只打 {@code voidedAt}，不进任何汇总——和库存单据一个纪律：账可以错，但不能没痕迹。
 */
@Entity
@Table(name = "erp_payment", indexes = {
        @Index(name = "idx_erp_pay_no", columnList = "pay_no", unique = true),
        @Index(name = "idx_erp_pay_partner", columnList = "partner_id"),
        @Index(name = "idx_erp_pay_order", columnList = "order_no"),
        @Index(name = "idx_erp_pay_date", columnList = "pay_date")
})
public class ErpPayment {

    /** 收款（客户给我们钱） */
    public static final String DIR_IN = "IN";
    /** 付款（我们给供应商钱） */
    public static final String DIR_OUT = "OUT";

    /** 关联销售订单 */
    public static final String ORDER_SALES = "SALES";
    /** 关联采购订单 */
    public static final String ORDER_PURCHASE = "PURCHASE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 单号：SKF + 日期 + 流水（收款/付款共用前缀，方向看 direction） */
    @Column(name = "pay_no", nullable = false, length = 40, unique = true)
    private String payNo;

    /** IN=收款，OUT=付款 */
    @Column(name = "direction", nullable = false, length = 8)
    private String direction;

    @Column(name = "partner_id")
    private Long partnerId;

    /** 往来单位名称快照 */
    @Column(name = "partner_name", length = 128)
    private String partnerName;

    /** 关联单据类型：SALES / PURCHASE；预收预付为空 */
    @Column(name = "order_type", length = 16)
    private String orderType;

    /** 关联订单号；预收预付为空 */
    @Column(name = "order_no", length = 40)
    private String orderNo;

    @Column(name = "amount", nullable = false, precision = 18, scale = 4)
    private BigDecimal amount;

    /** 收款方式：现金 / 银行转账 / 承兑汇票 / 微信 / 支付宝 / 其他 */
    @Column(name = "method", length = 24)
    private String method;

    /** 收付款日期（当天 00:00 的毫秒） */
    @Column(name = "pay_date", nullable = false)
    private long payDate = System.currentTimeMillis();

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "remark")
    private String remark;

    @Column(name = "operator", length = 64)
    private String operator;

    @Column(name = "created_at", nullable = false)
    private long createdAt = System.currentTimeMillis();

    @Column(name = "voided_at")
    private Long voidedAt;

    @Column(name = "voided_by", length = 64)
    private String voidedBy;

    public Long getId() { return id; }
    public String getPayNo() { return payNo; }
    public void setPayNo(String payNo) { this.payNo = payNo; }
    public String getDirection() { return direction; }
    public void setDirection(String direction) { this.direction = direction; }
    public Long getPartnerId() { return partnerId; }
    public void setPartnerId(Long partnerId) { this.partnerId = partnerId; }
    public String getPartnerName() { return partnerName; }
    public void setPartnerName(String partnerName) { this.partnerName = partnerName; }
    public String getOrderType() { return orderType; }
    public void setOrderType(String orderType) { this.orderType = orderType; }
    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }
    public long getPayDate() { return payDate; }
    public void setPayDate(long payDate) { this.payDate = payDate; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
    public Long getVoidedAt() { return voidedAt; }
    public void setVoidedAt(Long voidedAt) { this.voidedAt = voidedAt; }
    public String getVoidedBy() { return voidedBy; }
    public void setVoidedBy(String voidedBy) { this.voidedBy = voidedBy; }

    public boolean isVoided() { return voidedAt != null; }
}
