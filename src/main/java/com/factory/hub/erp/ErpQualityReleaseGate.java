package com.factory.hub.erp;

import java.util.List;

/**
 * 质量放行闸门——<b>ERP 层与质量域之间的契约</b>（QC-SPEC §2.11 / AC-15）。
 * <p>
 * <b>为什么接口放在 erp 包里</b>：依赖方向必须单向。
 * 质量域（{@code com.factory.hub.erp.quality.*}）依赖 ERP（{@code com.factory.hub.erp}），
 * 反过来就不行了。所以 ERP 层只声明"我需要一个放行闸门"这个契约，
 * 由质量域提供实现（{@code ErpQcReleaseService implements ErpQualityReleaseGate}），
 * Spring 在运行时装配。ERP 代码里不会出现任何 {@code quality} 包的引用。
 * <p>
 * <b>只有查询接口不算实现</b>：放行校验必须真的拦在出库路径上。
 * 界面上提示"这批货不能发"是可绕过的（界面关了照样提交），
 * 拦在 {@code ErpSalesService.ship} 里才是硬的（绕过前端直接调 API 一样被拒）。
 */
public interface ErpQualityReleaseGate {

    /**
     * 断言这批货能出库；不能就抛异常拒绝。
     * <p>
     * 判定是 fail-safe：<b>查不到依据就拦</b>，不许放行。
     *
     * @param whId   源仓 id
     * @param skuIds 本次出库的 SKU 列表
     * @throws IllegalArgumentException 不能出库时抛出（消息是给人看的错误说明）
     */
    void assertShippable(Long whId, List<Long> skuIds);
}
