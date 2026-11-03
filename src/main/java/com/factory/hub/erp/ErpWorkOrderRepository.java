package com.factory.hub.erp;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ErpWorkOrderRepository extends JpaRepository<ErpWorkOrder, Long> {

    Optional<ErpWorkOrder> findByOrderNo(String orderNo);

    /** 同一台设备同时只能有一张 RUNNING 工单：产出必须有唯一的归属 */
    List<ErpWorkOrder> findByDeviceCodeAndStatus(String deviceCode, String status);

    /** 全部在产工单：产出归属路由用（缓存刷新时一次取完，不逐台查库） */
    List<ErpWorkOrder> findByStatus(String status);

    /** 多状态查询：缺料看板要同时看「已下达」和「生产中」 */
    List<ErpWorkOrder> findByStatusIn(List<String> statuses);

    /** 某产品还没做完的工单（缺料/交期分析用） */
    List<ErpWorkOrder> findByProductSkuIdAndStatusIn(Long productSkuId, List<String> statuses);

    /** 关联销售订单的工单：订单详情里显示"这批货在哪张工单上" */
    List<ErpWorkOrder> findBySrcOrderNo(String srcOrderNo);

    /**
     * 工单列表。kw 同时搜工单号/产品编码/产品名称，status 为空表示全部。
     * 排序：在产的排前面（RUNNING > RELEASED > DRAFT > DONE/CANCELLED），同态按创建时间倒序——
     * 车间打开列表第一眼要看的就是"现在在做什么"。
     */
    @Query("select o from ErpWorkOrder o where " +
           "(:status is null or o.status = :status) and " +
           "(:kw is null or lower(o.orderNo) like lower(concat('%', :kw, '%')) " +
           " or lower(o.productCode) like lower(concat('%', :kw, '%')) " +
           " or lower(o.productName) like lower(concat('%', :kw, '%'))) " +
           "order by case o.status " +
           "  when 'RUNNING' then 0 when 'RELEASED' then 1 when 'DRAFT' then 2 else 3 end, " +
           "o.createdAt desc")
    Page<ErpWorkOrder> search(@Param("status") String status, @Param("kw") String kw, Pageable pageable);

    /** 当天最大流水号：单号生成用（与库存单同一套规则，按天重置） */
    @Query("select max(o.orderNo) from ErpWorkOrder o where o.orderNo like concat(:prefix, '%')")
    String maxNoByPrefix(@Param("prefix") String prefix);

    /** 在产/已下达/草稿的总数，概览卡片用 */
    long countByStatusIn(List<String> statuses);
}
