package com.factory.hub.erp.stock;

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
import java.util.concurrent.ConcurrentHashMap;
import com.factory.hub.erp.ErpPartner;
import com.factory.hub.erp.ErpPartnerRepository;
import com.factory.hub.erp.ErpSku;
import com.factory.hub.erp.ErpSkuRepository;
import com.factory.hub.erp.ErpWarehouse;
import com.factory.hub.erp.ErpWarehouseRepository;
import com.factory.hub.erp.StockBillVoidedEvent;

/**
 * 出入库与库存（ROADMAP DH-43b）。
 * <p>
 * <b>三条不会破的规矩：</b>
 * <ol>
 *   <li>库存只有一个算法：{@code sum(明细行 qty) where 未作废}。没有"库存表"，也就没有
 *       "库存表和流水对不上"这种 ERP 头号事故。
 *       <b>「未过账增量（pending）」不是库存，不进任何余额口径</b>，
 *       只作提示单列（{@code balances} 的 {@code pendingQty} /
 *       {@code summary} 的 {@code pendingTotal}）——见 {@link ErpStockBillRepository#aggregateStock()}。</li>
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
            ErpStockBill.TYPE_TRANSFER, ErpStockBill.TYPE_PO_RETURN, ErpStockBill.TYPE_SO_RETURN,
            ErpStockBill.TYPE_MATERIAL_OUT, ErpStockBill.TYPE_SCRAP_OUT);

    /**
     * 方向判定：这张单是不是"货从仓库出去"（qty 记为负数）。
     * <p>
     * <b>为什么收敛成一个方法</b>：出库白名单原来在 {@code createManual} 与
     * {@code checkStockForOut} 里各写了一份。两份必然漂移——新增一个出库类型（比如报废出库），
     * 只改了一处，另一处就当它是入库：qty 变正数（库存凭空涨一笔），
     * 并且因为"出库才查可用量"这条判断没命中，还能开出比库存还多的数，
     * <b>整个过程一个异常都不报</b>。本项目已经在采购退货和生产领料上各踩过一次。
     * 现在只剩这一处，新增类型只改这里（并由 {@code ErpStockOutboundTest} 遍历
     * 所有 {@code ErpStockBill.TYPE_*} 常量兜底：漏登记会立刻红）。
     * <p>
     * <b>调拨与盘点不算在内</b>：调拨是"源仓负数行 + 目标仓正数行"，方向由行自己带；
     * 盘点是按差异正负落行，两者都不走"整单一个方向"这条路。
     */
    public static boolean isOutbound(String type) {
        return ErpStockBill.TYPE_SO_OUT.equals(type)
                || ErpStockBill.TYPE_OTHER_OUT.equals(type)
                // 采购退货也是出库（退给供应商，货要离开库存）。
                // 原来漏在这里，于是采购退货能把库存做成负数还不报错——账实从这儿开始对不上。
                || ErpStockBill.TYPE_PO_RETURN.equals(type)
                // 生产领料同样是出库：料从仓库领到车间，库存必须真的减。
                // 漏了它，领料单能开出比库存还多的数（DH-70 的坑就在这儿）。
                || ErpStockBill.TYPE_MATERIAL_OUT.equals(type)
                // 报废出库：不良品处置产生，货真的没了，必须扣所在仓（QC-SPEC §3.4）
                || ErpStockBill.TYPE_SCRAP_OUT.equals(type);
    }

    /**
     * 库存页「新建单据」能选的类型：采购退货必须从采购订单发起（这样订单进度才对得上），
     * 生产领料必须从工单发起（这样需求量与已领量才对得上），所以都不出现在这里，
     * 但仍在 MANUAL_TYPES 里供各服务内部生成。
     */
    public static final List<String> MANUAL_UI_TYPES = List.of(
            ErpStockBill.TYPE_PO_IN, ErpStockBill.TYPE_SO_OUT,
            ErpStockBill.TYPE_OTHER_IN, ErpStockBill.TYPE_OTHER_OUT,
            ErpStockBill.TYPE_TRANSFER);


    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(ErpStockBill.TYPE_PO_IN, "采购入库");
        m.put(ErpStockBill.TYPE_SO_OUT, "销售出库");
        m.put(ErpStockBill.TYPE_SO_RETURN, "销售退货");
        m.put(ErpStockBill.TYPE_OTHER_IN, "其他入库");
        m.put(ErpStockBill.TYPE_OTHER_OUT, "其他出库");
        m.put(ErpStockBill.TYPE_TRANSFER, "调拨");
        m.put(ErpStockBill.TYPE_PO_RETURN, "采购退货");
        m.put(ErpStockBill.TYPE_STOCKTAKE, "盘点调整");
        m.put(ErpStockBill.TYPE_PROD_IN, "生产入库");
        m.put(ErpStockBill.TYPE_MATERIAL_OUT, "生产领料");
        m.put(ErpStockBill.TYPE_SCRAP_OUT, "报废出库");
        TYPE_LABELS = java.util.Collections.unmodifiableMap(m);

        TYPE_PREFIX.put(ErpStockBill.TYPE_PO_IN, "CGRK");
        TYPE_PREFIX.put(ErpStockBill.TYPE_SO_OUT, "XSCK");
        TYPE_PREFIX.put(ErpStockBill.TYPE_SO_RETURN, "XSTH");
        TYPE_PREFIX.put(ErpStockBill.TYPE_OTHER_IN, "QTRK");
        TYPE_PREFIX.put(ErpStockBill.TYPE_OTHER_OUT, "QTCK");
        TYPE_PREFIX.put(ErpStockBill.TYPE_TRANSFER, "DBCK");
        TYPE_PREFIX.put(ErpStockBill.TYPE_PO_RETURN, "POTH");
        TYPE_PREFIX.put(ErpStockBill.TYPE_STOCKTAKE, "PD");
        TYPE_PREFIX.put(ErpStockBill.TYPE_PROD_IN, "SCRK");
        TYPE_PREFIX.put(ErpStockBill.TYPE_MATERIAL_OUT, "SCLL");
        TYPE_PREFIX.put(ErpStockBill.TYPE_SCRAP_OUT, "BFCK");
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
    public Created createManual(String type, Long partnerId, Long whId, Long srcWhId,
                                String remark, String operator, List<LineReq> lines,
                                String srcOrderNo) {
        return createManual(type, partnerId, whId, srcWhId, remark, operator, lines, srcOrderNo, null);
    }

    /**
     * 同上，额外允许指定<b>业务日期</b>（补录历史单据用）。
     * 报表按 {@code coalesce(biz_date, created_at)} 统计，所以补录不会把数字算到今天。
     */
    @Transactional
    public synchronized Created createManual(String type, Long partnerId, Long whId, Long srcWhId,
                                             String remark, String operator, List<LineReq> lines,
                                             String srcOrderNo, Long bizDate) {
        if (!MANUAL_TYPES.contains(type)) {
            throw new IllegalArgumentException("不支持的单据类型：" + type);
        }
        boolean transfer = ErpStockBill.TYPE_TRANSFER.equals(type);
        boolean out = isOutbound(type);

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
            BigDecimal price = r.unitPrice() != null ? r.unitPrice() : defaultPrice(sku, type);
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
            r.setBizDate(bizDate);
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
        // 按 SKU 排序再逐个加锁：所有请求用同一个加锁顺序，避免死锁
        List<LineReq> sortedCounted = new ArrayList<>(counted);
        sortedCounted.sort(Comparator.comparing(r -> r.skuId() == null ? Long.MIN_VALUE : r.skuId()));
        for (LineReq r : sortedCounted) {
            if (r.skuId() == null || r.qty() == null) {
                continue;
            }
            lines++;
            // 带锁读：盘点的同时有人在发货，差异常常算错
            BigDecimal system = nz(billRepo.stockOfForUpdate(r.skuId(), whId));
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
        return createProdInbound(deviceCode, skuId, whId, qty, backfill, null);
    }

    /**
     * 同上，额外带上工单号（DH-71）。
     * <p>
     * 工单号落在 {@code src_order_no} 上——这一列的语义本来就是「这张单是哪个来源单据产生的」，
     * 采购入库记采购单号、销售出库记销售单号、生产入库记工单号，用 bill_type 就能区分。
     * 不另开一列，是因为「来源单号」这件事在报表和单据列表里已经有一整套渲染，
     * 多一列会让「按来源单号追溯」变成两种查法。
     */
    @Transactional
    public Created createProdInbound(String deviceCode, Long skuId, Long whId, BigDecimal qty,
                                     boolean backfill, String workOrderNo) {
        long ts = System.currentTimeMillis();
        String billNo = nextBillNo(ErpStockBill.TYPE_PROD_IN);
        // 零数量单只在"这一批产出全是不良"时出现（TOTAL 口径下 output == defect）。
        // 单据要留着：它把不良数记到了工单上，也是"这一批为什么没有良品入库"的唯一痕迹。
        // 但一定要在备注里说明白，否则查单的人会以为是系统出错。
        String remark = backfill ? "断线补记" : null;
        if (qty == null ? Boolean.TRUE : qty.signum() == 0) {
            remark = (remark == null ? "" : remark + "；")
                    + "仅记不良，库存未动（本批产出全部判为不良，良品入库 0 件）";
        }
        ErpStockBillHdr hdr = newHdr(billNo, ErpStockBill.TYPE_PROD_IN, ts, "system", remark);
        hdr.setDeviceCode(deviceCode);
        hdr.setDstWhId(whId);
        if (workOrderNo != null) {
            hdr.setSrcOrderNo(workOrderNo);
        }
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
        line.setSrcOrderNo(workOrderNo);
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
        for (Map.Entry<String, BigDecimal> e : sortedDelta(delta)) {
            String[] k = e.getKey().split("#");
            // 作废也要带锁：不然「作废入库单」和「同时发货」会各看各的库存，
            // 把已经用掉的货又作废掉，库存就成负数了。
            BigDecimal stock = nz(billRepo.stockOfForUpdate(Long.valueOf(k[0]), Long.valueOf(k[1])));
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

    /**
     * 尚未成单的生产增量（件）——<b>不是库存</b>。
     * <p>
     * 生产入库单按批次成（10 秒一批，单据整洁），但产量是每按一下就 +1 的。
     * 这里记的是「已经产出、还没过账」的量：它<b>只作提示</b>，
     * 由 {@link #balances} 以 {@code pendingQty} 单独返回，<b>不进任何余额口径</b>。
     * <p>
     * <b>为什么不能把它算进库存（DH-80）</b>：
     * <ul>
     *   <li>它记的是<b>原始总产出（含不良）</b>，而单据写的是<b>良品量</b>——
     *       两者不是同一个数。混进余额，成单瞬间余额会"掉一批不良数"，
     *       客户看到库存数字从 10 掉到 8（而库存页每 2 秒刷一次，掉数是稳定可见的）。</li>
     *   <li>它在 JVM 内存里，{@code FOR UPDATE} 的行锁锁不住它——
     *       混进"可用量"就等于在"读 → 扣减"之间开了窗口，会超卖；
     *       多实例部署时每个 JVM 一份，不同实例返回不同余额。</li>
     * </ul>
     * ⇒ 库存的唯一真相是<b>单据汇总</b>（{@link ErpStockBillRepository#aggregateStock()}），
     * 本 map 只负责让"机器在产出"这件事可见。
     */
    private final Map<String, BigDecimal> pendingStock = new ConcurrentHashMap<>();

    private static String stockKey(Long skuId, Long whId) {
        return skuId + "@" + whId;
    }

    /** 产量发生但还没成单：记进「未过账」账本 */
    public void addPendingStock(Long skuId, Long whId, BigDecimal qty) {
        if (skuId == null || whId == null || qty == null) {
            return;
        }
        pendingStock.merge(stockKey(skuId, whId), qty, BigDecimal::add);
    }

    /** 单据落库后扣掉对应增量（单据汇总里已经算进去了，不能重复） */
    public void clearPendingStock(Long skuId, Long whId, BigDecimal qty) {
        if (skuId == null || whId == null || qty == null) {
            return;
        }
        pendingStock.computeIfPresent(stockKey(skuId, whId), (k, v) -> {
            BigDecimal nv = v.subtract(qty);
            return nv.abs().compareTo(EPS) <= 0 ? null : nv;
        });
    }

    /**
     * 该 SKU+仓「已产出、未过账」的件数——<b>只作提示，不是库存</b>。
     * <p>
     * 抽成一个方法是因为 {@link #balances}（每行 pendingQty）与
     * {@link #summary}（全厂 pendingTotal）都要读同一张 map：
     * 两处各写一份读法，将来一处改了另一处不会报错，只会静静地对不上。
     *
     * @return 无未过账量时返回 0（不返回 null，调用方不必判空）
     */
    private BigDecimal pendingOf(Long skuId, Long whId) {
        BigDecimal v = pendingStock.get(stockKey(skuId, whId));
        return v == null ? BigDecimal.ZERO : v;
    }

    /**
     * 库存余额：<b>余额 = 单据汇总（唯一真相）</b>。
     * <p>
     * {@code qty} / {@code amount} 直接来自 {@link ErpStockBillRepository#aggregateStock()}
     * （{@code voidedAt is null} 的明细行累计），<b>不叠加任何未过账增量</b>。
     * 「已产出、还没成单」的量单独以 {@code pendingQty} 返回——它<b>只作提示</b>，
     * 界面据此显示「在产未过账 N 件」，<b>不得混进 {@code qty}</b>（DH-80）。
     * <p>
     * <b>为什么必须这样</b>：{@code pendingQty} 记的是含不良的原始总产出，单据写的是良品量，
     * 混进余额会让账面数在成单瞬间"掉一批不良数"；而它又在 JVM 内存里，
     * {@code FOR UPDATE} 的行锁锁不住，混进可用量就是超卖。
     *
     * @param whId 只看某个仓；null = 全部仓
     * @param q    按商品编码 / 名称 / 规格过滤；null = 不过滤
     * @return 每行 {@code qty} = 账面数（纯单据），{@code pendingQty} = 未过账量（仅提示）
     */
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
        List<Object[]> rows = new ArrayList<>(billRepo.aggregateStock());
        // 只有未过账增量、还没有任何单据的 SKU+仓：补一条 **qty = 0 的基础行**。
        // 补它的唯一理由是可看见性：新 SKU 刚投产的 10 秒窗口里一件都还没成单，
        // 没有这一行，现场会以为「根本没投产」（DH-80 AC-10）。
        // 该行 qty = 0、amount = 0，不污染任何账面数，也不改变 summary 的行数 KPI。
        for (String k : pendingStock.keySet()) {
            boolean has = false;
            for (Object[] a : rows) {
                if (stockKey(((Number) a[0]).longValue(), ((Number) a[1]).longValue()).equals(k)) {
                    has = true;
                    break;
                }
            }
            if (!has) {
                String[] ps = k.split("@");
                rows.add(new Object[]{Long.parseLong(ps[0]), Long.parseLong(ps[1]), BigDecimal.ZERO});
            }
        }
        for (Object[] a : rows) {
            Long skuId = ((Number) a[0]).longValue();
            Long wId = ((Number) a[1]).longValue();
            // qty 直接来自单据汇总（aggregateStock）：这是库存的唯一真相。
            // 未过账增量**不叠加**——它记的是原始总产出（含不良），单据写的是良品量，
            // 混进来会让余额在成单瞬间掉一批不良数（DH-80）。
            BigDecimal qty = dec(a[2]);
            BigDecimal pend = pendingOf(skuId, wId);
            // 账面为 0 但还有未过账量的行**不能跳过**：新 SKU 刚投产时还没成单，
            // 这一行是现场唯一能看见「它已经开始产出」的地方（DH-80 AC-10）。
            // 只跳过「账面和未过账都为 0」的纯空行。
            if (qty.abs().compareTo(EPS) <= 0 && pend.abs().compareTo(EPS) <= 0) {
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
            // 未过账的在产量：单独返回，**不并入 qty / amount**（DH-80）
            m.put("pendingQty", scale(pend));
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

    /**
     * KPI 汇总：库存总量/成本、今日本月出入库、预警数、单据数。
     * <p>
     * <b>口径（DH-80）</b>：{@code totalQty} / {@code totalValue} 与 {@code alertCount}
     * 现在同为<b>单据汇总</b>口径——改之前 totalQty 含未过账增量、alertCount 不含，
     * 同一行 KPI 里两个数各说各话。
     */
    public Map<String, Object> summary() {
        long now = System.currentTimeMillis();
        long todayStart = LocalDate.now(ZONE).atStartOfDay(ZONE).toInstant().toEpochMilli();
        long monthStart = LocalDate.now(ZONE).withDayOfMonth(1)
                .atStartOfDay(ZONE).toInstant().toEpochMilli();

        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal totalValue = BigDecimal.ZERO;
        BigDecimal pendingTotal = BigDecimal.ZERO;
        int skuCount = 0;
        java.util.Set<Long> skuSet = new java.util.HashSet<>();
        for (Map<String, Object> b : balances(null, null)) {
            BigDecimal qty = (BigDecimal) b.get("qty");
            totalQty = totalQty.add(qty);
            totalValue = totalValue.add((BigDecimal) b.get("amount"));
            Object p = b.get("pendingQty");
            if (p instanceof BigDecimal pd) {
                pendingTotal = pendingTotal.add(pd);
            }
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
        // 全厂「已产出未过账」合计：与 totalQty 相加才是「看得见的账 + 在产」（DH-80）
        out.put("pendingTotal", scale(pendingTotal));
        // 口径标记（先例 meterMode）：正向声明「余额 = 单据汇总」，让界面能据此提示
        out.put("stockMode", "BILL");
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
                                     boolean includeZeroQty, String kw, int page, int size) {
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
                withProd || ErpStockBill.TYPE_PROD_IN.equals(type),
                // 零数量的生产入库单默认不展示（数据留着，见 hdrRepo.search 注释）
                includeZeroQty, k, pageable);

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
        // 生产入库单被默认隐藏时，把「藏了多少张」明确告诉前端。
        // 不这么做的话，用户看到产量涨了、料也扣了，列表里却一张入库单都没有（因为默认过滤），
        // 只会判断成「入库丢了」。
        // 触发条件刻意收紧到「没有按类型筛选」这一种情况：
        //   · withProd=true 或按 PROD_IN 筛选 ⇒ 没隐藏，谈不上提示；
        //   · 按 PO_IN / SO_OUT 等其它类型筛选 ⇒ 用户根本没在找生产入库，
        //     这时弹「另有 3519 张生产入库单」纯属噪音。
        boolean prodFiltered = !withProd && (type == null || type.isBlank());
        out.put("prodHidden", prodFiltered
                ? hdrRepo.countByBillTypeAndVoidedAtIsNull(ErpStockBill.TYPE_PROD_IN)
                : 0L);
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

    /** 按 SKU 排序后的增量项：所有要加锁的地方都用同一个顺序，避免死锁 */
    private List<Map.Entry<String, BigDecimal>> sortedDelta(Map<String, BigDecimal> delta) {
        List<Map.Entry<String, BigDecimal>> out = new ArrayList<>(delta.entrySet());
        out.sort(Comparator.comparing(e -> Long.parseLong(e.getKey().split("#")[0])));
        return out;
    }

    /** 出库/调拨前查可用量，负数库存直接拒绝并说清差多少 */
    private void checkStockForOut(String type, List<LineReq> lines, Long whId, ErpWarehouse wh) {
        // 方向判定只有 isOutbound 一份，别再在这里抄一遍白名单——
        // 两份一定会漂移，而漂移那天不会有任何报错（见 isOutbound 的注释）
        boolean out = isOutbound(type);
        boolean transfer = ErpStockBill.TYPE_TRANSFER.equals(type);
        if (!out && !transfer) {
            return;
        }
        // 按 SKU 排序再加锁：两个请求如果锁的顺序相反（先 A 后 B / 先 B 后 A），
        // 数据库会判成死锁。让所有请求按同一个顺序拿锁，死锁就不会发生。
        List<LineReq> sorted = new ArrayList<>(lines);
        sorted.sort(Comparator.comparing(LineReq::skuId));
        for (LineReq r : sorted) {
            // 用带行锁的读：查到的可用量必须是「锁住之后」的库存，
            // 否则两个销售员同时发货会读到同一份「够」的数量，然后各自扣减 → 超卖。
            BigDecimal stock = nz(billRepo.stockOfForUpdate(r.skuId(), whId));
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

    /**
     * 单据没填单价时用哪个价。
     * <p>
     * <b>不是所有出库都按售价</b>——这里原来只判断「出库/入库」，于是生产领料也取了售价：
     * 实测领 20.6kg 铸铁，按售价 7.2 算出 148.32，而它的采购价是 5.8，
     * 真实材料成本只有 119.48 —— 材料成本虚高 24%，连带库存金额、毛利、报价全都跟着错。
     * <p>
     * 正确的划分按业务性质：
     * <ul>
     *   <li><b>销售出库 / 销售退货</b> → 售价（这是对客户开的交易价）</li>
     *   <li><b>生产领料 / 采购入库 / 采购退货 / 其他出入库</b> → 采购价。
     *       原料从仓库搬到车间，价值没变，变的只是位置；按售价算等于凭空给原料加了利润。</li>
     * </ul>
     */
    private BigDecimal defaultPrice(ErpSku s, String type) {
        boolean saleSide = ErpStockBill.TYPE_SO_OUT.equals(type)
                || ErpStockBill.TYPE_SO_RETURN.equals(type);
        if (saleSide && s.getSalePrice() != null) {
            return s.getSalePrice();
        }
        // 非销售侧一律按采购价；没维护采购价才退到售价（总比 null 好，成本会偏高但不会崩）
        return s.getPurchasePrice() != null ? s.getPurchasePrice() : s.getSalePrice();
    }

    private String nextBillNo(String type) {
        // 前缀缺失就抛异常，<b>不做 getOrDefault(..., "BILL") 兜底</b>：
        // 兜底会让"新增单据类型忘了登记前缀"从报错退化成悄悄生成 BILL20261103-0001，
        // 事后翻单据列表谁也看不出那是报废单；而且多个类型同时漏会撞号。
        // 配置缺失就该炸，这是"不做字面量兜底"这条通则的第一个具体案例。
        String prefix = TYPE_PREFIX.get(type);
        if (prefix == null) {
            throw new IllegalStateException("单据类型 " + type
                    + " 没有登记单号前缀（TYPE_PREFIX），请先在 ErpStockService 静态块里补上");
        }
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
