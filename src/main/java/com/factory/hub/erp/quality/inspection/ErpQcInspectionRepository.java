package com.factory.hub.erp.quality.inspection;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ErpQcInspectionRepository extends JpaRepository<ErpQcInspection, Long> {

    Optional<ErpQcInspection> findByInspectionNo(String inspectionNo);

    boolean existsByInspectionNo(String inspectionNo);

    /** 当天最大单号：单号生成用（与库存单同一套规则，按天重置） */
    @Query("select max(i.inspectionNo) from ErpQcInspection i where i.inspectionNo like concat(:prefix, '%')")
    String maxNoByPrefix(@Param("prefix") String prefix);

    /**
     * 窗口内的检验覆盖件数（全检按批量算，抽样按样本量算）。
     * <p>
     * 这是质检口径不良率的分母：质检判出的不良件数 ÷ 覆盖件数。
     */
    @Query("select coalesce(sum(case when i.fullInspection = true then i.lotQty " +
           "else i.sampleSize end), 0) from ErpQcInspection i " +
           "where i.createdAt >= :from and i.createdAt <= :to and i.status <> 'CANCELLED'")
    java.math.BigDecimal sumInspectedQty(@Param("from") long from, @Param("to") long to);

    /**
     * 列表。type/status 为空表示全部；时间窗按 {@code created_at}。
     * 排序：未判定的排前面（待办优先），同态按创建时间倒序。
     */
    @Query("select i from ErpQcInspection i where " +
           "(:type is null or i.inspectionType = :type) and " +
           "(:status is null or i.status = :status) and " +
           "i.createdAt >= :fromTs and (:toTs = 0 or i.createdAt <= :toTs) and " +
           "(:kw is null or lower(i.inspectionNo) like lower(concat('%', :kw, '%')) " +
           " or lower(i.skuCode) like lower(concat('%', :kw, '%')) " +
           " or lower(i.skuName) like lower(concat('%', :kw, '%')) " +
           " or lower(i.lotNo) like lower(concat('%', :kw, '%'))) " +
           "order by case i.status when 'SUBMITTED' then 0 when 'DRAFT' then 1 else 2 end, " +
           "i.createdAt desc")
    Page<ErpQcInspection> search(@Param("type") String type,
                                 @Param("status") String status,
                                 @Param("fromTs") long fromTs,
                                 @Param("toTs") long toTs,
                                 @Param("kw") String kw,
                                 Pageable pageable);
}
