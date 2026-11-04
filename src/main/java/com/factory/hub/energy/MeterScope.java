package com.factory.hub.energy;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.factory.hub.model.DeviceEntity;
import com.factory.hub.service.DeviceService;

/**
 * **计量层级的口径解析**——全系统只有这一处定义"某范围的电量/需量该怎么加"。
 * <p>
 * <b>要解决的问题</b>：设备上可能同时接了总表和分表（机床），
 * 而机床的电**已经包含在总表里**。如果把它们一起加，就是**重复计算**。
 * <p>
 * 演示库实测（2026-11-04）：总表 235.8kW + 机床之和 671kW = 906.8kW，
 * 是真实值的 <b>3.85 倍</b>（本月口径 4.6 倍）。后果是需量虚高 → 基本电费虚高 →
 * <b>合同需量告警误报「已超合同」</b>。
 * <p>
 * <b>规则只有一条</b>：<b>算某个范围时，如果这个范围里有总表，就只算总表；
 * 没有总表（那个层级本来就没有表），才算这个范围里的全部设备。</b>
 * <p>
 * 这条规则的好处是<b>不需要推断层级关系</b>：
 * <ul>
 *   <li>全厂有总表 → 全厂 = 总表 ✓</li>
 *   <li>某产线没有表 → 产线 = 该产线所有机床 ✓（产线级的电本来就是机床的电）</li>
 *   <li>某车间有表 → 车间 = 该表；产线级仍各算各的 ✓</li>
 * </ul>
 * 靠的是"这个范围里有没有总表"这个**局部判断**，而不是"谁覆盖谁"的全局推断
 * —— 后者需要可靠的父子关系，而设备的位置是自由文本，推断不可靠。
 * <p>
 * <b>标记由客户勾</b>（设备编辑 → 总表 / 进线计量点）：
 * 哪些是总表属于客户现场事实，猜错（把机床标成总表）会让电量直接错成单机功率，
 * <b>比不标更糟</b>。所以这里<b>不做任何自动推断</b>。
 * <p>
 * <b>兼容性</b>：客户还没勾任何总表时，{@link #legacy()} 为 true，
 * 各处<b>退回旧口径</b>（不偷偷改客户现有数字），但要在界面上明说——
 * 不说就等于让客户拿着一个虚高的数去跟供电局对账。
 */
@Component
public class MeterScope {

    private final DeviceService deviceService;

    public MeterScope(DeviceService deviceService) {
        this.deviceService = deviceService;
    }

    /** 所有标记为总表（进线计量点）的设备 */
    public List<DeviceEntity> meters() {
        return deviceService.meters();
    }

    /** 所有总表的设备编号 */
    public Set<String> meterCodes() {
        Set<String> s = new LinkedHashSet<>();
        for (DeviceEntity d : meters()) {
            s.add(d.getCode());
        }
        return s;
    }

    /**
     * 客户还没勾选任何总表 → 退回旧口径（各范围把设备全加起来）。
     * <p>
     * 这时数字是**重复计算过的虚高值**，但<b>不偷偷改</b>——
     * 发版后客户看到数字突然变了会以为系统坏了。正确做法是退回旧值 + 界面明说。
     */
    public boolean legacy() {
        return meterCodes().isEmpty();
    }

    /**
     * 算出这个范围里<b>哪些设备的数该计入</b>。
     * <p>
     * 有总表 → 只算总表；没有 → 全算（那个层级本来就没有表）。
     *
     * @param devices 该范围包含的设备编号
     */
    public Set<String> pick(Collection<String> devices) {
        Set<String> all = new LinkedHashSet<>(devices);
        Set<String> meters = new LinkedHashSet<>();
        for (String c : all) {
            if (meterCodes().contains(c)) {
                meters.add(c);
            }
        }
        return meters.isEmpty() ? all : meters;
    }

    /** 这个范围里有没有总表 */
    public boolean hasMeter(Collection<String> devices) {
        for (String c : devices) {
            if (meterCodes().contains(c)) {
                return true;
            }
        }
        return false;
    }
}
