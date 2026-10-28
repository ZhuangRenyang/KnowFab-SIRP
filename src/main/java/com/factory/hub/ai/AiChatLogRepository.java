package com.factory.hub.ai;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface AiChatLogRepository extends JpaRepository<AiChatLog, Long> {

    /** 只取本人的，按时间正序（前端直接按顺序渲染） */
    List<AiChatLog> findByUsernameOrderByCreatedAtAsc(String username, Pageable pageable);

    @Modifying
    @Transactional
    int deleteByUsername(String username);

    @Modifying
    @Transactional
    @Query("delete from AiChatLog c where c.createdAt < :before")
    int deleteOlderThan(@Param("before") long before);
}
