package com.factory.hub.quality;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;

public interface ErpQcDispositionRepository extends JpaRepository<ErpQcDisposition, Long> {

    List<ErpQcDisposition> findByDefectIdOrderBySeqNoAsc(Long defectId);

    List<ErpQcDisposition> findByDefectIdAndVoidedAtIsNullOrderBySeqNoAsc(Long defectId);

    /** 下一次处置的序号（1 起）。取 max 而不是 count：撤销过的行仍然占着序号 */
    @Query("select coalesce(max(x.seqNo), 0) from ErpQcDisposition x where x.defectId = :defectId")
    int maxSeqNo(@Param("defectId") Long defectId);

    /**
     * 已处置件数（有效消费量）= {@code sum(qty - failQty)}，只算未撤销的行。
     * <p>
     * <b>必须减 {@code failQty}</b>：返工失败的件数退回待处置了，没被真正消费掉。
     * 只加 {@code qty} 会让"6 件返工 1 件失败"的批次显示成处置完毕，
     * 那 1 件再也不会回到待处置队列——比数量算错更严重，是整条记录消失。
     */
    @Query("select coalesce(sum(x.qty - x.failQty), 0) from ErpQcDisposition x " +
           "where x.defectId = :defectId and x.voidedAt is null")
    BigDecimal sumActiveQty(@Param("defectId") Long defectId);

    /** 批量重算镜像列用：一次查多张不良单的已处置量，避免列表逐行查库 */
    @Query("select x.defectId, coalesce(sum(x.qty - x.failQty), 0) from ErpQcDisposition x " +
           "where x.defectId in :defectIds and x.voidedAt is null group by x.defectId")
    List<Object[]> sumActiveQtyByDefectIds(@Param("defectIds") List<Long> defectIds);

    /** 已撤销次数：列表上显示"已撤销 N 次"，让异常操作看得见 */
    @Query("select count(x) from ErpQcDisposition x " +
           "where x.defectId = :defectId and x.voidedAt is not null")
    long countVoided(@Param("defectId") Long defectId);

    /** 累计让价金额（未撤销） */
    @Query("select coalesce(sum(x.concessionAmount), 0) from ErpQcDisposition x " +
           "where x.defectId = :defectId and x.voidedAt is null")
    BigDecimal sumConcession(@Param("defectId") Long defectId);

    /** 质量成本汇总：按处置方式分组的件数/直接损失/让价损失 */
    @Query("select x.action, coalesce(sum(x.qty - x.failQty), 0), " +
           "coalesce(sum(x.costAmount), 0), coalesce(sum(x.concessionAmount), 0) " +
           "from ErpQcDisposition x " +
           "where x.disposedAt >= :from and x.disposedAt <= :to and x.voidedAt is null " +
           "group by x.action")
    List<Object[]> costByAction(@Param("from") long from, @Param("to") long to);

    /**
     * 未撤销的让步流水（禁发仓放行校验用）。
     * <p>
     * 放行判定是 fail-safe：<b>查不到就等于没有依据，必须拦</b>。
     * 按 SKU 过滤要 join 不良记录（本表没有 sku_id），由下一轮的放行服务负责组装，
     * 这里只提供"未撤销的让步流水"这一层。
     */
    @Query("select x from ErpQcDisposition x " +
           "where x.action = 'CONCESSION' and x.voidedAt is null order by x.disposedAt desc")
    List<ErpQcDisposition> findActiveConcessions();

    /** 按库存单据反查处置明细（作废单据时要联动，见撤销流程） */
    List<ErpQcDisposition> findByStockBillNo(String stockBillNo);
}
