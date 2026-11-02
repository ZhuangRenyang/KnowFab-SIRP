package com.factory.hub.manual;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface ManualEntryRepository extends JpaRepository<ManualEntry, Long> {

    /** 列表：含已撤销，供「补录记录可查」 */
    List<ManualEntry> findByDayBetweenOrderByDayDescIdDesc(LocalDate from, LocalDate to);

    /** 生效中的补录，按日期升序给报表用 */
    List<ManualEntry> findByRevokedFalseAndDayBetweenOrderByDayAscIdAsc(LocalDate from, LocalDate to);

    /** 生效补录按设备汇总：[deviceCode, kwh, kwhCost, output] */
    @Query("""
            select e.deviceCode, sum(e.kwh), sum(e.kwhCost), sum(e.output)
            from ManualEntry e
            where e.revoked = false and e.day between :from and :to
            group by e.deviceCode
            """)
    List<Object[]> sumByDevice(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /** 生效补录按天汇总：[day, kwh, kwhCost, output] */
    @Query("""
            select e.day, sum(e.kwh), sum(e.kwhCost), sum(e.output)
            from ManualEntry e
            where e.revoked = false and e.day between :from and :to
            group by e.day
            """)
    List<Object[]> sumByDay(@Param("from") LocalDate from, @Param("to") LocalDate to);
}
