package com.factory.hub.erp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 财务管理（ROADMAP DH-54）：应收 / 应付 / 收付款单 / 账龄。
 * <p>
 * <b>口径（先定死，避免以后各页对不上）：</b>
 * <ul>
 *   <li><b>应收</b> = 销售订单的<b>已发货金额</b>（Σ 净发货 × 单价），不是订单金额——
 *       没发出去的货不该找客户要钱。</li>
 *   <li><b>应付</b> = 采购订单的<b>已入库金额</b>，同理：没到的货不该付款。</li>
 *   <li>未收 = 应收 − 已核销收款；未付 = 应付 − 已核销付款。</li>
 *   <li><b>预收 / 预付</b>：没关联订单的收付款，挂在往来单位上，不冲减应收应付，
 *       单独列出来（客户先打款、我们还没发货，就是这个）。</li>
 *   <li><b>账龄</b>：从订单日期算到今天，分 0-30 / 31-60 / 61-90 / 90+ 四档；
 *       默认账期 30 天，超过即视为逾期（{@link #CREDIT_DAYS}）。</li>
 * </ul>
 * <b>不做总账</b>：没有科目、分录、凭证、试算平衡——那是另一门生意，做半套比不做更糟。
 */
@Service
public class ErpFinanceService {

    private static final Logger log = LoggerFactory.getLogger(ErpFinanceService.class);
    private static final ZoneId ZONE = ZoneId.systemDefault();
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final BigDecimal EPS = new BigDecimal("0.0001");

    /** 默认账期（天）：超过就算逾期。以后要按客户配置再拆。 */
    public static final int CREDIT_DAYS = 30;

    public static final List<String> METHODS = List.of(
            "银行转账", "现金", "承兑汇票", "微信", "支付宝", "其他");

    public static final Map<String, String> DIR_LABELS;
    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(ErpPayment.DIR_IN, "收款");
        m.put(ErpPayment.DIR_OUT, "付款");
        DIR_LABELS = java.util.Collections.unmodifiableMap(m);
    }

    /** 一行应收/应付（内部用，也直接回给前端） */
    private record OpenRow(String orderNo, Long partnerId, String partnerName, long orderDate,
                           BigDecimal total, BigDecimal billed, BigDecimal paid,
                           int days, String bucket) {
        BigDecimal outstanding() {
            return billed.subtract(paid).max(BigDecimal.ZERO);
        }
    }

    private final ErpPaymentRepository payRepo;
    private final ErpSalesOrderRepository salesRepo;
    private final ErpSalesOrderItemRepository salesItemRepo;
    private final ErpPurchaseOrderRepository purchaseRepo;
    private final ErpPurchaseOrderItemRepository purchaseItemRepo;
    private final ErpPartnerRepository partnerRepo;

    public ErpFinanceService(ErpPaymentRepository payRepo,
                             ErpSalesOrderRepository salesRepo,
                             ErpSalesOrderItemRepository salesItemRepo,
                             ErpPurchaseOrderRepository purchaseRepo,
                             ErpPurchaseOrderItemRepository purchaseItemRepo,
                             ErpPartnerRepository partnerRepo) {
        this.payRepo = payRepo;
        this.salesRepo = salesRepo;
        this.salesItemRepo = salesItemRepo;
        this.purchaseRepo = purchaseRepo;
        this.purchaseItemRepo = purchaseItemRepo;
        this.partnerRepo = partnerRepo;
    }

    /* ==================== 汇总 ==================== */

    public Map<String, Object> summary() {
        List<OpenRow> ar = openRows(true);
        List<OpenRow> ap = openRows(false);
        long now = System.currentTimeMillis();
        LocalDate today = LocalDate.now(ZONE);
        long monthStart = today.withDayOfMonth(1).atStartOfDay(ZONE).toInstant().toEpochMilli();
        long nextMonth = today.withDayOfMonth(1).plusMonths(1).atStartOfDay(ZONE).toInstant().toEpochMilli();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("arTotal", scale(sum(ar, OpenRow::billed)));
        out.put("arPaid", scale(sum(ar, OpenRow::paid)));
        out.put("arOutstanding", scale(sumOutstanding(ar)));
        out.put("arOverdue", scale(sumOverdue(ar)));
        out.put("arPre", scale(payRepo.sumUnallocated(ErpPayment.DIR_IN)));
        out.put("apTotal", scale(sum(ap, OpenRow::billed)));
        out.put("apPaid", scale(sum(ap, OpenRow::paid)));
        out.put("apOutstanding", scale(sumOutstanding(ap)));
        out.put("apOverdue", scale(sumOverdue(ap)));
        out.put("apPre", scale(payRepo.sumUnallocated(ErpPayment.DIR_OUT)));
        out.put("monthIn", scale(payRepo.sumBetween(ErpPayment.DIR_IN, monthStart, nextMonth)));
        out.put("monthOut", scale(payRepo.sumBetween(ErpPayment.DIR_OUT, monthStart, nextMonth)));
        out.put("arAging", agingOf(ar));
        out.put("apAging", agingOf(ap));
        out.put("creditDays", CREDIT_DAYS);
        out.put("methods", METHODS);
        out.put("at", now);
        return out;
    }

    /** 应收明细（分页 + 客户 + 只看未收 + 搜索） */
    public Map<String, Object> receivables(Long partnerId, boolean onlyOpen, String kw, int page, int size) {
        return pageOf(openRows(true), partnerId, onlyOpen, kw, page, size);
    }

    public Map<String, Object> payables(Long partnerId, boolean onlyOpen, String kw, int page, int size) {
        return pageOf(openRows(false), partnerId, onlyOpen, kw, page, size);
    }

    private Map<String, Object> pageOf(List<OpenRow> all, Long partnerId, boolean onlyOpen,
                                       String kw, int page, int size) {
        String k = kw == null ? "" : kw.trim().toLowerCase();
        List<OpenRow> rows = all.stream()
                .filter(r -> partnerId == null || partnerId.equals(r.partnerId()))
                .filter(r -> !onlyOpen || r.outstanding().compareTo(EPS) > 0)
                .filter(r -> k.isEmpty() || (r.orderNo().toLowerCase().contains(k)
                        || (r.partnerName() != null && r.partnerName().toLowerCase().contains(k))))
                .sorted(Comparator.comparing(OpenRow::orderDate))
                .toList();
        int p = Math.max(1, page);
        int s = Math.max(1, Math.min(200, size));
        int from = Math.min((p - 1) * s, rows.size());
        int to = Math.min(from + s, rows.size());

        List<Map<String, Object>> list = new ArrayList<>();
        for (OpenRow r : rows.subList(from, to)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("orderNo", r.orderNo());
            m.put("partnerId", r.partnerId());
            m.put("partnerName", r.partnerName());
            m.put("orderDate", r.orderDate());
            m.put("totalAmount", scale(r.total()));
            m.put("billedAmount", scale(r.billed()));
            m.put("paidAmount", scale(r.paid()));
            m.put("outstanding", scale(r.outstanding()));
            m.put("days", r.days());
            m.put("bucket", r.bucket());
            m.put("overdue", r.days() > CREDIT_DAYS);
            list.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("total", rows.size());
        out.put("page", p);
        out.put("size", s);
        out.put("rows", list);
        out.put("sumOutstanding", scale(sumOutstanding(rows)));
        out.put("sumBilled", scale(sum(rows, OpenRow::billed)));
        out.put("aging", agingOf(rows));
        return out;
    }

    /* ==================== 收付款单 ==================== */

    public Map<String, Object> payments(String dir, Long partnerId, Long fromTs, Long toTs,
                                        String kw, boolean withVoided, int page, int size) {
        int p = Math.max(1, page);
        int s = Math.max(1, Math.min(200, size));
        String k = kw == null || kw.trim().isEmpty() ? "" : "%" + kw.trim().toLowerCase() + "%";
        var pageable = org.springframework.data.domain.PageRequest.of(p - 1, s,
                org.springframework.data.domain.Sort.by(
                        org.springframework.data.domain.Sort.Direction.DESC, "payDate")
                        .and(org.springframework.data.domain.Sort.by(
                                org.springframework.data.domain.Sort.Direction.DESC, "id")));
        var res = payRepo.search(dir == null ? "" : dir, partnerId == null ? 0L : partnerId,
                fromTs == null ? 0L : fromTs, toTs == null ? 0L : toTs, withVoided, k, pageable);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (ErpPayment x : res.getContent()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("payNo", x.getPayNo());
            m.put("direction", x.getDirection());
            m.put("directionLabel", DIR_LABELS.getOrDefault(x.getDirection(), x.getDirection()));
            m.put("partnerId", x.getPartnerId());
            m.put("partnerName", x.getPartnerName());
            m.put("orderType", x.getOrderType());
            m.put("orderNo", x.getOrderNo());
            m.put("amount", scale(x.getAmount()));
            m.put("method", x.getMethod());
            m.put("payDate", x.getPayDate());
            m.put("remark", x.getRemark());
            m.put("operator", x.getOperator());
            m.put("voided", x.isVoided());
            m.put("voidedBy", x.getVoidedBy());
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

    @Transactional
    public synchronized Map<String, Object> createPayment(Map<String, Object> body, String operator) {
        String dir = str(body.get("direction")).toUpperCase();
        if (!ErpPayment.DIR_IN.equals(dir) && !ErpPayment.DIR_OUT.equals(dir)) {
            throw new IllegalArgumentException("方向只能是 IN（收款）或 OUT（付款）");
        }
        Long partnerId = asLong(body.get("partnerId"));
        ErpPartner partner = partnerId == null ? null : partnerRepo.findById(partnerId)
                .orElseThrow(() -> new IllegalArgumentException("往来单位不存在"));
        if (partner == null) {
            throw new IllegalArgumentException(ErpPayment.DIR_IN.equals(dir) ? "请选择客户" : "请选择供应商");
        }
        boolean in = ErpPayment.DIR_IN.equals(dir);
        if (in && ErpPartner.TYPE_SUPPLIER.equals(partner.getPartnerType())) {
            throw new IllegalArgumentException("「" + partner.getName() + "」是供应商，不能对它收款");
        }
        if (!in && ErpPartner.TYPE_CUSTOMER.equals(partner.getPartnerType())) {
            throw new IllegalArgumentException("「" + partner.getName() + "」是客户，不能对它付款");
        }

        BigDecimal amount = dec(body.get("amount"));
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("金额必须大于 0");
        }
        amount = scale(amount);

        String orderNo = str(body.get("orderNo"));
        String orderType = null;
        if (!orderNo.isEmpty()) {
            if (in) {
                ErpSalesOrder o = salesRepo.findByOrderNo(orderNo)
                        .orElseThrow(() -> new IllegalArgumentException("销售订单不存在：" + orderNo));
                if (!ErpSalesOrder.STATUS_CONFIRMED.equals(o.getStatus())
                        && !ErpSalesOrder.STATUS_PARTIAL.equals(o.getStatus())
                        && !ErpSalesOrder.STATUS_DONE.equals(o.getStatus())) {
                    throw new IllegalArgumentException("订单 " + orderNo + " 当前状态不能收款");
                }
                BigDecimal billed = scale(nz(salesItemRepo.shippedAmountOfOrder(o.getId())));
                BigDecimal paid = nz(payRepo.sumByOrder(orderNo, ErpPayment.DIR_IN));
                BigDecimal left = billed.subtract(paid);
                if (amount.subtract(left).compareTo(EPS) > 0) {
                    throw new IllegalArgumentException("订单 " + orderNo + " 未收 " + plain(left)
                            + "，本次填了 " + plain(amount));
                }
                orderType = ErpPayment.ORDER_SALES;
            } else {
                ErpPurchaseOrder o = purchaseRepo.findByOrderNo(orderNo)
                        .orElseThrow(() -> new IllegalArgumentException("采购订单不存在：" + orderNo));
                if (!ErpPurchaseOrder.STATUS_CONFIRMED.equals(o.getStatus())
                        && !ErpPurchaseOrder.STATUS_PARTIAL.equals(o.getStatus())
                        && !ErpPurchaseOrder.STATUS_DONE.equals(o.getStatus())) {
                    throw new IllegalArgumentException("订单 " + orderNo + " 当前状态不能付款");
                }
                BigDecimal billed = scale(nz(purchaseItemRepo.receivedAmountOfOrder(o.getId())));
                BigDecimal paid = nz(payRepo.sumByOrder(orderNo, ErpPayment.DIR_OUT));
                BigDecimal left = billed.subtract(paid);
                if (amount.subtract(left).compareTo(EPS) > 0) {
                    throw new IllegalArgumentException("订单 " + orderNo + " 未付 " + plain(left)
                            + "，本次填了 " + plain(amount));
                }
                orderType = ErpPayment.ORDER_PURCHASE;
            }
        }

        ErpPayment x = new ErpPayment();
        x.setPayNo(nextPayNo());
        x.setDirection(dir);
        x.setPartnerId(partner.getId());
        x.setPartnerName(partner.getName());
        x.setOrderType(orderType);
        x.setOrderNo(orderNo.isEmpty() ? null : orderNo);
        x.setAmount(amount);
        String method = str(body.get("method"));
        x.setMethod(method.isEmpty() ? "银行转账" : method);
        x.setPayDate(parseDay(body.get("payDate"), LocalDate.now(ZONE)));
        x.setRemark(str(body.get("remark")));
        x.setOperator(operator);
        x.setCreatedAt(System.currentTimeMillis());
        payRepo.saveAndFlush(x);
        log.info("新建{}单 {}：{} {} 元（{}）", in ? "收款" : "付款", x.getPayNo(),
                partner.getName(), amount, operator);

        Map<String, Object> out = ok((in ? "已收款 " : "已付款 ") + amount.stripTrailingZeros().toPlainString()
                + " 元，单号 " + x.getPayNo());
        out.put("payNo", x.getPayNo());
        return out;
    }

    @Transactional
    public void voidPayment(String payNo, String operator) {
        ErpPayment x = payRepo.findByPayNo(payNo)
                .orElseThrow(() -> new IllegalArgumentException("收付款单不存在：" + payNo));
        if (x.isVoided()) {
            throw new IllegalArgumentException("单据已作废，无需重复操作");
        }
        x.setVoidedAt(System.currentTimeMillis());
        x.setVoidedBy(operator);
        payRepo.save(x);
        log.info("作废收付款单 {}（{}）", payNo, operator);
    }

    /* ==================== 往来对账 ==================== */

    /**
     * 按单位汇总：应收/应付、已收/已付、未收/未付、预收/预付。
     * type=CUSTOMER 看客户，SUPPLIER 看供应商。
     */
    public List<Map<String, Object>> partners(String type) {
        boolean ar = type == null || !"SUPPLIER".equalsIgnoreCase(type);
        List<OpenRow> rows = openRows(ar);
        String dir = ar ? ErpPayment.DIR_IN : ErpPayment.DIR_OUT;

        Map<Long, BigDecimal[]> agg = new LinkedHashMap<>();   // 0=billed 1=paid
        Map<Long, String> names = new LinkedHashMap<>();
        for (OpenRow r : rows) {
            if (r.partnerId() == null) {
                continue;
            }
            BigDecimal[] v = agg.computeIfAbsent(r.partnerId(), k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            v[0] = v[0].add(r.billed());
            v[1] = v[1].add(r.paid());
            names.put(r.partnerId(), r.partnerName());
        }
        Map<Long, BigDecimal> pre = new HashMap<>();
        for (Object[] o : payRepo.sumUnallocatedByPartner(dir)) {
            pre.put(((Number) o[0]).longValue(), new BigDecimal(String.valueOf(o[1])));
        }

        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<Long, BigDecimal[]> e : agg.entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("partnerId", e.getKey());
            m.put("partnerName", names.get(e.getKey()));
            m.put("billed", scale(e.getValue()[0]));
            m.put("paid", scale(e.getValue()[1]));
            m.put("outstanding", scale(e.getValue()[0].subtract(e.getValue()[1]).max(BigDecimal.ZERO)));
            m.put("prepaid", scale(pre.getOrDefault(e.getKey(), BigDecimal.ZERO)));
            out.add(m);
        }
        out.sort(Comparator.comparing((Map<String, Object> m) -> (BigDecimal) m.get("outstanding")).reversed());
        return out;
    }

    /* ==================== 内部 ==================== */

    /**
     * 打开的单据行（应收或应付）。
     * 只算"已经发生"的部分：销售看已发货、采购看已入库；草稿/取消不算。
     */
    private List<OpenRow> openRows(boolean salesSide) {
        LocalDate today = LocalDate.now(ZONE);
        if (salesSide) {
            List<ErpSalesOrder> orders = salesRepo.findByStatusIn(List.of(
                    ErpSalesOrder.STATUS_CONFIRMED, ErpSalesOrder.STATUS_PARTIAL, ErpSalesOrder.STATUS_DONE));
            if (orders.isEmpty()) {
                return List.of();
            }
            List<Long> ids = orders.stream().map(ErpSalesOrder::getId).toList();
            Map<Long, BigDecimal> billed = new HashMap<>();
            for (Object[] r : salesItemRepo.sumShippedAmountByOrderIds(ids)) {
                billed.put(((Number) r[0]).longValue(), dec(r[1]));
            }
            Map<String, BigDecimal> paid = paidMap(orders.stream().map(ErpSalesOrder::getOrderNo).toList(),
                    ErpPayment.DIR_IN);
            List<OpenRow> out = new ArrayList<>();
            for (ErpSalesOrder o : orders) {
                BigDecimal b = billed.getOrDefault(o.getId(), BigDecimal.ZERO);
                if (b.compareTo(EPS) <= 0) {
                    continue;                       // 还没发货，不构成应收
                }
                int days = daysBetween(o.getOrderDate(), today);
                out.add(new OpenRow(o.getOrderNo(), o.getCustomerId(), o.getCustomerName(),
                        o.getOrderDate(), nz(o.getTotalAmount()), b,
                        paid.getOrDefault(o.getOrderNo(), BigDecimal.ZERO), days, bucket(days)));
            }
            return out;
        }

        List<ErpPurchaseOrder> orders = purchaseRepo.findByStatusIn(List.of(
                ErpPurchaseOrder.STATUS_CONFIRMED, ErpPurchaseOrder.STATUS_PARTIAL,
                ErpPurchaseOrder.STATUS_DONE));
        if (orders.isEmpty()) {
            return List.of();
        }
        List<Long> ids = orders.stream().map(ErpPurchaseOrder::getId).toList();
        Map<Long, BigDecimal> billed = new HashMap<>();
        for (Object[] r : purchaseItemRepo.sumReceivedAmountByOrderIds(ids)) {
            billed.put(((Number) r[0]).longValue(), dec(r[1]));
        }
        Map<String, BigDecimal> paid = paidMap(orders.stream().map(ErpPurchaseOrder::getOrderNo).toList(),
                ErpPayment.DIR_OUT);
        List<OpenRow> out = new ArrayList<>();
        for (ErpPurchaseOrder o : orders) {
            BigDecimal b = billed.getOrDefault(o.getId(), BigDecimal.ZERO);
            if (b.compareTo(EPS) <= 0) {
                continue;                           // 还没入库，不构成应付
            }
            int days = daysBetween(o.getOrderDate(), today);
            out.add(new OpenRow(o.getOrderNo(), o.getSupplierId(), o.getSupplierName(),
                    o.getOrderDate(), nz(o.getTotalAmount()), b,
                    paid.getOrDefault(o.getOrderNo(), BigDecimal.ZERO), days, bucket(days)));
        }
        return out;
    }

    private Map<String, BigDecimal> paidMap(List<String> orderNos, String dir) {
        Map<String, BigDecimal> map = new HashMap<>();
        if (orderNos.isEmpty()) {
            return map;
        }
        for (Object[] r : payRepo.sumByOrders(orderNos, dir)) {
            map.put(String.valueOf(r[0]), dec(r[1]));
        }
        return map;
    }

    private List<Map<String, Object>> agingOf(List<OpenRow> rows) {
        String[] buckets = {"0-30", "31-60", "61-90", "90+"};
        Map<String, BigDecimal> sum = new LinkedHashMap<>();
        for (String b : buckets) {
            sum.put(b, BigDecimal.ZERO);
        }
        for (OpenRow r : rows) {
            sum.merge(r.bucket(), r.outstanding(), BigDecimal::add);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (String b : buckets) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("bucket", b);
            m.put("amount", scale(sum.get(b)));
            out.add(m);
        }
        return out;
    }

    private static int daysBetween(long fromTs, LocalDate today) {
        LocalDate d = Instant.ofEpochMilli(fromTs).atZone(ZONE).toLocalDate();
        long days = java.time.temporal.ChronoUnit.DAYS.between(d, today);
        return (int) Math.max(0, days);
    }

    private static String bucket(int days) {
        if (days <= 30) {
            return "0-30";
        }
        if (days <= 60) {
            return "31-60";
        }
        if (days <= 90) {
            return "61-90";
        }
        return "90+";
    }

    private String nextPayNo() {
        String base = "SKF" + LocalDate.now(ZONE).format(DAY) + "-";
        long n = payRepo.countByPayNoPrefix(base + "%") + 1;
        String no;
        do {
            no = base + String.format("%04d", n);
            n++;
        } while (payRepo.existsByPayNo(no));
        return no;
    }

    private static BigDecimal sum(List<OpenRow> rows, java.util.function.Function<OpenRow, BigDecimal> f) {
        BigDecimal v = BigDecimal.ZERO;
        for (OpenRow r : rows) {
            v = v.add(nz(f.apply(r)));
        }
        return v;
    }

    private static BigDecimal sumOutstanding(List<OpenRow> rows) {
        BigDecimal v = BigDecimal.ZERO;
        for (OpenRow r : rows) {
            v = v.add(r.outstanding());
        }
        return v;
    }

    private static BigDecimal sumOverdue(List<OpenRow> rows) {
        BigDecimal v = BigDecimal.ZERO;
        for (OpenRow r : rows) {
            if (r.days() > CREDIT_DAYS) {
                v = v.add(r.outstanding());
            }
        }
        return v;
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
            return LocalDate.ofInstant(Instant.ofEpochMilli(ms), ZONE)
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
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(String.valueOf(o));
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
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

    private static Map<String, Object> ok(String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("message", msg);
        return m;
    }
}
