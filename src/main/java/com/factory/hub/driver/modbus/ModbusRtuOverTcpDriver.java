package com.factory.hub.driver.modbus;

import com.factory.hub.driver.DriverContext;
import com.factory.hub.driver.DriverFactory;
import com.factory.hub.driver.ProtocolDriver;
import org.springframework.stereotype.Component;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;

/**
 * Modbus RTU over TCP —— 帧还是 RTU 的（带 CRC、无 MBAP 头），只是不走串口，走网线。
 * <p>
 * 用在哪：串口服务器的「TCP 透传 / TCP Server」模式。老设备挂在 485 总线上，
 * 串口服务器把那条总线原样透传到网口，平台这边看到的就是这个协议。
 * 有些只支持透传、不支持 Modbus 网关模式的老串口服务器，只能走这条路。
 * <p>
 * 和标准 Modbus TCP 的区别只有一个：<b>没有 MBAP 头</b>。
 * <ul>
 *   <li>Modbus TCP：事务号(2) + 协议(2) + 长度(2) + 单元号(1) + 功能码 + 数据</li>
 *   <li>RTU over TCP：单元号(1) + 功能码 + 数据 + CRC(2)</li>
 * </ul>
 * <p>
 * 这里有个坑必须处理：TCP 是字节流，没有帧边界；而 RTU 帧没有长度字段。
 * 所以只能<b>按功能码推算响应应该有多长</b>，精确读够字节数。
 * 一旦上一次的迟到响应混进来，整条流就错位了，所以每次请求前要清残留，
 * 收到帧后要校验单元号和 CRC，不对就重发一次。
 */
public class ModbusRtuOverTcpDriver extends AbstractModbusDriver {

    private Socket socket;
    private InputStream in;
    private OutputStream out;

    @Override
    public String type() {
        return "modbus-rtu-tcp";
    }

    @Override
    public void connect(DriverContext ctx) throws Exception {
        disconnect();
        Socket s = new Socket();
        s.connect(new InetSocketAddress(ctx.getHost(), ctx.getPort()), ctx.getTimeoutMs());
        s.setSoTimeout(ctx.getTimeoutMs());
        s.setTcpNoDelay(true);      // 关掉 Nagle，否则小帧会被攒着发，白白多几十毫秒
        s.setKeepAlive(true);
        ModbusUtil.tuneKeepAlive(s);
        this.socket = s;
        this.in = s.getInputStream();
        this.out = s.getOutputStream();
    }

    @Override
    public boolean isConnected() {
        return socket != null && socket.isConnected() && !socket.isClosed();
    }

    @Override
    protected byte[] request(DriverContext ctx, int fc, int addr, int arg) throws Exception {
        if (!isConnected()) {
            throw new IOException("Modbus RTU over TCP 未连接");
        }

        // 重发一次的机会：现场偶发错位（上一次超时后迟到的响应）不该让这台设备直接判离线
        for (int attempt = 1; attempt <= 2; attempt++) {
            drainInput();

            byte[] frame = new byte[8];
            frame[0] = (byte) (ctx.getUnitId() & 0xFF);
            frame[1] = (byte) fc;
            frame[2] = (byte) ((addr >> 8) & 0xFF);
            frame[3] = (byte) (addr & 0xFF);
            frame[4] = (byte) ((arg >> 8) & 0xFF);
            frame[5] = (byte) (arg & 0xFF);

            out.write(ModbusUtil.withCrc(frame, 6));
            out.flush();

            try {
                return readResponse(ctx, fc);
            } catch (StaleFrameException e) {
                if (attempt == 2) {
                    throw new IOException("连续两次收到错位的响应帧，连接已重置。"
                            + "检查是否有别的软件同时在读这台设备（Modbus Poll / 组态软件）", e);
                }
                // 第一次错位：丢掉残留，重发
            }
        }
        throw new IOException("Modbus RTU over TCP 请求失败");
    }

