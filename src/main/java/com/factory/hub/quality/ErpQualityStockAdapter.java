package com.factory.hub.quality;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 处置 × 库存状态 六分支适配器（QC-SPEC §3.4）——<b>本模块最容易错的地方</b>。
 * <p>
 * 六个分支错了都不会报错：报废可能变成入库（库存凭空涨）、让步可能重复入库、
 * 返工可能让库存永远躺在成品仓里当良品卖。全都只在月底盘账时炸，那时已经发出货了。
 * <p>
 * 所以这里拆成两半：
 * <ul>
 *   <li>{@link #plan} <b>纯函数</b>：输入「不良单 + 处置动作 + 件数」，输出"要建哪几张单、
 *       什么类型、数量正负、从哪个仓到哪个仓"。</li>
 *   <li>{@link #execute} 极薄的执行层：照着计划调库存/工单服务，不做任何判断。</li>
 * </ul>
 * 六条单测断言的是 {@code plan} 的输出，不需要数据库——这样"建了几张单、什么类型、
 * qty 符号"能被逐支钉死。
 * <p>
 * <b>六分支矩阵（对着 Spec §3.4 的表格看）</b>：
 * <pre>
 *                 in_stock = false                        in_stock = true
 *   报废 SCRAP    不动库存，只改状态                         SCRAP_OUT 出库（qty 负，扣所在仓）
 *   返工 REWORK   建 REWORK 工单，不动库存                   TRANSFER 到待处理仓（不再可售）+ 建工单
 *   让步 CONCESS   OTHER_IN 入待处理仓                      TRANSFER 所在仓 → 待处理仓
 * </pre>
 * <b>为什么 {@code in_stock} 是分支点而不是"看仓库够不够"</b>：它记的是
 * "这批不良品<b>当时</b>有没有入过账"。没入过账的货去扣库存，扣的是别人的货，
 * 账实立刻分家；已入账的不出库，账上就永远比现实多一批。
 */
public final class ErpQualityStockAdapter {

    /** 处置动作（与 {@link ErpQcDisposition} 的常量一致） */
    private ErpQualityStockAdapter() {
    }

    /* ==================== 计划模型 ==================== */

    /** 计划的动作类型 */
    public enum Op {
        /** 建一张库存单据 */
        STOCK_BILL,
        /** 建一张返工工单（不动库存） */
        REWORK_ORDER,
        /** 什么都不做，只改不良单状态 */
        NONE
    }

    /**
     * 一条计划。
     *
     * @param op          做什么
     * @param billType    库存单据类型（{@link Op#STOCK_BILL} 时有值），取自 {@code ErpStockBill.TYPE_*}
     * @param qty         数量，<b>恒为正</b>——方向由 billType 决定（{@code ErpStockService} 内部按
     *                    出库类型取负）。这里要是允许传负号，就会出现"负的出库单"变成入库
     * @param srcWhId     调拨源仓（{@link Op#STOCK_BILL} 且调拨时有值）
     * @param whId        目标仓
     * @param refNo       关联单号（不良单号写进 {@code src_order_no}，追溯链靠它）
     * @param remark      单据备注：写清是哪个不良单处置来的，人工对账时一眼能看懂
     * @param reason      为什么这么做（给界面显示的一句话）
     */
    public record Intent(Op op, String billType, BigDecimal qty, Long srcWhId, Long whId,
                         String refNo, String remark, String reason) {
    }

    /**
     * 一份计划。
     *
     * @param intents 要执行的动作，可能为空（报废未入账时就是空）
     * @param summary 给用户看的一句话总结
     * @param warn    必须让人看见的风险提示（没有则 null）
     */
    public record Plan(List<Intent> intents, String summary, String warn) {
        public boolean isEmpty() {
            return intents.isEmpty();
        }

        /** 会建几张库存单据（单测就断言这个） */
        public long stockBillCount() {
            return intents.stream().filter(i -> i.op() == Op.STOCK_BILL).count();
        }

        /** 会建几张工单 */
        public long reworkOrderCount() {
            return intents.stream().filter(i -> i.op() == Op.REWORK_ORDER).count();
        }

        public List<String> billTypes() {
            List<String> out = new ArrayList<>();
            for (Intent i : intents) {
                if (i.op() == Op.STOCK_BILL) {
                    out.add(i.billType());
                }
            }
            return out;
        }
    }

    /**
     * 计划输入。
     *
     * @param inStock           不良品当时是否已入账（决定动不动库存）
     * @param action            SCRAP / REWORK / CONCESSION
     * @param qty               本次处置件数，必须 > 0
     * @param warehouseId       货当前所在仓（inStock=true 时必须有值）
     * @param blockedWarehouseId 待处理仓（禁发仓）id；让步与已入账返工必须有值
     * @param defectNo          不良单号
     */
    public record Ctx(boolean inStock, String action, BigDecimal qty, Long warehouseId,
                      Long blockedWarehouseId, String defectNo) {
    }

    /* ==================== 纯函数：算计划 ==================== */

    /**
     * 算计划。<b>这个方法是纯的：不查库、不写库、没有 Spring</b>，所以六条分支能被单测钉死。
     *
     * @throws IllegalArgumentException 输入不自洽（件数 ≤ 0、已入账却没有仓、让步没有待处理仓等）
     */
    public static Plan plan(Ctx c) {
        if (c == null) {
            throw new IllegalArgumentException("处置计划输入为空");
        }
        if (c.qty() == null || c.qty().signum() <= 0) {
            throw new IllegalArgumentException("处置件数必须大于 0（当前 "
                    + (c.qty() == null ? "空" : c.qty().toPlainString()) + "）");
        }
        String action = c.action();
        if (!ErpQcDisposition.ACTIONS.contains(action)) {
            throw new IllegalArgumentException("不支持的处置方式：" + action
                    + "（可用 SCRAP / REWORK / CONCESSION）");
        }
        // 已入账却不知道货在哪个仓：要么账错了，要么数据没填全。
        // 这时候如果"静默不动库存"，这批货就永远躺在某个仓里当良品，
        // 所以宁可报错让人补数据
        if (c.inStock() && c.warehouseId() == null) {
            throw new IllegalArgumentException("不良单 " + c.defectNo()
                    + " 标记为已入账，但不知道货在哪个仓——无法处置。请补齐所在仓库后重试");
        }

        return switch (action) {
            case ErpQcDisposition.ACT_SCRAP -> scrap(c);
            case ErpQcDisposition.ACT_REWORK -> rework(c);
            case ErpQcDisposition.ACT_CONCESSION -> concession(c);
            default -> throw new IllegalArgumentException("不支持的处置方式：" + action);
        };
    }

    /**
     * 分支①②：报废。
     * <ul>
     *   <li><b>未入账</b>：这批货从来没进过库存（设备采集的在制品、IQC 判定前的不良）
     *       → <b>不动库存</b>，只改单状态。去扣库存等于扣别人的货，账实立刻分家；</li>
     *   <li><b>已入账</b>：货真的在仓里 → {@code SCRAP_OUT} 出库，扣所在仓。</li>
     * </ul>
     */
    private static Plan scrap(Ctx c) {
        if (!c.inStock()) {
            return new Plan(List.of(),
                    "报废 " + plain(c.qty()) + " 件（这批货从未入账，不产生库存单据）",
                    null);
        }
        return new Plan(List.of(new Intent(
                        Op.STOCK_BILL, com.factory.hub.erp.ErpStockBill.TYPE_SCRAP_OUT, c.qty(),
                        null, c.warehouseId(), c.defectNo(),
                        "不良品处置报废（不良单 " + c.defectNo() + "）",
                        "从所在仓出库报废")),
                "报废出库 " + plain(c.qty()) + " 件",
                // 报废出库单的 qty 之所以为正，是因为方向由单据类型决定；
                // 界面显示时按类型加负号，别在这里再取反一次
                null);
    }

    /**
     * 分支③④：返工。
     * <ul>
     *   <li><b>未入账</b>：货不在任何仓里 → 只建 {@code REWORK} 工单，不动库存。
     *       补料走 {@code MATERIAL_OUT}，由工单领料流程负责，这里不预先记账；</li>
     *   <li><b>已入账</b>：先把货从所在仓 {@code TRANSFER} 到待处理仓（禁发仓），
     *       <b>让它不再可售</b>，再建工单。返工完成后由返工入库（{@code PROD_IN}）回到成品仓。</li>
     * </ul>
     * <b>为什么已入账要先调拨</b>：不调走的话，这批不良品还挂在成品仓里，
     * 销售查可用量时能看到它、发出去，等于把不良品当良品卖了。
     */
    private static Plan rework(Ctx c) {
        requireBlockedWh(c, "返工");
        // 货已经在待处理仓里（上一次处置已经调过）：不用再调，只建工单。
        // 硬调拨会被库存服务拦下来（源仓和目标仓不能是同一个），报错信息还不好懂
        boolean moved = c.inStock() && !c.warehouseId().equals(c.blockedWarehouseId());
        Intent order = new Intent(Op.REWORK_ORDER, null, c.qty(), null, null, c.defectNo(),
                "不良品返工（不良单 " + c.defectNo() + "）", "建返工工单");
        if (!moved) {
            return new Plan(List.of(order),
                    "建返工工单 " + plain(c.qty()) + " 件"
                            + (c.inStock() ? "（货已在待处理仓）" : "（这批货从未入账，不产生库存单据）"),
                    null);
        }
        List<Intent> intents = new ArrayList<>();
        intents.add(new Intent(Op.STOCK_BILL, com.factory.hub.erp.ErpStockBill.TYPE_TRANSFER, c.qty(),
                c.warehouseId(), c.blockedWarehouseId(), c.defectNo(),
                "不良品返工：转入待处理仓（不良单 " + c.defectNo() + "）",
                "从所在仓调到待处理仓，先让它不可售"));
        intents.add(order);
        return new Plan(intents,
                "调到待处理仓 " + plain(c.qty()) + " 件，并建返工工单",
                // 这条提示是 team-lead 明确要求写进返回 message 的（R24）：
                // 账上已经把货调到待处理仓（不再可售），但"从库里搬到车间"这一步系统做不了，
                // 不搬的话实物还在仓库、账上也在待处理仓，月底盘亏
                "货已调到待处理仓（不再可售）。实物从仓库搬到车间需要人工作业，"
                        + "返工完成后按返工入库回成品仓");
    }

    /**
     * 分支⑤⑥：让步接收。
     * <ul>
     *   <li><b>未入账</b>：{@code OTHER_IN} 入待处理仓（禁发仓），带 {@code src_order_no = 不良单号}；</li>
     *   <li><b>已入账</b>：{@code TRANSFER} 所在仓 → 待处理仓，同样带不良单号。</li>
     * </ul>
     * <b>不新增 {@code CONCESSION_IN} 类型</b>：让价损失记在处置明细的
     * {@code concession_amount} 上；发货风险由"待处理仓"这个仓维度硬拦
     * （源仓是禁发仓且无客户同意凭证即拒）。少一个单据类型 = 少 5 个登记点 × 2 的风险。
     */
    private static Plan concession(Ctx c) {
        requireBlockedWh(c, "让步接收");
        String warn = "待处理仓的货不能直接出库；客户同意后登记同意凭证才能放行";
        // 货已经在待处理仓：不用调，也不需要别的库存动作
        boolean moved = c.inStock() && !c.warehouseId().equals(c.blockedWarehouseId());
        if (c.inStock() && !moved) {
            return new Plan(List.of(),
                    "让步接收 " + plain(c.qty()) + " 件（货已在待处理仓，无库存动作）", warn);
        }
        if (!c.inStock()) {
            return new Plan(List.of(new Intent(
                            Op.STOCK_BILL, com.factory.hub.erp.ErpStockBill.TYPE_OTHER_IN, c.qty(),
                            null, c.blockedWarehouseId(), c.defectNo(),
                            "不良品让步接收（不良单 " + c.defectNo() + "）",
                            "让步接收，入待处理仓")),
                    "让步接收入待处理仓 " + plain(c.qty()) + " 件", warn);
        }
        return new Plan(List.of(new Intent(
                        Op.STOCK_BILL, com.factory.hub.erp.ErpStockBill.TYPE_TRANSFER, c.qty(),
                        c.warehouseId(), c.blockedWarehouseId(), c.defectNo(),
                        "不良品让步接收：原仓转到待处理仓（不良单 " + c.defectNo() + "）",
                        "让步接收，调到待处理仓")),
                "从所在仓调到待处理仓 " + plain(c.qty()) + " 件", warn);
    }

    private static void requireBlockedWh(Ctx c, String action) {
        if (c.blockedWarehouseId() == null) {
            throw new IllegalArgumentException(action + "需要「待处理仓」，但系统里没有配置禁发仓。"
                    + "请到「基础资料 → 仓库」建一个并把类型设为禁发仓");
        }
    }

    private static String plain(BigDecimal v) {
        BigDecimal s = v.stripTrailingZeros();
        return s.scale() <= 0 ? s.toBigInteger().toString() : s.toPlainString();
    }

    /* ==================== 执行层 ==================== */

    /**
     * 照计划建单。这里<b>不做任何判断</b>——所有该判断的都在 {@link #plan} 里判断完了，
     * 这样测试只要盯住 plan，执行层出错的空间被压到最小。
     *
     * @return 每一个动作对应的单号（按 intents 顺序；工单动作返回工单号）
     */
    public static List<String> execute(Plan plan, Long skuId, String operator,
                                       com.factory.hub.erp.ErpStockService stockService,
                                       com.factory.hub.erp.ErpWorkOrderService workOrderService,
                                       String srcWorkOrderNo) {
        List<String> created = new ArrayList<>();
        for (Intent i : plan.intents()) {
            switch (i.op()) {
                case STOCK_BILL -> {
                    // 数量恒为正；出库类型由 ErpStockService 统一取负（isOutbound 单一事实来源）
                    var req = List.of(new com.factory.hub.erp.ErpStockService.LineReq(
                            skuId, i.qty(), null));
                    var r = stockService.createManual(i.billType(), null, i.whId(), i.srcWhId(),
                            i.remark(), operator, req, i.refNo());
                    created.add(r.billNo());
                }
                case REWORK_ORDER -> {
                    java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
                    body.put("productSkuId", skuId);
                    body.put("planQty", i.qty());
                    body.put("remark", i.remark());
                    body.put("orderType", com.factory.hub.erp.ErpWorkOrder.TYPE_REWORK);
                    body.put("srcOrderNo", i.refNo());
                    if (srcWorkOrderNo != null) {
                        body.put("srcWorkOrderNo", srcWorkOrderNo);
                    }
                    // 返工不展开 BOM（用料清单为空），所以反冲不会重复扣料
                    body.put("autoBackflush", false);
                    var r = workOrderService.create(body, operator);
                    created.add(String.valueOf(r.get("orderNo")));
                }
                case NONE -> {
                    // 不改库存，什么都不做
                }
            }
        }
        return created;
    }
}
