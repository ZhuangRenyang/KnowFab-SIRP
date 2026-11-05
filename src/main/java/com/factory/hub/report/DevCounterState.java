package com.factory.hub.report;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 设备产量计数器的「处理水位」：这台设备上次已经处理到哪个读数（DH-81）。
 * <p>
 * <b>这张表只解决一件事：让 {@link ProdRollup} 重启后能接着上次的读数算增量。</b>
 * <p>
 * 为什么必须有它（不是"优化"，是修丢数）：
 * {@code DevState.count} 是内存字段、初值 -1，判据是 {@code st.count >= 0 && ts > st.countTs}。
 * 服务重启后第一次采样读到的是「新读数」而不是「增量」——如果没有这张表，
 * 「重启前最后读数 100 → 重启后 110」中间那 10 件会永久丢失，
 * 而且产量报表和 ERP 入库单据<b>同时少记</b>，事后连痕迹都查不到。
 * <p>
 * <b>为什么不做历史流水（一行一版本）</b>：
 * 这张表回答的问题只有一个——「上次处理到哪儿」。存历史会让"哪一行是当前水位"
 * 变含糊，而排查"某天丢了 10 件"靠的是日志里设备号+读数，不是这张表的历史。
 * <p>
 * <b>为什么 {@code countValue} 是 double 不是 long</b>：
 * 计数器点位可能是浮点（0.1 件精度、部分 PLC 用 REAL 传计数），
 * 截断成整数会让累计误差越滚越大——正好是这张表要守住的东西。
 * <p>
 * <b>已知边界</b>：设备计数器清零/换表的同时重启，持久化的是旧读数、新读数是 0，
 * 会走 {@code delta < 0} 分支记 0 + resets++，那一段产量补不回来。
 * 这是计数器的物理限制（读数本身丢了），不是这张表没做好。
 */
@Entity
@Table(name = "dev_counter_state")
public class DevCounterState {

    /** 设备编号，一设备一行（不生成自增 id：设备号本身就是唯一键） */
    @Id
    @Column(name = "device_code", length = 64, nullable = false)
    private String deviceCode;

    /** 上次已处理到的累计产量读数。重启后从这里接着算增量 */
    @Column(name = "count_value", nullable = false)
    private double countValue;

    /** 该读数的时间戳（epoch ms）。scan() 用 ts 严格递增来防同一笔数据重复计数 */
    @Column(name = "count_ts", nullable = false)
    private long countTs;

    /** 本行最后更新时间（epoch ms）。仅排障用，不参与业务判断 */
    @Column(name = "updated_at", nullable = false)
    private long updatedAt;

    public DevCounterState() {
    }

    public DevCounterState(String deviceCode, double countValue, long countTs, long updatedAt) {
        this.deviceCode = deviceCode;
        this.countValue = countValue;
        this.countTs = countTs;
        this.updatedAt = updatedAt;
    }

    public String getDeviceCode() {
        return deviceCode;
    }

    public void setDeviceCode(String deviceCode) {
        this.deviceCode = deviceCode;
    }

    public double getCountValue() {
        return countValue;
    }

    public void setCountValue(double countValue) {
        this.countValue = countValue;
    }

    public long getCountTs() {
        return countTs;
    }

    public void setCountTs(long countTs) {
        this.countTs = countTs;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }
}
