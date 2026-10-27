package com.factory.hub.ai;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DailyDigestRepository extends JpaRepository<DailyDigest, Long> {

    Optional<DailyDigest> findByDate(String date);

    List<DailyDigest> findTop30ByOrderByDateDesc();
}
