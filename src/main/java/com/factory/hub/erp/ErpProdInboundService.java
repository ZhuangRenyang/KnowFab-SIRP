package com.factory.hub.erp;

import com.factory.hub.model.DeviceEntity;
import com.factory.hub.service.DeviceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 生产入库联动：启用了联动的设备，计数点每 +N 就自动生成成品入库单。
 * <p>
 * 用户口径："每产一个入库一个"。实现上按 10 秒汇总一笔（10 秒内产出 5 件就是
 * 一张 qty=5 的单）——库存数字 10 秒内到位，人眼看不出差别，单据量却可控；
 * 高产线要逐件单据再改粒度，一个常量的事。
 * <p>
 * 数据流：{@code ProdRollup} 每秒算出计数增量（已处理清零/断线补记）→
 * {@link #onProduced} 过滤 → {@link #flush} 每 10 秒落 {@code erp_stock_bill}。
 * <p>
 * <b>两条归属路径（DH-71 起）</b>：
 * <ol>
 *   <li><b>工单驱动（优先）</b>：设备有在产工单时，产出算给工单——
 *       成品 SKU 与仓库都由工单决定，入库单带工单号，工单的完成量同步涨。
 *       这是"设备管理与 ERP 联动"真正的落点：不是简单地把产量塞进某个仓库，
 *       而是回答"这批货是给哪张单做的"。</li>
 *   <li><b>设备固定产品（兜底）</b>：没有在产工单时，走设备自己绑的
 *       {@code prod_sku_id} —— 单机台客户不开工单也能用，向后兼容。</li>
 * </ol>
 * 两条路径不会重复记账：{@link #onProduced} 里 if/else 二选一，
 * 一批增量只会进一个缓冲。
 * <p>
 * <b>2026-11-03 修的两个真问题</b>（都是实测复现的）：
 * <ol>
 *   <li><b>产量被静默丢弃</b>：原来 flush 先把缓冲清空，遇到「没仓库」的设备直接
 *       {@code continue}，那批产量既没成单也没留在缓冲里——凭空消失（实测 3 件）。
 *       现在处理不了的不清空，留在缓冲里下一轮继续。</li>
 *   <li><b>库存与单据可能记到两个仓</b>：原来「未成单增量」用配置缓存里的默认仓，
 *       落单时却用实时查到的默认仓，两边不一致时那截增量会永远挂在旧仓上。
 *       现在两边都用<b>同一份、按下单事件拍下来的</b>仓库快照，一批量对应一个仓。</li>
 * </ol>
 * 工单路径<b>不记「未成单余额」</b>：工单驱动的产出一律 10 秒内落单，余额由单据汇总接管，
 * 比"秒级可见"更重要的是"余额和单据永远对得上"——上一轮把这两者分开记，
 * 结果同屏出现 101 和 100 两个库存数，那种不一致比晚 10 秒难解释得多。
 * <p>
 * <b>两条路径都不把「未过账增量」计入余额（DH-80）</b>：上面这条 2026-11-03 的决策，
 * 现在推广到了<b>设备固定产品</b>路径——那条路径虽然仍记「已产出、还没成单」的量，
 * 但它<b>只作提示</b>，由 {@code ErpStockService.balances} 以 {@code pendingQty} 单独返回，
 * <b>不进任何余额口径</b>（库存余额 = 单据汇总，唯一真相）。
 * 理由：pending 记的是含不良的原始总产出、单据写的是良品量，混进余额会在成单瞬间掉一批不良数；
 * 且 pending 在 JVM 内存里 {@code FOR UPDATE} 锁不住它，混进可用量会超卖。
 * <p>
 * <b>不良品不进良品仓（QC-SPEC §7 F1）</b>：{@code role=reject} 计数器的小时增量也进这个缓冲
 * （{@link #onRejected}），落单时按 {@code device.counter_mode} 把「计数增量」换成「良品入库量」：
 * TOTAL → 总产出 − 不良；GOOD → 总产出。此前这里把 {@code BigDecimal.ZERO} 当不良数、
 * 直接把总产出入库，于是不良品被当良品进了成品仓——账面上比现实永远多一截，
 * 而且一个异常都不报。换算是纯函数（{@link ErpInboundQty}），单测钉死。
 */
@Component
public class ErpProdInboundService {

    private static final Logger log = LoggerFactory.getLogger(ErpProdInboundService.class);

    /** 配置缓存 60 秒：onProduced 每秒被调，不能每秒查库 */
    private record Cfg(Long skuId, Long whId, Long defaultWhId) {}

    /**
     * 缓冲里的一批待落库量。
     * <p>
     * {@code qty} 是要落单的量；{@code onStock} 是当初登记到「未过账账本」
     * （{@code ErpStockService.pendingStock}）上的量，
     * 两者必须分开记：没有仓库时只涨 qty（进不了未过账账本），落单后也就不用去减它。
     * <p>
     * <b>注意（DH-80）</b>：{@code onStock} <b>不进库存余额</b>，它只用于在落单后
     * 把「未过账」那笔减掉，让 {@code pendingQty} 归零。
     * 库存余额的唯一真相是单据汇总，未过账量只作提示单独返回——理由见
     * {@code ErpStockService#pendingStock} 的注释。
     * <p>
     * {@code defect} 是这一批里的不良件数（来自 role=reject 计数器）。
     * <b>它与 {@code qty} 分别累加</b>，落单时才按设备口径换算成良品量——
     * 因为不良计数器和主计数器是<b>两次独立采样</b>，谁先到都有可能，
     * 当场折算一定会两边打架（见 {@link ErpInboundQty}）。
     * <p>
     * {@code workOrderNo} 非空 = 这批量属于某张工单（工单路径，不碰余额）。
     */
    private static final class Buf {
        String deviceCode;
        Long skuId;
        Long whId;                       // null = 还没找到仓库（缓冲里等着）
        String workOrderNo;              // 非空 = 工单驱动的产量
        boolean backfill;
        BigDecimal qty = BigDecimal.ZERO;
        BigDecimal defect = BigDecimal.ZERO;
        BigDecimal onStock = BigDecimal.ZERO;
    }

    private final DeviceService deviceService;
    private final ErpStockService stockService;
    private final ErpWorkOrderService workOrderService;
    private final ErpWarehouseRepository whRepo;

    /** 缓冲：键 = 设备码@仓号[@工单号]。仓号那一段用 "-1" 表示"还没仓，先攒着"。 */
    private final Map<String, Buf> buffer = new ConcurrentHashMap<>();
    /** 每台设备上次告警的时间：同一台 5 分钟内不重复刷屏 */
    private final Map<String, Long> warnedAt = new ConcurrentHashMap<>();
    private Map<String, Cfg> cfgCache = new ConcurrentHashMap<>();
    /**
     * 主计数器口径缓存（设备码 → TOTAL / GOOD），与配置缓存同一次刷新。
     * <p>
     * 单独一张表是因为工单路径也需要口径，而 {@link #cfgCache} 只收
     * "启用了自动入库且绑了产品"的设备——工单驱动的设备不一定绑了固定产品，
     * 拿 cfgCache 查口径会查不到，然后按默认 TOTAL 算，把 GOOD 口径的良品数又减一遍不良。
     */
    private Map<String, String> counterModes = new ConcurrentHashMap<>();
    private volatile long cfgLoadedAt = 0;

    public ErpProdInboundService(DeviceService deviceService,
                                 ErpStockService stockService,
                                 ErpWorkOrderService workOrderService,
                                 ErpWarehouseRepository whRepo) {
        this.deviceService = deviceService;
        this.stockService = stockService;
        this.workOrderService = workOrderService;
        this.whRepo = whRepo;
    }

    /** ProdRollup 每次算出正增量时调用。必须便宜：不查库，只碰内存缓存。 */
    public void onProduced(String deviceCode, double delta, long ts, boolean backfill) {
        BigDecimal q = BigDecimal.valueOf(delta).setScale(4, RoundingMode.HALF_UP);

        // ① 工单优先：有在产工单就归工单，产品/仓库都听工单的
        ErpWorkOrderService.ActiveOrder ao = workOrderService.activeOrderOf(deviceCode);
        if (ao != null) {
            Long whId = ao.whId() != null ? ao.whId() : defaultWh();
            String key = deviceCode + "@" + (whId == null ? "-1" : whId) + "@" + ao.orderNo();
            Buf b = buffer.computeIfAbsent(key, k -> new Buf());
            b.deviceCode = deviceCode;
            b.skuId = ao.skuId();
            b.workOrderNo = ao.orderNo();
            b.backfill = b.backfill || backfill;
            b.qty = b.qty.add(q);
            if (whId != null) {
                b.whId = whId;
            }
            // 工单路径不记 pending：10 秒内落单，余额由单据汇总接管（见类注释）
            return;
        }

        // ② 没有工单：走设备自己绑的固定产品
        Cfg c = configs().get(deviceCode);
        if (c == null || c.skuId() == null) {
            return;
        }
        // 仓库按「这一笔产量发生时」的快照定下来：余额和单据必须落在同一个仓，
        // 否则中途改了仓库配置，余额就会永远挂在旧仓上（曾经的真 bug）。
        Long whId = c.whId() != null ? c.whId() : c.defaultWhId();
        String key = deviceCode + "@" + (whId == null ? "-1" : whId);
        Buf b = buffer.computeIfAbsent(key, k -> new Buf());
        b.deviceCode = deviceCode;
        b.skuId = c.skuId();
        b.workOrderNo = null;
        b.backfill = b.backfill || backfill;
        b.qty = b.qty.add(q);
        if (whId != null) {
            b.whId = whId;
            b.onStock = b.onStock.add(q);
            // 数量先登记进「未过账账本」：让「机器在产出」这件事立刻看得见，
            // 但**不进库存余额**（余额只认单据汇总，见 DH-80）；单据仍按批次成
            stockService.addPendingStock(c.skuId(), whId, q);
        }
    }

    /**
     * 不良品计数增量（{@code role=reject} 点位）发生时调用。
     * <p>
     * <b>与 {@link #onProduced} 分开两个入口，而不是给 onProduced 加一个 defect 参数</b>：
     * 不良计数器和主计数器是两次独立采样（不同的点、不同的时间戳），
     * 一个 10 秒批次里常常只有其中一个有增量。硬塞进一个方法就得让调用方造一个
     * "本次产出 0 件、不良 3 件"的假增量，那等于把两个信号混成一个。
     * 分开之后，缓冲里 qty 和 defect 各自累加，落单时才按口径换算（见 {@link ErpInboundQty}）。
     * <p>
     * 归属规则与产量完全一致：有在产工单就记到工单，否则记到设备固定产品；
     * 两者都没有就直接忽略（这台设备根本没接 ERP，不良数在 {@code prod_hourly} 里仍然有，
     * 报表照样能看，不影响质量看板的设备通道）。
     */
    public void onRejected(String deviceCode, double delta, long ts) {
        if (delta <= 0) {
            return;
        }
        BigDecimal q = BigDecimal.valueOf(delta).setScale(4, RoundingMode.HALF_UP);

        ErpWorkOrderService.ActiveOrder ao = workOrderService.activeOrderOf(deviceCode);
        Buf b;
        if (ao != null) {
            Long whId = ao.whId() != null ? ao.whId() : defaultWh();
            String key = deviceCode + "@" + (whId == null ? "-1" : whId) + "@" + ao.orderNo();
            b = buffer.computeIfAbsent(key, k -> new Buf());
            b.deviceCode = deviceCode;
            b.skuId = ao.skuId();
            b.workOrderNo = ao.orderNo();
            if (whId != null) {
                b.whId = whId;
            }
        } else {
            Cfg c = configs().get(deviceCode);
            if (c == null || c.skuId() == null) {
                return;
            }
            Long whId = c.whId() != null ? c.whId() : c.defaultWhId();
            String key = deviceCode + "@" + (whId == null ? "-1" : whId);
            b = buffer.computeIfAbsent(key, k -> new Buf());
            b.deviceCode = deviceCode;
            b.skuId = c.skuId();
            b.workOrderNo = null;
            if (whId != null) {
                b.whId = whId;
            }
        }
        b.defect = b.defect.add(q);
    }

    private synchronized Map<String, Cfg> configs() {
        long now = System.currentTimeMillis();
        if (now - cfgLoadedAt > 60_000) {
            Map<String, Cfg> m = new HashMap<>();
            Map<String, String> modes = new HashMap<>();
            try {
                Long defaultWh = defaultWh();
                for (DeviceEntity d : deviceService.all()) {
                    // 口径要全量收：工单路径不看 prod_sku_id，但一样要看口径
                    modes.put(d.getCode(), d.getCounterMode());
                    if (d.isProdAutoStockin() && d.getProdSkuId() != null) {
                        m.put(d.getCode(), new Cfg(d.getProdSkuId(), d.getProdWhId(), defaultWh));
                    }
                }
            } catch (Exception e) {
                log.warn("刷新生产入库配置失败（沿用旧缓存）: {}", e.getMessage());
            }
            cfgCache = m;
            counterModes = modes;
            cfgLoadedAt = now;
        }
        return cfgCache;
    }

    /** 这台设备的主计数器口径；查不到按默认 TOTAL（与全系统历史假设一致） */
    private String counterModeOf(String deviceCode) {
        configs();
        String v = counterModes.get(deviceCode);
        return v == null ? DeviceEntity.COUNTER_TOTAL : v;
    }

    @Scheduled(fixedDelay = 10_000L)
    public synchronized void flush() {
        if (buffer.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Buf> e : new HashMap<>(buffer).entrySet()) {
            String key = e.getKey();
            Buf b = e.getValue();
            // 只有「既没产出也没不良」才丢弃这批。
            // 单看 qty 会在「不良先到、产出后到」的那个批次里把不良数一起扔掉，
            // 而 prod_hourly 里那份不良数又已经在账上 —— 两边就永远对不上
            if (b.qty.signum() <= 0 && b.defect.signum() <= 0) {
                buffer.remove(key);
                continue;
            }
            if (b.workOrderNo != null) {
                flushByWorkOrder(key, b);
                continue;
            }
            if (b.whId == null) {
                // 还没仓库：现在再解析一次（配置缓存最多 60 秒刷新一次，默认仓建好后能自动接上）。
                // 解析不出来就把这批量留在缓冲里——绝不丢弃，产量丢了就再也回不来了。
                Long whId = resolveWh(b.deviceCode);
                if (whId == null) {
                    warnNoWarehouse(b.deviceCode);
                    continue;
                }
                // 归位到有仓的批次：这一批当初没进过「未过账账本」，所以 onStock 不用补，
                // 落单后也不用去减未过账量（否则会把别人的量减掉）。
                buffer.remove(key);
                String newKey = b.deviceCode + "@" + whId;
                Buf moved = buffer.computeIfAbsent(newKey, k -> new Buf());
                moved.deviceCode = b.deviceCode;
                moved.skuId = b.skuId;
                moved.whId = whId;
                moved.backfill = b.backfill;
                moved.qty = moved.qty.add(b.qty);
                moved.defect = moved.defect.add(b.defect);
                continue;               // 下一轮正式落库
            }
            try {
                // 按设备口径把「计数增量」换成「良品入库量」（QC-SPEC §2.3）：
                // TOTAL → 总产出 − 不良；GOOD → 总产出（不再减）。
                // 这就是 F1 的正中间：原来这里直接把 b.qty（总产出）入库，
                // 不良品被当成良品进了成品仓，账面库存永远比现实多一截。
                ErpInboundQty q = ErpInboundQty.resolve(
                        counterModeOf(b.deviceCode), b.qty, b.defect);
                if (q.clamped()) {
                    log.warn("设备 {} 本批不良数 {} 大于产出 {}（两个计数器采样错位），"
                                    + "本批入库按 0 处理，差额留待下一批产出补回",
                            b.deviceCode, q.defect().stripTrailingZeros().toPlainString(),
                            q.total().stripTrailingZeros().toPlainString());
                }
                // 走统一单据服务：生成单头 + 明细行，单据列表里与手工单同一套结构
                stockService.createProdInbound(b.deviceCode, b.skuId, b.whId, q.good(), b.backfill);
                // 已经写进单据了：从「未过账账本」里扣掉，让 pendingQty 归零。
                // 它从来就没进过余额，所以这里**不减余额**——余额自始至终只跟着单据走（DH-80）。
                // 扣的量必须和当初加上去的量一模一样（逐笔 round4 后累加），否则 pendingQty 会漂。
                if (b.onStock.signum() != 0) {
                    stockService.clearPendingStock(b.skuId, b.whId, b.onStock);
                }
                buffer.remove(key);
            } catch (Exception ex) {
                // 留在缓冲里，下轮重试；不丢产量
                log.error("生产入库单落库失败（10 秒后重试）: {}", ex.getMessage());
            }
        }
    }

    /**
     * 工单驱动的产出落库。
     * <p>
     * 三种结果分开处理，都不能静默丢：
     * <ul>
     *   <li><b>成功</b> → 摘掉缓冲；</li>
     *   <li><b>工单已结束</b>（完工/取消后设备还在转）→ 优先回退到设备固定产品；
     *       设备也没配，就<b>留在缓冲里等</b>并告警——绝不丢弃。
     *       下一个人开工单时，这批量会自动归到新工单（配置修好后立即补上）；</li>
     *   <li><b>异常</b>（数据库抖动）→ 留在缓冲里下轮重试。</li>
     * </ul>
     */
    private void flushByWorkOrder(String key, Buf b) {
        try {
            // 口径换算与设备路径完全一致：工单要记的良品量同样是「总产出 − 不良」，
            // 而 defect 作为独立参数交给 produce()（它内部写 erp_work_order_output.defect_qty
            // 并累加工单的不良数，良品量只加 doneQty —— 两边不能混）
            ErpInboundQty q = ErpInboundQty.resolve(
                    counterModeOf(b.deviceCode), b.qty, b.defect);
            if (q.clamped()) {
                log.warn("设备 {} 工单 {} 本批不良数 {} 大于产出 {}（两个计数器采样错位），"
                                + "本批入库按 0 处理，差额留待下一批产出补回",
                        b.deviceCode, b.workOrderNo,
                        q.defect().stripTrailingZeros().toPlainString(),
                        q.total().stripTrailingZeros().toPlainString());
            }
            String billNo = workOrderService.produce(b.workOrderNo, b.deviceCode, b.whId,
                    q.good(), q.defect(), b.backfill);
            if (billNo != null) {
                buffer.remove(key);
                return;
            }
        } catch (Exception ex) {
            log.error("工单 {} 产出落库失败（10 秒后重试）: {}", b.workOrderNo, ex.getMessage());
            return;
        }

        // 工单已经完工/取消，这批产量没有归属了
        Cfg c = configs().get(b.deviceCode);
        if (c != null && c.skuId() != null) {
            Long whId = c.whId() != null ? c.whId() : c.defaultWhId();
            buffer.remove(key);
            String newKey = b.deviceCode + "@" + (whId == null ? "-1" : whId);
            Buf moved = buffer.computeIfAbsent(newKey, k -> new Buf());
            moved.deviceCode = b.deviceCode;
            moved.skuId = c.skuId();
            moved.workOrderNo = null;
            moved.backfill = b.backfill;
            moved.qty = moved.qty.add(b.qty);
            moved.defect = moved.defect.add(b.defect);
            if (whId != null) {
                moved.whId = whId;
                // 转成「设备固定产品」路径后，这批量要补记进「未过账账本」，
                // 否则 pendingQty 永远少这一截（落单后会再扣掉，归零）。
                // 注意它依然**不进余额**：余额只认单据汇总（DH-80）。
                moved.onStock = moved.onStock.add(b.qty);
                stockService.addPendingStock(c.skuId(), whId, b.qty);
            }
            log.warn("设备 {} 的 {} 件产出发生在工单 {} 结束之后，已转到设备固定产品 {}",
                    b.deviceCode, b.qty.stripTrailingZeros().toPlainString(),
                    b.workOrderNo, c.skuId());
            return;
        }

        // 既没有在产工单，设备也没配固定产品：留在缓冲里等配置，绝不丢
        warnNoDestination(b.deviceCode, b.qty);
    }

    /** 按当前配置解析这台设备该进哪个仓：设备指定仓 > 默认仓 > 没有 */
    private Long resolveWh(String deviceCode) {
        Cfg c = configs().get(deviceCode);
        if (c == null) {
            return null;
        }
        return c.whId() != null ? c.whId() : c.defaultWhId();
    }

    /** 默认仓 id；没有返回 null。60 秒缓存，别在每秒回调里查库。 */
    private Long defaultWh() {
        try {
            return whRepo.findByDefaultWarehouseTrue().map(ErpWarehouse::getId)
                    .orElseGet(() -> {
                        // 没有显式默认仓：取第一个启用的仓（与库存页"未指定仓"的兜底一致）
                        for (ErpWarehouse w : whRepo.findAll()) {
                            if (w.isEnabled()) {
                                return w.getId();
                            }
                        }
                        return null;
                    });
        } catch (Exception e) {
            return null;
        }
    }

    /** 没有仓库：每台设备 5 分钟提醒一次，别刷屏也别一声不吭 */
    private void warnNoWarehouse(String deviceCode) {
        if (throttle("wh:" + deviceCode)) {
            return;
        }
        log.warn("生产入库联动：设备 {} 未指定仓库且没有默认仓，这批产量已暂存等仓库"
                + "（建一个默认仓或给设备指定仓库后会自动补上，产量不会丢）", deviceCode);
    }

    /** 产量没有归宿：既没在产工单，设备也没绑产品 */
    private void warnNoDestination(String deviceCode, BigDecimal qty) {
        if (throttle("dst:" + deviceCode)) {
            return;
        }
        log.warn("生产入库联动：设备 {} 有 {} 件产出但没有归属——既没有在产工单，"
                + "设备也没绑固定产品。这批量已暂存，开工单或给设备配产品后会自动补上，不会丢",
                deviceCode, qty.stripTrailingZeros().toPlainString());
    }

    private boolean throttle(String key) {
        long now = System.currentTimeMillis();
        Long last = warnedAt.get(key);
        if (last != null && now - last < 5 * 60_000L) {
            return true;
        }
        warnedAt.put(key, now);
        return false;
    }
}
