package com.factory.hub.erp;

import com.factory.hub.model.DeviceEntity;
import com.factory.hub.service.DeviceService;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

/**
 * ERP 库存测试夹具（DH-80 库存双口径收口）。
 *
 * <p><b>为什么不用真数据库</b>：本项目所有测试都是 Mockito 单元测试（没有一个
 * {@code @SpringBootTest}），H2 已于 2026-10-27 移除，MySQL 8 是唯一目标库。
 * 库存口径这件事要断言的是「几个出口的数字是不是同一个数」，
 * 夹具必须能<b>精确控制</b>「单据汇总是多少、未成单增量是多少」，
 * 真库反而做不到（pending 是纯内存的，库里根本没有它）。
 *
 * <p><b>夹具模拟了什么（别把它当真库用）</b>：
 * <ul>
 *   <li><b>单据账本</b>：{@code erp_stock_bill} 的内存副本。
 *       {@code aggregateStock()} / {@code stockOf()} / {@code flowOf()} 都从它现算，
 *       与生产实现一样「库存 = 未作废明细行 qty 累计」，没有冗余库存数。</li>
 *   <li><b>事务隔离</b>：写入先落在「本事务的写集」里，提交后才并入已提交数据。
 *       别的线程/连接看不见未提交的写入 —— 这一条是并发测试能不能杀掉
 *       「没上锁就超卖」这个缺陷的关键。</li>
 *   <li><b>行锁</b>：{@code stockOfForUpdate()} 会真的拿一把 ReentrantLock（按 sku@wh 分键），
 *       一直持有到事务结束（{@link #inTransaction} 的 finally 释放）。
 *       这就是 {@code SELECT ... FOR UPDATE} 的语义：锁到外层事务提交为止。</li>
 *   <li><b>竞态栅栏</b>：{@link #armRaceBarrier} 打开后，<b>无锁读</b>
 *       （{@code stockOf}）会让两个并发读者在同一时刻取快照 ——
 *       用来确定性地复现「没上 FOR UPDATE 就会超卖」。</li>
 * </ul>
 *
 * <p><b>没模拟的</b>：作废（voided）只做了「标记行失效」，没有模拟作废的隔离性；
 * 报表类查询（trend/dailyInOut）只做了最小实现。DH-80 的测试不依赖这两处。
 */
class ErpStockTestKit {

    /* ==================== 账本 ==================== */

    /** 账本行：实体 + 自增 id（模拟 MySQL IDENTITY） */
    private static final class LedgerRow {
        final long id;
        final ErpStockBill bill;

        LedgerRow(long id, ErpStockBill bill) {
            this.id = id;
            this.bill = bill;
        }
    }

    /** 事务内未提交的写入（别的连接看不见） */
    private static final class Tx {
        final List<LedgerRow> writes = new ArrayList<>();
    }

    /** 已提交的行 */
    private final List<LedgerRow> committed = new CopyOnWriteArrayList<>();

    /** 行 id → 作废时间戳 */
    private final Map<Long, Long> voidedAt = new ConcurrentHashMap<>();

    /** 当前线程的事务写集 */
    private final ThreadLocal<Tx> tx = new ThreadLocal<>();

    /** 行锁：key = skuId@whId */
    private final Map<String, ReentrantLock> rowLocks = new ConcurrentHashMap<>();

    /** 当前线程持有的行锁（事务结束释放） */
    private final ThreadLocal<Set<String>> heldLocks = ThreadLocal.withInitial(LinkedHashSet::new);

    /** 可选竞态栅栏（只在无锁读路径上等） */
    private volatile CyclicBarrier raceBarrier;

    private final AtomicLong seq = new AtomicLong(1000);
    private final AtomicLong hdrSeq = new AtomicLong(5000);

    private final Map<String, ErpStockBillHdr> headers = new ConcurrentHashMap<>();

    /* ==================== 主数据 ==================== */

    final List<ErpSku> skus = new CopyOnWriteArrayList<>();
    final List<ErpWarehouse> warehouses = new CopyOnWriteArrayList<>();

