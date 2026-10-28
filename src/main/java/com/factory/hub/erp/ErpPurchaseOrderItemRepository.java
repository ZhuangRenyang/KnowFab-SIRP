package com.factory.hub.erp;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;

public interface ErpPurchaseOrderItemRepository extends JpaRepository<ErpPurchaseOrderItem, Long> {

    List<ErpPurchaseOrderItem> findByOrderIdOrderByLineNoAsc(Long orderId);

    List<ErpPurchaseOrderItem> findByOrderIdInOrderByLineNoAsc(List<Long> orderIds);

    @Modifying
    @Query("delete from ErpPurchaseOrderItem i where i.orderId = :orderId")
    void deleteByOrderId(@Param("orderId") Long orderId);

    /** 已净入库合计（订单列表的进度条用，一次查一页，别 N+1） */
    @Query("select i.orderId, coalesce(sum(i.receivedQty), 0) from ErpPurchaseOrderItem i " +
           "where i.orderId in :orderIds group by i.orderId")
    List<Object[]> sumReceivedByOrderIds(@Param("orderIds") List<Long> orderIds);

    /** 单张订单的已入库金额 */
    @Query("select coalesce(sum(i.receivedQty * i.unitPrice), 0) from ErpPurchaseOrderItem i where i.orderId = :orderId")
    BigDecimal receivedAmountOfOrder(@Param("orderId") Long orderId);

    /** 一批订单各自的已入库金额（应付的来源） */
    @Query("select i.orderId, coalesce(sum(i.receivedQty * i.unitPrice), 0) from ErpPurchaseOrderItem i " +
           "where i.orderId in :orderIds group by i.orderId")
    List<Object[]> sumReceivedAmountByOrderIds(@Param("orderIds") List<Long> orderIds);

    @Modifying
    @Query("update ErpPurchaseOrderItem i set i.receivedQty = :qty where i.orderId = :orderId and i.skuId = :skuId")
    int updateReceived(@Param("orderId") Long orderId, @Param("skuId") Long skuId,
                       @Param("qty") BigDecimal qty);
}