    /**
     * 按功能码推算长度读一帧。
     * 响应结构（RTU）：
     * 读位/读寄存器 [unit][fc][byteCount][data...][crc2]
     * 写单值      [unit][fc][addr2][value2][crc2]     共 8 字节
     * 异常响应    [unit][fc|0x80][excCode][crc2]      共 5 字节
     */
    private byte[] readResponse(DriverContext ctx, int fc) throws Exception {
        byte[] head = readFully(2);
        int respUnit = head[0] & 0xFF;
        int respFc = head[1] & 0xFF;

        // 单元号或功能码对不上 => 收到的是上一条的残留，重来
        if (respUnit != (ctx.getUnitId() & 0xFF)
                || (respFc != fc && respFc != (fc | 0x80))) {
            throw new StaleFrameException("错位帧 unit=" + respUnit + " fc=" + respFc);
        }

        if (respFc == (fc | 0x80)) {
            byte[] tail = readFully(3);           // 异常码 + CRC2
            byte[] full = concat(head, tail);
            if (!ModbusUtil.checkCrc(full, full.length)) {
                throw new IOException("CRC 校验失败（异常响应）");
            }
            throw new ModbusException(fc, tail[0] & 0xFF);
        }

        if (fc == 1 || fc == 2 || fc == 3 || fc == 4) {
            byte[] bc = readFully(1);
            int byteCount = bc[0] & 0xFF;
            if (byteCount > 250) {
                throw new IOException("响应字节数异常: " + byteCount);
            }
            byte[] rest = readFully(byteCount + 2);   // data + CRC2
            byte[] full = concat(concat(head, bc), rest);
            if (!ModbusUtil.checkCrc(full, full.length)) {
                throw new IOException("CRC 校验失败，收到 " + full.length + " 字节"
                        + "。检查波特率/校验位是否与从站一致，或总线上有地址冲突");
            }
            byte[] body = new byte[1 + byteCount];
            body[0] = (byte) byteCount;
            System.arraycopy(rest, 0, body, 1, byteCount);
            return body;
        }

        // 写单个线圈/寄存器：回声 4 字节 + CRC2
        byte[] rest = readFully(6);
        byte[] full = concat(head, rest);
        if (!ModbusUtil.checkCrc(full, full.length)) {
            throw new IOException("CRC 校验失败（写响应）");
        }
        return new byte[0];
    }

    /** 读满指定字节数，socket 超时由 setSoTimeout 控制 */
    private byte[] readFully(int len) throws IOException {
        byte[] buf = new byte[len];
        int read = 0;
        while (read < len) {
            int n;
            try {
                n = in.read(buf, read, len - read);
            } catch (SocketTimeoutException e) {
                throw new IOException("等待响应超时（已收 " + read + "/" + len + " 字节）。"
                        + "从站没应答，按顺序查：串口服务器是否在线、485 站号是否配错、"
                        + "总线是否断线或 A/B 接反", e);
            }
            if (n < 0) {
                throw new EOFException("串口服务器关闭了连接");
            }
            read += n;
        }
        return buf;
    }

    /**
     * 清掉输入缓冲区里的残留字节。
     * TCP 没法像串口那样用 3.5 字符静默判断帧结束，只能靠这个 + 校验兜底。
     */
    private void drainInput() {
        try {
            int guard = 0;
            while (in.available() > 0 && guard < 512) {
                in.read();
                guard++;
            }
        } catch (IOException ignored) {
            // 读不出来就算了，后面的校验会兜住
        }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] r = new byte[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    @Override
    public void disconnect() {
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (Exception ignored) {
        } finally {
            socket = null;
            in = null;
            out = null;
        }
    }

    /** 内部用：收到不属于本次请求的帧 */
    private static class StaleFrameException extends IOException {
        StaleFrameException(String msg) {
            super(msg);
        }
    }

    @Component
    public static class Factory implements DriverFactory {
        @Override
        public String type() {
            return "modbus-rtu-tcp";
        }

        @Override
        public ProtocolDriver create() {
            return new ModbusRtuOverTcpDriver();
        }
    }
}
