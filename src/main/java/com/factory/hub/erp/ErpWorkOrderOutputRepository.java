package com.factory.hub.erp;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ErpWorkOrderOutputRepository extends JpaRepository<ErpWorkOrderOutput, Long> {

    /** 幂等判据：这张入库单是不是已经记过工单产出了 */
    boolean existsByBillNo(String billNo);

    List<ErpWorkOrderOutput> findByWorkOrderIdOrderByCreatedAtAsc(Long workOrderId);

    List<ErpWorkOrderOutput> findByWorkOrderIdOrderByCreatedAtDesc(Long workOrderId);
}
