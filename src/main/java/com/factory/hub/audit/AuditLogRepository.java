package com.factory.hub.audit;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    /**
     * 按时间 / 分类 / 关键词查。关键词同时匹配对象名、对象标识、操作人和详情——
     * 客户来问"谁改过电价"，搜「电价」两个字就能出来。
     */
    @Query("""
            select a from AuditLog a
            where a.at >= :from and a.at < :to
              and (:cat = '' or a.category = :cat)
              and (:kw = ''
                   or lower(coalesce(a.detail, '')) like :like
                   or lower(coalesce(a.targetName, '')) like :like
                   or lower(coalesce(a.target, '')) like :like
                   or lower(coalesce(a.operator, '')) like :like)
            order by a.at desc
            """)
    List<AuditLog> search(@Param("from") long from,
                          @Param("to") long to,
                          @Param("cat") String category,
                          @Param("kw") String keyword,
                          @Param("like") String like,
                          Pageable pageable);

    /**
     * 删除早于给定时间戳的记录（保留策略用）。
     * <p>
     * 审计表只增不删是"留痕"的前提，但也不能无限存——装三年会到几十万行。
     * 清理是唯一的删除路径，且只在超期时触发。
     */
    @Modifying
    @Transactional
    @Query("delete from AuditLog a where a.at < :before")
    int deleteOlderThan(@Param("before") long before);
}
