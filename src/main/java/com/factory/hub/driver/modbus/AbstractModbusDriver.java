package com.factory.hub.driver.modbus;

import com.factory.hub.driver.DriverContext;
import com.factory.hub.driver.ProtocolDriver;
import com.factory.hub.model.PointDef;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Modbus 公共逻辑：点表分组、批量读、解码、写值。
 * TCP 和 RTU 只需要实现"发一帧、收一帧"这一件事，其余逻辑在这里共享。
 */
public abstract class AbstractModbusDriver implements ProtocolDriver {

    /**
     * 发送一次请求，返回响应中【功能码之后】的字节。
     * 例：读寄存器的响应 [unit][fc][byteCount][data...][crc]
     * 返回 [byteCount][data...]
     */
    protected abstract byte[] request(DriverContext ctx, int fc, int addr, int arg) throws Exception;

    @Override
    public Map<String, Object> read(DriverContext ctx, List<PointDef> points) throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();

        Map<Integer, List<PointDef>> groups = new LinkedHashMap<>();
        for (PointDef p : points) {
            groups.computeIfAbsent(p.getFunctionCode(), k -> new ArrayList<>()).add(p);
        }

        for (Map.Entry<Integer, List<PointDef>> entry : groups.entrySet()) {
            int fc = entry.getKey();
            List<PointDef> list = entry.getValue();

            int start = Integer.MAX_VALUE;
            int end = 0;
            for (PointDef p : list) {
                start = Math.min(start, p.getAddress());
                int width = p.isBitType() ? 1 : p.registerCount();
                end = Math.max(end, p.getAddress() + width);
            }
            int count = end - start;
            if (count <= 0) {
                continue;
            }

            byte[] data = readRange(ctx, fc, start, count);

            for (PointDef p : list) {
                Object raw;
                if (p.isBitType()) {
                    int bitIndex = p.getAddress() - start;
                    int byteIndex = bitIndex / 8;
                    int bit = (data[byteIndex] >> (bitIndex % 8)) & 0x01;
                    raw = bit == 1;
                } else {
                    int offset = (p.getAddress() - start) * 2;
                    raw = ModbusUtil.decode(data, offset, p.getDataType());
                }
                result.put(p.getKey(), ModbusUtil.applyScale(raw, p));
            }
        }
        return result;
    }

    /** 超过单帧上限时自动分包，按顺序拼接 */
    private byte[] readRange(DriverContext ctx, int fc, int start, int count) throws Exception {
        boolean bitMode = (fc == 1 || fc == 2);
        int maxPerReq = bitMode ? 2000 : 125;
        if (bitMode && count > maxPerReq) {
            throw new IOException("位读取数量超限 " + count + "，请把点表拆成多个功能码组");
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int done = 0;
        while (done < count) {
            int chunk = Math.min(maxPerReq, count - done);
            byte[] body = request(ctx, fc, start + done, chunk);
            if (body.length < 1) {
                throw new IOException("Modbus 响应为空");
            }
            int byteCount = body[0] & 0xFF;
            if (body.length < 1 + byteCount) {
                throw new IOException("Modbus 响应数据不足，声明 " + byteCount + " 字节，实际 "
                        + (body.length - 1));
            }
            bos.write(body, 1, byteCount);
            done += chunk;
        }
        return bos.toByteArray();
    }

    @Override
    public void write(DriverContext ctx, PointDef point, Object value) throws Exception {
        int fc = point.isBitType() ? 5 : 6;
        int raw;
        if (point.isBitType()) {
            boolean on = Boolean.TRUE.equals(value)
                    || "1".equals(String.valueOf(value))
                    || "true".equalsIgnoreCase(String.valueOf(value));
            raw = on ? 0xFF00 : 0x0000;
        } else {
            raw = ModbusUtil.encodeU16(value, point);
        }
        request(ctx, fc, point.getAddress(), raw);
    }
}
