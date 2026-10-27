package com.factory.hub.discovery;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * 极简 Modbus 客户端，给「设备发现」用。
 * 支持两种帧格式：
 * - tcp：Modbus TCP（MBAP 头），网口直连设备
 * - rtu：Modbus RTU over TCP（串口服务器透传），485 设备挂盒子后面
 *
 * 只做两件事：测端口通不通、读寄存器。现场拿不到厂家地址表时，
 * 先用它把网段扫一遍、把 0-100 号寄存器读出来对拍找点。
 *
 * 红线：只实现功能码 03/04（读保持寄存器 / 读输入寄存器）。
 * 探测工具绝不能碰写功能码（06/16）——写错地址会改掉设备参数，
 * 这比接不上设备严重得多。
 */
final class ModbusTcpProbe {

    /** 帧格式：mbap = Modbus TCP；rtu = RTU over TCP（透传盒子） */
    static final String FRAMING_TCP = "tcp";
    static final String FRAMING_RTU = "rtu";

    private ModbusTcpProbe() {
    }

    /** 端口能否建立 TCP 连接（对 Modbus TCP 设备通常就是 502）。 */
    static boolean portOpen(String host, int port, int timeoutMs) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 读保持/输入寄存器或离散输入，返回无符号值数组。
     * fc2 = 离散输入（DI 通道，返回 0/1）；fc3 = 保持寄存器；fc4 = 输入寄存器。
     * 独立实现而不复用采集模块：采集那边是长连接轮询，
     * 发现这边要的是"对任意 IP 一次性短连接探测"，复用反而互相牵制。
     */
    static int[] readRegisters(String host, int port, int unitId,
                               int fc, int from, int count, int timeoutMs, String framing)
            throws IOException {
        if (fc != 2 && fc != 3 && fc != 4) {
            throw new IOException("只支持功能码 02/03/04（全是读，探测工具不写）");
        }
        if (unitId < 1 || unitId > 247) {
            throw new IOException("从站地址限 1-247");
        }
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            s.setSoTimeout(timeoutMs);
            DataOutputStream out = new DataOutputStream(s.getOutputStream());
            DataInputStream in = new DataInputStream(s.getInputStream());
            if (FRAMING_RTU.equals(framing)) {
                return readRtu(in, out, unitId, fc, from, count);
            }
            return readMbap(in, out, unitId, fc, from, count);
        }
    }

    /** Modbus TCP：MBAP 头 + PDU，读响应。 */
    private static int[] readMbap(DataInputStream in, DataOutputStream out,
                                  int unitId, int fc, int from, int count) throws IOException {
        int tid = (int) (System.nanoTime() & 0xFFFF);
        // PDU：功能码 + 起始地址(2) + 数量(2)
        byte[] pdu = {(byte) fc, (byte) (from >> 8), (byte) from,
                      (byte) (count >> 8), (byte) count};
        // MBAP：事务号(2) + 协议号0(2) + 长度(2) + 从站号(1)
        byte[] mbap = {(byte) (tid >> 8), (byte) tid, 0, 0,
                       0, (byte) (pdu.length + 1), (byte) unitId};
        out.write(mbap);
        out.write(pdu);
        out.flush();

        int rid = in.readUnsignedShort();
        int proto = in.readUnsignedShort();
        int len = in.readUnsignedShort();
        if (proto != 0 || len < 2 || len > 260) {
            throw new IOException("不是 Modbus TCP 响应（协议号/长度异常），端口开着但可能不是 Modbus——也可能是 RTU 透传盒子，试试切到 RTU 帧");
        }
        in.readUnsignedByte(); // 从站号回显，不校验（有的设备会改写）
        int rfc = in.readUnsignedByte();
        if (rid != tid) {
            throw new IOException("事务号不匹配，串包了");
        }
        if (rfc == ((fc | 0x80) & 0xFF)) {
            int code = in.readUnsignedByte();
            throw new IOException("从站返回 Modbus 异常码 " + code
                    + "（常见：2=地址非法 3=数量非法 11=从站忙）");
        }
        if (rfc != fc) {
            throw new IOException("功能码不匹配");
        }
        int byteCount = in.readUnsignedByte();
        if (fc == 2) {
            // 离散输入：按位打包，每字节 8 个通道，低位在前。
            // 先按字节读完，再解位——解位循环里不能碰流（4 通道只有 1 字节）
            if (byteCount != (count + 7) / 8) {
                throw new IOException("返回字节数不符（要 " + (count + 7) / 8 + " 实得 " + byteCount + "）");
            }
            byte[] packed = new byte[byteCount];
            for (int i = 0; i < byteCount; i++) {
                packed[i] = (byte) in.readUnsignedByte();
            }
            int[] vals = new int[count];
            for (int i = 0; i < count; i++) {
                vals[i] = (packed[i / 8] >> (i % 8)) & 1;
            }
            return vals;
        }
        if (byteCount != count * 2) {
            throw new IOException("返回字节数不符（要 " + count * 2 + " 实得 " + byteCount + "）");
        }
        int[] vals = new int[count];
        for (int i = 0; i < count; i++) {
            vals[i] = in.readUnsignedShort();
        }
        return vals;
    }

    /**
     * RTU over TCP：从站号+FC+数据+CRC16，读响应。
     * 串口服务器只做透传，帧格式和 485 线上一模一样。
     * 返回 null 表示从站没应答（超时/地址上没挂设备）——485 总线从站对
     * 别人的地址保持沉默是常态，不算错误。
     * CRC 必须验：透传链路上一个坏字节就是一条假数据。
     */
    static int[] readRtuOrNull(DataInputStream in, DataOutputStream out,
                               int unitId, int fc, int from, int count) throws IOException {
        byte[] req = new byte[8];
        req[0] = (byte) unitId;
        req[1] = (byte) fc;
        req[2] = (byte) (from >> 8);
        req[3] = (byte) from;
        req[4] = (byte) (count >> 8);
        req[5] = (byte) count;
        int crc = crc16(req, 6);
        req[6] = (byte) (crc & 0xFF);        // 低字节在前（Modbus 规定）
        req[7] = (byte) ((crc >> 8) & 0xFF);
        out.write(req);
        out.flush();

        // 响应帧：从站号 FC 字节数 数据... CRC(2)。正常 5+2N 字节，异常 5 字节
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        int[] head = new int[3];
        for (int i = 0; i < 3; i++) {
            int b = in.read();
            if (b < 0) return null;          // 对端关闭 = 无应答
            head[i] = b;
            buf.write(b);
        }
        if (head[0] != unitId) return null;
        if (head[1] == ((fc | 0x80) & 0xFF)) {
            int code = in.read();
            if (code < 0) return null;
            buf.write(code);
            int lo = in.read(), hi = in.read();
            if (lo < 0 || hi < 0) return null;
            buf.write(lo);
            buf.write(hi);
            byte[] ex = buf.toByteArray();
            if (!crcOk(ex)) return null;
            throw new IOException("从站 " + unitId + " 返回 Modbus 异常码 " + code
                    + "（地址/数量不被该从站支持）");
        }
        if (head[1] != fc) return null;
        int byteCount = head[2];
        int expectBytes = (fc == 2) ? (count + 7) / 8 : count * 2;
        if (byteCount < 1 || byteCount > 250 || byteCount != expectBytes) return null;
        byte[] data = new byte[byteCount];
        for (int i = 0; i < byteCount; i++) {
            int b = in.read();
            if (b < 0) return null;
            data[i] = (byte) b;
            buf.write(b);
        }
        int lo = in.read(), hi = in.read();
        if (lo < 0 || hi < 0) return null;
        buf.write(lo);
        buf.write(hi);
        if (!crcOk(buf.toByteArray())) return null;   // CRC 错 = 线上有干扰或不是 Modbus 设备
        int[] vals = new int[count];
        if (fc == 2) {
            for (int i = 0; i < count; i++) {
                vals[i] = (data[i / 8] >> (i % 8)) & 1;
            }
        } else {
            for (int i = 0; i < count; i++) {
                vals[i] = ((data[i * 2] & 0xFF) << 8) | (data[i * 2 + 1] & 0xFF);
            }
        }
        return vals;
    }

    private static int[] readRtu(DataInputStream in, DataOutputStream out,
                                 int unitId, int fc, int from, int count) throws IOException {
        int[] vals = readRtuOrNull(in, out, unitId, fc, from, count);
        if (vals == null) {
            throw new IOException("从站 " + unitId + " 无应答（该地址可能没挂设备，点「扫从站」找活的）");
        }
        return vals;
    }

    /** 从站探活：读 0 号寄存器 1 个，有响应就是活的。给「扫从站」用。 */
    static boolean unitAlive(String host, int port, int unitId, int timeoutMs, String framing) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            s.setSoTimeout(timeoutMs);
            DataOutputStream out = new DataOutputStream(s.getOutputStream());
            DataInputStream in = new DataInputStream(s.getInputStream());
            if (FRAMING_RTU.equals(framing)) {
                return readRtuOrNull(in, out, unitId, 3, 0, 1) != null;
            }
            readMbap(in, out, unitId, 3, 0, 1);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Modbus CRC16（多项式 0xA001，初值 0xFFFF），返回 16 位无符号。 */
    static int crc16(byte[] data, int len) {
        int crc = 0xFFFF;
        for (int i = 0; i < len; i++) {
            crc ^= (data[i] & 0xFF);
            for (int j = 0; j < 8; j++) {
                if ((crc & 1) != 0) {
                    crc = (crc >> 1) ^ 0xA001;
                } else {
                    crc >>= 1;
                }
            }
        }
        return crc & 0xFFFF;
    }

    /** 整帧 CRC 校验：帧 = 数据... + CRC(2)，CRC 低字节在前。 */
    private static boolean crcOk(byte[] frame) {
        if (frame.length < 4) return false;
        int expect = crc16(frame, frame.length - 2);
        int lo = frame[frame.length - 2] & 0xFF;
        int hi = frame[frame.length - 1] & 0xFF;
        return lo == (expect & 0xFF) && hi == ((expect >> 8) & 0xFF);
    }
}
