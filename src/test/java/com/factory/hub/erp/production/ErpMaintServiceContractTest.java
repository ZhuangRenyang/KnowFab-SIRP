package com.factory.hub.erp.production;

import com.factory.hub.ai.AiClient;
import com.factory.hub.ai.AiConfigStore;
import com.factory.hub.alarm.AlarmEventRepository;
import com.factory.hub.alarm.AlarmNotifier;
import com.factory.hub.auth.UserAccount;
import com.factory.hub.auth.UserRepository;
import com.factory.hub.erp.stock.ErpStockBill;
import com.factory.hub.erp.stock.ErpStockService;
// ⚠️ AlarmCodeDict 在 screen 包，不在 alarm 包（我第一版猜错了，编译报「找不到符号」）
import com.factory.hub.screen.AlarmCodeDict;
import com.factory.hub.service.DeviceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link ErpMaintService} 的**状态机口径**契约测试（2026-11-07 补）。
 * <p>
 * 为什么补：这个类 811 行、是维修工单的唯一状态机，此前 **0 个测试**。
 * 它坏掉的后果不是报错，而是**工人按错的状态流转**（比如已结案的单被重新开工）。
 * <p>
 * 钉的口径（全部来自 {@code docs/SPEC-DH85-WORKFLOW.md}）：
 * <ul>
 *   <li>只有 <b>ANALYZED</b> 的单能开工（start），其它状态一律拒绝</li>
 *   <li><b>指派不是状态机的一部分</b> —— SPEC 原话：「ANALYZED + 指派仍是 ANALYZED，
 *       只多了责任人」。这条最容易被「顺手」改成 setStatus，必须钉死</li>
 *   <li>传空 assignee = <b>取消指派</b>（清空三个字段，不报错）</li>
 * </ul>
 */
class ErpMaintServiceContractTest {

    private ErpMaintOrderRepository repo;
    private UserRepository userRepo;
    private ErpStockService stockService;
    private ErpMaintService service;

    @BeforeEach
    void setUp() {
        repo = mock(ErpMaintOrderRepository.class);
        userRepo = mock(UserRepository.class);
        stockService = mock(ErpStockService.class);
        service = new ErpMaintService(
                repo,
                mock(AlarmEventRepository.class),
                mock(DeviceService.class),
                mock(AlarmCodeDict.class),
                mock(AiClient.class),
                mock(AiConfigStore.class),
                new ObjectMapper(),
                userRepo,
                mock(AlarmNotifier.class),
                stockService);
    }

    private ErpMaintOrder order(String no, String status) {
        ErpMaintOrder o = new ErpMaintOrder();
        o.setOrderNo(no);
        o.setStatus(status);
        o.setDeviceCode("DEV-1");
        return o;
    }

    private void givenOrder(ErpMaintOrder o) {
        when(repo.findByOrderNo(o.getOrderNo())).thenReturn(Optional.of(o));
    }

    // ==================== 开工状态机 ====================

    @Test
    @DisplayName("start：ANALYZED 的单能开工，状态变 IN_PROGRESS 并记 startedAt")
    void startMovesAnalyzedToInProgress() {
        ErpMaintOrder o = order("MO-1", ErpMaintOrder.ST_ANALYZED);
        givenOrder(o);

        Map<String, Object> r = service.start("MO-1", "zhangsan");

        assertEquals(Boolean.TRUE, r.get("ok"));
        assertEquals(ErpMaintOrder.ST_IN_PROGRESS, o.getStatus(), "开工后状态必须是 IN_PROGRESS");
        assertNotNull(o.getStartedAt(), "开工必须记录开始时间");
    }

    @Test
    @DisplayName("start：只有 ANALYZED 能开工 —— IN_PROGRESS 的单再点开工必须被拒绝")
    void startRejectsNonAnalyzedStatus() {
        ErpMaintOrder o = order("MO-2", ErpMaintOrder.ST_IN_PROGRESS);
        givenOrder(o);

        Map<String, Object> r = service.start("MO-2", "zhangsan");

        assertEquals(Boolean.FALSE, r.get("ok"), "已开工的单不能再次开工");
        assertEquals(ErpMaintOrder.ST_IN_PROGRESS, o.getStatus(), "被拒绝时状态不能被改动");
        assertNull(o.getStartedAt(), "被拒绝时不能写 startedAt（否则会覆盖第一次开工时间）");
    }

