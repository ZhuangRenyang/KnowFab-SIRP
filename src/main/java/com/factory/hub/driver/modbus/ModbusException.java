package com.factory.hub.driver.modbus;

import java.io.IOException;

/**
 * Modbus 异常响应。现场最常见的：
 * 01 非法功能码 / 02 非法数据地址 / 03 非法数据值 / 04 从站设备故障 / 05 确认 / 06 从站忙
 */
public class ModbusException extends IOException {

    private final int functionCode;
    private final int exceptionCode;

    public ModbusException(int functionCode, int exceptionCode) {
        super("Modbus 异常: 功能码 " + functionCode + "，异常码 " + exceptionCode
                + "（" + describe(exceptionCode) + "）");
        this.functionCode = functionCode;
        this.exceptionCode = exceptionCode;
    }

    public int getFunctionCode() {
        return functionCode;
    }

    public int getExceptionCode() {
        return exceptionCode;
    }

    private static String describe(int code) {
        switch (code) {
            case 1:
                return "非法功能码，对方不支持该功能";
            case 2:
                return "非法数据地址，寄存器地址填错了或超出范围";
            case 3:
                return "非法数据值，写入值超出允许范围";
            case 4:
                return "从站设备故障，PLC 内部错误";
            case 5:
                return "确认，设备正在处理耗时命令";
            case 6:
                return "从站忙，稍后再试";
            case 8:
                return "存储奇偶校验错";
            case 10:
                return "网关路径不可用";
            case 11:
                return "网关目标设备无响应";
            default:
                return "未知异常码";
        }
    }
}
