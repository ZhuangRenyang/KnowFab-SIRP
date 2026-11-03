package com.factory.hub.erp;

import com.factory.hub.model.DeviceEntity;
import com.factory.hub.service.DeviceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 生产工单（DH-71）：设备与 ERP 联动的载体。
 * <p>
 * 三件事在这里闭环：
 * <ol>
 *   <li><b>料</b>：下达时按 BOM 展开需求量 → 领料生成 MATERIAL_OUT → 原料真的从仓库减掉；</li>
 *   <li><b>产</b>：设备计数增量挂到该设备当前 RUNNING 的工单 → 成品入库单带工单号 → 回写完成量；</li>
 *   <li><b>追</b>：工单挂销售订单号，交期、缺料、超领都能落到具体一张单上。</li>
 * </ol>
 * <p>
 * <b>为什么反冲和手工领料不能同时用</b>：反冲是"产出多少就按 BOM 扣多少"，
 * 手工领料是"一次性把料领够"。两个都开，同一批料会被扣两次，而且账面上看不出哪次是重的。
 * 所以工单上是一个开关：开了自动反冲，就只能走反冲（手工领料会被拒绝并说明原因）；
 * 关了，就只能手工领料（产出不动库存）。
 * <p>
 * <b>设备与工单的绑定是双向强制的</b>：同一台设备同时只能有一张 RUNNING 工单。
 * 不做这个约束的话，设备产出的 10 件不知道该算给哪张单，进度全乱。
 */
@Service
public class ErpWorkOrderService {

    private static final Logger log = LoggerFactory.getLogger(ErpWorkOrderService.class);

    private static final ZoneId ZONE = ZoneId.systemDefault();
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal EPS = new BigDecimal("0.0001");

    private final ErpWorkOrderRepository woRepo;
    private final ErpWorkOrderMaterialRepository womRepo;
    private final ErpWorkOrderOutputRepository wooRepo;
    private final ErpBomRepository bomRepo;
    private final ErpBomService bomService;
    private final ErpBackflushService backflushService;
    private final ErpStockService stockService;
    private final ErpStockBillRepository billRepo;
    private final ErpSkuRepository skuRepo;
    private final ErpProdLineRepository lineRepo;
    private final ErpWarehouseRepository whRepo;
    private final DeviceService deviceService;

    /**
     * 设备 → 当前在产工单的路由。
     * <p>
     * 带上成品 SKU 和仓库，是因为设备产出的回调每秒都在跑，<b>不能查库</b>；
     * 而工单驱动的产出必须落到工单指定的产品上（不是设备配置里那个固定产品）。
     * 这两个信息放在缓存里，回调只碰内存。
     */
    public record ActiveOrder(String orderNo, Long skuId, Long whId) {}

    private volatile Map<String, ActiveOrder> routing = Map.of();
    private volatile long routingAt = 0;

    public ErpWorkOrderService(ErpWorkOrderRepository woRepo,
                               ErpWorkOrderMaterialRepository womRepo,
                               ErpWorkOrderOutputRepository wooRepo,
                               ErpBomRepository bomRepo,
                               ErpBomService bomService,
                               ErpBackflushService backflushService,
                               ErpStockService stockService,
                               ErpStockBillRepository billRepo,
                               ErpSkuRepository skuRepo,
                               ErpProdLineRepository lineRepo,
                               ErpWarehouseRepository whRepo,
                               DeviceService deviceService) {
        this.woRepo = woRepo;
        this.womRepo = womRepo;
        this.wooRepo = wooRepo;
        this.bomRepo = bomRepo;
        this.bomService = bomService;
        this.backflushService = backflushService;
        this.stockService = stockService;
        this.billRepo = billRepo;
        this.skuRepo = skuRepo;
        this.lineRepo = lineRepo;
        this.whRepo = whRepo;
        this.deviceService = deviceService;
    }

    /** 行录入：领料时的一种材料 */
    public record IssueLine(Long skuId, BigDecimal qty) {}

    /* ==================== 查询 ==================== */

    public Map<String, Object> list(String status, String kw, int page, int size) {
        String st = blank(status) ? null : status.trim();
        String k = blank(kw) ? null : kw.trim();
        var p = woRepo.search(st, k,
                org.springframework.data.domain.PageRequest.of(Math.max(page, 1) - 1,
                        Math.min(Math.max(size, 1), 200)));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ErpWorkOrder o : p.getContent()) {
            rows.add(row(o, false));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("rows", rows);
        out.put("total", p.getTotalElements());
        out.put("page", page);
        out.put("size", size);
        out.put("pages", p.getTotalPages());
        out.put("summary", summary());
        return out;
    }

    /** 概览：在产/待开工/本月完工，列表页顶部四张卡 */
    public Map<String, Object> summary() {
        long running = woRepo.countByStatusIn(List.of(ErpWorkOrder.ST_RUNNING));
        long released = woRepo.countByStatusIn(List.of(ErpWorkOrder.ST_RELEASED));
        long draft = woRepo.countByStatusIn(List.of(ErpWorkOrder.ST_DRAFT));
        long done = woRepo.countByStatusIn(List.of(ErpWorkOrder.ST_DONE));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("running", running);
        m.put("released", released);
        m.put("draft", draft);
        m.put("done", done);
        return m;
    }

