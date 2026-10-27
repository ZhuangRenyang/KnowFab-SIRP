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

    @Modifying
    @Transactional
    @Query("delete from DemandWindow d where d.windowStart < :before")
    int deleteOlderThan(@Param("before") long before);

    @Modifying
    @Transactional
    @Query("delete from DemandWindow")
    int purgeAll();
}