    /* ==================== 被 mock 的仓储 ==================== */

    final ErpStockBillRepository billRepo = mock(ErpStockBillRepository.class,
            withSettings().defaultAnswer(this::answerBillRepo));
    final ErpStockBillHdrRepository hdrRepo = mock(ErpStockBillHdrRepository.class,
            withSettings().defaultAnswer(this::answerHdrRepo));
    final ErpSkuRepository skuRepo = mock(ErpSkuRepository.class,
            withSettings().defaultAnswer(this::answerSkuRepo));
    final ErpWarehouseRepository whRepo = mock(ErpWarehouseRepository.class,
            withSettings().defaultAnswer(this::answerWhRepo));
    final ErpPartnerRepository partnerRepo = mock(ErpPartnerRepository.class,
            withSettings().defaultAnswer(this::answerPartnerRepo));
    final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

    /* ==================== 造数 ==================== */

    /** 造一个商品（默认启用；min/max 给 null 表示不设限） */
    ErpSku sku(long id, String code, String name, BigDecimal minStock, BigDecimal maxStock) {
        ErpSku s = new ErpSku();
        setId(s, id);
        s.setCode(code);
        s.setName(name);
        s.setCategory("成品");
        s.setSpec("默认规格");
        s.setUnit("件");
        s.setPurchasePrice(new BigDecimal("10"));
        s.setSalePrice(new BigDecimal("15"));
        s.setMinStock(minStock);
        s.setMaxStock(maxStock);
        s.setEnabled(true);
        skus.add(s);
        return s;
    }

    /** 造一个仓库（默认启用、标准仓） */
    ErpWarehouse wh(long id, String code, String name) {
        ErpWarehouse w = new ErpWarehouse();
        w.setId(id);
        w.setCode(code);
        w.setName(name);
        w.setKind(ErpWarehouse.KIND_STANDARD);
        w.setEnabled(true);
        warehouses.add(w);
        return w;
    }

    /** 一个新的库存服务实例。同一套库 + 新的内存 pending ⇒ 相当于「进程重启」 */
    ErpStockService stockService() {
        return new ErpStockService(billRepo, hdrRepo, skuRepo, whRepo, partnerRepo, events);
    }

    /**
     * 生产入库联动服务（设备固定产品路径，无在产工单）。
     * 产出走 {@code onProduced}、不良走 {@code onRejected}、落单走 {@code flush}。
     *
     * @param stockService <b>必须传测试正在断言的那个实例</b>：
     *                      pendingStock 是服务实例上的内存字段，
     *                      换实例就换了一份 pending（那等于「重启」）。
     */
    ErpProdInboundService prodInboundService(String deviceCode, Long skuId, Long whId,
                                              String counterMode, ErpStockService stockService) {
        DeviceEntity dev = new DeviceEntity();
        dev.setCode(deviceCode);
        dev.setName(deviceCode);
        dev.setProdSkuId(skuId);
        dev.setProdWhId(whId);
        dev.setProdAutoStockin(true);
        dev.setCounterMode(counterMode);

        DeviceService devices = mock(DeviceService.class,
                withSettings().defaultAnswer((InvocationOnMock inv) -> {
                    if ("all".equals(inv.getMethod().getName())) {
                        return List.of(dev);
                    }
                    throw new UnsupportedOperationException("DeviceService." + inv.getMethod().getName());
                }));
        ErpWorkOrderService wo = mock(ErpWorkOrderService.class,
                withSettings().defaultAnswer((InvocationOnMock inv) -> {
                    if ("activeOrderOf".equals(inv.getMethod().getName())) {
                        return null;   // 没有在产工单 ⇒ 走设备固定产品路径
                    }
                    throw new UnsupportedOperationException("ErpWorkOrderService." + inv.getMethod().getName());
                }));
        return new ErpProdInboundService(devices, stockService, wo, whRepo);
    }

    /* ==================== 事务与锁 ==================== */

