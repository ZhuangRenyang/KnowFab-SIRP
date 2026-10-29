package com.factory.hub.discovery;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * 设备发现接口（仅管理员）。
 *
 * 为什么限管理员：这本质是一台网络扫描器，虽然只读且限了量，
 * 也不该交给班组长随手扫全网段——权限收紧没有成本。
 *
 * 为什么不提供写寄存器：探测工具只读是铁律，
 * 写错一个保持寄存器可能改掉设备加工参数。
 */
@RestController
@RequestMapping("/api/discovery")
public class DiscoveryController {

    private static final Pattern PREFIX = Pattern.compile(
            "^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$");

    private final DiscoveryService service;

    public DiscoveryController(DiscoveryService service) {
        this.service = service;
    }

    /**
     * 扫一批 C 段：prefixes 形如 "192.168.1" 或 "192.168.1,192.168.2,192.168.3"，
     * from/to 1-254，总台数 ≤ 1024（千台工厂基本跨 4 个以上 C 段）。
     */
    public record ScanReq(String prefixes, Integer from, Integer to,
                          Integer port, Integer timeoutMs) {}

    /**
     * 读一段寄存器：fc 只认 3/4，count ≤ 125（协议单帧上限）。
     * framing：tcp = Modbus TCP（网口直连）；rtu = RTU over TCP（串口服务器透传）。
     */
    public record ProbeReq(String ip, Integer port, Integer unitId, Integer fc,
                           Integer from, Integer count, Integer timeoutMs, String framing) {}

    /** 从站扫描：对一台串口服务器逐个从站号探活（B 路径：一个盒子后面挂多台设备）。 */
    public record UnitsReq(String ip, Integer port, String framing,
                           Integer from, Integer to, Integer timeoutMs) {}

