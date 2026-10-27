package com.factory.hub.energy;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 能耗查询。
 * <p>
 * 注意所有区间查询都是 {@code [from, to)}，上界是「当前小时起点」——
 * 当前这一小时的数据还活在内存里（{@link EnergyRollup} 的 current 桶），
 * 数据库里那份是 10 分钟前的快照，两者不能相加，否则数据会翻倍。
 */
public interface EnergyRepository extends JpaRepository<EnergyRecord, Long> {

    /** 取某小时的全部记录，落库时用来判重更新 */
    List<EnergyRecord> findByHourStart(long hourStart);

    /** 早报要算「昨天 vs 前 7 天均值」，需要按区间取 */
    List<EnergyRecord> findByHourStartBetween(long from, long to);

    List<EnergyRecord> findByHourStartGreaterThanEqualOrderByHourStartAsc(long from);

    List<EnergyRecord> findByDeviceCodeAndHourStartBetweenOrderByHourStartAsc(
            String deviceCode, long from, long to);

    /** 按设备汇总，能耗排行用 */
    @Query("""
            select e.deviceCode, sum(e.kwh), sum(e.cost), sum(e.backfillKwh)
            from EnergyRecord e
            where e.hourStart >= :from and e.hourStart < :to
            group by e.deviceCode
            """)
    List<Object[]> sumByDevice(@Param("from") long from, @Param("to") long to);

    /** 按电价时段汇总，峰谷分布图用 */
    @Query("""
            select e.segType, sum(e.kwh), sum(e.cost)
            from EnergyRecord e
            where e.hourStart >= :from and e.hourStart < :to
            group by e.segType
            """)
    List<Object[]> sumBySegment(@Param("from") long from, @Param("to") long to);

    /** 单台设备按时段汇总 */
    @Query("""
            select e.segType, sum(e.kwh), sum(e.cost)
            from EnergyRecord e
            where e.deviceCode = :code and e.hourStart >= :from and e.hourStart < :to
            group by e.segType
            """)
    List<Object[]> sumBySegmentOfDevice(@Param("code") String code,
                                        @Param("from") long from,
                                        @Param("to") long to);

    /**
     * 按小时汇总全厂。返回行数只有「小时数」，和台数无关——
     * 500 台设备查 7 天也只有 168 行，比把 8 万行明细拉进内存再算快得多。
     */
    @Query("""
            select e.hourStart, sum(e.kwh), sum(e.cost)
            from EnergyRecord e
            where e.hourStart >= :from and e.hourStart < :to
            group by e.hourStart
            order by e.hourStart
            """)
    List<Object[]> sumByHour(@Param("from") long from, @Param("to") long to);

    /**
     * 区间总量和总电费。没数据时返回一行 [null, null]。
     * <p>
     * 必须返回 List&lt;Object[]&gt; 而不是 Object[]：单行多列的聚合查询
     * 用 Object[] 做返回类型时，Spring Data 会把整个结果集包一层，
     * 拿到的 row[0] 又是一个 Object[]，字段全读成 0（这个坑踩过）。
     */
    @Query("""
            select sum(e.kwh), sum(e.cost)
            from EnergyRecord e
            where e.hourStart >= :from and e.hourStart < :to
            """)
    List<Object[]> totalBetween(@Param("from") long from, @Param("to") long to);

    @Transactional
    @Modifying
    @Query("delete from EnergyRecord e where e.hourStart < :before")
    int deleteOlderThan(@Param("before") long before);

    /** 清空全部能耗数据。交付前清演示数据用，要二次确认 */
    @Transactional
    @Modifying
    @Query("delete from EnergyRecord e")
    int purgeAll();
}
