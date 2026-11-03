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

    /**
     * 同一"基键"下的全部记录（基础行 + 所有 {@code :#n} 补记行）。
     * <p>
     * <b>设备同步的差额基准必须是这个和，不能是单条记录</b>：
     * {@code prod_hourly.defect} 是该小时该设备的<b>总</b>不良数，
     * 而某一条记录的 qty 只是其中一部分。按单条算差额会导致每轮同步都补记一次，
     * 补记行无限增殖（QA 静态验收 B2）。
     */
    @Query("select d from ErpQcDefect d where d.sourceKey = :key "
           + "or d.sourceKey like concat(:key, ':#%') order by d.id asc")
    List<ErpQcDefect> findBySourceKeyGroup(@Param("key") String key);

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
           "where d.defectCodeId = :codeId and d.voidedAt is null "
           + "and (d.reconState is null or d.reconState <> 'DROP')")
    BigDecimal sumQtyByDefectCode(@Param("codeId") Long codeId);

    /**
     * 不合格品数合计：看板"生产不良率"的分子之一。
     * <p>
     * <b>注意</b>：这个数<b>没有做通道去重</b>——同一工单的设备通道与人工通道会被相加。
     * 看板的质检口径分子必须走 {@link ErpQcChannelRule#effectiveQty}（按工单分组去重），
     * 这个方法只给"不需要去重"的场景用（例如按缺陷分类统计，那天然跨工单）。
     */
    @Query("select coalesce(sum(d.qty), 0) from ErpQcDefect d " +
           "where d.foundAt >= :from and d.foundAt <= :to and d.voidedAt is null "
           + "and (d.reconState is null or d.reconState <> 'DROP')")
    BigDecimal sumQtyInWindow(@Param("from") long from, @Param("to") long to);

    /**
     * 窗口内的全部未作废记录（给有效通道口径按工单分组用）。
     * <p>看板的质检口径必须用它而不是 {@link #sumQtyInWindow}——
     * 同一批货的设备通道与人工通道相加就是重复计数（QA 静态验收 P0-2）。
     */
    @Query("select d from ErpQcDefect d " +
           "where d.foundAt >= :from and d.foundAt <= :to and d.voidedAt is null " +
           "order by d.id asc")
    List<ErpQcDefect> findInWindow(@Param("from") long from, @Param("to") long to);

    /**
     * 帕累托：按缺陷分类汇总件数。
     * 返回 {@code [defectCodeId, defectCode, defectName, severity, sumQty]}。
     * 未分类的记录单独一行（defectCode 为 null），由调用方渲染成"未分类"——
     * 未分类不允许被静默丢弃，否则前几名会失真。
     */
    @Query("select d.defectCodeId, d.defectCode, d.defectName, d.severity, coalesce(sum(d.qty), 0) " +
           "from ErpQcDefect d where d.foundAt >= :from and d.foundAt <= :to and d.voidedAt is null " +
           "and (d.reconState is null or d.reconState <> 'DROP') " +
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
           "(:severity is null or d.severity = :severity) and " +
           "(:triaged is null or (:triaged = true and d.defectCodeId is not null) " +
           "  or (:triaged = false and d.defectCodeId is null)) and " +
           "(:partial is null or (:partial = true and d.disposedQty > 0 and d.disposedQty < d.qty) " +
           "  or (:partial = false and not (d.disposedQty > 0 and d.disposedQty < d.qty))) and " +
           "d.foundAt >= :fromTs and (:toTs = 0 or d.foundAt <= :toTs) and " +
           "(:kw is null or lower(d.defectNo) like lower(concat('%', :kw, '%')) " +
           " or lower(d.skuCode) like lower(concat('%', :kw, '%')) " +
           " or lower(d.skuName) like lower(concat('%', :kw, '%')) " +
           " or lower(d.deviceCode) like lower(concat('%', :kw, '%')) " +
           " or lower(coalesce(d.defectCode, '')) like lower(concat('%', :kw, '%'))) " +
           "order by d.foundAt desc, d.id desc")
    Page<ErpQcDefect> search(@Param("source") String source,
                             @Param("status") String status,
                             @Param("severity") String severity,
                             @Param("triaged") Boolean triaged,
                             @Param("partial") Boolean partial,
                             @Param("fromTs") long fromTs,
                             @Param("toTs") long toTs,
                             @Param("kw") String kw,
                             Pageable pageable);

    /** 看板：待处置件数按件算（不是按条算） */
    @Query("select coalesce(sum(d.qty - d.disposedQty), 0) from ErpQcDefect d " +
           "where d.status <> 'CANCELLED' and d.voidedAt is null and d.qty > d.disposedQty "
           + "and (d.reconState is null or d.reconState <> 'DROP')")
    BigDecimal pendingQtyTotal();

    /** 看板：待处置条数（副行"涉及 N 张单"） */
    @Query("select count(d) from ErpQcDefect d " +
           "where d.status = 'PENDING' and d.voidedAt is null and d.qty > d.disposedQty "
           + "and (d.reconState is null or d.reconState <> 'DROP')")
    long pendingRecordCount();

    /** 未分类条数：与件数分开给（2 条 3000 件 vs 3000 条 1 件，治理手段完全不同） */
    @Query("select count(d) from ErpQcDefect d where d.defectCodeId is null and d.voidedAt is null "
           + "and (d.reconState is null or d.reconState <> 'DROP')")
    long countUntriaged();

    /** 帕累托（按设备）：{@code [deviceCode, sumQty]} */
    @Query("select d.deviceCode, coalesce(sum(d.qty), 0) from ErpQcDefect d " +
           "where d.foundAt >= :from and d.foundAt <= :to and d.voidedAt is null " +
           "and (d.reconState is null or d.reconState <> 'DROP') " +
           "group by d.deviceCode order by coalesce(sum(d.qty), 0) desc")
    List<Object[]> paretoByDevice(@Param("from") long from, @Param("to") long to);

    /**
     * 看板：未定责的工单数。
     * <p>
     * 口径 = 「同一工单下，设备通道与人工通道<b>都有</b>记录，且还没定责」的工单数。
     * <p>
     * 为什么不是「未定责的工单数」：只有设备数据的工单不需要定责（人工那条是多余的），
     * 只有人工数据的工单也不需要（没得比）。<b>两个通道都有数、且数不一样</b>的才是账。
     * <p>
     * 没有这个数，用户不知道自己的"质检口径"里混了几笔没定责的账——
     * 那些工单的两套数还在各说各话。
     */
    @Query("select count(distinct d.workOrderNo) from ErpQcDefect d " +
           "where d.voidedAt is null and d.workOrderNo is not null and d.reconState = '' " +
           "and d.workOrderNo in (select x.workOrderNo from ErpQcDefect x " +
           "  where x.source = 'DEVICE' and x.voidedAt is null and x.workOrderNo is not null) " +
           "and d.workOrderNo in (select y.workOrderNo from ErpQcDefect y " +
           "  where y.source <> 'DEVICE' and y.voidedAt is null and y.workOrderNo is not null)")
    long countChallenged();

    /** 未分类件数：帕累托里固定有一行"未分类"，这个数不能静默变成 0 */
    @Query("select coalesce(sum(d.qty), 0) from ErpQcDefect d " +
           "where d.defectCodeId is null and d.voidedAt is null "
           + "and (d.reconState is null or d.reconState <> 'DROP')")
    BigDecimal untriagedQty();
}
