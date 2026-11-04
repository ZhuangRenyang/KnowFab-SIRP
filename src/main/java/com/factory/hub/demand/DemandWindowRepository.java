package com.factory.hub.demand;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface DemandWindowRepository extends JpaRepository<DemandWindow, Long> {

    List<DemandWindow> findByWindowStartBetween(long from, long to);

    List<DemandWindow> findByDeviceCodeAndWindowStartBetween(
            String deviceCode, long from, long to);

    List<DemandWindow> findByWindowStart(long windowStart);

    /** 只查指定的一组设备（进线/总表）：全厂需量口径只累加总表，机床的电已含在总表里 */
    List<DemandWindow> findByDeviceCodeInAndWindowStartBetween(
            java.util.Collection<String> deviceCodes, long from, long to);

    @Modifying
    @Transactional
    @Query("delete from DemandWindow d where d.windowStart < :before")
    int deleteOlderThan(@Param("before") long before);

    @Modifying
    @Transactional
    @Query("delete from DemandWindow")
    int purgeAll();
}