    public Map<String, Object> detail(String orderNo) {
        ErpWorkOrder o = load(orderNo);
        Map<String, Object> out = row(o, true);

        List<ErpWorkOrderMaterial> mats = womRepo.findByWorkOrderIdOrderBySkuIdAsc(o.getId());
        List<Map<String, Object>> matRows = new ArrayList<>();
        BigDecimal totalNeed = BigDecimal.ZERO;
        BigDecimal totalIssued = BigDecimal.ZERO;
        int shortageCount = 0;
        for (ErpWorkOrderMaterial m : mats) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", m.getId());
            r.put("skuId", m.getSkuId());
            r.put("skuCode", m.getSkuCode());
            r.put("skuName", m.getSkuName());
            r.put("unit", m.getSkuUnit());
            r.put("needQty", m.getNeedQty());
            r.put("issuedQty", m.getIssuedQty());
            BigDecimal gap = m.getNeedQty().subtract(m.getIssuedQty());
            r.put("gapQty", gap);
            r.put("shortage", gap.signum() > 0);
            r.put("over", gap.signum() < 0);      // 超领：车间补料/试模，标出来由人判断
            r.put("warehouseId", m.getWarehouseId());
            if (gap.signum() > 0) {
                shortageCount++;
            }
            totalNeed = totalNeed.add(m.getNeedQty());
            totalIssued = totalIssued.add(m.getIssuedQty());
            // 现有库存：界面直接显示"仓库里还够不够"，不用再点一次
            if (m.getWarehouseId() != null) {
                r.put("stockQty", stockOnHand(m.getSkuId(), m.getWarehouseId()));
            }
            matRows.add(r);
        }
        out.put("materials", matRows);
        out.put("materialNeedTotal", totalNeed);
        out.put("materialIssuedTotal", totalIssued);
        out.put("shortageCount", shortageCount);

