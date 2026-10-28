package com.factory.hub.erp;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;

public interface ErpSalesOrderItemRepository extends JpaRepository<ErpSalesOrderItem, Long> {

    List<ErpSalesOrderItem> findByOrderIdOrderByLineNoAsc(Long orderId);

    @Modifying
    @Query("delete from ErpSalesOrderItem i where i.orderId = :orderId")
    void deleteByOrderId(@Param("orderId") Long orderId);

    @Query("select i.orderId, coalesce(sum(i.shippedQty), 0) from ErpSalesOrderItem i " +
           "where i.orderId in :orderIds group by i.orderId")
    List<Object[]> sumShippedByOrderIds(@Param("orderIds") List<Long> orderIds);

    @Modifying
    @Query("update ErpSalesOrderItem i set i.shippedQty = :qty where i.orderId = :orderId and i.skuId = :skuId")
    int updateShipped(@Param("orderId") Long orderId, @Param("skuId") Long skuId,
                      @Param("qty") BigDecimal qty);
}
