package com.factory.hub.driver.modbus;

import com.factory.hub.model.PointDef;

/**
 * Modbus 报文工具：CRC16 校验 + 寄存器解码。
 * 全部自己实现，不依赖第三方协议库，现场出问题时你能一眼看懂、一行行断点。
 */
public final class ModbusUtil {

    private ModbusUtil() {
    }

    /** Modbus RTU 的 CRC16（多项式 0xA001，低位在前） */
    public static int crc16(byte[] data, int len) {
        int crc = 0xFFFF;
        for (int i = 0; i < len; i++) {
            crc ^= (data[i] & 0xFF);
            for (int j = 0; j < 8; j++) {
                if ((crc & 0x0001) != 0) {
                    crc >>= 1;
                    crc ^= 0xA001;
                } else {
                    crc >>= 1;
                }
            }
        }
        return crc & 0xFFFF;
    }

    /** 把 CRC 追加到帧尾（低字节在前） */
    public static byte[] withCrc(byte[] frame, int len) {
        int crc = crc16(frame, len);
        byte[] out = new byte[len + 2];
        System.arraycopy(frame, 0, out, 0, len);
        out[len] = (byte) (crc & 0xFF);
        out[len + 1] = (byte) ((crc >> 8) & 0xFF);
        return out;
    }

    /** 校验收到的帧尾 CRC 是否正确 */
    public static boolean checkCrc(byte[] frame, int len) {
        if (len < 4) {
            return false;
        }
        int expect = (frame[len - 2] & 0xFF) | ((frame[len - 1] & 0xFF) << 8);
        return crc16(frame, len - 2) == expect;
    }

    /**
     * 从寄存器字节区解码一个值。
     *
     * @param data   寄存器数据，高字节在前（Modbus 标准大端）
     * @param offset 字节偏移
     */
    public static Object decode(byte[] data, int offset, String dataType) {
        String t = dataType == null ? "u16" : dataType.toLowerCase();
        switch (t) {
            case "bool":
                return readU16(data, offset) != 0;
            case "s16":
                return (short) readU16(data, offset);
            case "u32":
                return ((long) readU16(data, offset) << 16) | readU16(data, offset + 2);
            case "s32":
                return (int) (((long) readU16(data, offset) << 16) | readU16(data, offset + 2));
            case "f32": {
                int bits = (int) (((long) readU16(data, offset) << 16) | readU16(data, offset + 2));
                return Float.intBitsToFloat(bits);
            }
            case "f64": {
                long hi = ((long) readU16(data, offset) << 16) | readU16(data, offset + 2);
                long lo = ((long) readU16(data, offset + 4) << 16) | readU16(data, offset + 6);
                return Double.longBitsToDouble((hi << 32) | lo);
            }
            case "u16":
            default:
                return readU16(data, offset);
        }
    }

    private static int readU16(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }

    /** 按点表配置做缩放换算，并按 decimals 保留小数 */
    public static Object applyScale(Object raw, PointDef p) {
        if (!(raw instanceof Number n)) {
            return raw;
        }
        double v = n.doubleValue() * p.getScale() + p.getOffset();
        int d = Math.max(0, Math.min(6, p.getDecimals()));
        double factor = Math.pow(10, d);
        double rounded = Math.round(v * factor) / factor;
        if (d == 0) {
            return (long) rounded;
        }
        return rounded;
    }

    /** 把业务值反向编码成寄存器原始值（用于写操作） */
    public static int encodeU16(Object value, PointDef p) {
        double raw = 0;
        if (value instanceof Number n) {
            raw = (n.doubleValue() - p.getOffset()) / (p.getScale() == 0 ? 1 : p.getScale());
        } else if (value instanceof Boolean b) {
            raw = b ? 1 : 0;
        }
        return (int) Math.round(raw) & 0xFFFF;
    }

    /**
     * 调紧 TCP keepalive。
     * <p>
     * Linux 默认 7200 秒（2 小时）才开始探测——无线网桥掉线、NAT 表项老化、
     * AP 重启这类"静默断链"，操作系统不会报错，socket 一直显示已连接，
     * 只有下次读写超时才会暴露。如果某台设备采集周期是 60 秒，
     * 意味着最坏要等一分钟才知道它断了；采集周期更长就更糟。
     * <p>
     * 这里改成 60 秒空闲 + 每 10 秒探一次 + 3 次失败判死，约 90 秒内能发现静默断链。
     * Windows 不支持这组参数（会抛 UnsupportedOperationException），
     * 忽略即可——那边靠读超时兜底，不影响功能。
     */
    public static void tuneKeepAlive(java.net.Socket s) {
        try {
            s.setOption(jdk.net.ExtendedSocketOptions.TCP_KEEPIDLE, 60);
            s.setOption(jdk.net.ExtendedSocketOptions.TCP_KEEPINTERVAL, 10);
            s.setOption(jdk.net.ExtendedSocketOptions.TCP_KEEPCOUNT, 3);
        } catch (Exception ignored) {
            // 平台不支持就保持系统默认，功能不受影响
        }
    }
}
