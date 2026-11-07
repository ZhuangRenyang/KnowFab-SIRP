package com.factory.hub.energy;

import com.factory.hub.model.DeviceEntity;
import com.factory.hub.service.DeviceService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link MeterScope} 的**计量口径**契约测试（2026-11-07 补）。
 * <p>
 * 为什么补：{@code EnergyService}（768 行）此前 **0 个测试**，而它算出的电量/需量直接
 * 决定客户的电费和「是否超合同需量」的告警。这条口径在 {@link MeterScope} 里，
 * 是**全系统唯一一处**定义「某范围的电量该怎么加」的地方（记忆铁律 R2）。
 * <p>
 * 要防的具体事故（MeterScope 类注释里记着实测数据）：设备上同时接了总表和机床分表，
 * 机床的电**已经含在总表里**。一起加会得到 906.8kW 而真实值是 235.8kW ——
 * <b>3.85 倍</b> ⇒ 需量虚高 ⇒ 基本电费虚高 ⇒ 合同需量告警误报「已超合同」。
 * <p>
 * 规则只有一条：**范围里有总表就只算总表；没有总表才算全部**。
 */
class MeterScopeContractTest {

    private static DeviceEntity device(String code, boolean isMeter) {
        DeviceEntity d = new DeviceEntity();
        d.setCode(code);
        d.setName(code);
        d.setIsMeter(isMeter);
        return d;
    }

    private static MeterScope scopeWith(DeviceEntity... devices) {
        DeviceService svc = mock(DeviceService.class);
        when(svc.all()).thenReturn(List.of(devices));
        // ⚠️ 必须 stub meters()：MeterScope.meterCodes() 调的是 deviceService.**meters()**，
        //    而不是「all() 里自己过滤 isMeter」（我第一版就是这么假设的 ⇒ 3 条测试全红）。
        //    教训：mock 要 mock **被实际调用的那个方法**，不是语义等价的另一个方法。
        when(svc.meters()).thenReturn(
                java.util.Arrays.stream(devices).filter(DeviceEntity::isMeter).toList());
        return new MeterScope(svc);
    }

    @Test
    @DisplayName("pick：范围里**有总表** ⇒ 只算总表（防止与机床分表重复计费）")
    void pickReturnsOnlyMeterWhenMeterExists() {
        // 全厂：总表 M1 + 两台机床（它们的电已经含在 M1 里）
        MeterScope scope = scopeWith(
                device("M1", true),
                device("CNC-01", false),
                device("CNC-02", false));

        Set<String> picked = scope.pick(List.of("M1", "CNC-01", "CNC-02"));

        // ⚠️ 若这里返回三台，就是重复计算 ⇒ 需量虚高 3.85 倍（实测数据）
        assertEquals(Set.of("M1"), picked,
                "有总表时只能算总表 —— 机床的电已经包含在总表里，一起加就是重复计算");
    }

    @Test
    @DisplayName("pick：范围里**没有总表** ⇒ 算这个范围里的全部设备（产线级没有表时是对的）")
    void pickReturnsAllWhenNoMeterInScope() {
        // 全厂有表，但这条产线上没有表 ⇒ 产线 = 该产线所有机床
        MeterScope scope = scopeWith(
                device("M1", true),
                device("CNC-01", false),
                device("CNC-02", false));

        Set<String> picked = scope.pick(List.of("CNC-01", "CNC-02"));

        assertEquals(new LinkedHashSet<>(List.of("CNC-01", "CNC-02")), picked,
                "这个范围里没有总表 ⇒ 就该算范围里的全部设备（产线级的电本来就是机床的电）");
    }

    @Test
    @DisplayName("pick：有多个总表时全部保留（车间级进线计量点各自算）")
    void pickKeepsAllMeters() {
        MeterScope scope = scopeWith(
                device("M1", true),
                device("M2", true),
                device("CNC-01", false));

        Set<String> picked = scope.pick(List.of("M1", "M2", "CNC-01"));

        assertEquals(Set.of("M1", "M2"), picked, "多个总表都要保留，不能只取第一个");
    }

    @Test
    @DisplayName("legacy：客户一个总表都没勾时返回 true（退回旧口径，不偷偷改数字）")
    void legacyWhenNoMeterConfigured() {
        // 兼容性口径：客户还没勾总表 ⇒ 数字仍是重复计算的虚高值，但**不偷偷改**
        // （发版后数字突然变了，客户会以为系统坏了）。正确做法是退回旧值 + 界面明说。
        MeterScope legacy = scopeWith(device("CNC-01", false), device("CNC-02", false));
        assertTrue(legacy.legacy(), "没勾任何总表时 legacy 必须是 true");

        MeterScope configured = scopeWith(device("M1", true), device("CNC-01", false));
        assertFalse(configured.legacy(), "勾了总表后 legacy 必须是 false");
    }

    @Test
    @DisplayName("pick：空范围返回空集（不抛异常，避免无设备时整页报表 500）")
    void pickEmptyScope() {
        MeterScope scope = scopeWith(device("M1", true));

        assertTrue(scope.pick(List.of()).isEmpty(), "空范围必须返回空集，不能抛异常");
    }
}
