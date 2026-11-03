package com.factory.hub.erp;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ErpWorkOrderOutputRepository extends JpaRepository<ErpWorkOrderOutput, Long> {

    /** 幂等判据：这张入库单是不是已经记过工单产出了 */
    boolean existsByBillNo(String billNo);

    List<ErpWorkOrderOutput> findByWorkOrderIdOrderByCreatedAtAsc(Long workOrderId);

    List<ErpWorkOrderOutput> findByWorkOrderIdOrderByCreatedAtDesc(Long workOrderId);

    /**
     * 某台设备在某个时间窗内的产出流水（设备不良同步按小时找归属用）。
     * <p>
     * 为什么按流水找而不是按"当前在产工单"：一小时里可能换过工单
     * （10:30 完工、10:40 开新单），那一小时的产出算给谁要看流水。
     */
    List<ErpWorkOrderOutput> findByDeviceCodeAndCreatedAtBetweenOrderByCreatedAtAsc(
            String deviceCode, long from, long to);
}
