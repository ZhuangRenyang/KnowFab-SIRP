package com.factory.hub.erp.quality.inspection;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface ErpQcAqlPlanRepository extends JpaRepository<ErpQcAqlPlan, Long> {

    /**
     * 按 (水平, 严格度, 批量, AQL) 定位那一格。
     * <p>
     * 批量用 {@code between} 落在 [lotFrom, lotTo] 区间里；{@code lotTo} 的最大档是
     * 999999999 哨兵，所以超大批量也能命中。
     * <p>
     * 用 {@code aql = :aql} 精确匹配：AQL 是 scale=4 的定点数，0.65 进来就是 0.6500，
     * 不存在浮点误差。区间匹配（比如"AQL ≤ 1.0 里最大的那档"）会让"配错 AQL 也能算出结果"，
     * 那等于把配错藏起来——查不到就该报错让人去补，见 {@link ErpAqlService#resolve}。
     */
    @Query("select p from ErpQcAqlPlan p where p.level = :level and p.strictLevel = :strict " +
           "and :lotQty between p.lotFrom and p.lotTo and p.aql = :aql")
    Optional<ErpQcAqlPlan> findPlan(@Param("level") String level,
                                    @Param("strict") String strict,
                                    @Param("lotQty") int lotQty,
                                    @Param("aql") BigDecimal aql);

    List<ErpQcAqlPlan> findByLevelAndStrictLevelOrderByAqlAscLotFromAsc(String level, String strictLevel);

    /** 方案表页：全表按水平/严格度/AQL/批量排序，一行就是国标的一格 */
    @Query("select p from ErpQcAqlPlan p order by p.level, p.strictLevel, p.aql, p.lotFrom")
    List<ErpQcAqlPlan> findAllOrdered();

    /**
     * 方案表里实际存在的「检验水平 + 严格度」组合及其方案数。
     * <p>
     * 前端的抽样下拉要按这个来——界面不能列出没有数据的选择项，
     * 让用户选了必然报"没配方案"。
     * <p>
     * 用 distinct 而不是 group by：组合数很少，扫一遍全表更简单，
     * 而且顺带能把方案数带出来（界面上可以显示"共 N 条方案"）。
     */
    @Query("select p.level, p.strictLevel, count(p) from ErpQcAqlPlan p "
           + "group by p.level, p.strictLevel order by p.level, p.strictLevel")
    List<Object[]> listAvailableCombos();

    long countByLevelAndStrictLevel(String level, String strictLevel);
}
