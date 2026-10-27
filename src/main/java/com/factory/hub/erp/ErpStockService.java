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

/**
 * 出入库与库存（ROADMAP DH-43b）。
 * <p>
 * <b>三条不会破的规矩：</b>
 * <ol>
 *   <li>库存只有一个算法：{@code sum(明细行 qty) where 未作废}。没有"库存表"，也就没有
 *       "库存表和流水对不上"这种 ERP 头号事故。</li>
 *   <li>出库先查可用量，不允许把库存做成负数（负库存在车间里等于账已经废了）。
 *       调拨查源仓；销售出库/其他出库查当前仓。</li>
 *   <li>单据只作废不删除，作废前再校验一次"作废后库存不能为负"。</li>
 * </ol>
 * <p>
 * 单号规则：前缀 + 日期 + 4 位流水（CGRK20261028-0001）。生成时按"当日同前缀已有单数 + 1"，
 * 方法加 {@code synchronized}：单机部署不会有并发竞争，多实例部署要改成数据库序列。
 */
@Service
public class ErpStockService {

    private static final Logger log = LoggerFactory.getLogger(ErpStockService.class);

    private static final ZoneId ZONE = ZoneId.systemDefault();
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final BigDecimal EPS = new BigDecimal("0.0001");

    /** 单据类型中文名（顺序即前端下拉顺序） */
    public static final Map<String, String> TYPE_LABELS;
    /** 单号前缀 */
    private static final Map<String, String> TYPE_PREFIX = new HashMap<>();
    /** 手工可建的单据类型（生产入库由设备联动、盘点走专用接口） */
    public static final List<String> MANUAL_TYPES = List.of(
            ErpStockBill.TYPE_PO_IN, ErpStockBill.TYPE_SO_OUT,
            ErpStockBill.TYPE_OTHER_IN, ErpStockBill.TYPE_OTHER_OUT,
            ErpStockBill.TYPE_TRANSFER, ErpStockBill.TYPE_PO_RETURN);

    /**
     * 库存页「新建单据」能选的类型：采购退货必须从采购订单发起（这样订单进度才对得上），
     * 所以不出现在这里，但仍在 MANUAL_TYPES 里供采购服务内部生成。
     */
    public static final List<String> MANUAL_UI_TYPES = List.of(
            ErpStockBill.TYPE_PO_IN, ErpStockBill.TYPE_SO_OUT,
            ErpStockBill.TYPE_OTHER_IN, ErpStockBill.TYPE_OTHER_OUT,
            ErpStockBill.TYPE_TRANSFER);


    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(ErpStockBill.TYPE_PO_IN, "采购入库");
        m.put(ErpStockBill.TYPE_SO_OUT, "销售出库");
        m.put(ErpStockBill.TYPE_OTHER_IN, "其他入库");
        m.put(ErpStockBill.TYPE_OTHER_OUT, "其他出库");
        m.put(ErpStockBill.TYPE_TRANSFER, "调拨");
        m.put(ErpStockBill.TYPE_PO_RETURN, "采购退货");
        m.put(ErpStockBill.TYPE_STOCKTAKE, "盘点调整");
        m.put(ErpStockBill.TYPE_PROD_IN, "生产入库");
        TYPE_LABELS = java.util.Collections.unmodifiableMap(m);

