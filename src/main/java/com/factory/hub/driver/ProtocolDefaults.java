package com.factory.hub.driver;

import java.util.Map;

/**
 * 「协议 → 默认端口」的<b>唯一</b>口径。
 * <p>
 * <b>为什么要有这个类</b>：502 是 <b>Modbus</b> 的端口，4840 是 <b>OPC UA</b> 的端口。
 * 它们曾经被写成一个协议无关的全局常量 502，散落在实体、控制器、模板、上下文、大屏
 * 等 9 处。后果有两个：
 * <ol>
 *   <li><b>功能缺陷</b>：新建一台 OPC UA 设备不填端口 ⇒ 落库 502 ⇒ 驱动去连 502
 *       ⇒ <b>必然连不上</b>。前端在表单里预填 4840 只是遮住了 UI 那一路，
 *       API 建设备、脚本导入、老数据仍然是 502。</li>
 *   <li><b>每加一个协议漏一处</b>：加第三个协议还要再改一遍那 9 处，漏改的那个协议
 *       就会去连错端口。OPC UA 就是这次漏掉的那个。</li>
 * </ol>
 * ⇒ 所以口径收回到这一处：<b>「默认端口是多少」是协议知识，但只在这里定义一次</b>，
 * 实体/控制器/模板/大屏都来这里查，谁也不许再写死 502。
 * <p>
 * <b>它和驱动里的兜底是什么关系</b>：本类是<b>正常路径</b>（上游按协议解析好端口）；
 * 各驱动自己那份 {@code port <= 0 ? DEFAULT : port} 是<b>防御性兜底</b> ——
 * 万一上游漏了解析，驱动不至于拼出 {@code opc.tcp://host:0}。
 * 两者不冲突：正常时驱动那份永远走不到。
 * <p>
 * 放在 {@code driver} 包而不是 {@code utils}：按项目规范，{@code utils} 只放
 * 无业务、无副作用的纯工具函数；这里是<b>协议知识</b>，归协议相关的地方。
 * <p>
 * ⚠️ <b>格式契约（前后端门禁靠它对齐）</b>：
 * <ul>
 *   <li><b>推荐写法</b>：映射保持「每行一条 {@code Map.entry("协议", 常量名)}」。
 *       前端解析器优先按这个解析。</li>
 *   <li>改成 if/switch 形式，<b>前端断言仍然能解析</b>（它两种都支持），只是可读性下降。</li>
 *   <li>⚠️ <b>端口常量必须是字面量赋值</b>（{@code = 数字;}）——
 *       带算术运算（{@code 4840 + 0}）解析不出来。
 *       原因：之前正则只读到表达式的<b>前缀</b>、把尾部丢掉 ⇒ 门禁照样绿，
 *       而实际值已经变了 ⇒ <b>真·假绿</b>。</li>
 *   <li>⚠️ <b>兜底值同样不能带算术</b>（{@code getOrDefault(p, 502 + 1)}）。</li>
 *   <li><b>解析失败会红，不会静默放过</b>：前端门禁会报「[解析失败]」并带上本文件路径，
 *       要求同步更新解析器 ⇒ 请改解析器，不要绕过门禁。</li>
 * </ul>
 * <p>
 * 前端那份 {@code PROTO_DEFAULT_PORT} 是<b>副本</b>，漂移的后果是
 * 「<b>设备连到错端口</b>」，不只是文案对不上
 * （前端预填的值会盖掉后端按协议的兜底）。
 */
public final class ProtocolDefaults {

    /** Modbus TCP / Modbus RTU over TCP 的标准端口。 */
    public static final int MODBUS_TCP_PORT = 502;

    /** OPC UA 的标准端口。 */
    public static final int OPC_UA_PORT = 4840;

    /**
     * 协议 → 默认端口。
     * <p>
     * {@code modbus-tcp} / {@code modbus-rtu-tcp} / {@code modbus-rtu} / {@code sim}
     * 都不单列，走兜底值 {@link #MODBUS_TCP_PORT}（502）——
     * sim 不走 TCP，端口对它无意义，填 502 只是让它有个确定值。
     * <p>
     * 用 {@code Map.ofEntries} 而不是 {@code Map.of}：两者都不可变，
     * 但 {@code Map.ofEntries} 可以一条一行地写，加协议时格式不变
     * （前端脚本按行解析，格式稳定 ⇒ 解析不会突然失效）。
     * ⚠️ 别写成 {@code Map.of(...)} 之后再想 {@code put} —— 会抛
     * {@code UnsupportedOperationException}（不可变映射）。
     */
    private static final Map<String, Integer> PROTOCOL_PORTS = Map.ofEntries(
            Map.entry("opcua", OPC_UA_PORT));

    private ProtocolDefaults() {
    }

    /**
     * 取某协议的默认端口。
     *
     * @param protocol 协议标识（{@code modbus-tcp} / {@code modbus-rtu-tcp} / {@code opcua} 等）；
     *                 为 null 或不认识的一律按 Modbus 的 502 处理（保守）
     * @return 该协议的默认端口
     */
    public static int defaultPort(String protocol) {
        if (protocol == null) {
            return MODBUS_TCP_PORT;
        }
        String p = protocol.trim().toLowerCase();
        return PROTOCOL_PORTS.getOrDefault(p, MODBUS_TCP_PORT);
    }
}
