package com.factory.hub.service;

/**
 * 实时推送出口（WebSocket 广播的**契约**）。
 * <p>
 * <b>为什么要有这个接口</b>：业务层（`alarm` / `collect` / `demand`）需要把实时数据
 * 推给前端，但它们**不应该认识 Web 层的实现类** —— 那样依赖方向是反的：
 * <pre>
 *   ❌ 改前：alarm.AlarmEngine ──→ web.WsHandler      （业务层依赖 Web 层）
 *   ✅ 改后：alarm.AlarmEngine ──→ service.Broadcaster ←── web.WsHandler
 * </pre>
 * 倒置的实际代价：想换传输方式（SSE / MQTT / 长轮询）或想在无 Web 环境的测试里跑业务逻辑，
 * 都得改业务层；而业务层本来与「怎么送到浏览器」无关。
 * <p>
 * <b>为什么不放在 {@code web} 包里</b>：接口若留在 `web`，业务层 import 的仍然是
 * `web.*` ⇒ 倒置没有解决，只是把「依赖实现」变成「依赖 web 包里的接口」，形似而神不似。
 * <p>
 * 实现类见 {@code com.factory.hub.web.WsHandler}（`@Component`，是容器里唯一的实现）。
 */
public interface Broadcaster {

    /**
     * 广播一段已序列化好的 JSON 文本给所有在线连接。
     * <p>
     * 约定：<b>调用方负责序列化</b>（用注入的 {@code ObjectMapper}），
     * 实现方只负责「发出去」—— 这样实现层不需要认识任何业务数据结构。
     * 没有在线连接时应当**静默返回**（不能抛异常，否则采集线程会被一次空广播打断）。
     */
    void broadcast(String payload);
}