        TYPE_PREFIX.put(ErpStockBill.TYPE_PO_IN, "CGRK");
        TYPE_PREFIX.put(ErpStockBill.TYPE_SO_OUT, "XSCK");
        TYPE_PREFIX.put(ErpStockBill.TYPE_OTHER_IN, "QTRK");
        TYPE_PREFIX.put(ErpStockBill.TYPE_OTHER_OUT, "QTCK");
        TYPE_PREFIX.put(ErpStockBill.TYPE_TRANSFER, "DBCK");
        TYPE_PREFIX.put(ErpStockBill.TYPE_PO_RETURN, "POTH");
        TYPE_PREFIX.put(ErpStockBill.TYPE_STOCKTAKE, "PD");
        TYPE_PREFIX.put(ErpStockBill.TYPE_PROD_IN, "SCRK");
    }

    private final ErpStockBillRepository billRepo;
    private final ErpStockBillHdrRepository hdrRepo;
    private final ErpSkuRepository skuRepo;
    private final ErpWarehouseRepository whRepo;
    private final ErpPartnerRepository partnerRepo;
    private final org.springframework.context.ApplicationEventPublisher events;

    public ErpStockService(ErpStockBillRepository billRepo, ErpStockBillHdrRepository hdrRepo,
                           ErpSkuRepository skuRepo, ErpWarehouseRepository whRepo,
                           ErpPartnerRepository partnerRepo,
                           org.springframework.context.ApplicationEventPublisher events) {
        this.billRepo = billRepo;
        this.hdrRepo = hdrRepo;
        this.skuRepo = skuRepo;
        this.whRepo = whRepo;
        this.partnerRepo = partnerRepo;
        this.events = events;
    }

    /** 一行录入：数量一律填正数，方向由单据类型决定 */
    public record LineReq(Long skuId, BigDecimal qty, BigDecimal unitPrice) {}

    /** 创建结果 */
    public record Created(String billNo, int lineCount) {}

    /* ==================== 创建单据 ==================== */

    /**
     * 手工建单（采购入库/采购退货/销售出库/其他出入库/调拨）。
     *
     * @param qtyList   行；数量必须为正，方向按 type 决定
     * @param srcOrderNo 来源采购订单号，可为 null（非采购单据）
     * @throws IllegalArgumentException 校验不通过（消息直接给用户看）
     */
    @Transactional
    public synchronized Created createManual(String type, Long partnerId, Long whId, Long srcWhId,
                                             String remark, String operator, List<LineReq> lines,
                                             String srcOrderNo) {
        if (!MANUAL_TYPES.contains(type)) {
            throw new IllegalArgumentException("不支持的单据类型：" + type);
        }
        boolean transfer = ErpStockBill.TYPE_TRANSFER.equals(type);
        boolean out = ErpStockBill.TYPE_SO_OUT.equals(type) || ErpStockBill.TYPE_OTHER_OUT.equals(type)
                || ErpStockBill.TYPE_PO_RETURN.equals(type);

        ErpWarehouse wh = whRepo.findById(whId == null ? -1L : whId)
                .orElseThrow(() -> new IllegalArgumentException("请选择仓库"));
        if (!wh.isEnabled()) {
            throw new IllegalArgumentException("仓库已停用：" + wh.getName());
        }
        ErpWarehouse srcWh = null;
        if (transfer) {
            if (srcWhId == null) {
                throw new IllegalArgumentException("调拨单必须选择源仓库");
            }
            if (srcWhId.equals(whId)) {
                throw new IllegalArgumentException("源仓库和目标仓库不能是同一个");
            }
            srcWh = whRepo.findById(srcWhId)
                    .orElseThrow(() -> new IllegalArgumentException("源仓库不存在"));
            if (!srcWh.isEnabled()) {
                throw new IllegalArgumentException("源仓库已停用：" + srcWh.getName());
            }
        }

        String partnerName = null;
        if (partnerId != null) {
            ErpPartner p = partnerRepo.findById(partnerId)
                    .orElseThrow(() -> new IllegalArgumentException("往来单位不存在"));
            partnerName = p.getName();
        }

        List<LineReq> clean = normalize(lines);
        if (clean.isEmpty()) {
            throw new IllegalArgumentException("至少录一行商品");
        }
        checkStockForOut(type, clean, transfer ? srcWhId : whId, transfer ? wh : null);

        long ts = System.currentTimeMillis();
        String billNo = nextBillNo(type);
        ErpStockBillHdr hdr = newHdr(billNo, type, ts, operator, remark);
        hdr.setPartnerId(partnerId);
        hdr.setPartnerName(partnerName);
        hdr.setSrcWhId(srcWhId);
        hdr.setDstWhId(whId);
        hdr.setSrcOrderNo(srcOrderNo);

        List<ErpStockBill> rows = new ArrayList<>();
        int lineNo = 1;
        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal totalAmount = BigDecimal.ZERO;
        for (LineReq r : clean) {
            ErpSku sku = skuRepo.findById(r.skuId())
                    .orElseThrow(() -> new IllegalArgumentException("商品不存在：" + r.skuId()));
            BigDecimal price = r.unitPrice() != null ? r.unitPrice() : defaultPrice(sku, out);
            if (transfer) {
                // 源仓出（负数） + 目标仓入（正数），两行同一单号
                rows.add(newLine(billNo, lineNo++, type, r.skuId(), srcWhId, r.qty().negate(), price, ts));
                rows.add(newLine(billNo, lineNo++, type, r.skuId(), whId, r.qty(), price, ts));
                totalQty = totalQty.add(r.qty());
            } else {
                BigDecimal signed = out ? r.qty().negate() : r.qty();
                rows.add(newLine(billNo, lineNo++, type, r.skuId(), whId, signed, price, ts));
                totalQty = totalQty.add(r.qty());
            }
            if (price != null) {
                totalAmount = totalAmount.add(r.qty().multiply(price));
            }
        }
        hdr.setTotalQty(scale(totalQty));
        hdr.setTotalAmount(scale(totalAmount));
        hdr.setLineCount(rows.size());
        hdr = hdrRepo.saveAndFlush(hdr);
        for (ErpStockBill r : rows) {
            r.setHeaderId(hdr.getId());
            r.setSrcOrderNo(srcOrderNo);
        }
        billRepo.saveAll(rows);
        log.info("新建单据 {} {} 共 {} 行，合计 {}（操作人 {}）",
                billNo, TYPE_LABELS.get(type), rows.size(), totalQty, operator);
        return new Created(billNo, rows.size());
    }

    /**
     * 盘点：只落有差异的行。实盘数传 0 也合法（盘亏到底）。
     *
     * @return 生成的单号；没有差异返回 null（不建空单）
     */
    @Transactional
    public synchronized Created createStocktake(Long whId, String operator, String remark,
                                                List<LineReq> counted) {
        ErpWarehouse wh = whRepo.findById(whId == null ? -1L : whId)
                .orElseThrow(() -> new IllegalArgumentException("请选择盘点仓库"));
        if (counted == null || counted.isEmpty()) {
            throw new IllegalArgumentException("没有可盘点的商品");
        }
        List<LineReq> diffs = new ArrayList<>();
        int lines = 0;
        for (LineReq r : counted) {
            if (r.skuId() == null || r.qty() == null) {
                continue;
            }
            lines++;
            BigDecimal system = nz(billRepo.stockOf(r.skuId(), whId));
            BigDecimal diff = r.qty().subtract(system);
            if (diff.abs().compareTo(EPS) <= 0) {
                continue;
            }
            ErpSku sku = skuRepo.findById(r.skuId()).orElse(null);
            BigDecimal price = sku == null ? null : sku.getPurchasePrice();
            diffs.add(new LineReq(r.skuId(), diff, price));
        }
        if (diffs.isEmpty()) {
            return null;
        }
        long ts = System.currentTimeMillis();
        String billNo = nextBillNo(ErpStockBill.TYPE_STOCKTAKE);
        ErpStockBillHdr hdr = newHdr(billNo, ErpStockBill.TYPE_STOCKTAKE, ts, operator,
                (remark == null || remark.isBlank() ? "" : remark + "；")
                        + "盘点差异调整（盘 " + lines + " 项，差异 " + diffs.size() + " 项）");
        hdr.setDstWhId(whId);

        List<ErpStockBill> rows = new ArrayList<>();
        int lineNo = 1;
        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal totalAmount = BigDecimal.ZERO;
        for (LineReq r : diffs) {
            rows.add(newLine(billNo, lineNo++, ErpStockBill.TYPE_STOCKTAKE, r.skuId(), whId,
                    r.qty(), r.unitPrice(), ts));
            totalQty = totalQty.add(r.qty());
            if (r.unitPrice() != null) {
                totalAmount = totalAmount.add(r.qty().multiply(r.unitPrice()));
            }
        }
        hdr.setTotalQty(scale(totalQty));
        hdr.setTotalAmount(scale(totalAmount));
        hdr.setLineCount(rows.size());
        hdr = hdrRepo.saveAndFlush(hdr);
        for (ErpStockBill r : rows) {
            r.setHeaderId(hdr.getId());
        }
        billRepo.saveAll(rows);
        log.info("盘点单 {}：{} 仓，盘 {} 项，差异 {} 项（操作人 {}）",
                billNo, wh.getName(), lines, rows.size(), operator);
        return new Created(billNo, rows.size());
    }

    /**
     * 生产入库联动专用：设备计数增量 → 一张单行入库单。
     * 与手工单同一套单头，列表里能看到来源设备；失败抛异常由调用方回缓冲重试。
     */
    @Transactional
    public Created createProdInbound(String deviceCode, Long skuId, Long whId, BigDecimal qty,
                                     boolean backfill) {
        long ts = System.currentTimeMillis();
        String billNo = nextBillNo(ErpStockBill.TYPE_PROD_IN);
        ErpStockBillHdr hdr = newHdr(billNo, ErpStockBill.TYPE_PROD_IN, ts, "system",
                backfill ? "断线补记" : null);
        hdr.setDeviceCode(deviceCode);
        hdr.setDstWhId(whId);
        ErpSku sku = skuRepo.findById(skuId).orElse(null);
        BigDecimal price = sku == null ? null : sku.getPurchasePrice();

        hdr.setTotalQty(scale(qty));
        hdr.setTotalAmount(price == null ? BigDecimal.ZERO : scale(qty.multiply(price)));
        hdr.setLineCount(1);
        hdr = hdrRepo.saveAndFlush(hdr);

        ErpStockBill line = newLine(billNo, 1, ErpStockBill.TYPE_PROD_IN, skuId, whId,
                qty, price, ts);
        line.setHeaderId(hdr.getId());
        line.setDeviceCode(deviceCode);
        billRepo.save(line);
        return new Created(billNo, 1);
    }

    /* ==================== 作废 ==================== */

    /**
     * 作废整单：不删数据，只打时间戳。
     * <p>
     * 作废前把"作废后库存"再算一遍：若某仓某商品会变成负数，说明这批货已经被用掉了，
     * 不允许作废——正确做法是先做一笔反向调整单。
     */
    @Transactional
    public void voidBill(String billNo, String operator) {
        ErpStockBillHdr hdr = hdrRepo.findByBillNo(billNo)
                .orElseThrow(() -> new IllegalArgumentException("单据不存在：" + billNo));
        if (hdr.isVoided()) {
            throw new IllegalArgumentException("单据已作废，无需重复操作");
        }
        List<ErpStockBill> lines = billRepo.findByBillNoOrderByLineNoAsc(billNo);
        Map<String, BigDecimal> delta = new LinkedHashMap<>();
        Map<String, String> desc = new LinkedHashMap<>();
        for (ErpStockBill l : lines) {
            String key = l.getSkuId() + "#" + l.getWarehouseId();
            delta.merge(key, l.getQty(), BigDecimal::add);
            desc.putIfAbsent(key, "「" + skuLabel(l.getSkuId()) + "」在「" + whLabel(l.getWarehouseId()) + "」");
        }
        for (Map.Entry<String, BigDecimal> e : delta.entrySet()) {
            String[] k = e.getKey().split("#");
            BigDecimal stock = nz(billRepo.stockOf(Long.valueOf(k[0]), Long.valueOf(k[1])));
            BigDecimal after = stock.subtract(e.getValue());
            if (after.signum() < 0) {
                throw new IllegalArgumentException("不能作废：" + desc.get(e.getKey())
                        + " 作废后库存为 " + after.stripTrailingZeros().toPlainString()
                        + "，请先做一笔反向调整单");
            }
        }
        long now = System.currentTimeMillis();
        hdr.setVoidedAt(now);
        hdr.setVoidedBy(operator);
        hdrRepo.save(hdr);
        int n = billRepo.voidByBillNo(billNo, now, operator);
        log.info("作废单据 {}（{} 行，操作人 {}）", billNo, n, operator);
        // 关联了采购订单的单据：通知采购重算进度（同步、同事务，作废行已被排除）
        if (hdr.getSrcOrderNo() != null) {
            events.publishEvent(new StockBillVoidedEvent(billNo, hdr.getBillType(), hdr.getSrcOrderNo()));
        }
    }

    /* ==================== 查询 ==================== */

    /** 库存余额：只列有流水的 SKU×仓库 组合，按数量归零的行不显示 */
    public List<Map<String, Object>> balances(Long whId, String q) {
        Map<Long, ErpSku> skus = new HashMap<>();
        for (ErpSku s : skuRepo.findAll()) {
            skus.put(s.getId(), s);
        }
        Map<Long, ErpWarehouse> whs = new HashMap<>();
        for (ErpWarehouse w : whRepo.findAll()) {
            whs.put(w.getId(), w);
        }
        String kw = q == null ? "" : q.trim().toLowerCase();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] a : billRepo.aggregateStock()) {
            Long skuId = ((Number) a[0]).longValue();
            Long wId = ((Number) a[1]).longValue();
            BigDecimal qty = dec(a[2]);
            if (qty.abs().compareTo(EPS) <= 0) {
                continue;
            }
            if (whId != null && !whId.equals(wId)) {
                continue;
            }
            ErpSku s = skus.get(skuId);
            if (s == null) {
                continue;
            }
            if (!kw.isEmpty()) {
                String hay = (s(s.getCode()) + " " + s(s.getName()) + " " + s(s.getSpec())
                        + " " + s(s.getCategory())).toLowerCase();
                if (!hay.contains(kw)) {
                    continue;
                }
            }
            BigDecimal cost = s.getPurchasePrice() == null ? BigDecimal.ZERO : s.getPurchasePrice();
            BigDecimal amount = qty.multiply(cost);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("skuId", skuId);
            m.put("code", s.getCode());
            m.put("name", s.getName());
            m.put("spec", s.getSpec());
            m.put("category", s.getCategory());
            m.put("unit", s.getUnit());
            m.put("warehouseId", wId);
            m.put("warehouseName", whs.containsKey(wId) ? whs.get(wId).getName() : "仓库#" + wId);
            m.put("qty", scale(qty));
            m.put("unitCost", scale(cost));
            m.put("amount", scale(amount));
            m.put("minStock", s.getMinStock());
            m.put("maxStock", s.getMaxStock());
            out.add(m);
        }
        out.sort(Comparator.comparing((Map<String, Object> m) -> String.valueOf(m.get("code")))
                .thenComparing(m -> String.valueOf(m.get("warehouseName"))));
        return out;
    }

    /**
     * 库存预警：按 SKU 全厂合计比上下限（不是按单仓），
     * 否则多仓企业每个仓都会报一次"低于下限"，实际总库存是够的。
     */
    public List<Map<String, Object>> alerts() {
        Map<Long, BigDecimal> total = new HashMap<>();
        Map<Long, List<Object[]>> byWh = new HashMap<>();
        Map<Long, ErpWarehouse> whs = new HashMap<>();
        for (ErpWarehouse w : whRepo.findAll()) {
            whs.put(w.getId(), w);
        }
        for (Object[] a : billRepo.aggregateStock()) {
            Long skuId = ((Number) a[0]).longValue();
            Long wId = ((Number) a[1]).longValue();
            BigDecimal qty = dec(a[2]);
            total.merge(skuId, qty, BigDecimal::add);
            byWh.computeIfAbsent(skuId, k -> new ArrayList<>()).add(new Object[]{wId, qty});
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (ErpSku s : skuRepo.findAll()) {
            if (!s.isEnabled()) {
                continue;
            }
            BigDecimal t = nz(total.get(s.getId()));
            String status = "";
            BigDecimal gap = BigDecimal.ZERO;
            if (s.getMinStock() != null && t.compareTo(s.getMinStock()) < 0) {
                status = "LOW";
                gap = s.getMinStock().subtract(t);
            } else if (s.getMaxStock() != null && t.compareTo(s.getMaxStock()) > 0) {
                status = "HIGH";
                gap = t.subtract(s.getMaxStock());
            }
            if (status.isEmpty()) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("skuId", s.getId());
            m.put("code", s.getCode());
            m.put("name", s.getName());
            m.put("spec", s.getSpec());
            m.put("unit", s.getUnit());
            m.put("total", scale(t));
            m.put("minStock", s.getMinStock());
            m.put("maxStock", s.getMaxStock());
            m.put("status", status);
            m.put("gap", scale(gap));
            List<Map<String, Object>> whList = new ArrayList<>();
            for (Object[] w : byWh.getOrDefault(s.getId(), List.of())) {
                Long wId = (Long) w[0];
                BigDecimal qty = (BigDecimal) w[1];
                if (qty.abs().compareTo(EPS) <= 0) {
                    continue;
                }
                Map<String, Object> wm = new LinkedHashMap<>();
                wm.put("warehouseId", wId);
                wm.put("warehouseName", whs.containsKey(wId) ? whs.get(wId).getName() : "仓库#" + wId);
                wm.put("qty", scale(qty));
                whList.add(wm);
            }
            m.put("byWarehouse", whList);
            out.add(m);
        }
        out.sort(Comparator.comparing((Map<String, Object> m) -> "LOW".equals(m.get("status")) ? 0 : 1)
                .thenComparing((Map<String, Object> m) -> ((BigDecimal) m.get("gap")).negate()));
        return out;
    }

    /** KPI 汇总：库存总量/成本、今日本月出入库、预警数、单据数 */
    public Map<String, Object> summary() {
        long now = System.currentTimeMillis();
        long todayStart = LocalDate.now(ZONE).atStartOfDay(ZONE).toInstant().toEpochMilli();
        long monthStart = LocalDate.now(ZONE).withDayOfMonth(1)
                .atStartOfDay(ZONE).toInstant().toEpochMilli();

        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal totalValue = BigDecimal.ZERO;
        int skuCount = 0;
        java.util.Set<Long> skuSet = new java.util.HashSet<>();
        for (Map<String, Object> b : balances(null, null)) {
            BigDecimal qty = (BigDecimal) b.get("qty");
            totalQty = totalQty.add(qty);
            totalValue = totalValue.add((BigDecimal) b.get("amount"));
            skuSet.add((Long) b.get("skuId"));
            skuCount++;
        }

        BigDecimal todayIn = nz(billRepo.sumInSince(todayStart));
        BigDecimal todayOut = nz(billRepo.sumOutSince(todayStart)).negate();
        BigDecimal monthIn = nz(billRepo.sumInSince(monthStart));
        BigDecimal monthOut = nz(billRepo.sumOutSince(monthStart)).negate();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("skuCount", skuSet.size());
        out.put("stockRowCount", skuCount);
        out.put("whCount", whRepo.count());
        out.put("totalQty", scale(totalQty));
        out.put("totalValue", scale(totalValue));
        out.put("alertCount", alerts().size());
        out.put("todayIn", scale(todayIn));
        out.put("todayOut", scale(todayOut));
        out.put("monthIn", scale(monthIn));
        out.put("monthOut", scale(monthOut));
        out.put("todayBills", hdrRepo.countByBillTypeNotAndVoidedAtIsNullAndCreatedAtGreaterThanEqual(
                ErpStockBill.TYPE_PROD_IN, todayStart));
        out.put("prodToday", hdrRepo.countByBillTypeAndCreatedAtGreaterThanEqual(
                ErpStockBill.TYPE_PROD_IN, todayStart));
        out.put("at", now);
        return out;
    }

    /** 近 N 天出入库趋势（按自然日） */
    public List<Map<String, Object>> trend(int days) {
        int n = Math.max(1, Math.min(120, days));
        LocalDate from = LocalDate.now(ZONE).minusDays(n - 1L);
        long fromTs = from.atStartOfDay(ZONE).toInstant().toEpochMilli();
        Map<String, Object[]> got = new HashMap<>();
        for (Object[] r : billRepo.dailyInOutSince(fromTs)) {
            got.put(String.valueOf(r[0]), r);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            LocalDate d = from.plusDays(i);
            String key = d.toString();
            Object[] r = got.get(key);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", d.format(DateTimeFormatter.ofPattern("MM-dd")));
            m.put("in", r == null ? BigDecimal.ZERO : dec(r[1]));
            m.put("out", r == null ? BigDecimal.ZERO : dec(r[2]));
            out.add(m);
        }
        return out;
    }

    /** 单据列表（分页） */
    public Map<String, Object> bills(String type, Long fromTs, Long toTs, boolean withProd,
                                     String kw, int page, int size) {
        int p = Math.max(1, page);
        int s = Math.max(1, Math.min(200, size));
        String k = kw == null || kw.trim().isEmpty() ? ""
                : "%" + kw.trim().toLowerCase() + "%";
        var pageable = org.springframework.data.domain.PageRequest.of(p - 1, s,
                org.springframework.data.domain.Sort.by(
                        org.springframework.data.domain.Sort.Direction.DESC, "createdAt")
                        .and(org.springframework.data.domain.Sort.by(
                                org.springframework.data.domain.Sort.Direction.DESC, "id")));
        var res = hdrRepo.search(type == null || type.isBlank() ? "" : type,
                fromTs == null ? 0L : fromTs, toTs == null ? 0L : toTs,
                // 明确筛生产入库时当然要显示，不能又被"隐藏生产单"的默认口径过滤掉
                withProd || ErpStockBill.TYPE_PROD_IN.equals(type), k, pageable);

        Map<Long, String> whNames = new HashMap<>();
        for (ErpWarehouse w : whRepo.findAll()) {
            whNames.put(w.getId(), w.getName());
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ErpStockBillHdr h : res.getContent()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("billNo", h.getBillNo());
            m.put("type", h.getBillType());
            m.put("typeLabel", TYPE_LABELS.getOrDefault(h.getBillType(), h.getBillType()));
            m.put("partnerName", h.getPartnerName());
            m.put("srcWarehouseName", h.getSrcWhId() == null ? null : whNames.get(h.getSrcWhId()));
            m.put("warehouseName", h.getDstWhId() == null ? null : whNames.get(h.getDstWhId()));
            m.put("deviceCode", h.getDeviceCode());
            m.put("srcOrderNo", h.getSrcOrderNo());
            m.put("qty", h.getTotalQty());
            m.put("amount", h.getTotalAmount());
            m.put("lineCount", h.getLineCount());
            m.put("operator", h.getOperator());
            m.put("remark", h.getRemark());
            m.put("createdAt", h.getCreatedAt());
            m.put("voided", h.isVoided());
            m.put("voidedBy", h.getVoidedBy());
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

    /** 单据详情：单头 + 明细行（带商品/仓库名称） */
    public Map<String, Object> billDetail(String billNo) {
        ErpStockBillHdr h = hdrRepo.findByBillNo(billNo).orElse(null);
        if (h == null) {
            throw new IllegalArgumentException("单据不存在：" + billNo);
        }
        Map<Long, ErpSku> skus = new HashMap<>();
        for (ErpSku s : skuRepo.findAll()) {
            skus.put(s.getId(), s);
        }
        Map<Long, ErpWarehouse> whs = new HashMap<>();
        for (ErpWarehouse w : whRepo.findAll()) {
            whs.put(w.getId(), w);
        }
        List<Map<String, Object>> lines = new ArrayList<>();
        for (ErpStockBill b : billRepo.findByBillNoOrderByLineNoAsc(billNo)) {
            ErpSku s = skus.get(b.getSkuId());
            ErpWarehouse w = whs.get(b.getWarehouseId());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("lineNo", b.getLineNo());
            m.put("skuId", b.getSkuId());
            m.put("skuCode", s == null ? ("商品#" + b.getSkuId()) : s.getCode());
            m.put("skuName", s == null ? "" : s.getName());
            m.put("spec", s == null ? "" : s.getSpec());
            m.put("unit", s == null ? "" : s.getUnit());
            m.put("warehouseName", w == null ? ("仓库#" + b.getWarehouseId()) : w.getName());
            m.put("qty", scale(b.getQty()));
            m.put("unitPrice", b.getUnitPrice() == null ? null : scale(b.getUnitPrice()));
            m.put("amount", b.getUnitPrice() == null ? null
                    : scale(b.getQty().multiply(b.getUnitPrice())));
            lines.add(m);
        }
        Map<String, Object> head = new LinkedHashMap<>();
        head.put("billNo", h.getBillNo());
        head.put("type", h.getBillType());
        head.put("typeLabel", TYPE_LABELS.getOrDefault(h.getBillType(), h.getBillType()));
        head.put("partnerName", h.getPartnerName());
        head.put("srcWarehouseName", h.getSrcWhId() == null ? null
                : whs.containsKey(h.getSrcWhId()) ? whs.get(h.getSrcWhId()).getName() : null);
        head.put("warehouseName", h.getDstWhId() == null ? null
                : whs.containsKey(h.getDstWhId()) ? whs.get(h.getDstWhId()).getName() : null);
        head.put("deviceCode", h.getDeviceCode());
        head.put("srcOrderNo", h.getSrcOrderNo());
        head.put("operator", h.getOperator());
        head.put("remark", h.getRemark());
        head.put("totalQty", h.getTotalQty());
        head.put("totalAmount", h.getTotalAmount());
        head.put("createdAt", h.getCreatedAt());
        head.put("voided", h.isVoided());
        head.put("voidedBy", h.getVoidedBy());
        head.put("voidedAt", h.getVoidedAt());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("bill", head);
        out.put("lines", lines);
        return out;
    }

    /** 库存流水：倒序取 N 条，结存从当前库存往回推 */
    public List<Map<String, Object>> flow(Long skuId, Long whId, int limit) {
        int n = Math.max(1, Math.min(300, limit));
        List<ErpStockBill> rows = billRepo.flowOf(skuId, whId,
                org.springframework.data.domain.PageRequest.of(0, n));
        Map<Long, String> whNames = new HashMap<>();
        for (ErpWarehouse w : whRepo.findAll()) {
            whNames.put(w.getId(), w.getName());
        }
        BigDecimal running = nz(billRepo.stockOf(skuId, whId));
        List<Map<String, Object>> out = new ArrayList<>();
        for (ErpStockBill b : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("createdAt", b.getCreatedAt());
            m.put("billNo", b.getBillNo());
            m.put("type", b.getBillType());
            m.put("typeLabel", TYPE_LABELS.getOrDefault(b.getBillType(), b.getBillType()));
            m.put("warehouseName", whNames.get(b.getWarehouseId()));
            m.put("qty", scale(b.getQty()));
            m.put("balance", scale(running));
            m.put("deviceCode", b.getDeviceCode());
            out.add(m);
            running = running.subtract(b.getQty());
        }
        return out;
    }

    /* ==================== 历史单头回填 ==================== */

    /**
     * 升级前生产入库是"一行即一单"、没有单头。启动时按单号补出单头并把行挂上去，
     * 幂等：只处理 header_id 为空的行；已有单头的只补 header_id。
     *
     * @return 本次补齐的单据数
     */
    @Transactional
    public int backfillHeaders() {
        List<String> orphans = billRepo.findOrphanBillNos();
        if (orphans.isEmpty()) {
            return 0;
        }
        int cap = 5000;
        boolean capped = orphans.size() > cap;
        if (capped) {
            log.warn("待回填单头的历史单据 {} 张，本次先处理 {} 张（下次启动继续）", orphans.size(), cap);
            orphans = orphans.subList(0, cap);
        }
        int done = 0;
        for (String no : orphans) {
            List<ErpStockBill> lines = billRepo.findByBillNoOrderByLineNoAsc(no);
            if (lines.isEmpty()) {
                continue;
            }
            ErpStockBillHdr h = hdrRepo.findByBillNo(no).orElse(null);
            if (h == null) {
                ErpStockBill first = lines.get(0);
                h = newHdr(no, first.getBillType(), first.getCreatedAt(), "system", null);
                h.setDeviceCode(first.getDeviceCode());
                h.setDstWhId(first.getWarehouseId());
                BigDecimal qty = BigDecimal.ZERO;
                BigDecimal amount = BigDecimal.ZERO;
                for (ErpStockBill l : lines) {
                    qty = qty.add(l.getQty());
                    if (l.getUnitPrice() != null) {
                        amount = amount.add(l.getQty().multiply(l.getUnitPrice()));
                    }
                }
                h.setTotalQty(scale(qty));
                h.setTotalAmount(scale(amount));
                h.setLineCount(lines.size());
                h = hdrRepo.save(h);
            }
            for (ErpStockBill l : lines) {
                if (l.getHeaderId() == null) {
                    l.setHeaderId(h.getId());
                }
            }
            billRepo.saveAll(lines);
            done++;
        }
        log.info("历史单据单头回填完成：{} 张", done);
        return done;
    }

    /* ==================== 内部工具 ==================== */

    private ErpStockBillHdr newHdr(String billNo, String type, long ts, String operator, String remark) {
        ErpStockBillHdr h = new ErpStockBillHdr();
        h.setBillNo(billNo);
        h.setBillType(type);
        h.setCreatedAt(ts);
        h.setOperator(operator == null || operator.isBlank() ? "system" : operator);
        h.setRemark(remark);
        return h;
    }

    private ErpStockBill newLine(String billNo, int lineNo, String type, Long skuId, Long whId,
                                 BigDecimal qty, BigDecimal price, long ts) {
        ErpStockBill b = new ErpStockBill();
        b.setBillNo(billNo);
        b.setLineNo(lineNo);
        b.setBillType(type);
        b.setSkuId(skuId);
        b.setWarehouseId(whId);
        b.setQty(scale(qty));
        b.setUnitPrice(price == null ? null : scale(price));
        b.setCreatedAt(ts);
        return b;
    }

    /** 去重 + 校验：数量必须为正、同一张单不重复同一商品 */
    private List<LineReq> normalize(List<LineReq> lines) {
        List<LineReq> out = new ArrayList<>();
        java.util.Set<Long> seen = new java.util.HashSet<>();
        if (lines == null) {
            return out;
        }
        for (LineReq r : lines) {
            if (r == null || r.skuId() == null) {
                throw new IllegalArgumentException("有一行没选商品");
            }
            if (r.qty() == null || r.qty().signum() <= 0) {
                throw new IllegalArgumentException("数量必须大于 0");
            }
            if (!seen.add(r.skuId())) {
                ErpSku s = skuRepo.findById(r.skuId()).orElse(null);
                throw new IllegalArgumentException("同一张单里商品重复："
                        + (s == null ? r.skuId() : s.getName()) + "，请合并成一行");
            }
            out.add(new LineReq(r.skuId(), scale(r.qty()),
                    r.unitPrice() == null ? null : scale(r.unitPrice())));
        }
        return out;
    }

    /** 出库/调拨前查可用量，负数库存直接拒绝并说清差多少 */
    private void checkStockForOut(String type, List<LineReq> lines, Long whId, ErpWarehouse wh) {
        boolean out = ErpStockBill.TYPE_SO_OUT.equals(type) || ErpStockBill.TYPE_OTHER_OUT.equals(type);
        boolean transfer = ErpStockBill.TYPE_TRANSFER.equals(type);
        if (!out && !transfer) {
            return;
        }
        for (LineReq r : lines) {
            BigDecimal stock = nz(billRepo.stockOf(r.skuId(), whId));
            if (stock.compareTo(r.qty()) < 0) {
                ErpSku s = skuRepo.findById(r.skuId()).orElse(null);
                String name = s == null ? ("商品#" + r.skuId()) : s.getName();
                String whName = wh != null ? wh.getName() : whLabel(whId);
                throw new IllegalArgumentException("库存不足：" + name + " 在「" + whName
                        + "」现有 " + stock.stripTrailingZeros().toPlainString()
                        + "，本次需要 " + r.qty().stripTrailingZeros().toPlainString());
            }
        }
    }

    private BigDecimal defaultPrice(ErpSku s, boolean out) {
        if (out && s.getSalePrice() != null) {
            return s.getSalePrice();
        }
        return s.getPurchasePrice() != null ? s.getPurchasePrice() : s.getSalePrice();
    }

    private String nextBillNo(String type) {
        String prefix = TYPE_PREFIX.getOrDefault(type, "BILL");
        String base = prefix + LocalDate.now(ZONE).format(DAY) + "-";
        long n = billRepo.countByBillNoPrefix(base + "%") + 1;
        String no;
        do {
            no = base + String.format("%04d", n);
            n++;
        } while (hdrRepo.existsByBillNo(no));
        return no;
    }

    private String skuLabel(Long skuId) {
        return skuRepo.findById(skuId).map(ErpSku::getName).orElse("商品#" + skuId);
    }

    private String whLabel(Long whId) {
        return whRepo.findById(whId).map(ErpWarehouse::getName).orElse("仓库#" + whId);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String s(String v) {
        return v == null ? "" : v;
    }

    private static BigDecimal scale(BigDecimal v) {
        return nz(v).setScale(4, RoundingMode.HALF_UP);
    }

    /** 原生查询返回的数字可能是 BigDecimal / Double / byte[]，统一收敛成 BigDecimal */
    private static BigDecimal dec(Object v) {
        if (v == null) {
            return BigDecimal.ZERO;
        }
        if (v instanceof BigDecimal b) {
            return scale(b);
        }
        if (v instanceof Number n) {
            return scale(new BigDecimal(n.toString()));
        }
        if (v instanceof byte[] bytes) {
            return scale(new BigDecimal(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)));
        }
        try {
            return scale(new BigDecimal(String.valueOf(v).trim()));
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    /** 单据类型下拉（前端直接用，避免两边各写一份中文名） */
    public static List<Map<String, String>> typeOptions() {
        List<Map<String, String>> out = new ArrayList<>();
        for (Map.Entry<String, String> e : TYPE_LABELS.entrySet()) {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("value", e.getKey());
            m.put("label", e.getValue());
            out.add(m);
        }
        return out;
    }

    /** 时间戳 → 本地日期（供其它模块复用） */
    public static LocalDate dayOf(long ts) {
        return Instant.ofEpochMilli(ts).atZone(ZONE).toLocalDate();
    }
}
