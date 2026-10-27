package com.factory.hub.erp;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ErpStockBillRepository extends JpaRepository<ErpStockBill, Long> {

    /** 库存 = 流水聚合：按 SKU+仓库 汇总数量（正入负出），绝不存冗余库存数 */
    @Query("select b.skuId, b.warehouseId, sum(b.qty) from ErpStockBill b " +
           "where b.billType in :types group by b.skuId, b.warehouseId")
    List<Object[]> aggregateStock(@Param("types") List<String> types);
}
