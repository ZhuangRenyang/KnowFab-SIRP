package com.factory.hub.driver.opcua;

import java.util.List;

import org.eclipse.milo.opcua.sdk.server.OpcUaServer;
import org.eclipse.milo.opcua.sdk.server.api.DataItem;
import org.eclipse.milo.opcua.sdk.server.api.ManagedNamespaceWithLifecycle;
import org.eclipse.milo.opcua.sdk.server.api.MonitoredItem;
import org.eclipse.milo.opcua.sdk.server.nodes.UaVariableNode;
import org.eclipse.milo.opcua.stack.core.Identifiers;
import org.eclipse.milo.opcua.stack.core.types.builtin.ByteString;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UByte;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UInteger;

/**
 * 测试用的 OPC UA 命名空间（ns=2）。
 * <p>
 * <b>为什么需要它</b>：现场真机是西门子 S7-200，<b>不支持 OPC UA</b>，
 * 所以「驱动能不能跑通」只能在本地用真 OPC UA Server 验证。
 * 这个类在本地 server 上挂几个真实节点，让 {@code OpcUaDriver} 去读，
 * 从而证明「驱动代码本身在 Spring Boot 4.1.2 + milo 0.6.11 下是好的」。
 * <p>
 * <b>命名空间索引固定为 2</b>：ns=0 是 OPC UA 标准空间，ns=1 是服务端应用空间，
 * 我们自己加的第一个命名空间就是 ns=2 —— 这也是现场点表最常用的写法。
 */
final class OpcUaTestNamespace extends ManagedNamespaceWithLifecycle {

    /** 命名空间 URI。现场 PLC 各自的 ns 索引不一样，这里只求与真机同构。 */
    static final String NAMESPACE_URI = "urn:knowfab:test:opcua";

    OpcUaTestNamespace(OpcUaServer server) {
        super(server, NAMESPACE_URI);
    }

    /**
     * 挂一个 Double 节点。
     *
     * @param identifier 节点标识，最终的 nodeId 形如 {@code ns=2;s=TestTag1}
     * @param value      初值
     * @param writable   是否允许写入（决定 accessLevel）
     */
    UaVariableNode addDouble(String identifier, double value, boolean writable) {
        UaVariableNode node = newVariable(identifier, Identifiers.Double, writable);
        node.setValue(new DataValue(new Variant(value)));
        return node;
    }

    /** 挂一个 Int32 节点。 */
    UaVariableNode addInt32(String identifier, int value, boolean writable) {
        UaVariableNode node = newVariable(identifier, Identifiers.Int32, writable);
        node.setValue(new DataValue(new Variant(value)));
        return node;
    }

    /** 挂一个 Boolean 节点。 */
    UaVariableNode addBoolean(String identifier, boolean value, boolean writable) {
        UaVariableNode node = newVariable(identifier, Identifiers.Boolean, writable);
        node.setValue(new DataValue(new Variant(value)));
        return node;
    }

    /**
     * 挂一个<b>节点号是 ByteString</b> 的节点（nodeId 形如 {@code ns=2;b=AAEC}）。
     * <p>
     * 为什么要专门造一个：milo 解析这类节点号要走
     * {@code NodeId.parse() → DatatypeConverter.parseBase64Binary}，
     * 而 {@code javax.xml.bind.DatatypeConverter} 在 Boot 4 的类路径上曾经整个消失。
     * 有这个节点，才能端到端验「ByteString 节点号在生产运行时真的能读」。
     *
     * @param identifier 节点号的字节内容
     * @param value      节点的值（这里用 Double，方便断言）
     */
    UaVariableNode addByteString(byte[] identifier, double value) {
        NodeId nodeId = new NodeId(getNamespaceIndex(), new ByteString(identifier));
        UaVariableNode node = new UaVariableNode(
                getNodeContext(),
                nodeId,
                newQualifiedName("ByteStringTag"),
                LocalizedText.english("ByteStringTag"),
                LocalizedText.english("KnowFab test tag with ByteString node id"),
                UInteger.valueOf(-1),
                UInteger.valueOf(-1));
        node.setDataType(Identifiers.Double);
        node.setAccessLevel(UByte.valueOf(0x01));
        node.setUserAccessLevel(UByte.valueOf(0x01));
        node.setValue(new DataValue(new Variant(value)));
        getNodeManager().addNode(node);
        return node;
    }

    /** 改节点当前值（用来模拟现场数值在变）。 */
    void updateValue(UaVariableNode node, Object value) {
        node.setValue(new DataValue(new Variant(value)));
    }

    private UaVariableNode newVariable(String identifier, NodeId dataType, boolean writable) {
        NodeId nodeId = new NodeId(getNamespaceIndex(), identifier);
        UaVariableNode node = new UaVariableNode(
                getNodeContext(),
                nodeId,
                newQualifiedName(identifier),
                LocalizedText.english(identifier),
                LocalizedText.english("KnowFab test tag " + identifier),
                UInteger.valueOf(-1),
                UInteger.valueOf(-1));
        node.setDataType(dataType);
        // accessLevel 是位掩码：0x01=可读，0x02=可写
        int accessLevel = writable ? 0x03 : 0x01;
        node.setAccessLevel(UByte.valueOf(accessLevel));
        node.setUserAccessLevel(UByte.valueOf(accessLevel));
        getNodeManager().addNode(node);
        return node;
    }

    @Override
    public void onMonitoringModeChanged(List<MonitoredItem> monitoredItems) {
        // 测试节点不参与订阅，无需处理
    }

    @Override
    public void onDataItemsCreated(List<DataItem> items) {
    }

    @Override
    public void onDataItemsModified(List<DataItem> items) {
    }

    @Override
    public void onDataItemsDeleted(List<DataItem> items) {
    }
}
