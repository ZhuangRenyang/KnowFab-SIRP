package com.factory.hub.driver.opcua;

import com.factory.hub.driver.DriverContext;
import com.factory.hub.driver.DriverFactory;
import com.factory.hub.driver.ProtocolDriver;
import com.factory.hub.model.PointDef;
import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.milo.opcua.sdk.client.api.config.OpcUaClientConfig;
import org.eclipse.milo.opcua.sdk.client.api.config.OpcUaClientConfigBuilder;
import org.eclipse.milo.opcua.stack.core.AttributeId;
import org.eclipse.milo.opcua.stack.core.security.SecurityPolicy;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.QualifiedName;
import org.eclipse.milo.opcua.stack.core.types.builtin.StatusCode;
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UInteger;
import org.eclipse.milo.opcua.stack.core.types.enumerated.TimestampsToReturn;
import org.eclipse.milo.opcua.stack.core.types.structured.EndpointDescription;
import org.eclipse.milo.opcua.stack.core.types.structured.ReadValueId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * OPC UA 客户端驱动（Eclipse Milo）。
 * <p>
 * <b>为什么做这个协议</b>：西门子 S7-1500、汇川、欧姆龙 NX 等新 PLC 默认开 OPC UA Server，
 * 是比 Modbus 更现代的统一入口；比 EtherCAT 现实得多——EtherCAT 要专用网卡 + 实时内核。
 * <p>
 * <b>连接方式</b>：匿名 + SecurityPolicy.None（厂内网最常见配置，零门槛）；需要用户名认证的现场暂不支持。
 * 端口没填时用本协议的标准端口 4840 —— 默认端口是协议知识，由各协议自己的驱动兜底（Modbus 是 502）。
 * <p>
 * <b>端点 URL 形态</b>：{@code opc.tcp://<host>:<port>}，<b>不带路径</b>。
 * <b>端点匹配规则</b>（哪部分归一、哪部分不归一）见 {@link OpcUaEndpointUrl} 的类注释：
 * <b>路径部分必须和服务端 advertise 的一致</b>，主机部分与尾斜杠会被归一。
 * 现场遇到「连不上但设备明明开着」时先怀疑这条 —— 报错 {@code Bad_TcpEndpointUrlInvalid}
 * <b>长得像「网络不通」，实际是「端点形态对不上」</b>。
 * <p>
 * <b>点表</b>：每个点位用 {@code nodeId} 指定节点，写法同 UaExpert：
 * {@code ns=2;s=Machine1.Output}、{@code ns=4;i=1234}（ns 索引各品牌不同，配错读不到值）。
 * <p>
 * <b>读取策略</b>：批量 Read（一次请求读全部点），和 Modbus 采集调度完全一致；后续如需降延迟可改订阅，接口不用变。
 */
public class OpcUaDriver implements ProtocolDriver {

    private static final Logger log = LoggerFactory.getLogger(OpcUaDriver.class);

    private OpcUaClient client;
    /** 当前连接的 endpoint，切换端点/重连时用来判断是否需要重建客户端 */
    private String endpoint;

    @Override
    public String type() {
        return "opcua";
    }

    @Override
    public void connect(DriverContext ctx) throws Exception {
        // 主机地址先消毒再拼 URL：客户常把 host 填成 "192.168.1.10/UA"、"192.168.1.10:4840"、
        // "opc.tcp://192.168.1.10"，直接拼会得到非法 URL，
        // 而报错（Bad_TcpEndpointUrlInvalid）长得像「网络不通」，实际是「地址填错了」，现场极难定位。
        // 详见 OpcUaEndpointUrl。
        String url = OpcUaEndpointUrl.build(ctx);
        if (client != null && url.equals(endpoint)) {
            return;
        }
        disconnect();

        int reqTimeout = Math.max(2000, ctx.getTimeoutMs() * 3);
        OpcUaClient newClient;
        try {
            newClient = OpcUaClient.create(
                    url,
                    // 只接受不加密的端点：厂内网；加密部署需要证书交换，MVP 阶段不做
                    endpoints -> endpoints.stream()
                            .filter(e -> SecurityPolicy.None.getUri().equals(e.getSecurityPolicyUri()))
                            .findFirst(),
                    (OpcUaClientConfigBuilder b) -> {
                        OpcUaClientConfig cfg = b
                                .setApplicationName(LocalizedText.english("KnowFab SIRP"))
                                .setApplicationUri("urn:knowfab:sirp:collector")
                                .setRequestTimeout(UInteger.valueOf(reqTimeout))
                                .setSessionTimeout(UInteger.valueOf(60_000))
                                .build();
                        return cfg;
                    });
            if (newClient == null) {
                throw new IllegalStateException("OPC UA 端点无可用匿名通道（服务端禁用了 None 安全策略）: " + url);
            }
            // connect() 也放进 try：端点不匹配发生在 create() 阶段（所以现在翻译得到），
            // 但将来任何 connect 阶段的失败都会漏翻译 —— 裸着抛 ExecutionException，
            // 现场又只能看到「像网络不通」的堆栈。翻译器对非端点问题原样返回，不会误伤。
            newClient.connect().get(reqTimeout, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            // 端点对不上时 milo 抛的是 Bad_TcpEndpointUrlInvalid / "unrecognized endpoint url"，
            // 最外层还包着 Netty 的 DecoderException —— 长得像网络不通。
            // 这里翻译成人话，否则现场会去查网线、查防火墙，而根因是端点形态不对。
            throw OpcUaErrors.translateEndpointFailure(e, url);
        }
        this.client = newClient;
        this.endpoint = url;
        log.info("OPC UA 已连接 {}", url);
    }

    @Override
    public boolean isConnected() {
        return client != null && client.getSession() != null && client.getSession().isDone()
                && !client.getSession().isCompletedExceptionally();
    }

    @Override
    public Map<String, Object> read(DriverContext ctx, List<PointDef> points) throws Exception {
        if (client == null) {
            connect(ctx);
        }
        List<ReadValueId> ids = new ArrayList<>();
        List<PointDef> valid = new ArrayList<>();
        // 记录每个点位为什么没读到：全部失败时要靠它给出可定位的异常信息，
        // 否则现场只看到「所有点位读取失败」这一句话，无从下手。
        List<String> failures = new ArrayList<>();
        for (PointDef p : points) {
            String raw = p.getNodeId();
            if (raw == null || raw.isBlank()) {
                continue;
            }
            NodeId nodeId;
            try {
                nodeId = NodeId.parse(raw.trim());
            } catch (Exception e) {
                // 语法不对：ns=…;s=… 写错了
                log.warn("[{}] 点位 {} 的 nodeId 格式不对：{}（{}）",
                        ctx.getHost(), p.getKey(), raw, e.getMessage());
                failures.add(p.getKey() + "=" + raw + " 格式不对");
                continue;
            } catch (LinkageError e) {
                // ⚠️ LinkageError 是 Error 不是 Exception，catch (Exception) 抓不到它。
                //    最常见的一种：ByteString 节点号（ns=N;b=…）解析要走
                //    javax.xml.bind.DatatypeConverter.parseBase64Binary，运行类路径上缺这个类
                //    就是 NoClassDefFoundError。不接住的话它会穿透 read() 抛出去 →
                //    不只是这一个点坏，而是<b>整批点位全废</b>（现场：设备在线但一个数据都没有）。
                log.warn("[{}] 点位 {} 的 nodeId {} 解析失败：缺少运行依赖"
                                + "（ByteString 节点号 ns=N;b=… 需要 javax.xml.bind，请检查依赖是否在运行时类路径上）：{}",
                        ctx.getHost(), p.getKey(), raw, e.toString());
                failures.add(p.getKey() + "=" + raw + " 缺少 javax.xml.bind");
                continue;
            }
            ids.add(new ReadValueId(nodeId, AttributeId.Value.uid(), null, QualifiedName.NULL_VALUE));
            valid.add(p);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        if (ids.isEmpty()) {
            if (failures.isEmpty()) {
                // 点位压根没配 nodeId（不是错误），保持原样返回空 Map
                return out;
            }
            // 所有点位都在解析阶段就挂了 —— 必须抛出来，不能静默返回空 Map：
            // 上层拿到空 Map 只会当成「这轮没数据」，而真正的原因是「每个点的 nodeId 都有问题」，
            // 不抛出来就等于把根因藏起来了。
            throw new IllegalStateException("所有点位读取失败（检查 nodeId 的 ns 索引与 PLC 的 OPC UA 配置）"
                    + "；失败明细：" + String.join("；", failures));
        }
        DataValue[] results = client.read(
                        0.0, TimestampsToReturn.Neither, ids)
                .get(Math.max(2000, ctx.getTimeoutMs() * 3), TimeUnit.MILLISECONDS)
                .getResults();
        for (int i = 0; i < valid.size() && i < results.length; i++) {
            DataValue dv = results[i];
            PointDef p = valid.get(i);
            if (dv == null) {
                continue;
            }
            StatusCode sc = dv.getStatusCode();
            if (sc != null && !sc.isGood()) {
                log.debug("[{}] 点位 {} 读失败：{}", ctx.getHost(), p.getKey(), sc);
                failures.add(p.getKey() + "=" + p.getNodeId() + " 服务端返回 " + sc);
                continue;
            }
            Variant v = dv.getValue();
            Object val = v == null ? null : v.getValue();
            if (val == null) {
                failures.add(p.getKey() + "=" + p.getNodeId() + " 值为空");
                continue;
            }
            out.put(p.getKey(), convert(val, p));
        }
        if (out.isEmpty()) {
            throw new IllegalStateException("所有点位读取失败（检查 nodeId 的 ns 索引与 PLC 的 OPC UA 配置）"
                    + (failures.isEmpty() ? "" : "；失败明细：" + String.join("；", failures)));
        }
        return out;
    }

    /** 统一转成 double（产量/温度/转速等）；布尔按 0/1；字符串原样返回 */
    private Object convert(Object v, PointDef p) {
        if (v instanceof Number) {
            double d = ((Number) v).doubleValue() * p.getScale() + p.getOffset();
            return d;
        }
        if (v instanceof Boolean) {
            return ((Boolean) v) ? 1.0d : 0.0d;
        }
        if (v instanceof String) {
            return v;
        }
        // 枚举/时间/其他结构体：取字符串形式，前端按原样显示
        return String.valueOf(v);
    }

    @Override
    public void write(DriverContext ctx, PointDef point, Object value) throws Exception {
        if (client == null) {
            connect(ctx);
        }
        String raw = point.getNodeId();
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("点位 " + point.getKey() + " 没配 nodeId");
        }
        NodeId nodeId;
        try {
            nodeId = NodeId.parse(raw.trim());
        } catch (Exception e) {
            throw new IllegalArgumentException("点位 " + point.getKey() + " 的 nodeId 格式不对：" + raw
                    + "（" + e.getMessage() + "）", e);
        } catch (LinkageError e) {
            // 同 read 路径：LinkageError 是 Error 不是 Exception，必须单独接住，
            // 否则缺依赖时这里抛的是裸 NoClassDefFoundError，现场看不出是哪个点位出的问题。
            throw new IllegalStateException("点位 " + point.getKey() + " 的 nodeId " + raw
                    + " 解析失败：缺少运行依赖"
                    + "（ByteString 节点号 ns=N;b=… 需要 javax.xml.bind，请检查依赖是否在运行时类路径上）："
                    + e, e);
        }
        Variant variant = new Variant(toUaValue(point, value));
        // 注意：服务端拒绝（类型不匹配/无权限）不会抛异常，状态码在返回值里，必须检查
        StatusCode sc = client.writeValue(nodeId, new DataValue(variant))
                .get(Math.max(2000, ctx.getTimeoutMs() * 3), TimeUnit.MILLISECONDS);
        if (sc != null && !sc.isGood()) {
            throw new IllegalStateException("写入被设备拒绝：" + sc + OpcUaErrors.writeRejectHint(sc));
        }
    }

    /** 按点位 dataType 把数值转成 OPC UA 严格类型：PLC 对 Int32/浮点类型不宽容 */
    private Object toUaValue(PointDef point, Object value) {
        if (value instanceof Boolean || value instanceof String) {
            return value;
        }
        double d = value instanceof Number ? ((Number) value).doubleValue() : 0d;
        String t = point.getDataType() == null ? "" : point.getDataType().toLowerCase();
        switch (t) {
            case "f32":
                return (float) d;
            case "u16":
                return org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UShort.valueOf((int) Math.round(d));
            case "u32":
                return UInteger.valueOf((long) Math.round(d));
            case "u64":
            case "s64":
            case "s32":
                return (long) Math.round(d);
            default:
                return d;
        }
    }

    @Override
    public void disconnect() {
        try {
            if (client != null) {
                client.disconnect().get(3000, TimeUnit.MILLISECONDS);
            }
        } catch (Exception ignored) {
        } finally {
            client = null;
            endpoint = null;
        }
    }

    /** 注册成 Spring Bean，DriverRegistry 自动发现 */
    @Component
    public static class Factory implements DriverFactory {
        @Override
        public String type() {
            return "opcua";
        }

        @Override
        public ProtocolDriver create() {
            return new OpcUaDriver();
        }
    }
}
