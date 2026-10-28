package com.factory.hub.erp;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * 收付款单仓储。所有汇总都带 {@code voidedAt is null}——作废的钱不算数。
 */
public interface ErpPaymentRepository extends JpaRepository<ErpPayment, Long> {

    Optional<ErpPayment> findByPayNo(String payNo);

    boolean existsByPayNo(String payNo);

    @Query("select count(p) from ErpPayment p where p.payNo like :prefix")
    long countByPayNoPrefix(@Param("prefix") String prefix);

    /** 收付款记录：方向 / 往来单位 / 日期区间 / 关键词 / 是否含作废 */
    @Query("select p from ErpPayment p where " +
           "(:dir = '' or p.direction = :dir) and " +
           "(:partnerId = 0 or p.partnerId = :partnerId) and " +
           "(:fromTs = 0 or p.payDate >= :fromTs) and " +
           "(:toTs = 0 or p.payDate <= :toTs) and " +
           "(:withVoided = true or p.voidedAt is null) and " +
           "(:kw = '' or lower(p.payNo) like :kw or lower(coalesce(p.partnerName, '')) like :kw " +
           "  or lower(coalesce(p.orderNo, '')) like :kw or lower(coalesce(p.remark, '')) like :kw)")
    Page<ErpPayment> search(@Param("dir") String dir,
                            @Param("partnerId") long partnerId,
                            @Param("fromTs") long fromTs,
                            @Param("toTs") long toTs,
                            @Param("withVoided") boolean withVoided,
                            @Param("kw") String kw,
                            Pageable pageable);

    /** 一批订单各自已核销的金额（收/付分开） */
    @Query("select p.orderNo, sum(p.amount) from ErpPayment p " +
           "where p.voidedAt is null and p.direction = :dir and p.orderNo in :orderNos " +
           "group by p.orderNo")
    List<Object[]> sumByOrders(@Param("orderNos") List<String> orderNos, @Param("dir") String dir);

    /** 单张订单已核销金额 */
    @Query("select coalesce(sum(p.amount), 0) from ErpPayment p " +
           "where p.voidedAt is null and p.direction = :dir and p.orderNo = :orderNo")
    BigDecimal sumByOrder(@Param("orderNo") String orderNo, @Param("dir") String dir);

    /** 期间收付款合计 */
    @Query("select coalesce(sum(p.amount), 0) from ErpPayment p " +
           "where p.voidedAt is null and p.direction = :dir and p.payDate >= :fromTs and p.payDate < :toTs")
    BigDecimal sumBetween(@Param("dir") String dir, @Param("fromTs") long fromTs, @Param("toTs") long toTs);

    /** 未关联订单的预收 / 预付总额 */
    @Query("select coalesce(sum(p.amount), 0) from ErpPayment p " +
           "where p.voidedAt is null and p.direction = :dir and p.orderNo is null")
    BigDecimal sumUnallocated(@Param("dir") String dir);

    /** 按往来单位汇总预收/预付（未关联订单的部分） */
    @Query("select p.partnerId, sum(p.amount) from ErpPayment p " +
           "where p.voidedAt is null and p.direction = :dir and p.orderNo is null and p.partnerId is not null " +
           "group by p.partnerId")
    List<Object[]> sumUnallocatedByPartner(@Param("dir") String dir);

    /** 按往来单位汇总（已核销部分） */
    @Query("select p.partnerId, sum(p.amount) from ErpPayment p " +
           "where p.voidedAt is null and p.direction = :dir and p.partnerId is not null " +
           "group by p.partnerId")
    List<Object[]> sumByPartner(@Param("dir") String dir);
}
