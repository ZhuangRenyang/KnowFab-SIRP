package com.factory.hub.erp;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface ErpSalesOrderRepository extends JpaRepository<ErpSalesOrder, Long> {

    Optional<ErpSalesOrder> findByOrderNo(String orderNo);

    boolean existsByOrderNo(String orderNo);

    @Query("select count(o) from ErpSalesOrder o where o.orderNo like :prefix")
    long countByOrderNoPrefix(@Param("prefix") String prefix);

    @Query("select o from ErpSalesOrder o where " +
           "(:status = '' or o.status = :status) and " +
           "(:customerId = 0 or o.customerId = :customerId) and " +
           "(:fromTs = 0 or o.orderDate >= :fromTs) and " +
           "(:toTs = 0 or o.orderDate <= :toTs) and " +
           "(:kw = '' or lower(o.orderNo) like :kw or lower(coalesce(o.customerName, '')) like :kw " +
           "  or lower(coalesce(o.remark, '')) like :kw or lower(coalesce(o.operator, '')) like :kw)")
    Page<ErpSalesOrder> search(@Param("status") String status,
                               @Param("customerId") long customerId,
                               @Param("fromTs") long fromTs,
                               @Param("toTs") long toTs,
                               @Param("kw") String kw,
                               Pageable pageable);

    /** 本月销售额（不含已取消） */
    @Query("select coalesce(sum(o.totalAmount), 0) from ErpSalesOrder o " +
           "where o.status <> 'CANCELLED' and o.orderDate >= :fromTs and o.orderDate < :toTs")
    BigDecimal sumAmountBetween(@Param("fromTs") long fromTs, @Param("toTs") long toTs);

    long countByStatus(String status);

    /** 逾期未发：已确认/部分发货、有预计发货日、已过今天零点 */
    @Query("select count(o) from ErpSalesOrder o where o.status in ('CONFIRMED', 'PARTIAL') " +
           "and o.deliverDate is not null and o.deliverDate < :todayStart")
    long countOverdue(@Param("todayStart") long todayStart);

    @Query("select o from ErpSalesOrder o where o.status in ('CONFIRMED', 'PARTIAL') " +
           "order by case when o.deliverDate is null then 1 else 0 end, o.deliverDate asc, o.id asc")
    List<ErpSalesOrder> findOpenOrders(Pageable pageable);
}