    /**
     * 在一个「事务」里跑一段业务代码：结束时提交写入并释放行锁。
     * <p>异常时写入不提交（回滚），锁照样释放 —— 与真实数据库一致。
     */
    <T> T inTransaction(Supplier<T> action) {
        Tx t = new Tx();
        tx.set(t);
        try {
            T r = action.get();
            committed.addAll(t.writes);
            return r;
        } finally {
            tx.remove();
            releaseLocks();
        }
    }

    /** 打开竞态栅栏：n 个并发「无锁读」必须取到同一时刻的快照 */
    void armRaceBarrier(int parties) {
        raceBarrier = new CyclicBarrier(parties);
    }

    void disarmRaceBarrier() {
        raceBarrier = null;
    }

    private void releaseLocks() {
        Set<String> keys = heldLocks.get();
        for (String key : keys) {
            ReentrantLock lock = rowLocks.get(key);
            if (lock != null && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
        keys.clear();
    }

    /* ==================== 读取出口（黑盒断言用） ==================== */

    /** 余额页里某个 SKU 在某个仓的数量；没有这一行按 0 算 */
    BigDecimal balanceQty(ErpStockService svc, Long skuId, Long whId) {
        for (Map<String, Object> m : svc.balances(null, null)) {
            if (skuId.equals(m.get("skuId")) && whId.equals(m.get("warehouseId"))) {
                return (BigDecimal) m.get("qty");
            }
        }
        return BigDecimal.ZERO;
    }

    /**
     * 余额页里某个 SKU 在某个仓的「已产出未过账」件数；没有这一行按 0 算。
     * <p>
     * DH-80：这个数是<b>提示</b>，不并入 {@link #balanceQty}。
     * 断言「账面不变、在产可见」时两个都要读。
     */
    BigDecimal pendingQty(ErpStockService svc, Long skuId, Long whId) {
        for (Map<String, Object> m : svc.balances(null, null)) {
            if (skuId.equals(m.get("skuId")) && whId.equals(m.get("warehouseId"))) {
                Object v = m.get("pendingQty");
                return v == null ? BigDecimal.ZERO : (BigDecimal) v;
            }
        }
        return BigDecimal.ZERO;
    }

    /** 余额页里某个 SKU 在所有仓的数量之和 */
    BigDecimal balanceQtyForSku(ErpStockService svc, Long skuId) {
        BigDecimal sum = BigDecimal.ZERO;
        for (Map<String, Object> m : svc.balances(null, null)) {
            if (skuId.equals(m.get("skuId"))) {
                sum = sum.add((BigDecimal) m.get("qty"));
            }
        }
        return sum;
    }

    /** 余额页里所有行的数量之和 */
    BigDecimal balanceTotal(ErpStockService svc) {
        BigDecimal sum = BigDecimal.ZERO;
        for (Map<String, Object> m : svc.balances(null, null)) {
            sum = sum.add((BigDecimal) m.get("qty"));
        }
        return sum;
    }

    /** 预警页给某个 SKU 用的总数；该 SKU 不在预警列表里按 0 算 */
    BigDecimal alertTotal(ErpStockService svc, Long skuId) {
        for (Map<String, Object> m : svc.alerts()) {
            if (skuId.equals(m.get("skuId"))) {
                return (BigDecimal) m.get("total");
            }
        }
        return BigDecimal.ZERO;
    }

    /** 预警页 byWarehouse 里某个仓的数量；没有按 0 算 */
    BigDecimal alertWarehouseQty(ErpStockService svc, Long skuId, Long whId) {
        for (Map<String, Object> m : svc.alerts()) {
            if (!skuId.equals(m.get("skuId"))) {
                continue;
            }
            Object list = m.get("byWarehouse");
            if (list instanceof List<?> rows) {
                for (Object row : rows) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> wm = (Map<String, Object>) row;
                    if (whId.equals(wm.get("warehouseId"))) {
                        return (BigDecimal) wm.get("qty");
                    }
                }
            }
        }
        return BigDecimal.ZERO;
    }

    /** 预警页 byWarehouse 各项之和（某个 SKU） */
    BigDecimal alertWarehouseSum(ErpStockService svc, Long skuId) {
        BigDecimal sum = BigDecimal.ZERO;
        for (Map<String, Object> m : svc.alerts()) {
            if (!skuId.equals(m.get("skuId"))) {
                continue;
            }
            Object list = m.get("byWarehouse");
            if (list instanceof List<?> rows) {
                for (Object row : rows) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> wm = (Map<String, Object>) row;
                    sum = sum.add((BigDecimal) wm.get("qty"));
                }
            }
        }
        return sum;
    }

