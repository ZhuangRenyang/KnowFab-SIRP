package com.factory.hub.erp.stock;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 单据单头仓储。
 * <p>
 * 分页查询用"哨兵值"（空串 / 0 / true）而不是 null 参数：JPQL 里
 * {@code :x is null} 在不同数据库上的类型推断不稳，MySQL 上偶发
 * "could not determine data type of parameter"，空串哨兵最稳。
 */
public interface ErpStockBillHdrRepository extends JpaRepository<ErpStockBillHdr, Long> {

    Optional<ErpStockBillHdr> findByBillNo(String billNo);

    boolean existsByBillNo(String billNo);

    /**
     * 单据列表：类型 / 时间区间 / 关键词 / 是否含生产自动入库 / 是否含零数量单。
     * 默认（withProd=false）不显示生产入库——产线 10 秒一张单，会把手工单据淹没。
     * <p>
     * 默认（includeZeroQty=false）也不显示<b>零数量的生产入库单</b>：那种单只在
     * "这一批产出全是不良"时出现（用于把不良数记到工单上），库存一分没动。
     * 列表里冒出一条 qty=0 的记录，用户第一反应是"这是什么东西"然后打电话问；
     * 数据要留着可追溯，但不该摆在日常列表里。
     */
    @Query("select h from ErpStockBillHdr h where " +
           "(:type = '' or h.billType = :type) and " +
           "(:fromTs = 0 or h.createdAt >= :fromTs) and " +
           "(:toTs = 0 or h.createdAt <= :toTs) and " +
           "(:withProd = true or h.billType <> 'PROD_IN') and " +
           "(:includeZeroQty = true or h.billType <> 'PROD_IN' or h.totalQty <> 0) and " +
           "(:kw = '' or lower(h.billNo) like :kw or lower(coalesce(h.remark, '')) like :kw " +
           "  or lower(coalesce(h.deviceCode, '')) like :kw or lower(coalesce(h.partnerName, '')) like :kw " +
           "  or lower(coalesce(h.operator, '')) like :kw)")
    Page<ErpStockBillHdr> search(@Param("type") String type,
                                @Param("fromTs") long fromTs,
                                @Param("toTs") long toTs,
                                @Param("withProd") boolean withProd,
                                @Param("includeZeroQty") boolean includeZeroQty,
                                @Param("kw") String kw,
                                Pageable pageable);

    /** 全部手工单据数（不含生产自动入库） */
    long countByBillTypeNot(String billType);

    /** 今日手工单据数（不含生产自动入库、不含作废） */
    long countByBillTypeNotAndVoidedAtIsNullAndCreatedAtGreaterThanEqual(String billType, long ts);

    /** 某类型今日单数 */
    long countByBillTypeAndCreatedAtGreaterThanEqual(String billType, long ts);

    /** 作废 / 红冲审计：按类型倒序列最近 N 张 */
    List<ErpStockBillHdr> findTop20ByOrderByCreatedAtDesc();

    /** 某张采购订单关联的入库/退货单据（订单详情页「收货记录」） */
    List<ErpStockBillHdr> findBySrcOrderNoOrderByCreatedAtDesc(String srcOrderNo);
}
