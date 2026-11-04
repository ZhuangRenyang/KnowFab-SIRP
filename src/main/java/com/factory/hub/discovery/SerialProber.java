package com.factory.hub.discovery;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.factory.hub.driver.DriverContext;
import com.factory.hub.driver.modbus.ModbusRtuDriver;
import com.factory.hub.model.PointDef;
import com.fazecast.jSerialComm.SerialPort;

/**
 * 本地串口（485 直连服务器 COM 口）的**点表对拍**。
 * <p>
 * <b>为什么没有「扫描」</b>：串口不像 TCP 有 IP 可扫——服务器上就那么几个 COM 口，
 * 而且一个 COM 口同一时刻只能被一个设备占用。串口「发现」的唯一动作是
 * <b>对着一个已知的从站地址逐个寄存器试读</b>，把读得通的地址整理成点表。
 * <p>
 * <b>对拍算法：区间探测 + 二分定位</b>。
 * <p>
 * Modbus 读一个连续地址区间，要么整段成功，要么设备返回异常码（Illegal Data Address）
 * 整段失败。所以：
 * <ol>
 *   <li>先读整段 → 成功说明这段地址连续存在，一次往返搞定</li>
 *   <li>失败 → 二分成两半分别试，把空洞定位出来</li>
 * </ol>
 * 复杂度 O(命中数 × log 区间长度)，比逐地址读快一个数量级
 * （逐地址读 256 个地址要 256 次往返，二分通常不到 30 次）。
 * <p>
 * <b>探测粒度是 u16（1 个寄存器）</b>：对拍只能确定「哪些寄存器读得到」，
 * <b>确定不了数据类型</b>——同一个寄存器可能是 u16 / i16 / f32 的一部分。
 * 所以生成的点表用 u16 打底，让用户在设备编辑里按现场实际改成 f32 之类。
 * <p>
 * <b>串口是独占资源</b>：采集任务正在用这个 COM 口时，探测会打不开端口。
 * 报错要说清是「被占用」还是「设备无响应」——这两种的现场处理完全不同。
 */
@Service
public class SerialProber {

    private static final Logger log = LoggerFactory.getLogger(SerialProber.class);

    /** 一次对拍最多探测多少个地址：串口是慢速设备，跑太多会等到天亮 */
    private static final int MAX_ADDRS = 256;

    /** 单次对拍的总时限（毫秒） */
    private static final long TOTAL_BUDGET_MS = 60_000L;

    /** 对拍参数 */
    public record Spec(
            String serialPort,
            Integer baudRate,
            Integer dataBits,
            Integer stopBits,
            Integer parity,
            Integer unitId,
            Integer startAddr,
            Integer count,
            Integer functionCode,
            String dataType,
            Integer timeoutMs) {
    }

    /**
     * 区间读取的抽象——把「读一段地址」这件事从串口里摘出来。
     * <p>
     * 不这么拆就没法单测：真跑一次对拍要开串口、要接设备，
     * 而<b>二分定位空洞这段逻辑恰恰是最容易写错的部分</b>（边界、递归终止、
     * 命中与未命中的计数）。抽出来就能用假实现把各种情形跑一遍。
     */
    @FunctionalInterface
    interface RangeReader {
        /** 读 [from, to] 这一段；读不通（设备返回异常码）就抛异常 */
        Map<String, Object> read(int fc, int from, int to) throws Exception;
    }

    /** 命中的一条：地址 + 读到的值 */
    public record Hit(int address, Object value, String key, String name) {
    }

    /** 本机可用的串口 */
    public List<Map<String, Object>> listPorts() {
        List<Map<String, Object>> out = new ArrayList<>();
        // jSerialComm 2.x 的 getCommPorts() 返回的是**数组**，不是 List
        for (SerialPort p : SerialPort.getCommPorts()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("port", p.getSystemPortName());      // COM3 / ttyUSB0
            m.put("path", p.getSystemPortPath());      // Windows 与 Linux 都能定位
            m.put("description", p.getDescriptivePortName());
            out.add(m);
        }
        return out;
    }

