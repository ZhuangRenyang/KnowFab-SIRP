package com.factory.hub.report;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 设备产量计数器处理水位（DH-81）。
 * <p>
 * 只有两种用法：
 * <ol>
 *   <li>{@code findAll()} —— 启动时把水位灌回内存（{@code ProdRollup.restore()}）</li>
 *   <li>{@code saveAll(...)} / {@code save(...)} —— {@code scan()} 每拍同步写回，
 *       让重启后能接着上次的读数算增量</li>
 * </ol>
 * 没有自定义查询：一行一设备，主键取，不需要任何检索条件。
 */
public interface DevCounterStateRepository extends JpaRepository<DevCounterState, String> {
}
