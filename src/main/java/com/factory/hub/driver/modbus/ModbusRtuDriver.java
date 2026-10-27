package com.factory.hub.driver.modbus;

import com.factory.hub.driver.DriverContext;
import com.factory.hub.driver.DriverFactory;
import com.factory.hub.driver.ProtocolDriver;
import com.fazecast.jSerialComm.SerialPort;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Modbus RTU（RS485/RS232）。现场用得最多的一种。
 * <p>
 * 硬件接入方式：USB 转 485 转换器（几十块） + 双绞线并联到设备 485 口，
 * A 接 A、B 接 B，末端加 120Ω 终端电阻，屏蔽层单端接地。
 */
public class ModbusRtuDriver extends AbstractModbusDriver {

    private SerialPort port;
    private InputStream in;
    private OutputStream out;

    @Override
    public String type() {
        return "modbus-rtu";
    }

    @Override
    public void connect(DriverContext ctx) throws Exception {
        disconnect();

        int stopBits;
        if (ctx.getStopBits() >= 2) {
            stopBits = SerialPort.TWO_STOP_BITS;
        } else {
            stopBits = SerialPort.ONE_STOP_BIT;
        }
        int parity;
        switch (ctx.getParity()) {
            case 1:
                parity = SerialPort.ODD_PARITY;
                break;
            case 2:
                parity = SerialPort.EVEN_PARITY;
                break;
            default:
                parity = SerialPort.NO_PARITY;
        }
        int dataBits = ctx.getDataBits() < 5 || ctx.getDataBits() > 8 ? 8 : ctx.getDataBits();

        SerialPort p = SerialPort.getCommPort(ctx.getSerialPort());
        if (p == null) {
            throw new IOException("找不到串口: " + ctx.getSerialPort());
        }
        p.setComPortParameters(ctx.getBaudRate(), dataBits, stopBits, parity);
        p.setComPortTimeouts(SerialPort.TIMEOUT_READ_BLOCKING, ctx.getTimeoutMs(), ctx.getTimeoutMs());

        if (!p.openPort()) {
            throw new IOException("串口打开失败: " + ctx.getSerialPort()
                    + "，可能被别的程序占用（Modbus Poll / 组态软件）");
        }
        this.port = p;
        this.in = p.getInputStream();
        this.out = p.getOutputStream();
    }

    @Override
    public boolean isConnected() {
        return port != null && port.isOpen();
    }

    @Override
    protected byte[] request(DriverContext ctx, int fc, int addr, int arg) throws Exception {
        if (!isConnected()) {
            throw new IOException("Modbus RTU 未连接");
        }

        // 清掉缓冲区里的残留字节，否则会错位
        while (in.available() > 0) {
            in.read();
        }

        byte[] frame = new byte[8];
        frame[0] = (byte) (ctx.getUnitId() & 0xFF);
        frame[1] = (byte) fc;
        frame[2] = (byte) ((addr >> 8) & 0xFF);
        frame[3] = (byte) (addr & 0xFF);
        frame[4] = (byte) ((arg >> 8) & 0xFF);
        frame[5] = (byte) (arg & 0xFF);
        byte[] outFrame = ModbusUtil.withCrc(frame, 6);

        out.write(outFrame);
        out.flush();

        // 帧间静默 ≥ 3.5 字符时间，9600 波特率下约 4ms，取 10ms 稳妥
        Thread.sleep(10);

        // 先读 2 字节：从站地址 + 功能码
        byte[] head = readExact(2, ctx.getTimeoutMs());
        int respUnit = head[0] & 0xFF;
        int respFc = head[1] & 0xFF;

        if (respFc == (fc | 0x80)) {
            byte[] err = readExact(3, ctx.getTimeoutMs()); // 异常码 + CRC
            throw new ModbusException(fc, err[0] & 0xFF);
        }
        if (respFc != fc) {
            throw new IOException("功能码不匹配：期望 " + fc + " 实际 " + respFc
                    + "（从站地址 " + respUnit + "，检查 unitId 是否配错）");
        }

        if (fc == 1 || fc == 2 || fc == 3 || fc == 4) {
            byte[] oneByte = readExact(1, ctx.getTimeoutMs());
            int byteCount = oneByte[0] & 0xFF;
            byte[] rest = readExact(byteCount + 2, ctx.getTimeoutMs()); // data + CRC
            byte[] body = new byte[1 + byteCount];
            body[0] = (byte) byteCount;
            System.arraycopy(rest, 0, body, 1, byteCount);
            return body;
        } else {
            // 写单个寄存器/线圈的响应：回声 4 字节 + CRC 2 字节
            readExact(6, ctx.getTimeoutMs());
            return new byte[0];
        }
    }

    /** 读满指定字节数，超时抛异常 */
    private byte[] readExact(int len, int timeoutMs) throws IOException {
        byte[] buf = new byte[len];
        int read = 0;
        long deadline = System.currentTimeMillis() + Math.max(200, timeoutMs);
        while (read < len) {
            int n = in.read(buf, read, len - read);
            if (n > 0) {
                read += n;
                deadline = System.currentTimeMillis() + Math.max(200, timeoutMs);
            } else if (System.currentTimeMillis() > deadline) {
                throw new IOException("串口读超时：期望 " + len + " 字节，实际收到 " + read
                        + " 字节。检查接线(A/B 是否反接)、从站地址、波特率和校验位");
            }
        }
        return buf;
    }

    @Override
    public void disconnect() {
        try {
            if (port != null) {
                port.closePort();
            }
        } catch (Exception ignored) {
        } finally {
            port = null;
            in = null;
            out = null;
        }
    }

    @Component
    public static class Factory implements DriverFactory {
        @Override
        public String type() {
            return "modbus-rtu";
        }

        @Override
        public ProtocolDriver create() {
            return new ModbusRtuDriver();
        }
    }
}
