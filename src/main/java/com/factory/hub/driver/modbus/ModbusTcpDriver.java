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

/**
 * Modbus TCP（端口 502）。纯 Socket 实现，没有第三方协议库依赖。
 * 现场调试：用 Modbus Poll 先能读到数，再对照点表配地址和功能码。
 */
public class ModbusTcpDriver extends AbstractModbusDriver {

    private Socket socket;
    private InputStream in;
    private OutputStream out;
    private int transactionId = 0;

    @Override
    public String type() {
        return "modbus-tcp";
    }

    @Override
    public void connect(DriverContext ctx) throws Exception {
        disconnect();
        Socket s = new Socket();
        s.connect(new InetSocketAddress(ctx.getHost(), ctx.getPort()), ctx.getTimeoutMs());
        s.setSoTimeout(ctx.getTimeoutMs());
        s.setTcpNoDelay(true);
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
            throw new IOException("Modbus TCP 未连接");
        }
        int tid = nextTid();
        byte[] req = new byte[12];
        req[0] = (byte) ((tid >> 8) & 0xFF);
        req[1] = (byte) (tid & 0xFF);
        req[2] = 0;                       // 协议标识
        req[3] = 0;
        req[4] = 0;                       // 后续字节数 = 6
        req[5] = 6;
        req[6] = (byte) (ctx.getUnitId() & 0xFF);
        req[7] = (byte) fc;
        req[8] = (byte) ((addr >> 8) & 0xFF);
        req[9] = (byte) (addr & 0xFF);
        req[10] = (byte) ((arg >> 8) & 0xFF);
        req[11] = (byte) (arg & 0xFF);

        out.write(req);
        out.flush();

        byte[] header = readFully(7);
        int respTid = ((header[0] & 0xFF) << 8) | (header[1] & 0xFF);
        int length = ((header[4] & 0xFF) << 8) | (header[5] & 0xFF);
        if (length < 2 || length > 260) {
            throw new IOException("Modbus TCP 响应长度异常: " + length);
        }
        byte[] pdu = readFully(length - 1);
        if (respTid != tid) {
            throw new IOException("事务号不匹配，可能是上一条迟到的响应（期望 " + tid + " 实际 " + respTid + "）");
        }
        int respFc = pdu[0] & 0xFF;
        if (respFc == (fc | 0x80)) {
            throw new ModbusException(fc, pdu.length > 1 ? (pdu[1] & 0xFF) : -1);
        }
        if (respFc != fc) {
            throw new IOException("功能码不匹配：期望 " + fc + " 实际 " + respFc);
        }
        byte[] body = new byte[pdu.length - 1];
        System.arraycopy(pdu, 1, body, 0, body.length);
        return body;
    }

    private byte[] readFully(int len) throws IOException {
        byte[] buf = new byte[len];
        int read = 0;
        while (read < len) {
            int n;
            try {
                n = in.read(buf, read, len - read);
            } catch (java.net.SocketTimeoutException e) {
                throw new IOException("等待响应超时（已收 " + read + "/" + len + " 字节）。"
                        + "检查：IP/端口是否正确、设备是否上电、单元号(unitId)是否配错、"
                        + "是否有防火墙拦截", e);
            }
            if (n < 0) {
                throw new EOFException("连接被对端关闭");
            }
            read += n;
        }
        return buf;
    }

    private synchronized int nextTid() {
        transactionId = (transactionId + 1) & 0xFFFF;
        return transactionId;
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

    /** 注册成 Spring Bean，框架启动时自动装配，业务代码不用管 */
    @Component
    public static class Factory implements DriverFactory {
        @Override
        public String type() {
            return "modbus-tcp";
        }

        @Override
        public ProtocolDriver create() {
            return new ModbusTcpDriver();
        }
    }
}
