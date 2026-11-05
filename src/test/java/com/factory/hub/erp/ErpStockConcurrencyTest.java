package com.factory.hub.erp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * <b>DH-80：并发与行锁（既有铁律，收口时不能改坏）。</b>
 *
 * <p><b>三条铁律</b>（{@code ErpStockService} 类注释里写的）：
 * <ol>
 *   <li>出库先查可用量，<b>不允许把库存做成负数</b>；</li>
 *   <li>查库存必须用 {@code stockOfForUpdate}（{@code SELECT ... FOR UPDATE}），
 *       锁到外层事务提交为止；</li>
 *   <li>加锁前<b>按 SKU 排序</b>，所有请求用同一个加锁顺序，避免死锁。</li>
 * </ol>
 *
 * <p><b>为什么 Java 层的 synchronized 不够</b>：它只挡得住本进程，
 * 而且挡不住「事务在锁外提交」这个窗口——两个销售员同时发货，
 * 各自读到同一份「够」的数量，然后各自扣减，就是超卖。
 * {@code FOR UPDATE} 把行锁到外层事务提交为止，
 * 后到的那个请求会等前一个提交完，再读到已经扣减过的库存。
 *
 * <p><b>本类怎么让「没上锁」这件事一定会被抓住</b>（而不是靠运气）：
 * <ul>
 *   <li>夹具的内存账本实现了<b>事务隔离</b>：未提交的写入别的线程看不见；</li>
 *   <li>{@code stockOfForUpdate} 会真的拿一把行锁，持有到事务结束；</li>
 *   <li>{@link ErpStockTestKit#armRaceBarrier} 让<b>无锁读</b>的两个并发线程
 *       在同一时刻取快照 —— 于是「没上 FOR UPDATE」必然超卖，测试必红。</li>
 * </ul>
 */
class ErpStockConcurrencyTest {

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    private ErpStockTestKit fx() {
        ErpStockTestKit kit = new ErpStockTestKit();
        kit.sku(1L, "SKU-A", "成品A", bd("100"), null);
        kit.wh(1L, "WH1", "主仓");
        return kit;
    }

    /** 出一张销售出库单 */
    private void noop() {
        // 占位：本类的出库动作都写在各自的用例里（要断言锁调用顺序，不好抽公共方法）
    }

    /* ================================================================
     * 1. 并发出库不能把库存做成负数
     * ================================================================ */

    @Test
    @Timeout(60)
    @DisplayName("并发出库不能把库存做成负数（FOR UPDATE 必须真的生效）")
    void concurrentOutboundMustNotMakeStockNegative() throws Exception {
        ErpStockTestKit kit = fx();
        ErpStockService seed = kit.stockService();
        seed.createProdInbound("DEV1", 1L, 1L, bd("10"), false);   // 库存 10

        // 两个「应用实例」：pending 与单号缓存都是实例级的，跨实例才有真并发。
        // 同一个实例上的 createManual 是 synchronized 的，挡不住跨进程竞争。
        ErpStockService appA = kit.stockService();
        ErpStockService appB = kit.stockService();

        // 打开竞态栅栏：若实现用的是「无锁读」，两个线程必须取到同一时刻的快照
        kit.armRaceBarrier(2);

        AtomicInteger ok = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> f1 = pool.submit(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
                ErpStockService svc = appA;
                try {
                    kit.inTransaction(() -> svc.createManual(
                            ErpStockBill.TYPE_SO_OUT, null, 1L, null, "并发销售出库", "tester",
                            List.of(new ErpStockService.LineReq(1L, bd("8"), null)), null));
                    ok.incrementAndGet();
                } catch (IllegalArgumentException e) {
                    rejected.incrementAndGet();
                }
                return null;
            });
            Future<?> f2 = pool.submit(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
                ErpStockService svc = appB;
                try {
                    kit.inTransaction(() -> svc.createManual(
                            ErpStockBill.TYPE_SO_OUT, null, 1L, null, "并发销售出库", "tester",
                            List.of(new ErpStockService.LineReq(1L, bd("8"), null)), null));
                    ok.incrementAndGet();
                } catch (IllegalArgumentException e) {
                    rejected.incrementAndGet();
                }
                return null;
            });
            start.countDown();
            f1.get(50, TimeUnit.SECONDS);
            f2.get(50, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
            kit.disarmRaceBarrier();
        }

        BigDecimal balance = kit.balanceQty(seed, 1L, 1L);
        assertTrue(balance.compareTo(BigDecimal.ZERO) >= 0,
                "并发出库后库存是 " + balance.toPlainString()
                        + " —— 负库存在车间里等于账已经废了。库存 10，两个线程各出 8，"
                        + "没上 FOR UPDATE 时两个都会读到 10 然后各扣 8，余额就成 -6 了。");
        assertEquals(1, ok.get(),
                "库存 10、两笔各 8：一笔必须成功、另一笔必须被「库存不足」拒绝。"
                        + "实际成功 " + ok.get() + " 笔、被拒 " + rejected.get() + " 笔");
        assertEquals(1, rejected.get(), "另一笔必须明确报「库存不足」，不能悄悄放行");
        assertEquals(0, bd("2").compareTo(balance), "最终余额必须是 2（10 − 8）");
    }

    /* ================================================================
     * 2. 必须用带行锁的读
     * ================================================================ */

    @Test
    @DisplayName("出库查库存必须用 stockOfForUpdate（FOR UPDATE），不能只用普通读")
    void outboundMustReadStockWithForUpdate() {
        ErpStockTestKit kit = fx();
        ErpStockService svc = kit.stockService();
        svc.createProdInbound("DEV1", 1L, 1L, bd("10"), false);

        kit.inTransaction(() -> svc.createManual(
                ErpStockBill.TYPE_SO_OUT, null, 1L, null, "销售出库", "tester",
                List.of(new ErpStockService.LineReq(1L, bd("3"), null)), null));

        verify(kit.billRepo, org.mockito.Mockito.atLeastOnce())
                .stockOfForUpdate(eq(1L), eq(1L));
        verify(kit.billRepo, never()).stockOf(eq(1L), eq(1L));
    }

    @Test
    @DisplayName("盘点查库存也必须用 stockOfForUpdate（盘点时有人在发货，差异常常算错）")
    void stocktakeMustReadStockWithForUpdate() {
        ErpStockTestKit kit = fx();
        ErpStockService svc = kit.stockService();
        svc.createProdInbound("DEV1", 1L, 1L, bd("10"), false);

        kit.inTransaction(() -> svc.createStocktake(1L, "tester", "盘点",
                List.of(new ErpStockService.LineReq(1L, bd("12"), null))));

        verify(kit.billRepo, org.mockito.Mockito.atLeastOnce())
                .stockOfForUpdate(eq(1L), eq(1L));
        verify(kit.billRepo, never()).stockOf(eq(1L), eq(1L));
    }

    /* ================================================================
     * 3. 加锁顺序：必须按 SKU 升序（防死锁）
     * ================================================================ */

    @Test
    @DisplayName("多个 SKU 出库时，加锁顺序必须按 SKU 升序（两个请求锁序相反会死锁）")
    void stockLocksMustBeTakenInSkuOrder() {
        ErpStockTestKit kit = new ErpStockTestKit();
        kit.sku(10L, "SKU-B", "成品B", bd("100"), null);
        kit.sku(20L, "SKU-A", "成品A", bd("100"), null);
        kit.wh(1L, "WH1", "主仓");
        ErpStockService svc = kit.stockService();
        svc.createProdInbound("DEV1", 10L, 1L, bd("5"), false);
        svc.createProdInbound("DEV1", 20L, 1L, bd("5"), false);

        // 单据行故意按 skuId 倒序传入：实现必须自己排序后再加锁
        kit.inTransaction(() -> svc.createManual(
                ErpStockBill.TYPE_SO_OUT, null, 1L, null, "两行出库", "tester",
                List.of(new ErpStockService.LineReq(20L, bd("1"), null),
                        new ErpStockService.LineReq(10L, bd("1"), null)),
                null));

        InOrder ordered = inOrder(kit.billRepo);
        ordered.verify(kit.billRepo).stockOfForUpdate(eq(10L), eq(1L));
        ordered.verify(kit.billRepo).stockOfForUpdate(eq(20L), eq(1L));
    }

    @Test
    @DisplayName("库存不足时必须明确拒绝，不能放行（负库存的铁律）")
    void outboundBeyondStockMustBeRejected() {
        ErpStockTestKit kit = fx();
        ErpStockService svc = kit.stockService();
        svc.createProdInbound("DEV1", 1L, 1L, bd("10"), false);

        IllegalArgumentException ex = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> kit.inTransaction(() -> svc.createManual(
                        ErpStockBill.TYPE_SO_OUT, null, 1L, null, "超量出库", "tester",
                        List.of(new ErpStockService.LineReq(1L, bd("11"), null)), null)));
        if (!ex.getMessage().contains("库存不足")) {
            fail("错误信息必须说清「库存不足」，实际是：" + ex.getMessage());
        }
        assertEquals(0, bd("10").compareTo(kit.balanceQty(svc, 1L, 1L)),
                "被拒绝的单据不能落库，库存必须还是 10");
    }
}
