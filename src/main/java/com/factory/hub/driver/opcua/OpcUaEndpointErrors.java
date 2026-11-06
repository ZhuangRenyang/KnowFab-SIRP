package com.factory.hub.driver.opcua;

import org.eclipse.milo.opcua.stack.core.StatusCodes;
import org.eclipse.milo.opcua.stack.core.UaException;
import org.eclipse.milo.opcua.stack.core.types.builtin.StatusCode;

/**
 * 把 milo 的「端点不匹配」错误翻译成人话。
 * <p>
 * <b>为什么必须翻译</b>：milo 抛的是 {@code Bad_TcpEndpointUrlInvalid} /
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
final class OpcUaEndpointErrors {

    private OpcUaEndpointErrors() {
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
