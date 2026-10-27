package com.factory.hub.discovery;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 设备发现服务：网段扫描 + 寄存器探测。
 *
 * 定位是"接第一台设备时的侦察工具"，不是常驻功能：
 * - 扫描：并发测一批 C 段里谁开着 Modbus 端口（默认 502），
 *   上限 1024 台、64 并发、短超时，千台规模十几秒出结果，不会把局域网打瘫。
 * - 探测：对指定 IP 读一段寄存器，前端每 2 秒读一次做对比，
 *   人在设备边启停机器，看哪个值跟着动——就是"对拍法找点"。
 */
@Service
public class DiscoveryService {

    static final int MAX_HOSTS = 1024;
    private static final int SCAN_THREADS = 64;

    /** 并发扫多个 C 段，只返回端口开着的 IP。入参校验在 Controller。 */
    public Map<String, Object> scan(List<String> prefixes, int from, int to,
                                    int port, int timeoutMs) throws InterruptedException {
        long t0 = System.currentTimeMillis();
        ExecutorService pool = Executors.newFixedThreadPool(SCAN_THREADS, r -> {
            Thread t = new Thread(r, "discovery-scan");
            t.setDaemon(true);
            return t;
        });
        try {
            List<Future<Map<String, Object>>> fs = new ArrayList<>();
            for (String prefix : prefixes) {
                for (int h = from; h <= to; h++) {
                    final String ip = prefix + "." + h;
                    fs.add(pool.submit(() -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("ip", ip);
                        m.put("port", port);
                        m.put("open", ModbusTcpProbe.portOpen(ip, port, timeoutMs));
                        return m;
                    }));
                }
            }
            List<Map<String, Object>> found = new ArrayList<>();
            for (Future<Map<String, Object>> f : fs) {
                Map<String, Object> m;
                try {
                    m = f.get();
                } catch (java.util.concurrent.ExecutionException e) {
                    continue; // 单个地址探测出意外，跳过即可，不影响整段扫描
                }
                if (Boolean.TRUE.equals(m.get("open"))) {
                    found.add(m);
                }
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("scanned", prefixes.size() * (to - from + 1));
            out.put("elapsedMs", System.currentTimeMillis() - t0);
            out.put("port", port);
            out.put("found", found);
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    /** 读一段寄存器。失败不抛异常，把人话错误带回给页面——现场的人看得懂才有用。 */
    public Map<String, Object> probe(String ip, int port, int unitId,
                                     int fc, int from, int count, int timeoutMs, String framing) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ip", ip);
        out.put("port", port);
        out.put("unitId", unitId);
        out.put("fc", fc);
        out.put("from", from);
        out.put("framing", framing);
        try {
            int[] vals = ModbusTcpProbe.readRegisters(ip, port, unitId, fc, from, count, timeoutMs, framing);
            out.put("ok", true);
            out.put("values", vals);
            List<String> hex = new ArrayList<>();
            for (int v : vals) {
                hex.add(String.format("%04X", v));
            }
            out.put("hex", hex);
            out.put("at", System.currentTimeMillis());
        } catch (IOException e) {
            String msg = e.getMessage() == null ? "读取失败" : e.getMessage();
            // 用 TCP 帧去探串口服务器是最常见的姿势错误：盒子收到不认识的帧会沉默，
            // 表现就是超时。这时候十有八九该切 RTU 帧，提示直接送到眼前。
            if (ModbusTcpProbe.FRAMING_TCP.equals(framing) && msg.contains("timed out")) {
                msg += "——如果这台是串口服务器（485 盒子），把帧格式切到「RTU 透传」再试";
            }
            out.put("ok", false);
            out.put("error", msg);
        }
        return out;
    }

    /**
     * 从站扫描（B 路径的关键一步）：一个串口服务器后面挂多台 485 设备，
     * 各占一个从站号。并发探活，返回有应答的从站列表。
     * 并发限 8：盒子后面是真串口，独占半双工线，打太狠会互相撞车；
     * 好处是串口服务器自己会排队，8 路足够把 247 个地址在十几秒内过完。
     */
    public Map<String, Object> scanUnits(String ip, int port, String framing,
                                         int from, int to, int timeoutMs) throws InterruptedException {
        long t0 = System.currentTimeMillis();
        ExecutorService pool = Executors.newFixedThreadPool(8, r -> {
            Thread t = new Thread(r, "discovery-units");
            t.setDaemon(true);
            return t;
        });
        try {
            List<Future<Map<String, Object>>> fs = new ArrayList<>();
            for (int u = from; u <= to; u++) {
                final int unitId = u;
                fs.add(pool.submit(() -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("unitId", unitId);
                    m.put("alive", ModbusTcpProbe.unitAlive(ip, port, unitId, timeoutMs, framing));
                    return m;
                }));
            }
            List<Integer> alive = new ArrayList<>();
            for (Future<Map<String, Object>> f : fs) {
                Map<String, Object> m;
                try {
                    m = f.get();
                } catch (java.util.concurrent.ExecutionException e) {
                    continue;
                }
                if (Boolean.TRUE.equals(m.get("alive"))) {
                    alive.add((Integer) m.get("unitId"));
                }
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("ip", ip);
            out.put("port", port);
            out.put("framing", framing);
            out.put("scanned", to - from + 1);
            out.put("elapsedMs", System.currentTimeMillis() - t0);
            out.put("alive", alive);
            return out;
        } finally {
            pool.shutdownNow();
        }
    }
}
