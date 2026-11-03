package com.factory.hub.quality;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface ErpQcDefectRepository extends JpaRepository<ErpQcDefect, Long> {

    Optional<ErpQcDefect> findByDefectNo(String defectNo);

    Optional<ErpQcDefect> findBySourceKey(String sourceKey);

    boolean existsBySourceKey(String sourceKey);

    long countByDefectCodeId(Long defectCodeId);

    @Query("select max(d.defectNo) from ErpQcDefect d where d.defectNo like concat(:prefix, '%')")
    String maxNoByPrefix(@Param("prefix") String prefix);

    /** 某张检验单产出的不良记录 */
    List<ErpQcDefect> findByInspectionNoOrderByIdAsc(String inspectionNo);

    /** 某张工单名下的不良记录（对账用：设备通道 vs 质量域） */
    List<ErpQcDefect> findByWorkOrderNoOrderByIdAsc(String workOrderNo);

    /** 设备通道同步：取某设备某时间窗的已同步记录（幂等键构造用，也可用于对账） */
    List<ErpQcDefect> findByDeviceCodeAndFoundAtBetween(String deviceCode, long from, long to);

    /**
     * 某个缺陷分类下的不良件数合计（未作废）。
     * <p>
     * 用途是「这个分类被用了多少」，也用于删除分类前的引用校验（这里只算件数，
     * 真正的引用计数用 {@link #countByDefectCodeId}）。
     * <b>注意</b>：它不是"待处置"——待处置要减掉处置流水，见
     * {@link ErpQcDispositionRepository#sumActiveQty}。
     */
    @Query("select coalesce(sum(d.qty), 0) from ErpQcDefect d " +
           "where d.defectCodeId = :codeId and d.voidedAt is null")
    BigDecimal sumQtyByDefectCode(@Param("codeId") Long codeId);

    /** 不合格品数合计：看板"生产不良率"的分子之一 */
    @Query("select coalesce(sum(d.qty), 0) from ErpQcDefect d " +
           "where d.foundAt >= :from and d.foundAt <= :to and d.voidedAt is null")
    BigDecimal sumQtyInWindow(@Param("from") long from, @Param("to") long to);

    /**
     * 帕累托：按缺陷分类汇总件数。
     * 返回 {@code [defectCodeId, defectCode, defectName, severity, sumQty]}。
     * 未分类的记录单独一行（defectCode 为 null），由调用方渲染成"未分类"——
     * 未分类不允许被静默丢弃，否则前几名会失真。
     */
    @Query("select d.defectCodeId, d.defectCode, d.defectName, d.severity, coalesce(sum(d.qty), 0) " +
           "from ErpQcDefect d where d.foundAt >= :from and d.foundAt <= :to and d.voidedAt is null " +
           "group by d.defectCodeId, d.defectCode, d.defectName, d.severity " +
           "order by coalesce(sum(d.qty), 0) desc")
    List<Object[]> paretoByCode(@Param("from") long from, @Param("to") long to);

    /**
     * 列表。status/source 为空表示全部；时间窗按 {@code found_at}（业务时间，不是录入时间）。
     * <p>
     * {@code status} 只有三态 PENDING / DISPOSED / CANCELLED。
     * 筛"处置了一半的"不要为此新增第四种状态（那会让 {@code status='PENDING'} 的筛选
     * 漏掉部分处置的行，8 件处置 3 件的单会从待处置队列里消失）；
     * 下一轮需要时用参数翻译成 {@code disposed_qty > 0 AND disposed_qty < qty}。
     */
    @Query("select d from ErpQcDefect d where " +
           "(:source is null or d.source = :source) and " +
           "(:status is null or d.status = :status) and " +
           "d.foundAt >= :fromTs and (:toTs = 0 or d.foundAt <= :toTs) and " +
           "(:kw is null or lower(d.defectNo) like lower(concat('%', :kw, '%')) " +
           " or lower(d.skuCode) like lower(concat('%', :kw, '%')) " +
           " or lower(d.skuName) like lower(concat('%', :kw, '%')) " +
           " or lower(d.deviceCode) like lower(concat('%', :kw, '%'))) " +
           "order by d.foundAt desc, d.id desc")
    Page<ErpQcDefect> search(@Param("source") String source,
                             @Param("status") String status,
                             @Param("fromTs") long fromTs,
                             @Param("toTs") long toTs,
                             @Param("kw") String kw,
                             Pageable pageable);

    /** 看板：待处置件数按件算（不是按条算） */
    @Query("select coalesce(sum(d.qty - d.disposedQty), 0) from ErpQcDefect d " +
           "where d.status <> 'CANCELLED' and d.voidedAt is null and d.qty > d.disposedQty")
    BigDecimal pendingQtyTotal();

    /** 看板：待处置条数（副行"涉及 N 张单"） */
    @Query("select count(d) from ErpQcDefect d " +
           "where d.status = 'PENDING' and d.voidedAt is null and d.qty > d.disposedQty")
    long pendingRecordCount();

    /** 未分类条数：与件数分开给（2 条 3000 件 vs 3000 条 1 件，治理手段完全不同） */
    @Query("select count(d) from ErpQcDefect d where d.defectCodeId is null and d.voidedAt is null")
    long countUntriaged();

    /** 帕累托（按设备）：{@code [deviceCode, sumQty]} */
    @Query("select d.deviceCode, coalesce(sum(d.qty), 0) from ErpQcDefect d " +
           "where d.foundAt >= :from and d.foundAt <= :to and d.voidedAt is null " +
           "group by d.deviceCode order by coalesce(sum(d.qty), 0) desc")
    List<Object[]> paretoByDevice(@Param("from") long from, @Param("to") long to);

    /** 未分类件数：帕累托里固定有一行"未分类"，这个数不能静默变成 0 */
    @Query("select coalesce(sum(d.qty), 0) from ErpQcDefect d " +
           "where d.defectCodeId is null and d.voidedAt is null")
    BigDecimal untriagedQty();
}
