package com.factory.hub.erp;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ErpMaintOrderRepository extends JpaRepository<ErpMaintOrder, Long> {

    Optional<ErpMaintOrder> findByOrderNo(String orderNo);

    /**
     * 我的待办（DH-85）：派给某人的、未结案的工单。
     * <p>
     * 未结案 = ANALYZED（已分析待处理）+ IN_PROGRESS（处理中）。
     * 走 idx_mo_assignee(assignee, status) 索引。
     */
    List<ErpMaintOrder> findByAssigneeAndStatusInOrderByAssignedAtDesc(
            String assignee, List<String> statuses);

    /**
     * 历史样本：同一台设备 + 同一个报警码，已经结案的维修记录。
     * <p>
     * 这是「AI 会越用越准」的数据来源——下次同类报警时把这些当参考喂给模型，
     * 它就能说出「上次这台机 AL.33 是再生电阻接线松了」，而不是给一堆通用排查步骤。
     * <p>
     * 只取 RESOLVED：没结案的不算经验（可能压根没修好，或者误报）。
     * 按 resolvedAt 倒序：越近的经验越可能是同一批设备状态下的。
     */
    List<ErpMaintOrder> findByDeviceCodeAndAlarmCodeAndStatusOrderByResolvedAtDesc(
            String deviceCode, String alarmCode, String status, Pageable pageable);

    Page<ErpMaintOrder> findByStatusOrderByCreatedAtDesc(String status, Pageable pageable);

    Page<ErpMaintOrder> findByOrderByCreatedAtDesc(Pageable pageable);

    long countByStatus(String status);

    /** 按原因分类统计（只统计已结案的）：哪类故障最常见，备件与预防性维护就照这个排 */
    @Query("select m.causeCategory, count(m) from ErpMaintOrder m "
            + "where m.status = 'RESOLVED' and m.causeCategory is not null "
            + "group by m.causeCategory order by count(m) desc")
    List<Object[]> countByCauseCategory();

    /** 按设备统计：哪台机最爱坏 */
    @Query("select m.deviceCode, m.deviceName, count(m), avg(m.downtimeMin) from ErpMaintOrder m "
            + "where m.status = 'RESOLVED' group by m.deviceCode, m.deviceName "
            + "order by count(m) desc")
    List<Object[]> countByDevice();

    /** 按报警码统计：哪个码最常见（积累够了就能把它从"查手册"变成字典里的已知码） */
    @Query("select m.alarmCode, count(m), avg(m.downtimeMin) from ErpMaintOrder m "
            + "where m.status = 'RESOLVED' and m.alarmCode is not null "
            + "group by m.alarmCode order by count(m) desc")
    List<Object[]> countByAlarmCode();

    /** 某台设备最近一条没结案的维修单（防止同一个报警重复建单） */
    List<ErpMaintOrder> findByDeviceCodeAndStatusInOrderByCreatedAtDesc(
            String deviceCode, List<String> statuses, Pageable pageable);

    /** 同一条报警是否已经有未结案的维修单（防止用户点两次建出两张单） */
    List<ErpMaintOrder> findByAlarmEventIdAndStatusInOrderByCreatedAtDesc(
            Long alarmEventId, List<String> statuses);

    /** 当天最大单号，用于生成下一个序号 */
    @Query("select max(m.orderNo) from ErpMaintOrder m where m.orderNo like concat(:prefix, '%')")
    String findMaxOrderNo(@Param("prefix") String prefix);

    @Query("select m from ErpMaintOrder m where m.status = :status "
            + "and (m.deviceCode like %:kw% or m.deviceName like %:kw% or m.orderNo like %:kw%) "
            + "order by m.createdAt desc")
    Page<ErpMaintOrder> searchByStatus(@Param("status") String status,
                                      @Param("kw") String kw, Pageable pageable);

    @Query("select m from ErpMaintOrder m where "
            + "m.deviceCode like %:kw% or m.deviceName like %:kw% or m.orderNo like %:kw% "
            + "order by m.createdAt desc")
    Page<ErpMaintOrder> search(@Param("kw") String kw, Pageable pageable);
}
