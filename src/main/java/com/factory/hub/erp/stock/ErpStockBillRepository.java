package com.factory.hub.erp.stock;

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
 * <p>
 * <b>这三个查询（{@link #aggregateStock()} / {@link #stockOf} / {@link #stockOfForUpdate}）
 * 是库存的唯一真相入口</b>（DH-80）：全系统的库存数字都必须由它们现算，
 * 不允许任何第二套算法、冗余库存表或缓存表。
 * <b>「未过账增量（pending）」不是库存</b>，它们也不包含它——pending 只作提示，
 * 由服务层以 {@code pendingQty} 单独返回，不进任何余额口径。
 */
public interface ErpStockBillRepository extends JpaRepository<ErpStockBill, Long> {

    /**
     * <b>这里是库存口径的定义处（DH-80）。</b>
     * <p>
     * 库存 = 未作废明细行的 qty 累计（正入负出），按 SKU+仓库分组；
     * <b>绝不存冗余库存数，也绝不包含未过账增量（pending）</b>。
     * <p>
     * <b>「未过账增量」不是库存</b>：它是"产量已发生、还没过账"的临时量，
     * 记的是<b>原始总产出（含不良）</b>，而本查询统计的是单据上的<b>良品量</b>——
     * 两者不是同一个数。把它混进余额，成单瞬间余额会掉一批不良数（客户看到 10 掉到 8），
     * 而且它在 JVM 内存里、{@code FOR UPDATE} 的行锁锁不住它，
     * 混进"可用量"会超卖、多实例部署还会各算各的。
     * ⇒ pending 只能作提示，由服务层以 {@code pendingQty} 单独返回。
     */
    @Query("select b.skuId, b.warehouseId, sum(b.qty) from ErpStockBill b " +
           "where b.voidedAt is null group by b.skuId, b.warehouseId")
    List<Object[]> aggregateStock();

    /** 单个 SKU 在某个仓的当前库存；没有流水返回 null */
    @Query("select sum(b.qty) from ErpStockBill b " +
           "where b.voidedAt is null and b.skuId = :skuId and b.warehouseId = :whId")
    BigDecimal stockOf(@Param("skuId") Long skuId, @Param("whId") Long whId);

    /**
     * 出库/作废/盘点前用：<b>带行锁</b>地读当前库存。
     * <p>
     * 为什么必须让数据库来锁：Java 层的 synchronized 只挡得住本进程，
     * 而且它挡不住「事务在锁外提交」这个窗口——两个销售员同时发货，
     * 各自读到同一份「够」的数量，然后各自扣减，就是超卖。
     * 这里 {@code FOR UPDATE} 把行锁到外层事务提交为止，
     * 后到的那个请求会等前一个提交完，再读到已经扣减过的库存。
     * <p>
     * 注意：这条必须在事务里调用（{@code FOR UPDATE} 需要事务）。
     */
    @Query(value = "select coalesce(sum(qty), 0) from erp_stock_bill " +
                   "where voided_at is null and sku_id = :skuId and warehouse_id = :whId for update",
            nativeQuery = true)
    BigDecimal stockOfForUpdate(@Param("skuId") Long skuId, @Param("whId") Long whId);

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
    @Query(value = "select coalesce(sum(qty), 0) from erp_stock_bill where voided_at is null " +
                   "and bill_type <> 'TRANSFER' and qty > 0 and coalesce(biz_date, created_at) >= :fromTs",
           nativeQuery = true)
    BigDecimal sumInSince(@Param("fromTs") long fromTs);

    /** 时间段内的出库合计（返回负数，调用方取反） */
    @Query(value = "select coalesce(sum(qty), 0) from erp_stock_bill where voided_at is null " +
                   "and bill_type <> 'TRANSFER' and qty < 0 and coalesce(biz_date, created_at) >= :fromTs",
           nativeQuery = true)
    BigDecimal sumOutSince(@Param("fromTs") long fromTs);

    /** 近 N 天每日出入库（趋势图）。按天分桶在库里做，避免把明细全拉回内存 */
    @Query(value = "select date_format(from_unixtime(coalesce(biz_date, created_at) / 1000), '%Y-%m-%d') as d, " +
                   "cast(sum(case when qty > 0 then qty else 0 end) as decimal(20,4)) as in_qty, " +
                   "cast(sum(case when qty < 0 then -qty else 0 end) as decimal(20,4)) as out_qty " +
                   "from erp_stock_bill where voided_at is null and bill_type <> 'TRANSFER' " +
                   "and coalesce(biz_date, created_at) >= :fromTs group by d order by d", nativeQuery = true)
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
    /**
     * 某工单的领料金额（成本核算用）——只算有效的 MATERIAL_OUT 行。
     * <p>
     * 出库行 qty 是负数，所以要 abs；单价缺失按 0 算（不能把 null 当异常，
     * 手工建单时确实可能没填价，成本会偏低但不会崩）。
     * <p>
     * 不按 BOM 标准成本算，按<b>实际领料</b>算：领多少算多少，超领也进成本——
     * 标准成本适合报价，实际成本才是月底对账要的那个数。
     */
    @Query("select coalesce(sum(abs(b.qty) * coalesce(b.unitPrice, 0)), 0) from ErpStockBill b " +
           "where b.voidedAt is null and b.billType = :type and b.srcOrderNo = :orderNo")
    java.math.BigDecimal amountByOrderAndType(@Param("orderNo") String orderNo,
                                              @Param("type") String type);

    @Query("select b.skuId, b.billType, sum(b.qty) from ErpStockBill b " +
           "where b.voidedAt is null and b.srcOrderNo = :orderNo " +
           "group by b.skuId, b.billType")
    List<Object[]> sumByOrder(@Param("orderNo") String orderNo);

    /* ==================== 报表聚合（DH-57） ==================== */
    /*
     * 报表一律"从流水现算"，不落地报表快照表：
     * 单据作废后报表必须立刻跟着变，快照表是账实不符的头号来源。
     * whId = 0 作为"全部仓库"的哨兵值（与仓储里其它查询保持同一约定）。
     */

    /** 期间某类型的数量合计（生产入库量等）；按业务日期，补录单据不会落到录入当天 */
    @Query(value = "select coalesce(sum(qty), 0) from erp_stock_bill where voided_at is null " +
                   "and bill_type = :type and coalesce(biz_date, created_at) >= :fromTs " +
                   "and coalesce(biz_date, created_at) < :toTs", nativeQuery = true)
    BigDecimal sumQtyByTypeInRange(@Param("type") String type,
                                   @Param("fromTs") long fromTs,
                                   @Param("toTs") long toTs);

    /** 进销存：期初 / 期间入 / 期间出（按 SKU+仓库），期末由服务层加出来 */
    @Query(value = "select sku_id, warehouse_id, " +
                   "cast(sum(case when coalesce(biz_date, created_at) < :fromTs then qty else 0 end) as decimal(20,4)) as opening, " +
                   "cast(sum(case when coalesce(biz_date, created_at) >= :fromTs and coalesce(biz_date, created_at) < :toTs and qty > 0 " +
                   "          then qty else 0 end) as decimal(20,4)) as in_qty, " +
                   "cast(sum(case when coalesce(biz_date, created_at) >= :fromTs and coalesce(biz_date, created_at) < :toTs and qty < 0 " +
                   "          then -qty else 0 end) as decimal(20,4)) as out_qty, " +
                   "sum(case when coalesce(biz_date, created_at) >= :fromTs and coalesce(biz_date, created_at) < :toTs then 1 else 0 end) as moves " +
                   "from erp_stock_bill where voided_at is null and (:whId = 0 or warehouse_id = :whId) " +
                   "group by sku_id, warehouse_id", nativeQuery = true)
    List<Object[]> stockLedger(@Param("fromTs") long fromTs, @Param("toTs") long toTs,
                               @Param("whId") long whId);

    /** 出入库统计：按单据类型 */
    @Query(value = "select bill_type, count(distinct bill_no) as bills, " +
                   "cast(sum(case when qty > 0 then qty else 0 end) as decimal(20,4)) as in_qty, " +
                   "cast(sum(case when qty < 0 then -qty else 0 end) as decimal(20,4)) as out_qty, " +
                   "cast(sum(case when qty > 0 then qty * coalesce(unit_price, 0) else 0 end) as decimal(20,2)) as in_amt, " +
                   "cast(sum(case when qty < 0 then -qty * coalesce(unit_price, 0) else 0 end) as decimal(20,2)) as out_amt " +
                   "from erp_stock_bill where voided_at is null " +
                   "and coalesce(biz_date, created_at) >= :fromTs and coalesce(biz_date, created_at) < :toTs and (:whId = 0 or warehouse_id = :whId) " +
                   "group by bill_type order by bill_type", nativeQuery = true)
    List<Object[]> flowByType(@Param("fromTs") long fromTs, @Param("toTs") long toTs,
                              @Param("whId") long whId);

    /** 出入库统计：按仓库 */
    @Query(value = "select warehouse_id, count(distinct bill_no) as bills, " +
                   "cast(sum(case when qty > 0 then qty else 0 end) as decimal(20,4)) as in_qty, " +
                   "cast(sum(case when qty < 0 then -qty else 0 end) as decimal(20,4)) as out_qty, " +
                   "cast(sum(case when qty > 0 then qty * coalesce(unit_price, 0) else 0 end) as decimal(20,2)) as in_amt, " +
                   "cast(sum(case when qty < 0 then -qty * coalesce(unit_price, 0) else 0 end) as decimal(20,2)) as out_amt " +
                   "from erp_stock_bill where voided_at is null " +
                   "and coalesce(biz_date, created_at) >= :fromTs and coalesce(biz_date, created_at) < :toTs " +
                   "group by warehouse_id order by warehouse_id", nativeQuery = true)
    List<Object[]> flowByWarehouse(@Param("fromTs") long fromTs, @Param("toTs") long toTs);

    /** 出入库统计：按天（趋势图） */
    @Query(value = "select date_format(from_unixtime(coalesce(biz_date, created_at) / 1000), '%Y-%m-%d') as d, " +
                   "cast(sum(case when qty > 0 then qty else 0 end) as decimal(20,4)) as in_qty, " +
                   "cast(sum(case when qty < 0 then -qty else 0 end) as decimal(20,4)) as out_qty, " +
                   "cast(sum(case when qty > 0 then qty * coalesce(unit_price, 0) else 0 end) as decimal(20,2)) as in_amt, " +
                   "cast(sum(case when qty < 0 then -qty * coalesce(unit_price, 0) else 0 end) as decimal(20,2)) as out_amt " +
                   "from erp_stock_bill where voided_at is null " +
                   "and coalesce(biz_date, created_at) >= :fromTs and coalesce(biz_date, created_at) < :toTs and (:whId = 0 or warehouse_id = :whId) " +
                   "group by d order by d", nativeQuery = true)
    List<Object[]> flowDaily(@Param("fromTs") long fromTs, @Param("toTs") long toTs,
                             @Param("whId") long whId);

    /** 出入库统计：商品动销排行（按金额） */
    @Query(value = "select sku_id, " +
                   "cast(sum(case when qty > 0 then qty else 0 end) as decimal(20,4)) as in_qty, " +
                   "cast(sum(case when qty < 0 then -qty else 0 end) as decimal(20,4)) as out_qty, " +
                   "cast(sum(abs(qty) * coalesce(unit_price, 0)) as decimal(20,2)) as amt " +
                   "from erp_stock_bill where voided_at is null " +
                   "and coalesce(biz_date, created_at) >= :fromTs and coalesce(biz_date, created_at) < :toTs and (:whId = 0 or warehouse_id = :whId) " +
                   "group by sku_id order by amt desc limit :topN", nativeQuery = true)
    List<Object[]> flowTopSkus(@Param("fromTs") long fromTs, @Param("toTs") long toTs,
                               @Param("whId") long whId, @Param("topN") int topN);

    /** 经营概况：每日采购入库额 / 销售出库额（净额，退货已冲减） */
    @Query(value = "select date_format(from_unixtime(coalesce(biz_date, created_at) / 1000), '%Y-%m-%d') as d, " +
                   "cast(sum(case when bill_type in ('PO_IN', 'PO_RETURN') " +
                   "          then qty * coalesce(unit_price, 0) else 0 end) as decimal(20,2)) as purchase, " +
                   "cast(sum(case when bill_type in ('SO_OUT', 'SO_RETURN') " +
                   "          then -qty * coalesce(unit_price, 0) else 0 end) as decimal(20,2)) as sales " +
                   "from erp_stock_bill where voided_at is null " +
                   "and coalesce(biz_date, created_at) >= :fromTs and coalesce(biz_date, created_at) < :toTs " +
                   "group by d order by d", nativeQuery = true)
    List<Object[]> tradeDaily(@Param("fromTs") long fromTs, @Param("toTs") long toTs);

    /** 经营概况：本期客户排行（按实际出库净额，含退货冲减） */
    @Query(value = "select h.partner_id, coalesce(h.partner_name, '') as pname, " +
                   "cast(sum(-b.qty * coalesce(b.unit_price, 0)) as decimal(20,2)) as amt " +
                   "from erp_stock_bill b join erp_stock_bill_hdr h on h.id = b.header_id " +
                   "where b.voided_at is null and b.bill_type in ('SO_OUT', 'SO_RETURN') " +
                   "and coalesce(b.biz_date, b.created_at) >= :fromTs and coalesce(b.biz_date, b.created_at) < :toTs and h.partner_id is not null " +
                   "group by h.partner_id, h.partner_name order by amt desc limit :topN", nativeQuery = true)
    List<Object[]> salesByPartner(@Param("fromTs") long fromTs, @Param("toTs") long toTs,
                                  @Param("topN") int topN);

    /** 经营概况：本期供应商排行（按实际入库净额，含退货冲减） */
    @Query(value = "select h.partner_id, coalesce(h.partner_name, '') as pname, " +
                   "cast(sum(b.qty * coalesce(b.unit_price, 0)) as decimal(20,2)) as amt " +
                   "from erp_stock_bill b join erp_stock_bill_hdr h on h.id = b.header_id " +
                   "where b.voided_at is null and b.bill_type in ('PO_IN', 'PO_RETURN') " +
                   "and coalesce(b.biz_date, b.created_at) >= :fromTs and coalesce(b.biz_date, b.created_at) < :toTs and h.partner_id is not null " +
                   "group by h.partner_id, h.partner_name order by amt desc limit :topN", nativeQuery = true)
    List<Object[]> purchaseByPartner(@Param("fromTs") long fromTs, @Param("toTs") long toTs,
                                     @Param("topN") int topN);
}