    /** 流水页最新一行的结存；没有流水返回 null */
    BigDecimal flowBalance(ErpStockService svc, Long skuId, Long whId) {
        List<Map<String, Object>> flow = svc.flow(skuId, whId, 50);
        if (flow.isEmpty()) {
            return null;
        }
        return (BigDecimal) flow.get(0).get("balance");
    }

    /** 账本口径的库存（= 单据汇总，不含任何内存增量） */
    BigDecimal ledgerSum(Long skuId, Long whId) {
        BigDecimal sum = BigDecimal.ZERO;
        for (LedgerRow r : visibleRows()) {
            if (isVoided(r)) {
                continue;
            }
            ErpStockBill b = r.bill;
            if (skuId.equals(b.getSkuId()) && whId.equals(b.getWarehouseId())) {
                sum = sum.add(b.getQty());
            }
        }
        return sum;
    }

    /** 账本里某个 SKU 在所有仓的数量之和 */
    BigDecimal ledgerSumForSku(Long skuId) {
        BigDecimal sum = BigDecimal.ZERO;
        for (LedgerRow r : visibleRows()) {
            if (isVoided(r)) {
                continue;
            }
            ErpStockBill b = r.bill;
            if (skuId.equals(b.getSkuId())) {
                sum = sum.add(b.getQty());
            }
        }
        return sum;
    }

    /** 账本里一共写了几行明细 */
    int ledgerRowCount() {
        return visibleRows().size();
    }

    /* ==================== 仓储实现 ==================== */

