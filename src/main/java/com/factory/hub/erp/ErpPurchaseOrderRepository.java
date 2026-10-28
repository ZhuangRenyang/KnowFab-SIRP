package com.factory.hub.erp;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * 采购订单仓储。查询用空串/0 哨兵而不是 null 参数（MySQL 上类型推断更稳，同 DH-43b）。
 */
public interface ErpPurchaseOrderRepository extends JpaRepository<ErpPurchaseOrder, Long> {

    Optional<ErpPurchaseOrder> findByOrderNo(String orderNo);

    boolean existsByOrderNo(String orderNo);

    @Query("select count(o) from ErpPurchaseOrder o where o.orderNo like :prefix")
    long countByOrderNoPrefix(@Param("prefix") String prefix);

    /** 订单列表：状态 / 供应商 / 下单日期区间 / 关键词（单号、供应商名、备注、制单人） */
    @Query("select o from ErpPurchaseOrder o where " +
           "(:status = '' or o.status = :status) and " +
           "(:supplierId = 0 or o.supplierId = :supplierId) and " +
           "(:fromTs = 0 or o.orderDate >= :fromTs) and " +
           "(:toTs = 0 or o.orderDate <= :toTs) and " +
           "(:kw = '' or lower(o.orderNo) like :kw or lower(coalesce(o.supplierName, '')) like :kw " +
           "  or lower(coalesce(o.remark, '')) like :kw or lower(coalesce(o.operator, '')) like :kw)")
    Page<ErpPurchaseOrder> search(@Param("status") String status,
                                  @Param("supplierId") long supplierId,
                                  @Param("fromTs") long fromTs,
                                  @Param("toTs") long toTs,
                                  @Param("kw") String kw,
                                  Pageable pageable);

    /** 本月采购额（不含已取消） */
    @Query("select coalesce(sum(o.totalAmount), 0) from ErpPurchaseOrder o " +
           "where o.status <> 'CANCELLED' and o.orderDate >= :fromTs and o.orderDate < :toTs")
    BigDecimal sumAmountBetween(@Param("fromTs") long fromTs, @Param("toTs") long toTs);

    long countByStatus(String status);

    /** 财务要用：所有"已经发生业务"的订单（已确认及以后） */
    List<ErpPurchaseOrder> findByStatusIn(java.util.Collection<String> statuses);

    /** 逾期未到：确认/部分入库、且有预计到货日、已过今天零点 */
    @Query("select count(o) from ErpPurchaseOrder o where o.status in ('CONFIRMED', 'PARTIAL') " +
           "and o.expectDate is not null and o.expectDate < :todayStart")
    long countOverdue(@Param("todayStart") long todayStart);

    /** 待入库（含部分入库）订单，按预计到货日升序，给"今天该催哪些货"用 */
    @Query("select o from ErpPurchaseOrder o where o.status in ('CONFIRMED', 'PARTIAL') " +
           "order by case when o.expectDate is null then 1 else 0 end, o.expectDate asc, o.id asc")
    List<ErpPurchaseOrder> findOpenOrders(Pageable pageable);

    /** 报表用：某段时间内下单、且未取消的订单（按日期倒序） */
    @Query("select o from ErpPurchaseOrder o where o.status <> 'CANCELLED' " +
           "and o.orderDate >= :fromTs and o.orderDate < :toTs order by o.orderDate desc")
    List<ErpPurchaseOrder> inPeriod(@Param("fromTs") long fromTs, @Param("toTs") long toTs);
}