    @Test
    @DisplayName("start：单不存在时返回失败，不抛异常（前端拿到的是 {ok:false} 而不是 500）")
    void startReturnsFailureWhenOrderMissing() {
        when(repo.findByOrderNo("MO-NONE")).thenReturn(Optional.empty());

        Map<String, Object> r = service.start("MO-NONE", "zhangsan");

        assertEquals(Boolean.FALSE, r.get("ok"));
        assertTrue(String.valueOf(r.get("message")).contains("不存在"));
    }

    // ==================== 指派不是状态机（SPEC 核心）====================

    @Test
    @DisplayName("assign：指派**不改变状态** —— ANALYZED + 指派仍是 ANALYZED（SPEC 明文要求）")
    void assignDoesNotChangeStatus() {
        ErpMaintOrder o = order("MO-3", ErpMaintOrder.ST_ANALYZED);
        givenOrder(o);

        UserAccount u = new UserAccount();
        u.setUsername("lisi");
        // role 默认是 LEADER，本来就在可派范围内（ADMIN / MANAGER / LEADER）
        when(userRepo.findByUsername("lisi")).thenReturn(Optional.of(u));

        Map<String, Object> r = service.assign("MO-3", "lisi", "admin");

        assertEquals(Boolean.TRUE, r.get("ok"));
        // ⚠️ 这三条是 SPEC-DH85 的原话：「指派**不是状态机的一部分**：
        //    ANALYZED + 指派仍是 ANALYZED，只多了责任人」
        //    若有人把它改成 setStatus(IN_PROGRESS)，工人还没动手单就显示「维修中」
        assertEquals(ErpMaintOrder.ST_ANALYZED, o.getStatus(),
                "指派后状态必须仍是 ANALYZED —— 指派不是状态机的一部分（SPEC-DH85）");
        assertEquals("lisi", o.getAssignee(), "责任人必须写入");
        assertNotNull(o.getAssignedAt(), "指派时间必须写入");
        assertEquals("admin", o.getAssignedBy(), "指派人必须写入");
    }

    @Test
    @DisplayName("assign：传空字符串 = 取消指派（清空三个字段，状态不变，不报错）")
    void assignBlankMeansUnassign() {
        ErpMaintOrder o = order("MO-4", ErpMaintOrder.ST_ANALYZED);
        o.setAssignee("lisi");
        o.setAssignedAt(123L);
        o.setAssignedBy("admin");
        givenOrder(o);

        Map<String, Object> r = service.assign("MO-4", "   ", "admin");

        assertEquals(Boolean.TRUE, r.get("ok"), "取消指派是正常操作，不能报错");
        assertNull(o.getAssignee(), "assignee 必须清空");
        assertNull(o.getAssignedAt(), "assignedAt 必须清空（否则待办页会显示一个没有责任人的指派时间）");
        assertNull(o.getAssignedBy(), "assignedBy 必须清空");
        assertEquals(ErpMaintOrder.ST_ANALYZED, o.getStatus(), "取消指派同样不改状态");
    }

    @Test
    @DisplayName("assign：已结案/已取消的单不能再指派")
    void assignRejectsClosedOrders() {
        ErpMaintOrder resolved = order("MO-5", ErpMaintOrder.ST_RESOLVED);
        givenOrder(resolved);

        Map<String, Object> r = service.assign("MO-5", "lisi", "admin");

        assertEquals(Boolean.FALSE, r.get("ok"), "已结案的单不能再改责任人");
        assertNull(resolved.getAssignee(), "被拒绝时不能写入责任人");
    }

    // ==================== 文案映射 ====================

    @Test
    @DisplayName("statusText / causeText：状态与原因码的中文映射稳定（前端直接显示）")
    void statusAndCauseTextMapping() {
        assertEquals("已诊断", ErpMaintService.statusText(ErpMaintOrder.ST_ANALYZED));
        assertEquals("维修中", ErpMaintService.statusText(ErpMaintOrder.ST_IN_PROGRESS));
        assertEquals("已结案", ErpMaintService.statusText(ErpMaintOrder.ST_RESOLVED));
        assertEquals("已取消", ErpMaintService.statusText(ErpMaintOrder.ST_CANCELLED));

        // 未知值必须原样回显，不能返回 null（前端会显示空白，看起来像数据丢了）
        assertEquals("SOMETHING_NEW", ErpMaintService.statusText("SOMETHING_NEW"));
        assertFalse(ErpMaintService.statusText(null).isEmpty(), "null 也要给一个可读文案");
    }

