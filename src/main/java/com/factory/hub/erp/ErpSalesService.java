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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 销售管理（ROADMAP DH-45b）：客户订单 → 发货 → 客户退货。
 * <p>
 * 与采购是镜像结构，但三条业务差别必须守住：
 * <ol>
 *   <li><b>发货必须先有货</b>：{@code SO_OUT} 由 {@link ErpStockService} 校验可用量，
 *       库存不够时直接拒绝（"现有 70，本次需要 100"），不会把库存做成负数。</li>
 *   <li><b>退货是入库</b>：{@code SO_RETURN} 是正数行，退回的货重新变成可发库存，
 *       订单的"净发货"相应减少。</li>
 *   <li><b>应收 = 已发货金额</b>：本服务只算金额口径（Σ 净发货 × 单价），
 *       真正的收款单/账龄在 DH-54 财务里做。</li>
 * </ol>
 * 进度和采购一样不手工维护：{@link #recalc} 按"未作废 SO_OUT − SO_RETURN"重算。
 */
@Service
public class ErpSalesService {

    private static final Logger log = LoggerFactory.getLogger(ErpSalesService.class);
    private static final ZoneId ZONE = ZoneId.systemDefault();
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final BigDecimal EPS = new BigDecimal("0.0001");

    public static final Map<String, String> STATUS_LABELS;
    public static final List<String> STATUS_ORDER = List.of(
            ErpSalesOrder.STATUS_DRAFT, ErpSalesOrder.STATUS_CONFIRMED,
            ErpSalesOrder.STATUS_PARTIAL, ErpSalesOrder.STATUS_DONE,
            ErpSalesOrder.STATUS_CANCELLED);

    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(ErpSalesOrder.STATUS_DRAFT, "草稿");
        m.put(ErpSalesOrder.STATUS_CONFIRMED, "已确认");
        m.put(ErpSalesOrder.STATUS_PARTIAL, "部分发货");
        m.put(ErpSalesOrder.STATUS_DONE, "已完成");
        m.put(ErpSalesOrder.STATUS_CANCELLED, "已取消");
        STATUS_LABELS = java.util.Collections.unmodifiableMap(m);
    }

    public record ItemReq(Long skuId, BigDecimal qty, BigDecimal unitPrice, String remark) {}

    private final ErpSalesOrderRepository orderRepo;
    private final ErpSalesOrderItemRepository itemRepo;
    private final ErpStockService stockService;
    private final ErpStockBillRepository billRepo;
    private final ErpStockBillHdrRepository hdrRepo;
    private final ErpSkuRepository skuRepo;
    private final ErpPartnerRepository partnerRepo;

    public ErpSalesService(ErpSalesOrderRepository orderRepo,
                           ErpSalesOrderItemRepository itemRepo,
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
        Long customerId = asLong(body.get("customerId"));
        ErpPartner customer = customerId == null ? null : partnerRepo.findById(customerId)
                .orElseThrow(() -> new IllegalArgumentException("客户不存在"));
        if (customer == null) {
            throw new IllegalArgumentException("请选择客户");
        }
        List<ItemReq> items = normalizeItems(body.get("lines"));

        ErpSalesOrder o = new ErpSalesOrder();
        o.setOrderNo(nextOrderNo());
        o.setCustomerId(customer.getId());
        o.setCustomerName(customer.getName());
        o.setStatus(ErpSalesOrder.STATUS_DRAFT);
        o.setOrderDate(parseDay(body.get("orderDate"), LocalDate.now(ZONE)));
        o.setDeliverDate(parseDay(body.get("deliverDate"), null));
        o.setRemark(str(body.get("remark")));
        o.setOperator(operator);
        o.setCreatedAt(System.currentTimeMillis());
        o.setUpdatedAt(o.getCreatedAt());
        applyTotals(o, items);
        o = orderRepo.saveAndFlush(o);

        saveItems(o.getId(), items);
        log.info("新建销售订单 {}，{} 行，合计 {} 元（{}）",
                o.getOrderNo(), items.size(), o.getTotalAmount(), operator);
        Map<String, Object> out = ok("销售订单已创建：" + o.getOrderNo());
        out.put("orderNo", o.getOrderNo());
        return out;
    }

    @Transactional
    public void update(String orderNo, Map<String, Object> body, String operator) {
        ErpSalesOrder o = load(orderNo);
        if (!o.isEditable()) {
            throw new IllegalArgumentException("只有草稿状态的订单可以修改");
        }
        Long customerId = asLong(body.get("customerId"));
        if (customerId != null) {
            ErpPartner p = partnerRepo.findById(customerId)
                    .orElseThrow(() -> new IllegalArgumentException("客户不存在"));
            o.setCustomerId(p.getId());
            o.setCustomerName(p.getName());
        }
        List<ItemReq> items = normalizeItems(body.get("lines"));
        o.setOrderDate(parseDay(body.get("orderDate"), LocalDate.now(ZONE)));
        o.setDeliverDate(parseDay(body.get("deliverDate"), null));
        o.setRemark(str(body.get("remark")));
        o.setUpdatedAt(System.currentTimeMillis());
        applyTotals(o, items);
        orderRepo.save(o);
        itemRepo.deleteByOrderId(o.getId());
        saveItems(o.getId(), items);
        log.info("修改销售订单 {}（{}）", orderNo, operator);
    }

    @Transactional
    public void confirm(String orderNo, String operator) {
        ErpSalesOrder o = load(orderNo);
        if (!ErpSalesOrder.STATUS_DRAFT.equals(o.getStatus())) {
            throw new IllegalArgumentException("只有草稿状态的订单可以确认");
        }
        if (itemRepo.findByOrderIdOrderByLineNoAsc(o.getId()).isEmpty()) {
            throw new IllegalArgumentException("订单没有明细，不能确认");
        }
        o.setStatus(ErpSalesOrder.STATUS_CONFIRMED);
        o.setUpdatedAt(System.currentTimeMillis());
        orderRepo.save(o);
        log.info("确认销售订单 {}（{}）", orderNo, operator);
    }

    @Transactional
    public void cancel(String orderNo, String operator) {
        ErpSalesOrder o = load(orderNo);
        if (ErpSalesOrder.STATUS_DONE.equals(o.getStatus())) {
            throw new IllegalArgumentException("订单已完成，不能取消");
        }
        if (ErpSalesOrder.STATUS_CANCELLED.equals(o.getStatus())) {
            throw new IllegalArgumentException("订单已取消，无需重复操作");
        }
        BigDecimal shipped = nz(o.getShippedQty());
        if (shipped.compareTo(EPS) > 0) {
            throw new IllegalArgumentException("订单已发货 " + plain(shipped)
                    + "，不能直接取消；请先做客户退货或作废对应的出库单");
        }
        o.setStatus(ErpSalesOrder.STATUS_CANCELLED);
        o.setUpdatedAt(System.currentTimeMillis());
        orderRepo.save(o);
        log.info("取消销售订单 {}（{}）", orderNo, operator);
    }

    /* ==================== 发货 / 退货 ==================== */

    /**
     * 从订单发货：生成 SO_OUT（负数出库，库存不足会被库存服务拦下），再重算订单进度。
     */
    @Transactional
    public Map<String, Object> ship(String orderNo, Long whId, Object rawLines,
                                    String remark, String operator) {
        ErpSalesOrder o = load(orderNo);
        if (!o.isShippable()) {
            throw new IllegalArgumentException("订单当前状态是「" + STATUS_LABELS.get(o.getStatus())
                    + "」，不能发货（只有已确认/部分发货的订单可以发货）");
        }
        List<ErpSalesOrderItem> items = itemRepo.findByOrderIdOrderByLineNoAsc(o.getId());
        Map<Long, ErpSalesOrderItem> bySku = new HashMap<>();
        Map<Long, ErpSalesOrderItem> byId = new HashMap<>();
        for (ErpSalesOrderItem it : items) {
            bySku.put(it.getSkuId(), it);
            byId.put(it.getId(), it);
        }

        List<ErpStockService.LineReq> reqs = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (Map<?, ?> m : maps(rawLines)) {
            ErpSalesOrderItem it = pick(m, byId, bySku);
            BigDecimal qty = nz(dec(m.get("qty")));
            if (qty.signum() <= 0) {
                continue;
            }
            if (!seen.add(it.getSkuId())) {
                throw new IllegalArgumentException("同一张单里商品重复：" + skuName(it.getSkuId()));
            }
            BigDecimal remain = nz(it.getQty()).subtract(nz(it.getShippedQty()));
            if (qty.subtract(remain).compareTo(EPS) > 0) {
                throw new IllegalArgumentException("「" + skuName(it.getSkuId()) + "」还能发货 "
                        + plain(remain) + "，本次填了 " + plain(qty));
            }
            BigDecimal price = dec(m.get("unitPrice"));
            reqs.add(new ErpStockService.LineReq(it.getSkuId(), qty,
                    price == null ? it.getUnitPrice() : price));
        }
        if (reqs.isEmpty()) {
            throw new IllegalArgumentException("没有要发货的数量");
        }

        String note = "销售出库（订单 " + orderNo + "）"
                + (remark == null || remark.isBlank() ? "" : "；" + remark);
        ErpStockService.Created c = stockService.createManual(
                ErpStockBill.TYPE_SO_OUT, o.getCustomerId(), whId, null, note, operator, reqs, orderNo);
        recalc(orderNo);

        Map<String, Object> out = ok("已发货，单据号 " + c.billNo());
        out.put("billNo", c.billNo());
        out.put("lineCount", c.lineCount());
        return out;
    }

    /** 客户退货：生成 SO_RETURN（正数入库），数量不能超过净发货 */
    @Transactional
    public Map<String, Object> returnFromCustomer(String orderNo, Long whId, Object rawLines,
                                                  String remark, String operator) {
        ErpSalesOrder o = load(orderNo);
        if (ErpSalesOrder.STATUS_CANCELLED.equals(o.getStatus())
                || ErpSalesOrder.STATUS_DRAFT.equals(o.getStatus())) {
            throw new IllegalArgumentException("草稿/已取消的订单没有货可退");
        }
        List<ErpSalesOrderItem> items = itemRepo.findByOrderIdOrderByLineNoAsc(o.getId());
        Map<Long, ErpSalesOrderItem> bySku = new HashMap<>();
        Map<Long, ErpSalesOrderItem> byId = new HashMap<>();
        for (ErpSalesOrderItem it : items) {
            bySku.put(it.getSkuId(), it);
            byId.put(it.getId(), it);
        }

        List<ErpStockService.LineReq> reqs = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (Map<?, ?> m : maps(rawLines)) {
            ErpSalesOrderItem it = pick(m, byId, bySku);
            BigDecimal qty = nz(dec(m.get("qty")));
            if (qty.signum() <= 0) {
                continue;
            }
            if (!seen.add(it.getSkuId())) {
                throw new IllegalArgumentException("同一张单里商品重复：" + skuName(it.getSkuId()));
            }
            BigDecimal net = nz(it.getShippedQty());
            if (qty.subtract(net).compareTo(EPS) > 0) {
                throw new IllegalArgumentException("「" + skuName(it.getSkuId()) + "」最多可退 "
                        + plain(net) + "，本次填了 " + plain(qty));
            }
            reqs.add(new ErpStockService.LineReq(it.getSkuId(), qty, it.getUnitPrice()));
        }
        if (reqs.isEmpty()) {
            throw new IllegalArgumentException("没有要退货的数量");
        }

        String note = "销售退货（订单 " + orderNo + "）"
                + (remark == null || remark.isBlank() ? "" : "；" + remark);
        ErpStockService.Created c = stockService.createManual(
                ErpStockBill.TYPE_SO_RETURN, o.getCustomerId(), whId, null, note, operator, reqs, orderNo);
        recalc(orderNo);

        Map<String, Object> out = ok("已退货，单据号 " + c.billNo());
        out.put("billNo", c.billNo());
        return out;
    }

    /** 库存页作废销售单据后，订单进度跟着退回 */
    @EventListener
    public void onBillVoided(StockBillVoidedEvent e) {
        if (e.srcOrderNo() == null
                || !(ErpStockBill.TYPE_SO_OUT.equals(e.billType())
                     || ErpStockBill.TYPE_SO_RETURN.equals(e.billType()))) {
            return;
        }
        try {
            recalc(e.srcOrderNo());
            log.info("单据 {} 作废，已重算销售订单 {} 进度", e.billNo(), e.srcOrderNo());
        } catch (Exception ex) {
            log.warn("单据 {} 作废后重算销售订单 {} 失败：{}", e.billNo(), e.srcOrderNo(), ex.getMessage());
        }
    }

    @Transactional
    public void recalc(String orderNo) {
        ErpSalesOrder o = orderRepo.findByOrderNo(orderNo).orElse(null);
        if (o == null) {
            return;
        }
        Map<Long, BigDecimal> net = new HashMap<>();
        for (Object[] r : billRepo.sumByOrder(orderNo)) {
            Long skuId = ((Number) r[0]).longValue();
            BigDecimal qty = r[2] == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(r[2]));
            net.merge(skuId, qty, BigDecimal::add);
        }
        List<ErpSalesOrderItem> items = itemRepo.findByOrderIdOrderByLineNoAsc(o.getId());
        BigDecimal totalShipped = BigDecimal.ZERO;
        for (ErpSalesOrderItem it : items) {
            // SO_OUT 是负数、SO_RETURN 是正数，取反才是"净发货"
            BigDecimal v = net.getOrDefault(it.getSkuId(), BigDecimal.ZERO).negate();
            if (v.signum() < 0) {
                v = BigDecimal.ZERO;             // 退货多于发货的脏数据兜底，不显示负发货
            }
            it.setShippedQty(scale(v));
            totalShipped = totalShipped.add(v);
        }
        itemRepo.saveAll(items);
        o.setShippedQty(scale(totalShipped));
        o.setStatus(nextStatus(o, totalShipped));
        o.setUpdatedAt(System.currentTimeMillis());
        orderRepo.save(o);
    }

    private String nextStatus(ErpSalesOrder o, BigDecimal shipped) {
        if (ErpSalesOrder.STATUS_CANCELLED.equals(o.getStatus())
                || ErpSalesOrder.STATUS_DRAFT.equals(o.getStatus())) {
            return o.getStatus();
        }
        BigDecimal total = nz(o.getTotalQty());
        if (shipped.compareTo(EPS) <= 0) {
            return ErpSalesOrder.STATUS_CONFIRMED;
        }
        return shipped.compareTo(total) >= 0
                ? ErpSalesOrder.STATUS_DONE
                : ErpSalesOrder.STATUS_PARTIAL;
    }

    /* ==================== 查询 ==================== */

    public Map<String, Object> list(String status, Long customerId, Long fromTs, Long toTs,
                                    String kw, int page, int size) {
        int p = Math.max(1, page);
        int s = Math.max(1, Math.min(200, size));
        String k = kw == null || kw.trim().isEmpty() ? "" : "%" + kw.trim().toLowerCase() + "%";
        var pageable = org.springframework.data.domain.PageRequest.of(p - 1, s,
                org.springframework.data.domain.Sort.by(
                        org.springframework.data.domain.Sort.Direction.DESC, "orderNo"));
        var res = orderRepo.search(status == null ? "" : status,
                customerId == null ? 0L : customerId,
                fromTs == null ? 0L : fromTs,
                toTs == null ? 0L : toTs, k, pageable);

        List<Long> ids = res.getContent().stream().map(ErpSalesOrder::getId).toList();
        Map<Long, BigDecimal> shippedMap = new HashMap<>();
        if (!ids.isEmpty()) {
            for (Object[] r : itemRepo.sumShippedByOrderIds(ids)) {
                shippedMap.put(((Number) r[0]).longValue(),
                        r[1] == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(r[1])));
            }
        }
        long todayStart = LocalDate.now(ZONE).atStartOfDay(ZONE).toInstant().toEpochMilli();

        List<Map<String, Object>> rows = new ArrayList<>();
        for (ErpSalesOrder o : res.getContent()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("orderNo", o.getOrderNo());
            m.put("customerId", o.getCustomerId());
            m.put("customerName", o.getCustomerName());
            m.put("status", o.getStatus());
            m.put("statusLabel", STATUS_LABELS.getOrDefault(o.getStatus(), o.getStatus()));
            m.put("orderDate", o.getOrderDate());
            m.put("deliverDate", o.getDeliverDate());
            m.put("totalQty", o.getTotalQty());
            m.put("totalAmount", o.getTotalAmount());
            m.put("shippedQty", shippedMap.getOrDefault(o.getId(), nz(o.getShippedQty())));
            m.put("progress", progress(nz(shippedMap.getOrDefault(o.getId(), o.getShippedQty())),
                    nz(o.getTotalQty())));
            m.put("overdue", o.getDeliverDate() != null && o.getDeliverDate() < todayStart
                    && o.isShippable());
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
        ErpSalesOrder o = orderRepo.findByOrderNo(orderNo)
                .orElseThrow(() -> new IllegalArgumentException("销售订单不存在：" + orderNo));
        Map<Long, ErpSku> skus = new HashMap<>();
        for (ErpSku s : skuRepo.findAll()) {
            skus.put(s.getId(), s);
        }
        List<Map<String, Object>> lines = new ArrayList<>();
        BigDecimal shippedAmount = BigDecimal.ZERO;
        for (ErpSalesOrderItem it : itemRepo.findByOrderIdOrderByLineNoAsc(o.getId())) {
            ErpSku s = skus.get(it.getSkuId());
            BigDecimal qty = nz(it.getQty());
            BigDecimal got = nz(it.getShippedQty());
            if (it.getUnitPrice() != null) {
                shippedAmount = shippedAmount.add(got.multiply(it.getUnitPrice()));
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("itemId", it.getId());
            m.put("lineNo", it.getLineNo());
            m.put("skuId", it.getSkuId());
            m.put("skuCode", s == null ? ("商品#" + it.getSkuId()) : s.getCode());
            m.put("skuName", s == null ? "" : s.getName());
            m.put("spec", s == null ? "" : s.getSpec());
            m.put("unit", s == null ? "" : s.getUnit());
            m.put("qty", scale(qty));
            m.put("shippedQty", scale(got));
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
        head.put("customerId", o.getCustomerId());
        head.put("customerName", o.getCustomerName());
        head.put("status", o.getStatus());
        head.put("statusLabel", STATUS_LABELS.getOrDefault(o.getStatus(), o.getStatus()));
        head.put("orderDate", o.getOrderDate());
        head.put("deliverDate", o.getDeliverDate());
        head.put("totalQty", o.getTotalQty());
        head.put("totalAmount", o.getTotalAmount());
        head.put("shippedQty", o.getShippedQty());
        head.put("shippedAmount", scale(shippedAmount));
        head.put("progress", progress(nz(o.getShippedQty()), nz(o.getTotalQty())));
        head.put("remark", o.getRemark());
        head.put("operator", o.getOperator());
        head.put("createdAt", o.getCreatedAt());
        head.put("updatedAt", o.getUpdatedAt());
        head.put("editable", o.isEditable());
        head.put("shippable", o.isShippable());

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
        out.put("openCount", orderRepo.countByStatus(ErpSalesOrder.STATUS_CONFIRMED)
                + orderRepo.countByStatus(ErpSalesOrder.STATUS_PARTIAL));
        out.put("partialCount", orderRepo.countByStatus(ErpSalesOrder.STATUS_PARTIAL));
        out.put("overdueCount", orderRepo.countOverdue(todayStart));
        out.put("draftCount", orderRepo.countByStatus(ErpSalesOrder.STATUS_DRAFT));
        out.put("statusOptions", statusOptions());
        return out;
    }

    public List<Map<String, Object>> openOrders(int limit) {
        int n = Math.max(1, Math.min(50, limit));
        List<Map<String, Object>> out = new ArrayList<>();
        long todayStart = LocalDate.now(ZONE).atStartOfDay(ZONE).toInstant().toEpochMilli();
        for (ErpSalesOrder o : orderRepo.findOpenOrders(
                org.springframework.data.domain.PageRequest.of(0, n))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("orderNo", o.getOrderNo());
            m.put("customerName", o.getCustomerName());
            m.put("status", o.getStatus());
            m.put("statusLabel", STATUS_LABELS.getOrDefault(o.getStatus(), o.getStatus()));
            m.put("deliverDate", o.getDeliverDate());
            m.put("overdue", o.getDeliverDate() != null && o.getDeliverDate() < todayStart);
            m.put("totalQty", o.getTotalQty());
            m.put("shippedQty", o.getShippedQty());
            m.put("progress", progress(nz(o.getShippedQty()), nz(o.getTotalQty())));
            out.add(m);
        }
        return out;
    }

    /* ==================== 内部 ==================== */

    private ErpSalesOrder load(String orderNo) {
        return orderRepo.findByOrderNo(orderNo)
                .orElseThrow(() -> new IllegalArgumentException("销售订单不存在：" + orderNo));
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

    private void applyTotals(ErpSalesOrder o, List<ItemReq> items) {
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
        List<ErpSalesOrderItem> rows = new ArrayList<>();
        for (ItemReq r : items) {
            ErpSalesOrderItem it = new ErpSalesOrderItem();
            it.setOrderId(orderId);
            it.setLineNo(line++);
            it.setSkuId(r.skuId());
            it.setQty(r.qty());
            it.setShippedQty(BigDecimal.ZERO);
            it.setUnitPrice(r.unitPrice());
            it.setAmount(r.unitPrice() == null ? BigDecimal.ZERO
                    : scale(r.qty().multiply(r.unitPrice())));
            it.setRemark(r.remark());
            rows.add(it);
        }
        itemRepo.saveAll(rows);
    }

    private ErpSalesOrderItem pick(Map<?, ?> m, Map<Long, ErpSalesOrderItem> byId,
                                   Map<Long, ErpSalesOrderItem> bySku) {
        Long itemId = asLong(m.get("itemId"));
        if (itemId != null) {
            ErpSalesOrderItem it = byId.get(itemId);
            if (it == null) {
                throw new IllegalArgumentException("订单里没有这一行（itemId=" + itemId + "）");
            }
            return it;
        }
        Long skuId = asLong(m.get("skuId"));
        ErpSalesOrderItem it = skuId == null ? null : bySku.get(skuId);
        if (it == null) {
            throw new IllegalArgumentException("商品不在该订单里：" + skuId);
        }
        return it;
    }

    private String nextOrderNo() {
        String base = "XSDD" + LocalDate.now(ZONE).format(DAY) + "-";
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

    private static int progress(BigDecimal shipped, BigDecimal total) {
        if (total.signum() <= 0) {
            return 0;
        }
        return shipped.multiply(BigDecimal.valueOf(100))
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
}
