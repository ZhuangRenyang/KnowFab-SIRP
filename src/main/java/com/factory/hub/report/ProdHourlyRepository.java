package com.factory.hub.report;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface ProdHourlyRepository extends JpaRepository<ProdHourly, Long> {

    List<ProdHourly> findByHourStartBetween(long from, long to);

    List<ProdHourly> findByHourStart(long hourStart);

    @Modifying
    @Transactional
    @Query("delete from ProdHourly p where p.hourStart < :before")
    int deleteOlderThan(@Param("before") long before);

    @Modifying
    @Transactional
    @Query("delete from ProdHourly")
    int purgeAll();
}
