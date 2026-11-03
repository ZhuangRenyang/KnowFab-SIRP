package com.factory.hub.report;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

public interface ProdHourlyRepository extends JpaRepository<ProdHourly, Long> {

    List<ProdHourly> findByHourStartBetween(long from, long to);

    /**
     * 时间窗 {@code [from, to)}，上界不含。
     * <p>
     * <b>不能用 {@code findByHourStartBetween}</b>：它两边都含，整点数据会被相邻两个窗口
     * 各算一次——日报算成 25 小时（多算次日 0 点那一小时），数据完整度算出 102%，
     * AI 早报的产量还会跟报表对不上。窗口是半开区间，边界那一小时归后一天。
     */
    @Query("select p from ProdHourly p where p.hourStart >= :from and p.hourStart < :to order by p.hourStart")
    List<ProdHourly> findInWindow(@Param("from") long from, @Param("to") long to);

    List<ProdHourly> findByDeviceCodeAndHourStartBetween(String deviceCode, long from, long to);

    /** 多条产线一次取完（避免每条产线各查一次库） */
    List<ProdHourly> findByDeviceCodeInAndHourStartBetween(Collection<String> codes, long from, long to);

    List<ProdHourly> findByHourStart(long hourStart);

    /**
     * 某设备在 {@code [from, to)} 内的运行秒数——成本核算里的"真工时"。
     * <p>
     * 为什么不用「开工到完工」的墙钟时间：那里面包含停机、待料、换班，
     * 拿它乘人工单价会把停机时间也算成人工成本，算出来的单位成本必然偏高。
     * 设备运行时长才是真正的人在干活的时间。
     */
    @Query("select coalesce(sum(p.runSec), 0) from ProdHourly p " +
           "where p.deviceCode = :code and p.hourStart >= :from and p.hourStart < :to")
    double runSecOf(@Param("code") String code, @Param("from") long from, @Param("to") long to);


    /** 单设备单小时的精确行（内存桶重启后从这里继承累计，防覆盖写冲掉已落库增量） */
    ProdHourly findByDeviceCodeAndHourStart(String deviceCode, long hourStart);

    /** 该设备最早的一条小时产量（综合表"从系统运行时算起"的起点）；无数据返回 null */
    @Query("select min(p.hourStart) from ProdHourly p where p.deviceCode = :code")
    Long minHourStart(@Param("code") String code);

    @Modifying
    @Transactional
    @Query("delete from ProdHourly p where p.hourStart < :before")
    int deleteOlderThan(@Param("before") long before);

    @Modifying
    @Transactional
    @Query("delete from ProdHourly")
    int purgeAll();

    /** 删除设备时清掉它的全部产量痕迹（今日/本月/今年综合表的数据源都在这） */
    @Modifying
    @Transactional
    @Query("delete from ProdHourly p where p.deviceCode = :code")
    int deleteByDeviceCode(@Param("code") String code);
}
