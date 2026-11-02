package com.factory.hub.energy;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 能效对标值（DH-12）。
 * <p>
 * 一条记录 = 一个「对标对象 + 一套限额值」，对应国标里的一张表：
 * 例如「注塑车间单位产品综合能耗 · GB 25323-2023 · 先进值/准入值/限定值」。
 * <p>
 * <b>为什么三级值放在一条里，而不是一条一个等级：</b>
 * 国标的限额表本来就是同一产品一行三档（先进值 / 准入值 / 限定值）。
 * 拆成三条会让客户录三遍标准号，还会出现「先进值填了、限定值忘了」的半套配置。
 * <p>
 * <b>为什么单位可改：</b>离散制造按件（kWh/件），流程行业按吨/立方米
 * （kWh/t、kWh/m³）。单位只是标注，计算始终是「电量 ÷ 产量」。
 */
public class EnergyBenchmark {

    public static final String SCOPE_PLANT = "PLANT";
    public static final String SCOPE_WORKSHOP = "WORKSHOP";
    public static final String SCOPE_LINE = "LINE";
    public static final String SCOPE_DEVICE = "DEVICE";

    private String id = "";

    /** 对标对象名，如「注塑车间单位产品综合能耗」 */
    private String name = "";

    /** 标准号，如 GB 25323-2023；企业内控标准可填 Q/XXX-2026 */
    private String standardNo = "";

    /** 适用范围：PLANT / WORKSHOP / LINE / DEVICE */
    private String scopeType = SCOPE_PLANT;

    /** 具体对象：车间名 / 产线 id / 设备编码；全厂时留空 */
    private String scopeValue = "";

    /** 指标单位，默认 kWh/件 */
    private String unit = "kWh/件";

    /** 先进值（越小越好，国标里通常是最严的一档） */
    private Double advancedValue;

    /** 准入值 */
    private Double accessValue;

    /** 限定值（红线，超过就是超标） */
    private Double limitValue;

    private boolean enabled = true;

    /** 备注：标准出处、取值依据、生效日期等 */
    private String note = "";

    /** 规范化后的范围值，防止前端传小写或 null */
    public String normScope() {
        String s = scopeType == null ? "" : scopeType.trim().toUpperCase(Locale.ROOT);
        return switch (s) {
            case SCOPE_WORKSHOP, SCOPE_LINE, SCOPE_DEVICE -> s;
            default -> SCOPE_PLANT;
        };
    }

    /**
     * 配置体检。返回空列表 = 没发现问题。
     * <p>
     * 只做「会让报表算不出来或算错」的检查；限额值本身是否合理（比如客户抄错一个数量级）
     * 软件判断不了，交给客户自己核对——但三级值的大小关系能判断，必须拦下来。
     */
    public List<String> validate() {
        List<String> w = new ArrayList<>();
        if (name == null || name.isBlank()) {
            w.add("对标名称不能为空");
        }
        if (!SCOPE_PLANT.equals(normScope())
                && (scopeValue == null || scopeValue.isBlank())) {
            w.add("适用范围选了车间/产线/设备，就必须指定具体对象");
        }
        if (advancedValue == null && accessValue == null && limitValue == null) {
            w.add("先进值 / 准入值 / 限定值至少要填一个，否则无法判定");
        }
        negative(w, advancedValue, "先进值");
        negative(w, accessValue, "准入值");
        negative(w, limitValue, "限定值");
        // 能耗是「越小越好」：先进值 ≤ 准入值 ≤ 限定值
        if (advancedValue != null && accessValue != null && advancedValue > accessValue) {
            w.add("先进值应小于等于准入值（能耗越低越好）");
        }
        if (accessValue != null && limitValue != null && accessValue > limitValue) {
            w.add("准入值应小于等于限定值");
        }
        if (advancedValue != null && limitValue != null && advancedValue > limitValue) {
            w.add("先进值应小于等于限定值");
        }
        return w;
    }

    private static void negative(List<String> w, Double v, String label) {
        if (v != null && v <= 0) {
            w.add(label + "必须大于 0");
        }
    }

    /** 用于自动生成名称时的兜底，也方便日志 */
    public String displayName() {
        return (name == null || name.isBlank()) ? (standardNo == null ? "未命名对标" : standardNo) : name;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getStandardNo() {
        return standardNo;
    }

    public void setStandardNo(String standardNo) {
        this.standardNo = standardNo;
    }

    public String getScopeType() {
        return scopeType;
    }

    public void setScopeType(String scopeType) {
        this.scopeType = scopeType;
    }

    public String getScopeValue() {
        return scopeValue;
    }

    public void setScopeValue(String scopeValue) {
        this.scopeValue = scopeValue;
    }

    public String getUnit() {
        return unit;
    }

    public void setUnit(String unit) {
        this.unit = unit;
    }

    public Double getAdvancedValue() {
        return advancedValue;
    }

    public void setAdvancedValue(Double advancedValue) {
        this.advancedValue = advancedValue;
    }

    public Double getAccessValue() {
        return accessValue;
    }

    public void setAccessValue(Double accessValue) {
        this.accessValue = accessValue;
    }

    public Double getLimitValue() {
        return limitValue;
    }

    public void setLimitValue(Double limitValue) {
        this.limitValue = limitValue;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }
}
