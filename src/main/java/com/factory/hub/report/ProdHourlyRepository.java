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

    List<ProdHourly> findByDeviceCodeAndHourStartBetween(String deviceCode, long from, long to);

    /** 多条产线一次取完（避免每条产线各查一次库） */
    List<ProdHourly> findByDeviceCodeInAndHourStartBetween(Collection<String> codes, long from, long to);

    List<ProdHourly> findByHourStart(long hourStart);

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
}