    private Object answerBillRepo(InvocationOnMock inv) {
        String m = inv.getMethod().getName();
        switch (m) {
            case "aggregateStock":
                return aggregateStock();
            case "stockOf": {
                Long skuId = inv.getArgument(0);
                Long whId = inv.getArgument(1);
                BigDecimal v = readSum(skuId, whId);
                // 无锁读：两个并发读者必须取到同一时刻的快照（竞态复现用）
                awaitRaceBarrier();
                return v;
            }
            case "stockOfForUpdate": {
                Long skuId = inv.getArgument(0);
                Long whId = inv.getArgument(1);
                return stockOfForUpdate(skuId, whId);
            }
            case "countByBillNoPrefix":
                return countByBillNoPrefix(inv.getArgument(0));
            case "findByBillNoOrderByLineNoAsc":
                return findByBillNo(inv.getArgument(0));
            case "flowOf":
                return flowOf(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2));
            case "sumInSince":
                return sumSigned(inv.getArgument(0), true);
            case "sumOutSince":
                return sumSigned(inv.getArgument(0), false);
            case "dailyInOutSince":
                return dailyInOut(inv.getArgument(0));
            case "save": {
                ErpStockBill b = inv.getArgument(0);
                addRow(b);
                return b;
            }
            case "saveAll": {
                Object arg = inv.getArgument(0);
                if (arg instanceof Iterable<?> it) {
                    for (Object o : it) {
                        addRow((ErpStockBill) o);
                    }
                }
                return arg;
            }
            case "voidByBillNo":
                return voidByBillNo(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2));
            case "findAll":
                return List.of();
            case "count":
                return 0L;
            case "existsById":
                return false;
            // 报表服务（ErpReportService.overview）所需的读法：DH-80 AC-9 只关心
            // stockValue，其余维度（趋势/排行/区间产量）给空实现即可，不影响库存口径。
            case "tradeDaily":
                return List.of();
            case "sumQtyByTypeInRange":
                return BigDecimal.ZERO;
            case "salesByPartner":
            case "purchaseByPartner":
            case "flowTopSkus":
                return List.of();
            default:
                throw new UnsupportedOperationException("ErpStockTestKit 未实现 billRepo." + m);
        }
    }

    private Object answerHdrRepo(InvocationOnMock inv) {
        String m = inv.getMethod().getName();
        switch (m) {
            case "save":
            case "saveAndFlush": {
                ErpStockBillHdr h = inv.getArgument(0);
                if (h.getId() == null) {
                    setId(h, hdrSeq.incrementAndGet());
                }
                headers.put(h.getBillNo(), h);
                return h;
            }
            case "existsByBillNo":
                return headers.containsKey(inv.getArgument(0));
            case "findByBillNo":
                return Optional.ofNullable(headers.get(inv.getArgument(0)));
            case "countByBillTypeNot":
                return countHdr(h -> !h.getBillType().equals(inv.getArgument(0)));
            case "countByBillTypeNotAndVoidedAtIsNullAndCreatedAtGreaterThanEqual": {
                String type = inv.getArgument(0);
                long ts = inv.getArgument(1);
                return countHdr(h -> !h.getBillType().equals(type)
                        && h.getVoidedAt() == null && h.getCreatedAt() >= ts);
            }
            case "countByBillTypeAndCreatedAtGreaterThanEqual": {
                String type = inv.getArgument(0);
                long ts = inv.getArgument(1);
                return countHdr(h -> h.getBillType().equals(type) && h.getCreatedAt() >= ts);
            }
            case "findTop20ByOrderByCreatedAtDesc":
                return new ArrayList<>(headers.values());
            case "findBySrcOrderNoOrderByCreatedAtDesc": {
                String no = inv.getArgument(0);
                List<ErpStockBillHdr> out = new ArrayList<>();
                for (ErpStockBillHdr h : headers.values()) {
                    if (no.equals(h.getSrcOrderNo())) {
                        out.add(h);
                    }
                }
                return out;
            }
            case "search":
                return Page.empty();
            case "findAll":
                return new ArrayList<>(headers.values());
            case "count":
                return (long) headers.size();
            default:
                throw new UnsupportedOperationException("ErpStockTestKit 未实现 hdrRepo." + m);
        }
    }

    private Object answerSkuRepo(InvocationOnMock inv) {
        String m = inv.getMethod().getName();
        switch (m) {
            case "findAll":
                return new ArrayList<>(skus);
            case "findById": {
                Long id = inv.getArgument(0);
                for (ErpSku s : skus) {
                    if (s.getId().equals(id)) {
                        return Optional.of(s);
                    }
                }
                return Optional.empty();
            }
            case "findByCodeIgnoreCase": {
                String code = inv.getArgument(0);
                for (ErpSku s : skus) {
                    if (s.getCode().equalsIgnoreCase(code)) {
                        return Optional.of(s);
                    }
                }
                return Optional.empty();
            }
            case "count":
                return (long) skus.size();
            default:
                throw new UnsupportedOperationException("ErpStockTestKit 未实现 skuRepo." + m);
        }
    }

    private Object answerWhRepo(InvocationOnMock inv) {
        String m = inv.getMethod().getName();
        switch (m) {
            case "findAll":
                return new ArrayList<>(warehouses);
            case "findById": {
                Long id = inv.getArgument(0);
                for (ErpWarehouse w : warehouses) {
                    if (w.getId().equals(id)) {
                        return Optional.of(w);
                    }
                }
                return Optional.empty();
            }
            case "findByDefaultWarehouseTrue": {
                for (ErpWarehouse w : warehouses) {
                    if (w.isDefaultWarehouse()) {
                        return Optional.of(w);
                    }
                }
                return Optional.empty();
            }
            case "findByDefaultWarehouseTrueAndIdNot": {
                Long not = inv.getArgument(0);
                for (ErpWarehouse w : warehouses) {
                    if (w.isDefaultWarehouse() && !w.getId().equals(not)) {
                        return Optional.of(w);
                    }
                }
                return Optional.empty();
            }
            case "findByCodeIgnoreCase": {
                String code = inv.getArgument(0);
                for (ErpWarehouse w : warehouses) {
                    if (w.getCode().equalsIgnoreCase(code)) {
                        return Optional.of(w);
                    }
                }
                return Optional.empty();
            }
            case "count":
                return (long) warehouses.size();
            default:
                throw new UnsupportedOperationException("ErpStockTestKit 未实现 whRepo." + m);
        }
    }

    private Object answerPartnerRepo(InvocationOnMock inv) {
        String m = inv.getMethod().getName();
        switch (m) {
            case "findById":
            case "findByCodeIgnoreCase":
                return Optional.empty();
            case "count":
                return 0L;
            default:
                throw new UnsupportedOperationException("ErpStockTestKit 未实现 partnerRepo." + m);
        }
    }

    /* ==================== 内部实现 ==================== */

    private List<LedgerRow> visibleRows() {
        List<LedgerRow> out = new ArrayList<>(committed);
        Tx t = tx.get();
        if (t != null) {
            out.addAll(t.writes);
        }
        return out;
    }

    private boolean isVoided(LedgerRow r) {
        return r.bill.getVoidedAt() != null || voidedAt.containsKey(r.id);
    }

    private void addRow(ErpStockBill bill) {
        LedgerRow row = new LedgerRow(seq.incrementAndGet(), bill);
        Tx t = tx.get();
        if (t != null) {
            t.writes.add(row);
        } else {
            committed.add(row);
        }
    }

    private BigDecimal readSum(Long skuId, Long whId) {
        BigDecimal sum = BigDecimal.ZERO;
        for (LedgerRow r : visibleRows()) {
            if (isVoided(r)) {
                continue;
            }
            ErpStockBill b = r.bill;
            if (skuId.equals(b.getSkuId()) && whId.equals(b.getWarehouseId())) {
                sum = sum.add(b.getQty());
            }
        }
        return sum;
    }

    /** SELECT ... FOR UPDATE：拿行锁，锁到事务结束 */
    private BigDecimal stockOfForUpdate(Long skuId, Long whId) {
        String key = skuId + "@" + whId;
        ReentrantLock lock = rowLocks.computeIfAbsent(key, k -> new ReentrantLock(true));
        lock.lock();
        heldLocks.get().add(key);
        return readSum(skuId, whId);
    }

    private void awaitRaceBarrier() {
        CyclicBarrier b = raceBarrier;
        if (b == null) {
            return;
        }
        try {
            b.await(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("竞态栅栏等待失败：并发测试写法有问题，"
                    + "或者只有一个线程走到了无锁读点", e);
        }
    }

    private List<Object[]> aggregateStock() {
        Map<String, BigDecimal> sums = new LinkedHashMap<>();
        Map<String, Long[]> keys = new LinkedHashMap<>();
        for (LedgerRow r : visibleRows()) {
            if (isVoided(r)) {
                continue;
            }
            ErpStockBill b = r.bill;
            String k = b.getSkuId() + "@" + b.getWarehouseId();
            sums.merge(k, b.getQty(), BigDecimal::add);
            keys.putIfAbsent(k, new Long[]{b.getSkuId(), b.getWarehouseId()});
        }
        List<Object[]> out = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> e : sums.entrySet()) {
            Long[] key = keys.get(e.getKey());
            out.add(new Object[]{key[0], key[1], e.getValue()});
        }
        return out;
    }

    private long countByBillNoPrefix(String prefix) {
        String regex = prefix.replace("%", ".*").replace("_", ".");
        Pattern p = Pattern.compile(regex);
        Set<String> nos = new HashSet<>();
        for (LedgerRow r : visibleRows()) {
            if (p.matcher(r.bill.getBillNo()).matches()) {
                nos.add(r.bill.getBillNo());
            }
        }
        return nos.size();
    }

    private List<ErpStockBill> findByBillNo(String billNo) {
        List<LedgerRow> rows = new ArrayList<>();
        for (LedgerRow r : visibleRows()) {
            if (billNo.equals(r.bill.getBillNo())) {
                rows.add(r);
            }
        }
        rows.sort(Comparator.comparingInt(r -> r.bill.getLineNo()));
        List<ErpStockBill> out = new ArrayList<>();
        for (LedgerRow r : rows) {
            out.add(r.bill);
        }
        return out;
    }

    private List<ErpStockBill> flowOf(Long skuId, Long whId, Pageable pageable) {
        List<LedgerRow> rows = new ArrayList<>();
        for (LedgerRow r : visibleRows()) {
            if (isVoided(r)) {
                continue;
            }
            ErpStockBill b = r.bill;
            if (skuId.equals(b.getSkuId()) && whId.equals(b.getWarehouseId())) {
                rows.add(r);
            }
        }
        rows.sort(Comparator.comparingLong((LedgerRow r) -> r.bill.getCreatedAt()).reversed()
                .thenComparing(Comparator.comparingLong((LedgerRow r) -> r.id).reversed()));
        int from = (int) pageable.getOffset();
        int to = Math.min(rows.size(), from + pageable.getPageSize());
        List<ErpStockBill> out = new ArrayList<>();
        for (int i = from; i < to; i++) {
            out.add(rows.get(i).bill);
        }
        return out;
    }

    private BigDecimal sumSigned(Long ts, boolean in) {
        BigDecimal sum = BigDecimal.ZERO;
        for (LedgerRow r : visibleRows()) {
            if (isVoided(r)) {
                continue;
            }
            ErpStockBill b = r.bill;
            if (ErpStockBill.TYPE_TRANSFER.equals(b.getBillType())) {
                continue;
            }
            long eff = b.getBizDate() != null ? b.getBizDate() : b.getCreatedAt();
            if (eff < ts) {
                continue;
            }
            if (in && b.getQty().signum() > 0) {
                sum = sum.add(b.getQty());
            }
            if (!in && b.getQty().signum() < 0) {
                sum = sum.add(b.getQty());
            }
        }
        return sum;
    }

    private List<Object[]> dailyInOut(Long fromTs) {
        Map<String, BigDecimal[]> byDay = new LinkedHashMap<>();
        for (LedgerRow r : visibleRows()) {
            if (isVoided(r)) {
                continue;
            }
            ErpStockBill b = r.bill;
            if (ErpStockBill.TYPE_TRANSFER.equals(b.getBillType())) {
                continue;
            }
            long eff = b.getBizDate() != null ? b.getBizDate() : b.getCreatedAt();
            if (eff < fromTs) {
                continue;
            }
            String day = java.time.Instant.ofEpochMilli(eff)
                    .atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString();
            BigDecimal[] pair = byDay.computeIfAbsent(day, k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            if (b.getQty().signum() > 0) {
                pair[0] = pair[0].add(b.getQty());
            } else {
                pair[1] = pair[1].add(b.getQty());
            }
        }
        List<Object[]> out = new ArrayList<>();
        byDay.forEach((day, pair) -> out.add(new Object[]{day, pair[0], pair[1]}));
        return out;
    }

    private int voidByBillNo(String billNo, Long ts, String by) {
        int n = 0;
        for (LedgerRow r : visibleRows()) {
            if (billNo.equals(r.bill.getBillNo()) && !isVoided(r)) {
                voidedAt.put(r.id, ts);
                r.bill.setVoidedAt(ts);
                r.bill.setVoidedBy(by);
                n++;
            }
        }
        return n;
    }

    private long countHdr(java.util.function.Predicate<ErpStockBillHdr> p) {
        long n = 0;
        for (ErpStockBillHdr h : headers.values()) {
            if (p.test(h)) {
                n++;
            }
        }
        return n;
    }

    /** 实体没有 setId（@GeneratedValue），用反射写入 */
    private static void setId(Object target, Long id) {
        try {
            Field f = target.getClass().getDeclaredField("id");
            f.setAccessible(true);
            f.set(target, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("给实体写 id 失败：" + target.getClass().getName(), e);
        }
    }
}
