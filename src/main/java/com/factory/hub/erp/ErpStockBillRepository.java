package com.factory.hub.erp;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 单据明细行仓储。<b>库存相关的查询全部带 {@code voidedAt is null}</b>——
 * 作废单据不进库存，这是"库存 = 有效流水累计"的唯一口径。
 * <p>
 * 聚合类查询用原生 SQL：H2 已经彻底移除（2026-10-27），MySQL 是唯一目标库，
 * 按天分桶用 {@code date(from_unixtime(...))} 比把几十万行拉回 JVM 再分桶省得多。
 */
public interface ErpStockBillRepository extends JpaRepository<ErpStockBill, Long> {

    /** 库存 = 流水聚合：按 SKU+仓库 汇总数量（正入负出），绝不存冗余库存数 */
    @Query("select b.skuId, b.warehouseId, sum(b.qty) from ErpStockBill b " +
           "where b.voidedAt is null group by b.skuId, b.warehouseId")
    List<Object[]> aggregateStock();

    /** 单个 SKU 在某个仓的当前库存；没有流水返回 null */
    @Query("select sum(b.qty) from ErpStockBill b " +
           "where b.voidedAt is null and b.skuId = :skuId and b.warehouseId = :whId")
    BigDecimal stockOf(@Param("skuId") Long skuId, @Param("whId") Long whId);

    List<ErpStockBill> findByHeaderIdOrderByLineNoAsc(Long headerId);

    List<ErpStockBill> findByBillNoOrderByLineNoAsc(String billNo);

    /** 历史单据（升级前生产入库）没有单头，启动时按单号补齐 */
    @Query("select distinct b.billNo from ErpStockBill b where b.headerId is null")
    List<String> findOrphanBillNos();

    /** 作废：整单所有行一起打戳（已作废的行跳过，幂等） */
    @Modifying
    @Query("update ErpStockBill b set b.voidedAt = :ts, b.voidedBy = :by " +
           "where b.billNo = :billNo and b.voidedAt is null")
    int voidByBillNo(@Param("billNo") String billNo, @Param("ts") long ts, @Param("by") String by);

    /** 单号流水号：同前缀当天已有多少张（用明细去重，单头表可能尚未回填） */
    @Query("select count(distinct b.billNo) from ErpStockBill b where b.billNo like :prefix")
    long countByBillNoPrefix(@Param("prefix") String prefix);

    /** 时间段内的入库合计（KPI 用）；调拨是厂内搬货，不算进出，避免一笔货算两次 */
    @Query("select coalesce(sum(b.qty), 0) from ErpStockBill b where b.voidedAt is null " +
           "and b.billType <> 'TRANSFER' and b.qty > 0 and b.createdAt >= :fromTs")
    BigDecimal sumInSince(@Param("fromTs") long fromTs);

    /** 时间段内的出库合计（返回负数，调用方取反） */
    @Query("select coalesce(sum(b.qty), 0) from ErpStockBill b where b.voidedAt is null " +
           "and b.billType <> 'TRANSFER' and b.qty < 0 and b.createdAt >= :fromTs")
    BigDecimal sumOutSince(@Param("fromTs") long fromTs);

    /** 近 N 天每日出入库（趋势图）。按天分桶在库里做，避免把明细全拉回内存 */
    @Query(value = "select date_format(from_unixtime(created_at / 1000), '%Y-%m-%d') as d, " +
                   "cast(sum(case when qty > 0 then qty else 0 end) as decimal(20,4)) as in_qty, " +
                   "cast(sum(case when qty < 0 then -qty else 0 end) as decimal(20,4)) as out_qty " +
                   "from erp_stock_bill where voided_at is null and bill_type <> 'TRANSFER' " +
                   "and created_at >= :fromTs group by d order by d", nativeQuery = true)
    List<Object[]> dailyInOutSince(@Param("fromTs") long fromTs);

    /** 某个 SKU 在某仓的流水（倒序取 N 条，结存在服务层倒推） */
    @Query("select b from ErpStockBill b where b.voidedAt is null " +
           "and b.skuId = :skuId and b.warehouseId = :whId " +
           "order by b.createdAt desc, b.id desc")
    List<ErpStockBill> flowOf(@Param("skuId") Long skuId, @Param("whId") Long whId, Pageable pageable);

    /** 某仓库有流水的 SKU（库存页/盘点页先按这个集合铺，避免把全厂商品都列出来） */
    @Query(value = "select distinct sku_id from erp_stock_bill " +
                   "where voided_at is null and warehouse_id = :whId", nativeQuery = true)
    List<Long> skuIdsWithStock(@Param("whId") Long whId);

    /**
     * 某张采购订单已发生的库存流水（按 SKU + 单据类型汇总）。
     * 订单进度就靠它重算：PO_IN 正数、PO_RETURN 负数，作废行天然被排除。
     */
    @Query("select b.skuId, b.billType, sum(b.qty) from ErpStockBill b " +
           "where b.voidedAt is null and b.srcOrderNo = :orderNo " +
           "group by b.skuId, b.billType")
    List<Object[]> sumByOrder(@Param("orderNo") String orderNo);
}