    /**
     * 对拍：把读得通的寄存器整理成点表。
     *
     * @return ok + found（命中明细）+ points（可直接用的点表）+ 统计
     */
    public Map<String, Object> probe(Spec spec) {
        Map<String, Object> out = new LinkedHashMap<>();
        String port = spec.serialPort() == null ? "" : spec.serialPort().trim();
        if (port.isEmpty()) {
            out.put("ok", false);
            out.put("message", "请先选择串口");
            return out;
        }
        int start = spec.startAddr() == null ? 0 : spec.startAddr();
        int count = spec.count() == null ? 32 : spec.count();
        if (count <= 0) {
            out.put("ok", false);
            out.put("message", "探测个数必须大于 0");
            return out;
        }
        if (count > MAX_ADDRS) {
            out.put("ok", false);
            out.put("message", "一次最多探测 " + MAX_ADDRS + " 个地址（串口是慢速设备）");
            return out;
        }
        int fc = spec.functionCode() == null ? 3 : spec.functionCode();
        if (fc != 3 && fc != 4) {
            out.put("ok", false);
            out.put("message", "对拍只支持保持寄存器（3）或输入寄存器（4）；"
                    + "线圈（1/2）一次只能读一个地址，没有区间可言");
            return out;
        }

        DriverContext ctx = new DriverContext();
        ctx.setCode("serial-probe");
        ctx.setSerialPort(port);
        ctx.setBaudRate(spec.baudRate() == null ? 9600 : spec.baudRate());
        ctx.setDataBits(spec.dataBits() == null ? 8 : spec.dataBits());
        ctx.setStopBits(spec.stopBits() == null ? 1 : spec.stopBits());
        ctx.setParity(spec.parity() == null ? 0 : spec.parity());
        ctx.setUnitId(spec.unitId() == null ? 1 : spec.unitId());
        ctx.setTimeoutMs(spec.timeoutMs() == null ? 500 : spec.timeoutMs());

        ModbusRtuDriver driver = new ModbusRtuDriver();
        long t0 = System.currentTimeMillis();
        List<Hit> hits = new ArrayList<>();
        int[] stats = new int[]{0, 0};   // [0]=命中数 [1]=读不通的地址数
        try {
            driver.connect(ctx);
        } catch (Exception e) {
            out.put("ok", false);
            out.put("message", explain(e));
            return out;
        }
        try {
            probeRange((rfc, from, to) -> {
                List<PointDef> batch = new ArrayList<>();
                for (int a = from; a <= to; a++) {
                    batch.add(point(rfc, a));
                }
                return driver.read(ctx, batch);
            }, fc, start, start + count - 1, hits, stats, t0);
        } catch (Exception e) {
            log.warn("串口对拍中断：{}", e.getMessage());
            out.put("ok", false);
            out.put("message", explain(e));
            out.put("found", hits);
            return out;
        } finally {
            try {
                driver.disconnect();
            } catch (Exception ignored) {
                // 断开失败不影响结果：端口本来就要释放
            }
        }

        List<Map<String, Object>> points = new ArrayList<>();
        for (Hit h : hits) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", h.key());
            m.put("name", h.name());
            m.put("address", h.address());
            m.put("functionCode", fc);
            m.put("dataType", spec.dataType() == null ? "u16" : spec.dataType());
            m.put("scale", 1.0d);
            m.put("offset", 0.0d);
            m.put("unit", "");
            m.put("decimals", 0);
            m.put("role", "value");
            m.put("writable", false);
            points.add(m);
        }

        out.put("ok", true);
        out.put("port", port);
        out.put("unitId", ctx.getUnitId());
        out.put("startAddr", start);
        out.put("count", count);
        out.put("hit", stats[0]);
        out.put("miss", stats[1]);
        out.put("found", hits);
        out.put("points", points);
        out.put("elapsedMs", System.currentTimeMillis() - t0);
        out.put("message", stats[0] == 0
                ? "一个地址都没读到。检查：从站地址（当前 " + ctx.getUnitId() + "）、"
                        + "波特率、接线（A/B 是否反接）"
                : "读到 " + stats[0] + " 个寄存器，已生成点表（数据类型默认 u16，请按现场实际调整）");
        return out;
    }

    /**
     * 区间探测：成功则整段命中，失败则二分。
     * <p>
     * 每次都传「每个地址一个点」的列表——{@code AbstractModbusDriver.read} 会按
     * 功能码分组、取地址区间的 min/max 一次性读完，所以一次 read 就是一整段。
     */
    /** 包级可见：单测要用假设备跑二分定位（不开真串口） */
    void probeRange(RangeReader reader, int fc,
                            int from, int to, List<Hit> hits, int[] stats, long t0) throws Exception {
        if (from > to) {
            return;
        }
        if (System.currentTimeMillis() - t0 > TOTAL_BUDGET_MS) {
            throw new IllegalStateException("对拍超过 " + (TOTAL_BUDGET_MS / 1000) + " 秒，已停止");
        }
        int n = to - from + 1;
        try {
            Map<String, Object> values = reader.read(fc, from, to);
            for (int a = from; a <= to; a++) {
                PointDef p = point(fc, a);
                Object v = values.get(p.getKey());
                hits.add(new Hit(p.getAddress(), v, p.getKey(), p.getName()));
                stats[0]++;
            }
        } catch (Exception e) {
            if (n == 1) {
                stats[1]++;   // 这个地址确实读不通
                return;
            }
            int mid = from + n / 2;
            probeRange(reader, fc, from, mid - 1, hits, stats, t0);
            probeRange(reader, fc, mid, to, hits, stats, t0);
        }
    }

    private static PointDef point(int fc, int address) {
        PointDef p = new PointDef();
        p.setKey("reg_" + address);
        p.setName("寄存器 " + address);
        p.setAddress(address);
        p.setFunctionCode(fc);
        // 探测粒度固定 u16：每个地址占 1 个寄存器，区间才是连续的。
        // 数据类型交给用户按现场实际改——对拍读不出类型。
        p.setDataType("u16");
        p.setScale(1.0d);
        p.setOffset(0.0d);
        p.setUnit("");
        p.setDecimals(0);
        p.setRole("value");
        p.setWritable(false);
        return p;
    }

    /**
     * 把底层异常翻译成现场看得懂的话。
     * <p>
     * 「串口被占用」和「设备没响应」的处理方式完全不同：前者要停掉采集任务，
     * 后者要查接线/波特率/从站地址。混成一句「探测失败」等于没提示。
     */
    private static String explain(Exception e) {
        String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        String lower = m.toLowerCase();
        if (lower.contains("access is denied") || lower.contains("port is in use")
                || lower.contains("being used") || lower.contains("resource busy")) {
            return "串口被占用：" + m + "。这台设备如果正在采集，先把它停用再对拍";
        }
        if (lower.contains("timeout") || lower.contains("timed out")) {
            return "设备无响应（超时）：" + m + "。检查接线与 A/B 是否反接、波特率、从站地址";
        }
        if (lower.contains("no such port") || lower.contains("找不到串口")
                || lower.contains("cannot find") || lower.contains("not found")) {
            return "找不到串口：" + m + "。刷新一下端口列表确认设备还在";
        }
        return m;
    }
}
