package com.factory.hub.erp.production;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ErpWorkOrderMaterialRepository extends JpaRepository<ErpWorkOrderMaterial, Long> {

    List<ErpWorkOrderMaterial> findByWorkOrderIdOrderBySkuIdAsc(Long workOrderId);

    void deleteByWorkOrderId(Long workOrderId);

    /**
     * 领料回写：只加 issued_qty，<b>不做上限校验</b>（允许超领，见实体注释）。
     * 用一条 UPDATE 而不是"读出来加再 save"，是为了让并发领料不会互相覆盖。
     */
    @Modifying
    @Query("update ErpWorkOrderMaterial m set m.issuedQty = m.issuedQty + :qty " +
           "where m.workOrderId = :woId and m.skuId = :skuId")
    int addIssued(@Param("woId") Long workOrderId, @Param("skuId") Long skuId,
                  @Param("qty") java.math.BigDecimal qty);

    /** 缺料清单：需求大于已领的工单用料（AI 与看板共用） */
    @Query("select m from ErpWorkOrderMaterial m where m.workOrderId in :woIds and m.needQty > m.issuedQty")
    List<ErpWorkOrderMaterial> findShortage(@Param("woIds") List<Long> workOrderIds);
}
