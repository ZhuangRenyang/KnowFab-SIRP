package com.factory.hub.quality;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ErpQcDefectCodeRepository extends JpaRepository<ErpQcDefectCode, Long> {

    Optional<ErpQcDefectCode> findByCode(String code);

    boolean existsByCode(String code);

    /** 代码唯一性校验（改自己时排除自己） */
    boolean existsByCodeAndIdNot(String code, Long id);

    List<ErpQcDefectCode> findByEnabledTrueOrderBySortNoAscCodeAsc();

    /**
     * 列表查询：三个条件都可为空。
     * 排序把 CR 排最前——分级决定判定后果，列表第一眼要能看到最危险的几条。
     */
    @Query("select c from ErpQcDefectCode c where " +
           "(:kw is null or lower(c.code) like lower(concat('%', :kw, '%')) " +
           " or lower(c.name) like lower(concat('%', :kw, '%')) " +
           " or lower(c.category) like lower(concat('%', :kw, '%'))) and " +
           "(:severity is null or c.severity = :severity) and " +
           "(:enabled is null or c.enabled = :enabled) " +
           "order by case c.severity when 'CR' then 0 when 'MA' then 1 else 2 end, " +
           "c.sortNo asc, c.code asc")
    List<ErpQcDefectCode> search(@Param("kw") String kw,
                                 @Param("severity") String severity,
                                 @Param("enabled") Boolean enabled);
}
