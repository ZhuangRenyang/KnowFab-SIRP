package com.factory.hub.erp.production;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/**
 * 工单产出流水（DH-71）：一次入库对应一条，{@code bill_no} 唯一。
 * <p>
 * <b>为什么要单独一张表，而不是直接改工单上的 doneQty</b>：设备产出是 10 秒一批、
 * 断线还会补记，重试和重放都有可能。如果只累加一个数字，重复入库一次就永久多算，
 * 而且没有任何地方能查出"哪一笔是重的"。
 * <p>
 * 这里用 {@code bill_no} 做唯一约束：同一张入库单只能记一次工单产出，
 * 定时器重跑、异常重试都会撞到唯一键上，被当成重复而跳过——<b>幂等由数据库保证，
 * 不靠应用层"我记得我写过"</b>。
 * <p>
 * 顺带的好处：任何一个工单的产出都能按时间摊开，追溯"这个 12 件是哪台机、哪一分钟出的"。
 */
@Entity
@Table(name = "erp_work_order_output", indexes = {
        @Index(name = "uk_woo_bill", columnList = "bill_no", unique = true),
        @Index(name = "idx_woo_wo", columnList = "work_order_id"),
        @Index(name = "idx_woo_device", columnList = "device_code"),
        @Index(name = "idx_woo_created", columnList = "created_at")
})
public class ErpWorkOrderOutput {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "work_order_id", nullable = false)
    private Long workOrderId;

    @Column(name = "work_order_no", length = 40)
    private String workOrderNo;

    /** 对应的生产入库单号，唯一 —— 防重复入账的最后一道闸 */
    @Column(name = "bill_no", nullable = false, length = 40)
    private String billNo;

    @Column(name = "device_code", length = 64)
    private String deviceCode;

    /**
     * 良品数量（<b>已进库存</b>的那部分，与 {@code PROD_IN} 单据的数量一致）。
     * <p>
     * 口径钉死在这里：{@code qty} = 良品数，{@link #defectQty} = 不良数，
     * 两者相加 = 总产出。这样 {@code sum(qty)} 与工单的 {@code doneQty} 永远相等，
     * 客户拿两个数一对就能验证。
     * <p>
     * <b>注意反冲不按这个数</b>：领料要按 {@code qty + defectQty} 扣，因为不良件的料
     * 也是真消耗掉了。写在 {@code ErpWorkOrderService.produce()} 里。
     */
    @Column(name = "qty", nullable = false, precision = 18, scale = 4)
    private BigDecimal qty = BigDecimal.ZERO;

    /** 不良品数量：只记数，不进良品库存（报废/待处理走各自的单据） */
    @Column(name = "defect_qty", nullable = false, precision = 18, scale = 4)
    private BigDecimal defectQty = BigDecimal.ZERO;

    /** 本批总产出 = 良品 + 不良。只存在于计算里，不落列（免得两份真值） */
    public BigDecimal totalQty() {
        return qty.add(defectQty);
    }

    @Column(name = "created_at", nullable = false)
    private long createdAt = System.currentTimeMillis();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getWorkOrderId() { return workOrderId; }
    public void setWorkOrderId(Long workOrderId) { this.workOrderId = workOrderId; }
    public String getWorkOrderNo() { return workOrderNo; }
    public void setWorkOrderNo(String workOrderNo) { this.workOrderNo = workOrderNo; }
    public String getBillNo() { return billNo; }
    public void setBillNo(String billNo) { this.billNo = billNo; }
    public String getDeviceCode() { return deviceCode; }
    public void setDeviceCode(String deviceCode) { this.deviceCode = deviceCode; }
    public BigDecimal getQty() { return qty; }
    public void setQty(BigDecimal qty) { this.qty = qty; }
    public BigDecimal getDefectQty() { return defectQty; }
    public void setDefectQty(BigDecimal defectQty) { this.defectQty = defectQty; }
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
}