        List<ErpWorkOrderOutput> outs = wooRepo.findByWorkOrderIdOrderByCreatedAtDesc(o.getId());
        List<Map<String, Object>> outRows = new ArrayList<>();
        for (ErpWorkOrderOutput x : outs) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("billNo", x.getBillNo());
            r.put("deviceCode", x.getDeviceCode());
            r.put("qty", x.getQty());
            r.put("defectQty", x.getDefectQty());
            r.put("createdAt", x.getCreatedAt());
            outRows.add(r);
        }
        out.put("outputs", outRows);
        return out;
    }

    /** 设备当前在做的工单；没有在产工单返回 null。缓存 10 秒，开工/完工时主动失效。 */
    public ActiveOrder activeOrderOf(String deviceCode) {
        if (deviceCode == null) {
            return null;
        }
        refreshRouting();
        return routing.get(deviceCode);
    }

    /**
     * 缺料看板：已下达/在产的工单里，需求大于已领的材料。
     * <p>
     * 这是「设备管理与 ERP 联动」最实用的一张表——车间看它决定今天去哪领料，
     * 采购看它决定今天补什么货，AI 看它回答「哪张工单会因为缺料停线」。
     * 只算未完工的工单：完工了再缺料已经没有意义（那叫"少领"，是成本核算的事）。
     */
    public Map<String, Object> shortage() {
        List<ErpWorkOrder> open = woRepo.findByStatusIn(
                List.of(ErpWorkOrder.ST_RELEASED, ErpWorkOrder.ST_RUNNING));
        Map<Long, ErpWorkOrder> byId = new LinkedHashMap<>();
        for (ErpWorkOrder o : open) {
            byId.put(o.getId(), o);
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        if (!open.isEmpty()) {
            for (ErpWorkOrderMaterial m : womRepo.findShortage(new ArrayList<>(byId.keySet()))) {
                ErpWorkOrder o = byId.get(m.getWorkOrderId());
                if (o == null) {
                    continue;
                }
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("orderNo", o.getOrderNo());
                r.put("productName", o.getProductName());
                r.put("status", o.getStatus());
                r.put("skuId", m.getSkuId());
                r.put("skuCode", m.getSkuCode());
                r.put("skuName", m.getSkuName());
                r.put("unit", m.getSkuUnit());
                r.put("needQty", m.getNeedQty());
                r.put("issuedQty", m.getIssuedQty());
                BigDecimal gap = m.getNeedQty().subtract(m.getIssuedQty());
                r.put("gapQty", gap);
                // 现有库存：仓库里到底还有没有，决定"补领"还是"补采购"
                r.put("stockQty", m.getWarehouseId() == null ? null
                        : stockOnHand(m.getSkuId(), m.getWarehouseId()));
                rows.add(r);
            }
        }
        // 缺口大的排前面：最急的先看到
        rows.sort((a, b) -> ((BigDecimal) b.get("gapQty")).compareTo((BigDecimal) a.get("gapQty")));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("rows", rows);
        out.put("total", rows.size());
        return out;
    }

    /** 兼容旧调用：只要工单号 */
    public String runningOrderOf(String deviceCode) {
        ActiveOrder a = activeOrderOf(deviceCode);
        return a == null ? null : a.orderNo();
    }

    private void refreshRouting() {
        long now = System.currentTimeMillis();
        if (now - routingAt < 10_000L) {
            return;
        }
        synchronized (this) {
            if (now - routingAt < 10_000L) {
                return;
            }
            Map<String, ActiveOrder> m = new HashMap<>();
            try {
                for (ErpWorkOrder o : woRepo.findByStatus(ErpWorkOrder.ST_RUNNING)) {
                    if (o.getDeviceCode() != null) {
                        m.put(o.getDeviceCode(),
                                new ActiveOrder(o.getOrderNo(), o.getProductSkuId(), o.getWarehouseId()));
                    }
                }
            } catch (Exception e) {
                log.warn("刷新工单路由失败（沿用旧缓存）: {}", e.getMessage());
                routingAt = now;
                return;
            }
            routing = m;
            routingAt = now;
        }
    }

    private void invalidateRouting() {
        routingAt = 0;
    }

    /* ==================== 状态机 ==================== */

    @Transactional
    public Map<String, Object> create(Map<String, Object> body, String operator) {
        Long productSkuId = longOf(body.get("productSkuId"));
        ErpSku product = skuRepo.findById(productSkuId == null ? -1L : productSkuId)
                .orElseThrow(() -> new IllegalArgumentException("请选择要生产的产品"));
        BigDecimal planQty = decOf(body.get("planQty"));
        if (planQty == null || planQty.signum() <= 0) {
            throw new IllegalArgumentException("计划产量必须大于 0");
        }

        ErpWorkOrder o = new ErpWorkOrder();
        o.setOrderNo(nextOrderNo());
        o.setProductSkuId(product.getId());
        o.setProductCode(product.getCode());
        o.setProductName(product.getName());
        o.setProductUnit(product.getUnit());
        o.setPlanQty(planQty);
        o.setStatus(ErpWorkOrder.ST_DRAFT);
        o.setLineCode(str(body.get("lineCode")));
        o.setDeviceCode(str(body.get("deviceCode")));
        o.setWarehouseId(longOf(body.get("warehouseId")));
        o.setMaterialWhId(longOf(body.get("materialWhId")));
        o.setSrcOrderNo(str(body.get("srcOrderNo")));
        // 工单类型：只认 REWORK，其余（含空）一律 PROD。
        // 返工单由不良品处置生成，指向原工单；原工单号可以为空（客户退回的旧货常常找不到原单，
        // 强制必填会让人随便编一个填进去，那比留空更糟——假追溯链比断链危险）
        o.setOrderType(ErpWorkOrder.TYPE_REWORK.equalsIgnoreCase(str(body.get("orderType")))
                ? ErpWorkOrder.TYPE_REWORK : ErpWorkOrder.TYPE_PROD);
        o.setSrcWorkOrderNo(str(body.get("srcWorkOrderNo")));
        o.setAutoBackflush(bool(body.get("autoBackflush"), true));
        o.setPlanStart(longOf(body.get("planStart")));
        o.setPlanEnd(longOf(body.get("planEnd")));
        o.setRemark(str(body.get("remark")));
        o.setCreatedBy(operator);
        o.setCreatedAt(System.currentTimeMillis());
        o.setUpdatedAt(o.getCreatedAt());
        woRepo.save(o);

        log.info("新建工单 {}：产品 {} × {}（操作人 {}）",
                o.getOrderNo(), product.getCode(), planQty, operator);
        return ok("工单 " + o.getOrderNo() + " 已建（草稿），下达后才展开用料", "orderNo", o.getOrderNo());
    }

    @Transactional
    public Map<String, Object> update(String orderNo, Map<String, Object> body, String operator) {
        ErpWorkOrder o = load(orderNo);
        if (!ErpWorkOrder.ST_DRAFT.equals(o.getStatus())) {
            throw new IllegalArgumentException("只有草稿能改。已下达的工单要用「取消」重开，"
                    + "否则已展开的用料会和改动对不上");
        }
        if (body.containsKey("productSkuId")) {
            Long pid = longOf(body.get("productSkuId"));
            ErpSku p = skuRepo.findById(pid == null ? -1L : pid)
                    .orElseThrow(() -> new IllegalArgumentException("产品不存在"));
            o.setProductSkuId(p.getId());
            o.setProductCode(p.getCode());
            o.setProductName(p.getName());
            o.setProductUnit(p.getUnit());
        }
        if (body.containsKey("planQty")) {
            BigDecimal q = decOf(body.get("planQty"));
            if (q == null || q.signum() <= 0) {
                throw new IllegalArgumentException("计划产量必须大于 0");
            }
            o.setPlanQty(q);
        }
        if (body.containsKey("lineCode")) { o.setLineCode(str(body.get("lineCode"))); }
        if (body.containsKey("deviceCode")) { o.setDeviceCode(str(body.get("deviceCode"))); }
        if (body.containsKey("warehouseId")) { o.setWarehouseId(longOf(body.get("warehouseId"))); }
        if (body.containsKey("materialWhId")) { o.setMaterialWhId(longOf(body.get("materialWhId"))); }
        if (body.containsKey("srcOrderNo")) { o.setSrcOrderNo(str(body.get("srcOrderNo"))); }
        if (body.containsKey("autoBackflush")) { o.setAutoBackflush(bool(body.get("autoBackflush"), true)); }
        if (body.containsKey("planStart")) { o.setPlanStart(longOf(body.get("planStart"))); }
        if (body.containsKey("planEnd")) { o.setPlanEnd(longOf(body.get("planEnd"))); }
        if (body.containsKey("remark")) { o.setRemark(str(body.get("remark"))); }
        o.setUpdatedAt(System.currentTimeMillis());
        woRepo.save(o);
        return ok("工单已更新", "orderNo", orderNo);
    }

    /**
     * 下达：DRAFT → RELEASED，<b>这一刻把 BOM 展开成用料快照</b>。
     * <p>
     * 展开失败（产品没配 BOM）直接抛异常、状态不变——不允许"下达了但没用料"的工单存在，
     * 那种工单领料时才发现没料可领，现场只能绕开系统手工出库。
     */
    @Transactional
    public Map<String, Object> release(String orderNo, String operator) {
        ErpWorkOrder o = load(orderNo);
        if (!ErpWorkOrder.ST_DRAFT.equals(o.getStatus())) {
            throw new IllegalArgumentException("只有草稿能下达，当前状态：" + statusLabel(o.getStatus()));
        }
        List<ErpWorkOrderMaterial> mats = bomService.expand(o.getProductSkuId(), o.getPlanQty(),
                o.getMaterialWhId() != null ? o.getMaterialWhId() : defaultWhId());

        // 重复下达不常见，但重试会走到这里：先清掉旧快照再写，避免唯一键冲突
        womRepo.deleteByWorkOrderId(o.getId());
        womRepo.flush();
        for (ErpWorkOrderMaterial m : mats) {
            m.setWorkOrderId(o.getId());
        }
        womRepo.saveAll(mats);

        o.setStatus(ErpWorkOrder.ST_RELEASED);
        o.setUpdatedAt(System.currentTimeMillis());
        woRepo.save(o);

        BigDecimal totalNeed = mats.stream().map(ErpWorkOrderMaterial::getNeedQty)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        log.info("工单 {} 下达：{} 种材料，需求量合计 {}（操作人 {}）",
                orderNo, mats.size(), totalNeed, operator);
        return ok("工单已下达，按 BOM 展开 " + mats.size() + " 种材料", "materialCount", mats.size());
    }

    /**
     * 开工：RELEASED → RUNNING。
     * <p>
     * 两件事必须在这一步定下来：<b>谁来做</b>（设备）、<b>这台设备是不是已经在做别的单</b>。
     * 设备没指定但有产线时，取该产线的成品计数设备——保证「产量算给谁」和「工单算给谁」
     * 是同一台机器，否则产量统计和工单进度会各算各的。
     */
    @Transactional
    public Map<String, Object> start(String orderNo, String operator) {
        ErpWorkOrder o = load(orderNo);
        if (!ErpWorkOrder.ST_RELEASED.equals(o.getStatus())) {
            throw new IllegalArgumentException("只有已下达的工单能开工，当前状态：" + statusLabel(o.getStatus()));
        }
        String device = o.getDeviceCode();
        if (blank(device)) {
            device = resolveLineDevice(o.getLineCode());
            if (device == null) {
                throw new IllegalArgumentException("这张工单没有指定设备，也没有可用的产线成品计数设备，"
                        + "无法把产量算给谁。请给工单指定一台设备");
            }
            o.setDeviceCode(device);
        }
        // 同一台设备只能有一张在产工单：产出必须有唯一归属
        for (ErpWorkOrder other : woRepo.findByDeviceCodeAndStatus(device, ErpWorkOrder.ST_RUNNING)) {
            if (!other.getId().equals(o.getId())) {
                throw new IllegalArgumentException("设备 " + device + " 正在做工单 " + other.getOrderNo()
                        + "，请先完工或取消那张单，再开工这张");
            }
        }
        o.setStatus(ErpWorkOrder.ST_RUNNING);
        o.setActualStart(System.currentTimeMillis());
        o.setUpdatedAt(o.getActualStart());
        woRepo.save(o);
        invalidateRouting();

        log.info("工单 {} 开工：设备 {}（操作人 {}）", orderNo, device, operator);
        return ok("已开工，设备 " + device + " 的产量将记到这张工单", "deviceCode", device);
    }

    /** 完工：RUNNING → DONE。少产也允许（试产失败、客户砍单），但会提示差多少。 */
    @Transactional
    public Map<String, Object> finish(String orderNo, String operator) {
        ErpWorkOrder o = load(orderNo);
        if (!ErpWorkOrder.ST_RUNNING.equals(o.getStatus())) {
            throw new IllegalArgumentException("只有在产的工单能完工，当前状态：" + statusLabel(o.getStatus()));
        }
        o.setStatus(ErpWorkOrder.ST_DONE);
        o.setActualEnd(System.currentTimeMillis());
        o.setUpdatedAt(o.getActualEnd());
        woRepo.save(o);
        invalidateRouting();

        BigDecimal gap = o.getPlanQty().subtract(o.getDoneQty());
        String msg = gap.signum() > 0
                ? "工单已完工：计划 " + plain(o.getPlanQty()) + "，实际 " + plain(o.getDoneQty())
                  + "，少产 " + plain(gap)
                : "工单已完工：实际 " + plain(o.getDoneQty()) + " 件";
        log.info("工单 {} 完工：计划 {} 实际 {} 不良 {}（操作人 {}）",
                orderNo, plain(o.getPlanQty()), plain(o.getDoneQty()), plain(o.getDefectQty()), operator);
        return ok(msg, "doneQty", o.getDoneQty(), "defectQty", o.getDefectQty());
    }

    /**
     * 取消：DRAFT / RELEASED / RUNNING → CANCELLED。
     * <p>
     * <b>已领的料不会自动退</b>。退料是一条独立的业务事实（料从车间回仓库、可能已经拆包损耗），
     * 由人走 OTHER_IN 退料单。自动退会掩盖"这批料到底动没动过"，而且退错仓、退错量没人能发现。
     */
    @Transactional
    public Map<String, Object> cancel(String orderNo, String operator, String reason) {
        ErpWorkOrder o = load(orderNo);
        if (ErpWorkOrder.ST_DONE.equals(o.getStatus())) {
            throw new IllegalArgumentException("已完工的工单不能取消");
        }
        if (ErpWorkOrder.ST_CANCELLED.equals(o.getStatus())) {
            throw new IllegalArgumentException("这张工单已经是取消状态了");
        }
        String was = o.getStatus();
        o.setStatus(ErpWorkOrder.ST_CANCELLED);
        o.setUpdatedAt(System.currentTimeMillis());
        woRepo.save(o);
        invalidateRouting();

        BigDecimal issued = womRepo.findByWorkOrderIdOrderBySkuIdAsc(o.getId()).stream()
                .map(ErpWorkOrderMaterial::getIssuedQty).reduce(BigDecimal.ZERO, BigDecimal::add);
        String msg = "工单已取消（原状态：" + statusLabel(was) + "）";
        if (issued.signum() > 0) {
            msg += "。已领料 " + plain(issued) + " 件仍挂在仓库账上，"
                    + "需要退料请走「其他入库」建一张退料单——系统不替你自动退，退料是要人点过数的";
        }
        if (!blank(reason)) {
            o.setRemark((o.getRemark() == null ? "" : o.getRemark() + "；") + "取消原因：" + reason);
            woRepo.save(o);
        }
        log.info("工单 {} 取消（原 {}，操作人 {}）：{}", orderNo, was, operator, reason);
        return ok(msg);
    }

    /* ==================== 领料 ==================== */

    /**
     * 手工领料：按工单用料从仓库领出，生成一张 MATERIAL_OUT 出库单。
     * <p>
     * 库存不足会被 {@link ErpStockService#createManual} 里的行锁校验拦下——
     * 拦的依据是<b>目标仓的真实库存</b>，不是"这张工单还差多少"。
     * 两个概念在一处混用是很多 ERP 的通病：单子差 100、仓库只有 30，
     * 系统按单子放行就等于承认了 70 件不存在的货。
     */
    @Transactional
    public synchronized Map<String, Object> issue(String orderNo, List<IssueLine> lines, Long warehouseId,
                                                  String operator) {
        ErpWorkOrder o = load(orderNo);
        if (!ErpWorkOrder.ST_RELEASED.equals(o.getStatus())
                && !ErpWorkOrder.ST_RUNNING.equals(o.getStatus())) {
            throw new IllegalArgumentException("只有已下达或在产的工单能领料，当前状态："
                    + statusLabel(o.getStatus()));
        }
        if (o.isAutoBackflush() && ErpWorkOrder.ST_RUNNING.equals(o.getStatus())) {
            throw new IllegalArgumentException("这张工单开着「产出自动反冲」，产出时已按 BOM 扣过料。"
                    + "再手工领料会重复扣。如果确实要手工领料，请先在工单上关掉自动反冲");
        }
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("请选择要领的材料和数量");
        }
        List<ErpWorkOrderMaterial> mats = womRepo.findByWorkOrderIdOrderBySkuIdAsc(o.getId());
        Map<Long, ErpWorkOrderMaterial> bySku = new HashMap<>();
        for (ErpWorkOrderMaterial m : mats) {
            bySku.put(m.getSkuId(), m);
        }

        Long wh = warehouseId;
        if (wh == null) {
            // 仓库优先级：本次指定 > 工单的领料仓 > 用料行上记的 > 默认仓
            wh = o.getMaterialWhId() != null ? o.getMaterialWhId()
                    : mats.stream().map(ErpWorkOrderMaterial::getWarehouseId)
                        .filter(java.util.Objects::nonNull).findFirst()
                        .orElseGet(this::defaultWhId);
        }
        if (wh == null) {
            throw new IllegalArgumentException("请选择领料仓库（系统里还没有默认仓）");
        }
        final Long useWh = wh;

        List<ErpStockService.LineReq> reqs = new ArrayList<>();
        for (IssueLine l : lines) {
            if (l.skuId() == null || l.qty() == null || l.qty().signum() <= 0) {
                continue;
            }
            ErpWorkOrderMaterial m = bySku.get(l.skuId());
            if (m == null) {
                throw new IllegalArgumentException("商品 #" + l.skuId() + " 不在本工单的用料清单里");
            }
            reqs.add(new ErpStockService.LineReq(l.skuId(), l.qty(), null));
            m.setWarehouseId(useWh);
        }
        if (reqs.isEmpty()) {
            throw new IllegalArgumentException("领料数量必须大于 0");
        }

        ErpStockService.Created c = stockService.createManual(
                ErpStockBill.TYPE_MATERIAL_OUT, null, useWh, null,
                "工单 " + orderNo + " 领料", operator, reqs, orderNo, null);

        // 回写已领量：在内存里累加后整批保存。
        // 不能「先 UPDATE 累加、再 saveAll 内存对象」——saveAll 会把内存里的旧值写回去，
        // 把刚做的累加覆盖掉（这个坑在评审时抓过一次，别再改回去）。
        for (IssueLine l : lines) {
            if (l.skuId() == null || l.qty() == null || l.qty().signum() <= 0) {
                continue;
            }
            ErpWorkOrderMaterial m = bySku.get(l.skuId());
            if (m != null) {
                m.setIssuedQty(nz(m.getIssuedQty()).add(l.qty()));
            }
        }
        womRepo.saveAll(mats);
        o.setUpdatedAt(System.currentTimeMillis());
        woRepo.save(o);

        log.info("工单 {} 领料：单号 {}，{} 种材料（操作人 {}）", orderNo, c.billNo(), reqs.size(), operator);
        return ok("已领料，出库单 " + c.billNo(), "billNo", c.billNo());
    }

    /** 一键按缺口领全：把每种材料「还差多少」一次性领掉，仓库不够会整体失败 */
    @Transactional
    public Map<String, Object> autoIssue(String orderNo, Long warehouseId, String operator) {
        ErpWorkOrder o = load(orderNo);
        List<ErpWorkOrderMaterial> mats = womRepo.findByWorkOrderIdOrderBySkuIdAsc(o.getId());
        List<IssueLine> lines = new ArrayList<>();
        for (ErpWorkOrderMaterial m : mats) {
            BigDecimal gap = m.getNeedQty().subtract(m.getIssuedQty());
            if (gap.signum() > 0) {
                lines.add(new IssueLine(m.getSkuId(), gap));
            }
        }
        if (lines.isEmpty()) {
            return ok("这张工单的料已经领齐了，不用再领");
        }
        return issue(orderNo, lines, warehouseId, operator);
    }

    /* ==================== 产出（设备联动入口） ==================== */

    /**
     * 记一次工单产出：生成生产入库单 + 产出流水 + 回写完成量（+ 反冲领料）。
     * <p>
     * <b>幂等由 {@code erp_work_order_output.bill_no} 唯一键保证</b>：定时器重跑、
     * 落库重试都会撞到唯一键，这里先 {@code existsByBillNo} 快速返回。
     * 不靠"我记得我写过"这种应用层判断——那个在进程重启后就失效了。
     *
     * @param qty       良品数量（进 {@code PROD_IN} 入库、累加 doneQty）
     * @param defectQty 不良品数量（只记数不进良品库存，累加到 defectQty；
     *                  <b>但反冲领料要按 qty + defectQty 扣</b>，见方法内注释）
     * @return 生成的入库单号；工单已不是 RUNNING 时返回 null（调用方应回退到无工单逻辑）
     */
    @Transactional
    public synchronized String produce(String orderNo, String deviceCode, Long whId,
                                       BigDecimal qty, BigDecimal defectQty, boolean backfill) {
        ErpWorkOrder o = woRepo.findByOrderNo(orderNo).orElse(null);
        if (o == null) {
            return null;
        }
        // 完工/取消之后仍有产出在缓冲里（设备滞后或补记）：不再入账，交给调用方按无工单处理，
        // 否则会把产量记到一张已经结束的工单上，进度超过计划还关不掉
        if (!ErpWorkOrder.ST_RUNNING.equals(o.getStatus())) {
            return null;
        }
        Long useWh = whId != null ? whId : o.getWarehouseId();
        if (useWh == null) {
            useWh = whRepo.findByDefaultWarehouseTrue().map(ErpWarehouse::getId).orElse(null);
        }
        if (useWh == null) {
            throw new IllegalStateException("工单 " + orderNo + " 没有成品仓，也没有默认仓");
        }

        String billNo = stockService.createProdInbound(deviceCode, o.getProductSkuId(), useWh,
                qty, backfill, orderNo).billNo();

        if (wooRepo.existsByBillNo(billNo)) {
            // 同一张入库单已经记过工单产出：幂等命中，不重复累加
            log.debug("工单 {} 产出幂等命中，跳过：{}", orderNo, billNo);
            return billNo;
        }
        ErpWorkOrderOutput rec = new ErpWorkOrderOutput();
        rec.setWorkOrderId(o.getId());
        rec.setWorkOrderNo(orderNo);
        rec.setBillNo(billNo);
        rec.setDeviceCode(deviceCode);
        rec.setQty(qty);
        rec.setDefectQty(defectQty == null ? BigDecimal.ZERO : defectQty);
        rec.setCreatedAt(System.currentTimeMillis());
        wooRepo.save(rec);

        o.setDoneQty(o.getDoneQty().add(qty));
        if (defectQty != null && defectQty.signum() > 0) {
            o.setDefectQty(o.getDefectQty().add(defectQty));
        }
        o.setUpdatedAt(System.currentTimeMillis());
        woRepo.save(o);

        // 自动反冲：<b>按总产出扣料，不是按良品</b>。
        // 不良件的料是真实消耗掉了（料已经变成那件废品），按良品扣等于这部分料永远不扣，
        // 材料成本永久少计——账面一切正常，只是每个产品的成本都偏低，客户拿它去报价就赔了，
        // 而且不会有任何报错。
        // 返工单不展开 BOM（用料清单为空），所以这里不会重复扣。
        // ↓ 这一行不要"顺手修正"成 backflush(o, qty)。
        if (o.isAutoBackflush()) {
            BigDecimal totalProduced = qty.add(defectQty == null ? BigDecimal.ZERO : defectQty);
            backflush(o, totalProduced);
        }
        return billNo;
    }

    /**
     * 反冲领料：按本次产出的实际数量扣料。
     * <p>
     * 与「下达时展开的 needQty」无关——那是按<b>计划量</b>算的，反冲要按<b>实际产出</b>算，
     * 否则计划 1000 只做了 200 件，料却按 1000 件扣走了。
     * <p>
     * <b>三件事必须做对</b>（都是实测踩出来的）：
     * <ol>
     *   <li><b>从领料仓扣，不是成品仓</b>：成品入成品仓、原料在原料仓，
     *       拿成品仓的库存去找原料永远是 0，反冲必然失败。</li>
     *   <li><b>独立事务</b>：反冲失败不能拖垮产出。见 {@link ErpBackflushService} 类注释。</li>
     *   <li><b>失败不阻塞产出</b>：产出已经发生了，挡也挡不住。缺料体现在「已领 &lt; 需求」上，
     *       由缺料看板暴露，由人决定补料还是改 BOM。</li>
     * </ol>
     */
    private void backflush(ErpWorkOrder o, BigDecimal producedQty) {
        List<ErpBom> boms = bomRepo.findByProductSkuIdOrderByMaterialSkuIdAsc(o.getProductSkuId());
        if (boms.isEmpty()) {
            return;
        }
        Map<Long, BigDecimal> want = new LinkedHashMap<>();
        for (ErpBom b : boms) {
            BigDecimal factor = BigDecimal.ONE.add(nz(b.getLossRate()).divide(HUNDRED, 6, RoundingMode.HALF_UP));
            BigDecimal need = producedQty.multiply(b.getQty()).multiply(factor)
                    .setScale(4, RoundingMode.HALF_UP);
            if (need.signum() <= 0) {
                continue;
            }
            want.merge(b.getMaterialSkuId(), need, BigDecimal::add);
        }
        if (want.isEmpty()) {
            return;
        }
        Long whId = o.getMaterialWhId() != null ? o.getMaterialWhId() : defaultWhId();
        if (whId == null) {
            log.warn("工单 {} 反冲领料未执行：既没有领料仓也没有默认仓。"
                    + "产出已正常入账，料需要人工补领", o.getOrderNo());
            return;
        }
        try {
            backflushService.run(o.getOrderNo(), o.getId(), whId, want);
        } catch (Exception ex) {
            // 独立事务，抛出的是它自己的回滚；产出不受影响
            log.warn("工单 {} 反冲领料失败（产出已入账，缺料请补料或手工领料）：{}",
                    o.getOrderNo(), ex.getMessage());
        }
    }

    /* ==================== 内部工具 ==================== */

    private ErpWorkOrder load(String orderNo) {
        return woRepo.findByOrderNo(orderNo)
                .orElseThrow(() -> new IllegalArgumentException("工单不存在：" + orderNo));
    }

    private String nextOrderNo() {
        String base = "WO" + LocalDate.now(ZONE).format(DAY) + "-";
        String max = woRepo.maxNoByPrefix(base);
        int n = 1;
        if (max != null && max.length() > base.length()) {
            try {
                n = Integer.parseInt(max.substring(base.length())) + 1;
            } catch (NumberFormatException ignore) {
                // 单号被手工改过，从 1 重新找空号
            }
        }
        String no;
        do {
            no = base + String.format("%04d", n);
            n++;
        } while (woRepo.findByOrderNo(no).isPresent());
        return no;
    }

    /** 产线 → 成品计数设备（与产量统计同一套规则：显式指定优先，否则工位序号最大的一台） */
    private String resolveLineDevice(String lineCode) {
        if (blank(lineCode)) {
            return null;
        }
        ErpProdLine line = lineRepo.findByCode(lineCode).orElse(null);
        if (line == null) {
            return null;
        }
        if (!blank(line.getFinishedDeviceCode())) {
            return line.getFinishedDeviceCode();
        }
        List<DeviceEntity> members = new ArrayList<>();
        for (DeviceEntity d : deviceService.all()) {
            if (line.getCode().equals(deviceLineCode(d))) {
                members.add(d);
            }
        }
        return members.stream()
                .max(Comparator.comparingInt(DeviceEntity::getLineSeq))
                .map(DeviceEntity::getCode).orElse(null);
    }

    private String deviceLineCode(DeviceEntity d) {
        if (d.getProdLineId() == null) {
            return null;
        }
        return lineRepo.findById(d.getProdLineId()).map(ErpProdLine::getCode).orElse(null);
    }

    private Map<String, Object> row(ErpWorkOrder o, boolean withDetail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", o.getId());
        m.put("orderNo", o.getOrderNo());
        m.put("productSkuId", o.getProductSkuId());
        m.put("productCode", o.getProductCode());
        m.put("productName", o.getProductName());
        m.put("productUnit", o.getProductUnit());
        m.put("planQty", o.getPlanQty());
        m.put("doneQty", o.getDoneQty());
        m.put("defectQty", o.getDefectQty());
        m.put("status", o.getStatus());
        m.put("statusLabel", statusLabel(o.getStatus()));
        m.put("lineCode", o.getLineCode());
        m.put("deviceCode", o.getDeviceCode());
        m.put("warehouseId", o.getWarehouseId());
        m.put("materialWhId", o.getMaterialWhId());
        m.put("srcOrderNo", o.getSrcOrderNo());
        m.put("autoBackflush", o.isAutoBackflush());
        m.put("planStart", o.getPlanStart());
        m.put("planEnd", o.getPlanEnd());
        m.put("actualStart", o.getActualStart());
        m.put("actualEnd", o.getActualEnd());
        m.put("remark", o.getRemark());
        m.put("createdBy", o.getCreatedBy());
        m.put("createdAt", o.getCreatedAt());
        m.put("updatedAt", o.getUpdatedAt());
        // 进度：计划为 0 时给 null 而不是 0%，"没有计划"和"一件没做"是两件事
        BigDecimal p = o.getPlanQty().signum() <= 0 ? null
                : o.getDoneQty().multiply(HUNDRED).divide(o.getPlanQty(), 1, RoundingMode.HALF_UP);
        m.put("progress", p);
        BigDecimal gap = o.getPlanQty().subtract(o.getDoneQty());
        m.put("gapQty", gap.signum() > 0 ? gap : BigDecimal.ZERO);
        // 交期：有计划结束时间且没完工，才算"逾期/临期"
        boolean open = !ErpWorkOrder.ST_DONE.equals(o.getStatus())
                && !ErpWorkOrder.ST_CANCELLED.equals(o.getStatus());
        Long due = o.getPlanEnd();
        boolean overdue = open && due != null && System.currentTimeMillis() > due;
        m.put("overdue", overdue);
        m.put("dueInDays", open && due != null
                ? (due - System.currentTimeMillis()) / 86_400_000L : null);
        return m;
    }

    public static String statusLabel(String s) {
        return switch (s == null ? "" : s) {
            case ErpWorkOrder.ST_DRAFT -> "草稿";
            case ErpWorkOrder.ST_RELEASED -> "已下达";
            case ErpWorkOrder.ST_RUNNING -> "生产中";
            case ErpWorkOrder.ST_DONE -> "已完工";
            case ErpWorkOrder.ST_CANCELLED -> "已取消";
            default -> s;
        };
    }

    private BigDecimal stockOnHand(Long skuId, Long whId) {
        try {
            return billRepo.stockOf(skuId, whId);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 默认仓：显式默认优先，没有就取第一个启用的仓。
     * <p>
     * 不返回 null 让调用方各自兜底，是因为「工单下达时总得记一个计划领料仓」——
     * 记不上，缺料看板就显示不出库存，车间看到"现有 --"只能跑去仓库问。
     */
    private Long defaultWhId() {
        try {
            Long id = whRepo.findByDefaultWarehouseTrue().map(ErpWarehouse::getId).orElse(null);
            if (id != null) {
                return id;
            }
            for (ErpWarehouse w : whRepo.findAll()) {
                if (w.isEnabled()) {
                    return w.getId();
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static Map<String, Object> ok(String message, Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("message", message);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String str(Object o) {
        if (o == null) {
            return null;
        }
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }

    private static Long longOf(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number n) {
            return n.longValue();
        }
        String s = String.valueOf(o).trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static BigDecimal decOf(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof BigDecimal b) {
            return b;
        }
        if (o instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        String s = String.valueOf(o).trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean bool(Object o, boolean def) {
        if (o == null) {
            return def;
        }
        if (o instanceof Boolean b) {
            return b;
        }
        String s = String.valueOf(o).trim();
        return "true".equalsIgnoreCase(s) || "1".equals(s) || "yes".equalsIgnoreCase(s);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String plain(BigDecimal v) {
        return v == null ? "0" : v.stripTrailingZeros().toPlainString();
    }
}
