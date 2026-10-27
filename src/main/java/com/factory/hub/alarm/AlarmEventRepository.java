package com.factory.hub.alarm;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

public interface AlarmEventRepository extends JpaRepository<AlarmEvent, Long>,
        JpaSpecificationExecutor<AlarmEvent> {

    /** 启动时把还没恢复的告警捞回来，继续跟踪。否则重启后「一直在停」的设备不再报警 */
    List<AlarmEvent> findByStateIn(Collection<String> states);
    /** 某个规则+设备当前是否已经有未恢复的告警，用于去重 */
    List<AlarmEvent> findByRuleIdAndDeviceCodeAndStateIn(
            String ruleId, String deviceCode, Collection<String> states);

    /** 未确认的活跃告警数，页头铃铛用 */
    long countByState(String state);

    long countByStateAndLevel(String state, String level);

    long countByStartedAtGreaterThanEqual(long ts);

    long countByClearedAtGreaterThanEqual(long ts);

    /** 报表用：某段时间内开始的告警，用来统计停机原因和停机次数 */
    List<AlarmEvent> findByStartedAtBetween(long from, long to);

    /**
     * 各设备今日告警数排行。交给数据库聚合，
     * 别把一天的告警拉进内存再分组——几百台设备时差别很大。
     */
    @Query("""
            select e.deviceCode, count(e) from AlarmEvent e
            where e.startedAt >= :from
            group by e.deviceCode
            order by count(e) desc
            """)
    List<Object[]> countByDeviceSince(@Param("from") long from);

    /** 按规则统计：哪条规则最吵。吵的规则说明阈值配错了，要提醒客户 */
    @Query("""
            select e.ruleId, e.ruleName, count(e) from AlarmEvent e
            where e.startedAt >= :from
            group by e.ruleId, e.ruleName
            order by count(e) desc
            """)
    List<Object[]> countByRuleSince(@Param("from") long from);

    /** 每天新增告警数，做趋势图 */
    @Query("""
            select e.startedAt from AlarmEvent e
            where e.startedAt >= :from
            """)
    List<Long> startedTimesSince(@Param("from") long from);

    @Transactional
    @Modifying
    @Query("delete from AlarmEvent e where e.startedAt < :before and e.state = 'CLEARED'")
    int deleteClearedOlderThan(@Param("before") long before);

    @Transactional
    @Modifying
    @Query("delete from AlarmEvent e where e.startedAt < :before")
    int deleteOlderThan(@Param("before") long before);

    @Transactional
    @Modifying
    @Query("delete from AlarmEvent e")
    int purgeAll();

    /** 规则被删掉后，它的未恢复告警要一起清掉，否则永远挂在列表里 */
    @Query("""
            select e from AlarmEvent e
            where e.state in ('ACTIVE','ACKED')
            """)
    List<AlarmEvent> findOpen();

    /** 对外接口：某台设备的报警（OPEN = 未恢复的，含已确认） */
    List<AlarmEvent> findByDeviceCodeAndStateInOrderByStartedAtDesc(
            String deviceCode, java.util.Collection<String> states);

    /** 对外接口：某台设备的报警历史（含已恢复），限制条数防大表拖垮 MES 轮询 */
    List<AlarmEvent> findTop100ByDeviceCodeOrderByStartedAtDesc(String deviceCode);

    List<AlarmEvent> findTop20ByStateOrderByStartedAtDesc(String state);
}
