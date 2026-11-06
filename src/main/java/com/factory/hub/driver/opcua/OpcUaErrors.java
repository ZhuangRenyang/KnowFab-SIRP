package com.factory.hub.driver.opcua;

import org.eclipse.milo.opcua.stack.core.StatusCodes;
import org.eclipse.milo.opcua.stack.core.UaException;
import org.eclipse.milo.opcua.stack.core.types.builtin.StatusCode;

/**
 * 把 milo 抛出来的东西翻译成人话。
 * <p>
 * <b>为什么单独一个类</b>：milo 的异常与状态码是<b>协议层语言</b>，
 * 直接抛给现场等于让客户去查 OPC UA 规范。这一类「翻译」集中放一处，
 * 免得同一个坑在几个地方各翻译一次、各说各的。
 * <p>
 * 现在管两件事：
 * <ul>
 *   <li>{@link #translateEndpointFailure} —— 端点不匹配（看着像网络不通，其实是端点形态对不上）</li>
 *   <li>{@link #writeRejectHint} —— 写入被拒的原因（只读 vs 类型不匹配，<b>修法完全相反</b>，
 *       混着说会让现场照着错的线索改半天）</li>
 * </ul>
 * <p>
 * <b>端点不匹配为什么要翻译</b>：milo 抛的是 {@code Bad_TcpEndpointUrlInvalid} /
 * {@code "unrecognized endpoint url"}，最外层还包着 Netty 的 {@code DecoderException}
 * —— 长得像网络不通，现场会去查网线、查防火墙、怀疑 PLC，
 * 而根因是「我们拼的端点 URL 和服务端 advertise 的不是同一个」。
 * <p>
 * <b>精确语义</b>：客户端只会尝试两个入口 —— ① 我们给它的 URL ② 那个 URL + {@code /discovery}；
 * 服务端只认自己 advertise 出来的 endpointUrl（尾斜杠会归一化，
 * 所以 {@code opc.tcp://host:port} 能匹配 advertise 的 {@code opc.tcp://host:port/}）。
 * ⇒ 规则不是「客户端连的不是 GetEndpoints 返回的 URL」，
 * 而是「<b>我们给客户端的 URL 必须和服务端 advertise 的 endpointUrl 匹配</b>」。
 */
final class OpcUaErrors {

    private OpcUaErrors() {
    }

    /**
     * 翻译端点不匹配错误；不是端点不匹配时原样返回。
     *
     * @param e   原始异常
     * @param url 我们拼出来的端点 URL
     * @return 翻译后的异常
     */
    static Exception translateEndpointFailure(Exception e, String url) {
        if (!isEndpointUrlMismatch(e)) {
            return e;
        }
        return new IllegalStateException(
                "端点 URL 与服务端不匹配（不是网络不通）：我们拼的是 " + url
                        + "，服务端只认自己 advertise 出来的 endpointUrl，与它对不上。"
                        + "若服务端把 endpoint 挂在子路径下（如 /milo），"
                        + "请去掉主机栏里的路径部分（本系统只支持 opc.tcp://<host>:<port> 形态）。"
                        + "原因：" + e.getMessage(),
                e);
    }

    /**
     * 按状态码说明「写入为什么被拒」。
     * <p>
     * <b>为什么要按状态码分流</b>：OPC UA 的拒绝理由有好几种，而最常撞的两种
     * （<b>节点只读</b> / <b>类型不匹配</b>）<b>修法完全相反</b> ——
     * 前者要去开设备端的写权限，后者要去改点表的 dataType。
     * 一律说「常见原因是 dataType 不一致」⇒ 现场照着改点表，改半天没用，
     * 而真正的原因（权限）就藏在状态码里没人看。
     * <p>
     * 状态码本身就在消息里（{@code sc} 会打印出
     * {@code StatusCode{name=Bad_NotWritable,...}}），按它分流才能给出对症的提示。
     *
     * @param sc 服务端返回的状态码
     * @return 给现场看的原因说明（可直接拼进异常消息）
     */
    static String writeRejectHint(StatusCode sc) {
        long v = sc.getValue();
        if (v == StatusCodes.Bad_NotWritable) {
            return "（Bad_NotWritable = 该节点是只读的，写入权限不足；"
                    + "如果这个点位本该可写，请检查设备端该节点的访问权限）";
        }
        if (v == StatusCodes.Bad_TypeMismatch) {
            return "（Bad_TypeMismatch = 点位 dataType 与 PLC 节点类型不一致，如把 f32 写成 double）";
        }
        if (v == StatusCodes.Bad_UserAccessDenied) {
            return "（Bad_UserAccessDenied = 该账号没有该节点的写权限）";
        }
        return "（常见原因：点位无写权限，或 dataType 与 PLC 节点类型不一致）";
    }

    /** 异常链里有没有「端点 URL 对不上」这个信号。 */
    static boolean isEndpointUrlMismatch(Throwable t) {
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            if (cur instanceof UaException) {
                StatusCode sc = ((UaException) cur).getStatusCode();
                if (sc != null && sc.getValue() == StatusCodes.Bad_TcpEndpointUrlInvalid) {
                    return true;
                }
            }
            String msg = cur.getMessage();
            if (msg != null && (msg.contains("Bad_TcpEndpointUrlInvalid")
                    || msg.contains("unrecognized endpoint url"))) {
                return true;
            }
            if (cur.getCause() == cur) {
                break;
            }
        }
        return false;
    }
}