    /**
     * 本机所在网段（自动扫描的第一步）：枚举服务器网卡，返回各 IPv4 所在的 C 段前缀。
     * <p>
     * 2026-10-29 加：发现页网段框原来写死 192.168.1，用户设备在 192.168.0.x 时
     * 直接点扫描扫了个空段，看起来像"没有自动扫描这个功能"。
     * 前端打开发现页先调这个接口预填网段，一键就能扫到。
     * <p>
     * 过滤规则：只留 IPv4；去掉回环（127.）、链路本地（169.254.，DHCP 失败的兜底地址）、
     * 未启用的网卡；名字含 docker/vmnet/vbox/vEthernet/Loopback 的虚拟网卡一并排除，
     * 避免把装机自带的 VMware 网段当成本厂网段。
     */
    @GetMapping("/local-subnets")
    public Map<String, Object> localSubnets() {
        Map<String, Object> out = new LinkedHashMap<>();
        TreeSet<String> prefixes = new TreeSet<>();
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs.hasMoreElements()) {
                NetworkInterface ni = ifs.nextElement();
                String name = (ni.getName() + " " + ni.getDisplayName()).toLowerCase();
                if (ni.isLoopback() || !ni.isUp() || name.contains("docker") || name.contains("vmnet")
                        || name.contains("vbox") || name.contains("vethernet") || name.contains("loopback")) {
                    continue;
                }
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (!(a instanceof Inet4Address)) {
                        continue;                    // 只要 IPv4，IPv6 不适合按 C 段扫
                    }
                    String ip = a.getHostAddress();
                    if (ip.startsWith("127.") || ip.startsWith("169.254.")) {
                        continue;
                    }
                    int last = ip.lastIndexOf('.');
                    if (last > 0) {
                        prefixes.add(ip.substring(0, last));
                    }
                }
            }
        } catch (Exception e) {
            out.put("ok", false);
            out.put("error", "枚举网卡失败：" + e.getMessage());
            out.put("subnets", List.of());
            return out;
        }
        out.put("ok", true);
        out.put("subnets", new ArrayList<>(prefixes));
        return out;
    }

    @PostMapping("/scan")
    public Map<String, Object> scan(@RequestBody ScanReq req) throws InterruptedException {
        List<String> prefixes = parsePrefixes(req);
        if (prefixes == null) {
            return fail("网段格式应为 192.168.1，多段用英文逗号隔开，如 192.168.1,192.168.2");
        }
        int from = req.from() == null ? 1 : req.from();
        int to = req.to() == null ? 254 : req.to();
        if (from < 1 || to > 254 || from > to || prefixes.size() * (to - from + 1) > DiscoveryService.MAX_HOSTS) {
            return fail("扫描范围限 1-254，一次最多 " + DiscoveryService.MAX_HOSTS + " 台"
                    + "（当前 " + prefixes.size() * (to - from + 1) + " 台，分几批扫）");
        }
        if (!portOk(req.port())) {
            return fail("端口不合法");
        }
        if (req.timeoutMs() != null && (req.timeoutMs() < 50 || req.timeoutMs() > 2000)) {
            return fail("超时限于 50-2000ms");
        }
        int port = req.port() == null ? 502 : req.port();
        int timeout = req.timeoutMs() == null ? 300 : req.timeoutMs();
        return service.scan(prefixes, from, to, port, timeout);
    }

    /** 拆多段前缀，逐段校验格式；不合法返回 null。 */
    private List<String> parsePrefixes(ScanReq req) {
        if (req == null || req.prefixes() == null || req.prefixes().isBlank()) {
            return null;
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String raw : req.prefixes().split("[,，\\s]+")) {
            String p = raw.trim();
            if (p.isEmpty()) {
                continue;
            }
            if (!PREFIX.matcher(p).matches()) {
                return null;
            }
            for (String octet : p.split("\\.")) {
                if (Integer.parseInt(octet) > 255) {
                    return null;
                }
            }
            out.add(p);
        }
        return out.isEmpty() ? null : new ArrayList<>(out);
    }

    @PostMapping("/probe")
    public Map<String, Object> probe(@RequestBody ProbeReq req) {
        Map<String, Object> bad = invalidProbe(req);
        if (bad != null) {
            return bad;
        }
        int port = req.port() == null ? 502 : req.port();
        int unit = req.unitId() == null ? 1 : req.unitId();
        int fc = req.fc() == null ? 3 : req.fc();
        int from = req.from() == null ? 0 : req.from();
        int count = req.count() == null ? 10 : req.count();
        int timeout = req.timeoutMs() == null ? 1000 : req.timeoutMs();
        return service.probe(req.ip(), port, unit, fc, from, count, timeout, framingOf(req.framing()));
    }

    @PostMapping("/units")
    public Map<String, Object> units(@RequestBody UnitsReq req) throws InterruptedException {
        if (req == null || !validIp(req.ip())) {
            return fail("IP 格式不对");
        }
        if (!portOk(req.port())) {
            return fail("端口不合法");
        }
        String framing = framingOf(req.framing());
        if (framing == null) {
            return fail("framing 只能是 tcp / rtu");
        }
        int from = req.from() == null ? 1 : req.from();
        int to = req.to() == null ? 32 : req.to();
        if (from < 1 || to > 247 || from > to) {
            return fail("从站地址范围限 1-247");
        }
        if (req.timeoutMs() != null && (req.timeoutMs() < 100 || req.timeoutMs() > 3000)) {
            return fail("超时限于 100-3000ms");
        }
        int port = req.port() == null ? 502 : req.port();
        int timeout = req.timeoutMs() == null ? 600 : req.timeoutMs();
        return service.scanUnits(req.ip(), port, framing, from, to, timeout);
    }

    /** framing 缺省 tcp；只认 tcp/rtu，其余返回 null 由调用方报错。 */
    private String framingOf(String framing) {
        if (framing == null || framing.isBlank()) {
            return ModbusTcpProbe.FRAMING_TCP;
        }
        String f = framing.trim().toLowerCase();
        return f.equals(ModbusTcpProbe.FRAMING_TCP) || f.equals(ModbusTcpProbe.FRAMING_RTU) ? f : null;
    }

    private Map<String, Object> invalidProbe(ProbeReq req) {
        if (req == null || !validIp(req.ip())) {
            return fail("IP 格式不对");
        }
        if (!portOk(req.port())) {
            return fail("端口不合法");
        }
        String framing = framingOf(req.framing());
        if (framing == null) {
            return fail("framing 只能是 tcp / rtu");
        }
        int unit = req.unitId() == null ? 1 : req.unitId();
        if (unit < 1 || unit > 247) {
            return fail("从站地址限 1-247");
        }
        int fc = req.fc() == null ? 3 : req.fc();
        if (fc != 2 && fc != 3 && fc != 4) {
            return fail("探测只支持功能码 02/03/04（全是读，工具不写）");
        }
        int from = req.from() == null ? 0 : req.from();
        int count = req.count() == null ? 10 : req.count();
        if (from < 0 || from > 65535 || count < 1 || count > 125) {
            return fail("起始地址 0-65535，数量 1-125");
        }
        if (from + count > 65536) {
            return fail("起始地址 + 数量超出寄存器范围");
        }
        if (req.timeoutMs() != null && (req.timeoutMs() < 100 || req.timeoutMs() > 5000)) {
            return fail("超时限于 100-5000ms");
        }
        return null;
    }

    private boolean validIp(String ip) {
        if (ip == null || !ip.matches("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")) {
            return false;
        }
        for (String p : ip.split("\\.")) {
            if (Integer.parseInt(p) > 255) {
                return false;
            }
        }
        return true;
    }

    private boolean portOk(Integer port) {
        return port == null || (port >= 1 && port <= 65535);
    }

    private Map<String, Object> fail(String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", false);
        m.put("error", msg);
        return m;
    }
}