    // ==================== 维修领料（维修 ↔ 库存联动） ====================

    private static final List<ErpMaintService.PartLine> ONE_PART =
            List.of(new ErpMaintService.PartLine(7L, new BigDecimal("2")));

    @Test
    @DisplayName("领料：已取消的维修单必须拒绝，且根本不能碰库存服务")
    void issuePartsRejectsCancelledOrder() {
        ErpMaintOrder o = order("MO-C", ErpMaintOrder.ST_CANCELLED);
        givenOrder(o);

        Map<String, Object> r = service.issueParts("MO-C", 3L, ONE_PART, "zhangsan");

        assertEquals(Boolean.FALSE, r.get("ok"));
        assertTrue(String.valueOf(r.get("message")).contains("已取消"),
                "拒绝理由要说清是「已取消」，不能只说失败");
        verifyNoInteractions(stockService);
    }

    @Test
    @DisplayName("领料：空清单 / 数量非正数一律拒绝，且不产生任何单据")
    void issuePartsRejectsEmptyOrBadLines() {
        ErpMaintOrder o = order("MO-E", ErpMaintOrder.ST_IN_PROGRESS);
        givenOrder(o);

        assertNotOk(service.issueParts("MO-E", 3L, List.of(), "u"), "空清单");
        assertNotOk(service.issueParts("MO-E", 3L,
                List.of(new ErpMaintService.PartLine(7L, BigDecimal.ZERO)), "u"), "数量为 0");
        assertNotOk(service.issueParts("MO-E", 3L,
                List.of(new ErpMaintService.PartLine(7L, new BigDecimal("-1"))), "u"), "数量为负");
        assertNotOk(service.issueParts("MO-E", 3L,
                List.of(new ErpMaintService.PartLine(null, BigDecimal.ONE)), "u"), "没选备件");
        verifyNoInteractions(stockService);
    }

    @Test
    @DisplayName("领料：走 MAINT_OUT 单据类型，并把维修单号写进 srcOrderNo（备件成本才算得回原单）")
    void issuePartsCreatesMaintOutWithSourceOrder() {
        ErpMaintOrder o = order("MO-OK", ErpMaintOrder.ST_IN_PROGRESS);
        givenOrder(o);
        when(stockService.createManual(eq(ErpStockBill.TYPE_MAINT_OUT), isNull(), eq(3L), isNull(),
                anyString(), eq("zhangsan"), anyList(), eq("MO-OK")))
                .thenReturn(new ErpStockService.Created("WXLL20261108-0001", 1));
        when(stockService.bySourceOrder("MO-OK", ErpStockBill.TYPE_MAINT_OUT))
                .thenReturn(Map.of("totalAmount", new BigDecimal("11.60")));

        Map<String, Object> r = service.issueParts("MO-OK", 3L, ONE_PART, "zhangsan");

        assertEquals(Boolean.TRUE, r.get("ok"), "正常领料应成功");
        assertEquals("WXLL20261108-0001", r.get("billNo"));
        // 这条是核心：单据类型错了会混进「其他出库」，备件消耗统计就没了
        verify(stockService).createManual(eq(ErpStockBill.TYPE_MAINT_OUT), isNull(), eq(3L),
                isNull(), anyString(), eq("zhangsan"), anyList(), eq("MO-OK"));
    }

    @Test
    @DisplayName("领料：库存不足要返回 ok:false + 那句人话，不能把异常抛给前端变成 500")
    void issuePartsTurnsStockShortageIntoMessage() {
        ErpMaintOrder o = order("MO-SHORT", ErpMaintOrder.ST_IN_PROGRESS);
        givenOrder(o);
        when(stockService.createManual(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException(
                        "「深沟球轴承 6206」在「原材料仓」库存不足：现有 1，本次需要 2"));

        Map<String, Object> r = service.issueParts("MO-SHORT", 3L,
                List.of(new ErpMaintService.PartLine(8L, new BigDecimal("2"))), "zhangsan");

        assertEquals(Boolean.FALSE, r.get("ok"));
        assertTrue(String.valueOf(r.get("message")).contains("库存不足"),
                "必须把后端那句人话透出来，否则用户只看到 500");
        assertTrue(String.valueOf(r.get("message")).contains("现有 1"),
                "要带现有量，否则仓管不知道该补多少");
    }

    private static void assertNotOk(Map<String, Object> r, String why) {
        assertEquals(Boolean.FALSE, r.get("ok"), why + " 应被拒绝");
    }
}
