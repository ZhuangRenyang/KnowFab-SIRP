package com.factory.hub.erp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 采购管理（ROADMAP DH-52）：采购订单 → 从订单入库 → 退货给供应商。
 * <p>
 * <b>订单不动库存</b>：它只是跟供应商的约定。库存只由 {@code PO_IN} / {@code PO_RETURN}
 * 单据变动（复用 DH-43b 的单头+明细行），单据上带 {@code src_order_no} 指回订单。
 * <p>
 * <b>进度不手工维护</b>：{@link #recalc} 永远按"该订单号下未作废的 PO_IN − PO_RETURN"重算
 * 每一行的净入库量和订单状态。入库、退货、作废单据三条路径都调它，所以数据能自愈——
 * 哪怕有人在库存页把一张入库单作废了，订单进度也会立即跟着退回去。
 * <p>
 * <b>数量口径</b>：未入库 = 订货量 − 净入库；可退 = 净入库。退掉的货会重新变成"未入库"，
 * 符合现场"送错货退回去再补发"的习惯。
 */
@Service
public class ErpPurchaseService {

    private static final Logger log = LoggerFactory.getLogger(ErpPurchaseService.class);
    private static final ZoneId ZONE = ZoneId.systemDefault();
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final BigDecimal EPS = new BigDecimal("0.0001");

    public static final Map<String, String> STATUS_LABELS;
    /** 下拉顺序：草稿 → 已确认 → 部分入库 → 已完成 → 已取消 */
    public static final List<String> STATUS_ORDER = List.of(
            ErpPurchaseOrder.STATUS_DRAFT, ErpPurchaseOrder.STATUS_CONFIRMED,
            ErpPurchaseOrder.STATUS_PARTIAL, ErpPurchaseOrder.STATUS_DONE,
            ErpPurchaseOrder.STATUS_CANCELLED);

    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(ErpPurchaseOrder.STATUS_DRAFT, "草稿");
        m.put(ErpPurchaseOrder.STATUS_CONFIRMED, "已确认");
        m.put(ErpPurchaseOrder.STATUS_PARTIAL, "部分入库");
        m.put(ErpPurchaseOrder.STATUS_DONE, "已完成");
        m.put(ErpPurchaseOrder.STATUS_CANCELLED, "已取消");
        STATUS_LABELS = java.util.Collections.unmodifiableMap(m);
    }

    /** 一行订货 */
    public record ItemReq(Long skuId, BigDecimal qty, BigDecimal unitPrice, String remark) {}

    private final ErpPurchaseOrderRepository orderRepo;
    private final ErpPurchaseOrderItemRepository itemRepo;
    private final ErpStockService stockService;
    private final ErpStockBillRepository billRepo;
    private final ErpStockBillHdrRepository hdrRepo;
    private final ErpSkuRepository skuRepo;
    private final ErpPartnerRepository partnerRepo;

    public ErpPurchaseService(ErpPurchaseOrderRepository orderRepo,
                              ErpPurchaseOrderItemRepository itemRepo,
                              ErpStockService stockService,
                              ErpStockBillRepository billRepo,
                              ErpStockBillHdrRepository hdrRepo,
                              ErpSkuRepository skuRepo,
                              ErpPartnerRepository partnerRepo) {
        this.orderRepo = orderRepo;
        this.itemRepo = itemRepo;
        this.stockService = stockService;
        this.billRepo = billRepo;
        this.hdrRepo = hdrRepo;
        this.skuRepo = skuRepo;
        this.partnerRepo = partnerRepo;
    }

    /* ==================== 建单 / 改单 ==================== */

    @Transactional
    public synchronized Map<String, Object> create(Map<String, Object> body, String operator) {
        Long supplierId = asLong(body.get("supplierId"));
        ErpPartner supplier = supplierId == null ? null : partnerRepo.findById(supplierId)
                .orElseThrow(() -> new IllegalArgumentException("供应商不存在"));
        if (supplier == null) {
            throw new IllegalArgumentException("请选择供应商");
        }
        List<ItemReq> items = normalizeItems(body.get("lines"));

        ErpPurchaseOrder o = new ErpPurchaseOrder();
        o.setOrderNo(nextOrderNo());
        o.setSupplierId(supplier.getId());
        o.setSupplierName(supplier.getName());
        o.setStatus(ErpPurchaseOrder.STATUS_DRAFT);
        o.setOrderDate(parseDay(body.get("orderDate"), LocalDate.now(ZONE)));
        o.setExpectDate(parseDay(body.get("expectDate"), null));
        o.setRemark(str(body.get("remark")));
        o.setOperator(operator);
        o.setCreatedAt(System.currentTimeMillis());
        o.setUpdatedAt(o.getCreatedAt());
        applyTotals(o, items);
        o = orderRepo.saveAndFlush(o);

        saveItems(o.getId(), items);
        log.info("新建采购订单 {}，{} 行，合计 {} 元（{}）",
                o.getOrderNo(), items.size(), o.getTotalAmount(), operator);
        Map<String, Object> out = ok("采购订单已创建：" + o.getOrderNo());
        out.put("orderNo", o.getOrderNo());
        return out;
    }

    /** 改单：只有草稿能改；整单行替换（草稿不会有入库，直接重写最干净） */
    @Transactional
    public void update(String orderNo, Map<String, Object> body, String operator) {
        ErpPurchaseOrder o = load(orderNo);
        if (!o.isEditable()) {
            throw new IllegalArgumentException("只有草稿状态的订单可以修改");
        }
        Long supplierId = asLong(body.get("supplierId"));
        if (supplierId != null) {
            ErpPartner p = partnerRepo.findById(supplierId)
                    .orElseThrow(() -> new IllegalArgumentException("供应商不存在"));
            o.setSupplierId(p.getId());
            o.setSupplierName(p.getName());
        }
        List<ItemReq> items = normalizeItems(body.get("lines"));
        o.setOrderDate(parseDay(body.get("orderDate"), LocalDate.now(ZONE)));
        o.setExpectDate(parseDay(body.get("expectDate"), null));
        o.setRemark(str(body.get("remark")));
        o.setUpdatedAt(System.currentTimeMillis());
        applyTotals(o, items);
        orderRepo.save(o);
        itemRepo.deleteByOrderId(o.getId());
        saveItems(o.getId(), items);
        log.info("修改采购订单 {}（{}）", orderNo, operator);
    }

    @Transactional
    public void confirm(String orderNo, String operator) {
        ErpPurchaseOrder o = load(orderNo);
        if (!ErpPurchaseOrder.STATUS_DRAFT.equals(o.getStatus())) {
            throw new IllegalArgumentException("只有草稿状态的订单可以确认");
        }
        if (itemRepo.findByOrderIdOrderByLineNoAsc(o.getId()).isEmpty()) {
            throw new IllegalArgumentException("订单没有明细，不能确认");
        }
        o.setStatus(ErpPurchaseOrder.STATUS_CONFIRMED);
        o.setUpdatedAt(System.currentTimeMillis());
        orderRepo.save(o);
        log.info("确认采购订单 {}（{}）", orderNo, operator);
    }

    /** 取消：只有还没入过货的订单能取消（入过货请先退货或作废入库单） */
    @Transactional
    public void cancel(String orderNo, String operator) {
        ErpPurchaseOrder o = load(orderNo);
        if (ErpPurchaseOrder.STATUS_DONE.equals(o.getStatus())) {
            throw new IllegalArgumentException("订单已完成，不能取消");
        }
        if (ErpPurchaseOrder.STATUS_CANCELLED.equals(o.getStatus())) {
            throw new IllegalArgumentException("订单已取消，无需重复操作");
        }
        BigDecimal received = nz(o.getReceivedQty());
        if (received.compareTo(EPS) > 0) {
            throw new IllegalArgumentException("订单已入库 " + plain(received)
                    + "，不能直接取消；请先退货或作废对应的入库单");
        }
        o.setStatus(ErpPurchaseOrder.STATUS_CANCELLED);
        o.setUpdatedAt(System.currentTimeMillis());
        orderRepo.save(o);
        log.info("取消采购订单 {}（{}）", orderNo, operator);
    }

    /* ==================== 入库 / 退货 ==================== */

    /**
     * 从订单入库：生成 PO_IN 单据（可多行、可多次、可少入），然后重算订单进度。
     * 行里 itemId / skuId 二选一（前端传 itemId，脚本可用 skuId）。
     */
    @Transactional
    public Map<String, Object> receive(String orderNo, Long whId, Object rawLines,
                                       String remark, String operator) {
        ErpPurchaseOrder o = load(orderNo);
        if (!o.isReceivable()) {
            throw new IllegalArgumentException("订单当前状态是「" + STATUS_LABELS.get(o.getStatus())
                    + "」，不能入库（只有已确认/部分入库的订单可以收货）");
        }
        List<ErpPurchaseOrderItem> items = itemRepo.findByOrderIdOrderByLineNoAsc(o.getId());
        Map<Long, ErpPurchaseOrderItem> bySku = new HashMap<>();
        Map<Long, ErpPurchaseOrderItem> byId = new HashMap<>();
        for (ErpPurchaseOrderItem it : items) {
            bySku.put(it.getSkuId(), it);
            byId.put(it.getId(), it);
        }

        List<ErpStockService.LineReq> reqs = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (Map<?, ?> m : maps(rawLines)) {
            ErpPurchaseOrderItem it = pick(m, byId, bySku);
            BigDecimal qty = nz(dec(m.get("qty")));
            if (qty.signum() <= 0) {
                continue;
            }
            if (!seen.add(it.getSkuId())) {
                throw new IllegalArgumentException("同一张单里商品重复：" + skuName(it.getSkuId()));
            }
            BigDecimal remain = nz(it.getQty()).subtract(nz(it.getReceivedQty()));
            if (qty.subtract(remain).compareTo(EPS) > 0) {
                throw new IllegalArgumentException("「" + skuName(it.getSkuId()) + "」还能入库 "
                        + plain(remain) + "，本次填了 " + plain(qty));
            }
            BigDecimal price = dec(m.get("unitPrice"));
            reqs.add(new ErpStockService.LineReq(it.getSkuId(), qty,
                    price == null ? it.getUnitPrice() : price));
        }
        if (reqs.isEmpty()) {
            throw new IllegalArgumentException("没有要入库的数量");
        }

        String note = "采购入库（订单 " + orderNo + "）"
                + (remark == null || remark.isBlank() ? "" : "；" + remark);
        ErpStockService.Created c = stockService.createManual(
                ErpStockBill.TYPE_PO_IN, o.getSupplierId(), whId, null, note, operator, reqs, orderNo);
        recalc(orderNo);

        Map<String, Object> out = ok("已入库，单据号 " + c.billNo());
        out.put("billNo", c.billNo());
        out.put("lineCount", c.lineCount());
        return out;
    }

    /** 退货给供应商：生成 PO_RETURN（负数出库）；数量不能超过净入库 */
    @Transactional
    public Map<String, Object> returnToSupplier(String orderNo, Long whId, Object rawLines,
                                                String remark, String operator) {
        ErpPurchaseOrder o = load(orderNo);
        if (ErpPurchaseOrder.STATUS_CANCELLED.equals(o.getStatus())
                || ErpPurchaseOrder.STATUS_DRAFT.equals(o.getStatus())) {
            throw new IllegalArgumentException("草稿/已取消的订单没有货可退");
        }
        List<ErpPurchaseOrderItem> items = itemRepo.findByOrderIdOrderByLineNoAsc(o.getId());
        Map<Long, ErpPurchaseOrderItem> bySku = new HashMap<>();
        Map<Long, ErpPurchaseOrderItem> byId = new HashMap<>();
        for (ErpPurchaseOrderItem it : items) {
            bySku.put(it.getSkuId(), it);
            byId.put(it.getId(), it);
        }

        List<ErpStockService.LineReq> reqs = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (Map<?, ?> m : maps(rawLines)) {
            ErpPurchaseOrderItem it = pick(m, byId, bySku);
            BigDecimal qty = nz(dec(m.get("qty")));
            if (qty.signum() <= 0) {
                continue;
            }
            if (!seen.add(it.getSkuId())) {
                throw new IllegalArgumentException("同一张单里商品重复：" + skuName(it.getSkuId()));
            }
            BigDecimal net = nz(it.getReceivedQty());
            if (qty.subtract(net).compareTo(EPS) > 0) {
                throw new IllegalArgumentException("「" + skuName(it.getSkuId()) + "」最多可退 "
                        + plain(net) + "，本次填了 " + plain(qty));
            }
            reqs.add(new ErpStockService.LineReq(it.getSkuId(), qty, it.getUnitPrice()));
        }
        if (reqs.isEmpty()) {
            throw new IllegalArgumentException("没有要退货的数量");
        }

        String note = "采购退货（订单 " + orderNo + "）"
                + (remark == null || remark.isBlank() ? "" : "；" + remark);
        ErpStockService.Created c = stockService.createManual(
                ErpStockBill.TYPE_PO_RETURN, o.getSupplierId(), whId, null, note, operator, reqs, orderNo);
        recalc(orderNo);

        Map<String, Object> out = ok("已退货，单据号 " + c.billNo());
        out.put("billNo", c.billNo());
        out.put("lineCount", c.lineCount());
        return out;
    }

    /** 库存页把入库/退货单作废后，订单进度要跟着退回 */
    @EventListener
    public void onBillVoided(StockBillVoidedEvent e) {
        // 销售单据也走同一个事件，这里只认采购的两种，别去查销售订单
        if (e.srcOrderNo() == null
                || !(ErpStockBill.TYPE_PO_IN.equals(e.billType())
                     || ErpStockBill.TYPE_PO_RETURN.equals(e.billType()))) {
            return;
        }
        try {
            recalc(e.srcOrderNo());
            log.info("单据 {} 作废，已重算采购订单 {} 进度", e.billNo(), e.srcOrderNo());
        } catch (Exception ex) {
            // 不抛：作废本身已经成功，别让订单重算把库存操作一起回滚
            log.warn("单据 {} 作废后重算订单 {} 失败：{}", e.billNo(), e.srcOrderNo(), ex.getMessage());
        }
    }

    /**
     * 按单据流水重算订单进度与状态（唯一事实来源）。
     * 净入库 = Σ PO_IN.qty + Σ PO_RETURN.qty（后者是负数），作废行已被聚合排除。
     */
    @Transactional
    public void recalc(String orderNo) {
        ErpPurchaseOrder o = orderRepo.findByOrderNo(orderNo).orElse(null);
        if (o == null) {
            return;
        }
        Map<Long, BigDecimal> net = new HashMap<>();
        for (Object[] r : billRepo.sumByOrder(orderNo)) {
            Long skuId = ((Number) r[0]).longValue();
            BigDecimal qty = r[2] == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(r[2]));
            net.merge(skuId, qty, BigDecimal::add);
        }
        List<ErpPurchaseOrderItem> items = itemRepo.findByOrderIdOrderByLineNoAsc(o.getId());
        BigDecimal totalReceived = BigDecimal.ZERO;
        for (ErpPurchaseOrderItem it : items) {
            BigDecimal v = net.getOrDefault(it.getSkuId(), BigDecimal.ZERO);
            if (v.signum() < 0) {
                v = BigDecimal.ZERO;             // 历史脏数据兜底，不显示负数入库
            }
            it.setReceivedQty(scale(v));
            totalReceived = totalReceived.add(v);
        }
        itemRepo.saveAll(items);
        o.setReceivedQty(scale(totalReceived));
        o.setStatus(nextStatus(o, totalReceived));
        o.setUpdatedAt(System.currentTimeMillis());
        orderRepo.save(o);
    }

    private String nextStatus(ErpPurchaseOrder o, BigDecimal received) {
        if (ErpPurchaseOrder.STATUS_CANCELLED.equals(o.getStatus())
                || ErpPurchaseOrder.STATUS_DRAFT.equals(o.getStatus())) {
            return o.getStatus();
        }
        BigDecimal total = nz(o.getTotalQty());
        if (received.compareTo(EPS) <= 0) {
            return ErpPurchaseOrder.STATUS_CONFIRMED;
        }
        return received.compareTo(total) >= 0
                ? ErpPurchaseOrder.STATUS_DONE
                : ErpPurchaseOrder.STATUS_PARTIAL;
    }

    /* ==================== 查询 ==================== */

    public Map<String, Object> list(String status, Long supplierId, Long fromTs, Long toTs,
                                    String kw, int page, int size) {
        int p = Math.max(1, page);
        int s = Math.max(1, Math.min(200, size));
        String k = kw == null || kw.trim().isEmpty() ? "" : "%" + kw.trim().toLowerCase() + "%";
        var pageable = org.springframework.data.domain.PageRequest.of(p - 1, s,
                org.springframework.data.domain.Sort.by(
                        org.springframework.data.domain.Sort.Direction.DESC, "orderNo"));
        var res = orderRepo.search(status == null ? "" : status,
                supplierId == null ? 0L : supplierId,
                fromTs == null ? 0L : fromTs,
                toTs == null ? 0L : toTs, k, pageable);

        List<Long> ids = res.getContent().stream().map(ErpPurchaseOrder::getId).toList();
        Map<Long, BigDecimal> receivedMap = new HashMap<>();
        if (!ids.isEmpty()) {
            for (Object[] r : itemRepo.sumReceivedByOrderIds(ids)) {
                receivedMap.put(((Number) r[0]).longValue(),
                        r[1] == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(r[1])));
            }
        }
        long todayStart = LocalDate.now(ZONE).atStartOfDay(ZONE).toInstant().toEpochMilli();

        List<Map<String, Object>> rows = new ArrayList<>();
        for (ErpPurchaseOrder o : res.getContent()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("orderNo", o.getOrderNo());
            m.put("supplierId", o.getSupplierId());
            m.put("supplierName", o.getSupplierName());
            m.put("status", o.getStatus());
            m.put("statusLabel", STATUS_LABELS.getOrDefault(o.getStatus(), o.getStatus()));
            m.put("orderDate", o.getOrderDate());
            m.put("expectDate", o.getExpectDate());
            m.put("totalQty", o.getTotalQty());
            m.put("totalAmount", o.getTotalAmount());
            m.put("receivedQty", receivedMap.getOrDefault(o.getId(), nz(o.getReceivedQty())));
            m.put("progress", progress(nz(receivedMap.getOrDefault(o.getId(), o.getReceivedQty())),
                    nz(o.getTotalQty())));
            m.put("overdue", o.getExpectDate() != null && o.getExpectDate() < todayStart
                    && o.isReceivable());
            m.put("operator", o.getOperator());
            m.put("remark", o.getRemark());
            rows.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("total", res.getTotalElements());
        out.put("page", p);
        out.put("size", s);
        out.put("rows", rows);
        return out;
    }

    public Map<String, Object> detail(String orderNo) {
        ErpPurchaseOrder o = orderRepo.findByOrderNo(orderNo)
                .orElseThrow(() -> new IllegalArgumentException("采购订单不存在：" + orderNo));
        Map<Long, ErpSku> skus = new HashMap<>();
        for (ErpSku s : skuRepo.findAll()) {
            skus.put(s.getId(), s);
        }
        List<Map<String, Object>> lines = new ArrayList<>();
        for (ErpPurchaseOrderItem it : itemRepo.findByOrderIdOrderByLineNoAsc(o.getId())) {
            ErpSku s = skus.get(it.getSkuId());
            BigDecimal qty = nz(it.getQty());
            BigDecimal got = nz(it.getReceivedQty());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("itemId", it.getId());
            m.put("lineNo", it.getLineNo());
            m.put("skuId", it.getSkuId());
            m.put("skuCode", s == null ? ("商品#" + it.getSkuId()) : s.getCode());
            m.put("skuName", s == null ? "" : s.getName());
            m.put("spec", s == null ? "" : s.getSpec());
            m.put("unit", s == null ? "" : s.getUnit());
            m.put("qty", scale(qty));
            m.put("receivedQty", scale(got));
            m.put("remainQty", scale(qty.subtract(got).max(BigDecimal.ZERO)));
            m.put("unitPrice", it.getUnitPrice() == null ? null : scale(it.getUnitPrice()));
            m.put("amount", it.getAmount() == null ? null : scale(it.getAmount()));
            m.put("remark", it.getRemark());
            lines.add(m);
        }

        List<Map<String, Object>> bills = new ArrayList<>();
        for (ErpStockBillHdr h : hdrRepo.findBySrcOrderNoOrderByCreatedAtDesc(orderNo)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("billNo", h.getBillNo());
            m.put("type", h.getBillType());
            m.put("typeLabel", ErpStockService.TYPE_LABELS.getOrDefault(h.getBillType(), h.getBillType()));
            m.put("qty", h.getTotalQty());
            m.put("amount", h.getTotalAmount());
            m.put("createdAt", h.getCreatedAt());
            m.put("operator", h.getOperator());
            m.put("voided", h.isVoided());
            bills.add(m);
        }

        Map<String, Object> head = new LinkedHashMap<>();
        head.put("orderNo", o.getOrderNo());
        head.put("supplierId", o.getSupplierId());
        head.put("supplierName", o.getSupplierName());
        head.put("status", o.getStatus());
        head.put("statusLabel", STATUS_LABELS.getOrDefault(o.getStatus(), o.getStatus()));
        head.put("orderDate", o.getOrderDate());
        head.put("expectDate", o.getExpectDate());
        head.put("totalQty", o.getTotalQty());
        head.put("totalAmount", o.getTotalAmount());
        head.put("receivedQty", o.getReceivedQty());
        head.put("progress", progress(nz(o.getReceivedQty()), nz(o.getTotalQty())));
        head.put("remark", o.getRemark());
        head.put("operator", o.getOperator());
        head.put("createdAt", o.getCreatedAt());
        head.put("updatedAt", o.getUpdatedAt());
        head.put("editable", o.isEditable());
        head.put("receivable", o.isReceivable());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("order", head);
        out.put("lines", lines);
        out.put("bills", bills);
        return out;
    }

    public Map<String, Object> stats() {
        LocalDate today = LocalDate.now(ZONE);
        long monthStart = today.withDayOfMonth(1).atStartOfDay(ZONE).toInstant().toEpochMilli();
        long nextMonth = today.withDayOfMonth(1).plusMonths(1).atStartOfDay(ZONE).toInstant().toEpochMilli();
        long todayStart = today.atStartOfDay(ZONE).toInstant().toEpochMilli();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("monthAmount", scale(orderRepo.sumAmountBetween(monthStart, nextMonth)));
        out.put("openCount", orderRepo.countByStatus(ErpPurchaseOrder.STATUS_CONFIRMED)
                + orderRepo.countByStatus(ErpPurchaseOrder.STATUS_PARTIAL));
        out.put("partialCount", orderRepo.countByStatus(ErpPurchaseOrder.STATUS_PARTIAL));
        out.put("overdueCount", orderRepo.countOverdue(todayStart));
        out.put("draftCount", orderRepo.countByStatus(ErpPurchaseOrder.STATUS_DRAFT));
        out.put("statusOptions", statusOptions());
        return out;
    }

    /** 待收货订单（顶栏催货清单也能用） */
    public List<Map<String, Object>> openOrders(int limit) {
        int n = Math.max(1, Math.min(50, limit));
        List<Map<String, Object>> out = new ArrayList<>();
        long todayStart = LocalDate.now(ZONE).atStartOfDay(ZONE).toInstant().toEpochMilli();
        for (ErpPurchaseOrder o : orderRepo.findOpenOrders(
                org.springframework.data.domain.PageRequest.of(0, n))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("orderNo", o.getOrderNo());
            m.put("supplierName", o.getSupplierName());
            m.put("status", o.getStatus());
            m.put("statusLabel", STATUS_LABELS.getOrDefault(o.getStatus(), o.getStatus()));
            m.put("expectDate", o.getExpectDate());
            m.put("overdue", o.getExpectDate() != null && o.getExpectDate() < todayStart);
            m.put("totalQty", o.getTotalQty());
            m.put("receivedQty", o.getReceivedQty());
            m.put("progress", progress(nz(o.getReceivedQty()), nz(o.getTotalQty())));
            out.add(m);
        }
        return out;
    }

    /* ==================== 内部 ==================== */

    private ErpPurchaseOrder load(String orderNo) {
        return orderRepo.findByOrderNo(orderNo)
                .orElseThrow(() -> new IllegalArgumentException("采购订单不存在：" + orderNo));
    }

    private List<ItemReq> normalizeItems(Object raw) {
        List<ItemReq> out = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (Map<?, ?> m : maps(raw)) {
            Long skuId = asLong(m.get("skuId"));
            BigDecimal qty = dec(m.get("qty"));
            if (skuId == null) {
                throw new IllegalArgumentException("有一行没选商品");
            }
            if (qty == null || qty.signum() <= 0) {
                throw new IllegalArgumentException("订货数量必须大于 0");
            }
            if (!seen.add(skuId)) {
                throw new IllegalArgumentException("同一张订单里商品重复：" + skuName(skuId));
            }
            ErpSku sku = skuRepo.findById(skuId)
                    .orElseThrow(() -> new IllegalArgumentException("商品不存在：" + skuId));
            if (!sku.isEnabled()) {
                throw new IllegalArgumentException("商品已停用：" + sku.getName());
            }
            BigDecimal price = dec(m.get("unitPrice"));
            out.add(new ItemReq(skuId, scale(qty), price == null ? null : scale(price),
                    str(m.get("remark"))));
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException("至少录一行商品");
        }
        return out;
    }

    private void applyTotals(ErpPurchaseOrder o, List<ItemReq> items) {
        BigDecimal qty = BigDecimal.ZERO;
        BigDecimal amount = BigDecimal.ZERO;
        for (ItemReq it : items) {
            qty = qty.add(it.qty());
            if (it.unitPrice() != null) {
                amount = amount.add(it.qty().multiply(it.unitPrice()));
            }
        }
        o.setTotalQty(scale(qty));
        o.setTotalAmount(scale(amount));
    }

    private void saveItems(Long orderId, List<ItemReq> items) {
        int line = 1;
        List<ErpPurchaseOrderItem> rows = new ArrayList<>();
        for (ItemReq r : items) {
            ErpPurchaseOrderItem it = new ErpPurchaseOrderItem();
            it.setOrderId(orderId);
            it.setLineNo(line++);
            it.setSkuId(r.skuId());
            it.setQty(r.qty());
            it.setReceivedQty(BigDecimal.ZERO);
            it.setUnitPrice(r.unitPrice());
            it.setAmount(r.unitPrice() == null ? BigDecimal.ZERO
                    : scale(r.qty().multiply(r.unitPrice())));
            it.setRemark(r.remark());
            rows.add(it);
        }
        itemRepo.saveAll(rows);
    }

    private ErpPurchaseOrderItem pick(Map<?, ?> m, Map<Long, ErpPurchaseOrderItem> byId,
                                      Map<Long, ErpPurchaseOrderItem> bySku) {
        Long itemId = asLong(m.get("itemId"));
        if (itemId != null) {
            ErpPurchaseOrderItem it = byId.get(itemId);
            if (it == null) {
                throw new IllegalArgumentException("订单里没有这一行（itemId=" + itemId + "）");
            }
            return it;
        }
        Long skuId = asLong(m.get("skuId"));
        ErpPurchaseOrderItem it = skuId == null ? null : bySku.get(skuId);
        if (it == null) {
            throw new IllegalArgumentException("商品不在该订单里：" + skuId);
        }
        return it;
    }

    private String nextOrderNo() {
        String base = "CGDD" + LocalDate.now(ZONE).format(DAY) + "-";
        long n = orderRepo.countByOrderNoPrefix(base + "%") + 1;
        String no;
        do {
            no = base + String.format("%04d", n);
            n++;
        } while (orderRepo.existsByOrderNo(no));
        return no;
    }

    private String skuName(Long skuId) {
        return skuRepo.findById(skuId).map(ErpSku::getName).orElse("商品#" + skuId);
    }

    private static int progress(BigDecimal received, BigDecimal total) {
        if (total.signum() <= 0) {
            return 0;
        }
        return received.multiply(BigDecimal.valueOf(100))
                .divide(total, 0, RoundingMode.HALF_UP).intValue();
    }

    private static List<Map<?, ?>> maps(Object raw) {
        List<Map<?, ?>> out = new ArrayList<>();
        if (!(raw instanceof List<?> list)) {
            return out;
        }
        for (Object o : list) {
            if (o instanceof Map<?, ?> m) {
                out.add(m);
            }
        }
        return out;
    }

    /** 接受 "2026-10-28" 字符串或毫秒数；空值返回 fallback（null 表示不限） */
    private static Long parseDay(Object v, LocalDate fallback) {
        if (v == null || String.valueOf(v).isBlank()) {
            return fallback == null ? null : fallback.atStartOfDay(ZONE).toInstant().toEpochMilli();
        }
        String s = String.valueOf(v).trim();
        try {
            if (s.length() == 10 && s.charAt(4) == '-') {
                return LocalDate.parse(s).atStartOfDay(ZONE).toInstant().toEpochMilli();
            }
            long ms = Long.parseLong(s);
            return LocalDate.ofInstant(java.time.Instant.ofEpochMilli(ms), ZONE)
                    .atStartOfDay(ZONE).toInstant().toEpochMilli();
        } catch (Exception e) {
            throw new IllegalArgumentException("日期格式不对：" + s);
        }
    }

    private static Long asLong(Object o) {
        if (o == null) {
            return null;
        }
        String s = String.valueOf(o).trim();
        if (s.isEmpty() || "null".equalsIgnoreCase(s)) {
            return null;
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static BigDecimal dec(Object o) {
        if (o == null) {
            return null;
        }
        String s = String.valueOf(o).trim();
        if (s.isEmpty() || "null".equalsIgnoreCase(s)) {
            return null;
        }
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal scale(BigDecimal v) {
        return nz(v).setScale(4, RoundingMode.HALF_UP);
    }

    private static String plain(BigDecimal v) {
        return nz(v).stripTrailingZeros().toPlainString();
    }

    public static List<Map<String, String>> statusOptions() {
        List<Map<String, String>> out = new ArrayList<>();
        for (String s : STATUS_ORDER) {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("value", s);
            m.put("label", STATUS_LABELS.get(s));
            out.add(m);
        }
        return out;
    }

    private static Map<String, Object> ok(String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("message", msg);
        return m;
    }

    /** 排序工具：按状态顺序给前端排（未用到时保留，避免前端重复维护顺序） */
    public static Comparator<ErpPurchaseOrder> byStatusOrder() {
        return Comparator.comparingInt(o -> STATUS_ORDER.indexOf(o.getStatus()));
    }
}
