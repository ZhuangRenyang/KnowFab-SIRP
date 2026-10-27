package com.factory.hub.model;

/**
 * 点表定义。一个 PointDef 对应设备上的一个数据项。
 * 整个框架里，接新设备只改这里的配置，不改代码。
 */
public class PointDef {

    /** 点标识，全局唯一（设备内唯一即可），如 status / output / spindle_speed */
    private String key;

    /** 显示名，如 "运行状态" */
    private String name;

    /** 寄存器地址（Modbus 从 0 开始） */
    private int address;

    /** 功能码：1=线圈 2=离散输入 3=保持寄存器 4=输入寄存器 */
    private int functionCode = 3;

    /** 数据类型：u16 / s16 / u32 / s32 / f32 / bool */
    private String dataType = "u16";

    /** 缩放系数，实际值 = 原始值 * scale + offset */
    private double scale = 1.0d;

    /** 偏移量 */
    private double offset = 0.0d;

    /** 单位 */
    private String unit = "";

    /** 保留小数位 */
    private int decimals = 0;

    /**
     * 业务角色，前端和统计用它区分处理方式：
     * <ul>
     *   <li><b>status</b> 设备状态机（0 停机 1 待机 2 运行 3 报警）</li>
     *   <li><b>count</b> 累计计数（产量、模次、冲次）——只显示，不做电费计算</li>
     *   <li><b>energy</b> 累计电量（kWh）——<b>必须配在只会增大的累计寄存器上</b>，
     *       系统靠它前后相减算用电量。配到瞬时功率上会导致电量算不出来，
     *       系统会提示「读数频繁变小」</li>
     *   <li><b>power</b> 瞬时有功功率（kW）——只用来显示当前负荷，配这个单位必须是 kW</li>
     *   <li><b>value</b> 普通数值（温度、转速、电流）</li>
     *   <li><b>alarm</b> 报警标志</li>
     * </ul>
     */
    private String role = "value";

    /** 该点占用的寄存器数量 */
    public int registerCount() {
        String t = dataType == null ? "u16" : dataType.toLowerCase();
        switch (t) {
            case "u32":
            case "s32":
            case "f32":
                return 2;
            case "u64":
            case "s64":
            case "f64":
                return 4;
            default:
                return 1;
        }
    }

    /** 是否为线圈/离散量（按位读取） */
    public boolean isBitType() {
        return functionCode == 1 || functionCode == 2;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getName() {
        return name == null ? key : name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getAddress() {
        return address;
    }

    public void setAddress(int address) {
        this.address = address;
    }

    public int getFunctionCode() {
        return functionCode;
    }

    public void setFunctionCode(int functionCode) {
        this.functionCode = functionCode;
    }

    public String getDataType() {
        return dataType;
    }

    public void setDataType(String dataType) {
        this.dataType = dataType;
    }

    public double getScale() {
        return scale;
    }

    public void setScale(double scale) {
        this.scale = scale;
    }

    public double getOffset() {
        return offset;
    }

    public void setOffset(double offset) {
        this.offset = offset;
    }

    public String getUnit() {
        return unit == null ? "" : unit;
    }

    public void setUnit(String unit) {
        this.unit = unit;
    }

    public int getDecimals() {
        return decimals;
    }

    public void setDecimals(int decimals) {
        this.decimals = decimals;
    }

    public String getRole() {
        return role == null ? "value" : role;
    }

    public void setRole(String role) {
        this.role = role;
    }
}
