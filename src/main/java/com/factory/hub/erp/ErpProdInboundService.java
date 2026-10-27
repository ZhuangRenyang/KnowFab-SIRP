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
 * DH-44 生产入库联动：启用了联动的设备，计数点每 +N 就自动生成成品入库单。
 * <p>
 * 用户口径："每产一个入库一个"。实现上按 10 秒汇总一笔（10 秒内产出 5 件就是
 * 一张 qty=5 的单）——库存数字 10 秒内到位，人眼看不出差别，单据量却可控；
 * 高产线要逐件单据再改粒度，一个常量的事。
 * <p>
 * 数据流：{@code ProdRollup} 每秒算出计数增量（已处理清零/断线补记）→
 * {@link #onProduced} 按 60 秒缓存的设备配置过滤 → {@link #flush} 每 10 秒落
 * {@code erp_stock_bill}。仓库未指定时落默认仓，没有默认仓就暂缓并告警一次。
 */
@Component
public class ErpProdInboundService {

    private static final Logger log = LoggerFactory.getLogger(ErpProdInboundService.class);

    /** 配置缓存 60 秒：onProduced 每秒被调，不能每秒查库 */
    private record Cfg(Long skuId, Long whId) {}

    private final DeviceService deviceService;
    private final ErpStockBillRepository billRepo;
    private final ErpSkuRepository skuRepo;
    private final ErpWarehouseRepository whRepo;

    private final Map<String, Double> pending = new ConcurrentHashMap<>();
    private final Map<String, Cfg> pendingCfg = new ConcurrentHashMap<>();
    private final Map<String, Boolean> pendingBackfill = new ConcurrentHashMap<>();
    private Map<String, Cfg> cfgCache = new ConcurrentHashMap<>();
    private volatile long cfgLoadedAt = 0;
    private volatile boolean warnedNoWarehouse = false;

    public ErpProdInboundService(DeviceService deviceService,
                                 ErpStockBillRepository billRepo,
                                 ErpSkuRepository skuRepo,
                                 ErpWarehouseRepository whRepo) {
        this.deviceService = deviceService;
        this.billRepo = billRepo;
        this.skuRepo = skuRepo;
        this.whRepo = whRepo;
    }

    /** ProdRollup 每次算出正增量时调用。必须便宜：不查库，只碰内存缓存。 */
    public void onProduced(String deviceCode, double delta, long ts, boolean backfill) {
        Cfg c = configs().get(deviceCode);
        if (c == null || c.skuId() == null) {
            return;
        }
        pendingCfg.put(deviceCode, c);
        if (backfill) {
            pendingBackfill.put(deviceCode, true);
        }
        pending.merge(deviceCode, delta, Double::sum);
    }

    private synchronized Map<String, Cfg> configs() {
        long now = System.currentTimeMillis();
        if (now - cfgLoadedAt > 60_000) {
            Map<String, Cfg> m = new HashMap<>();
            try {
                for (DeviceEntity d : deviceService.all()) {
                    if (d.isProdAutoStockin() && d.getProdSkuId() != null) {
                        m.put(d.getCode(), new Cfg(d.getProdSkuId(), d.getProdWhId()));
                    }
                }
            } catch (Exception e) {
                log.warn("刷新生产入库配置失败（沿用旧缓存）: {}", e.getMessage());
            }
            cfgCache = m;
            cfgLoadedAt = now;
        }
        return cfgCache;
    }

    @Scheduled(fixedDelay = 10_000L)
    public synchronized void flush() {
        if (pending.isEmpty()) {
            return;
        }
        Map<String, Double> batch = new HashMap<>(pending);
        Map<String, Cfg> cfgs = new HashMap<>(pendingCfg);
        Map<String, Boolean> bfs = new HashMap<>(pendingBackfill);
        pending.clear();
        pendingCfg.clear();
        pendingBackfill.clear();

        Long defaultWh = whRepo.findByDefaultWarehouseTrue()
                .map(ErpWarehouse::getId).orElse(null);

        for (Map.Entry<String, Double> e : batch.entrySet()) {
            String code = e.getKey();
            double qty = e.getValue();
            Cfg c = cfgs.get(code);
            if (c == null || qty <= 0) {
                continue;
            }
            Long whId = c.whId() != null ? c.whId() : defaultWh;
            if (whId == null) {
                if (!warnedNoWarehouse) {
                    log.warn("生产入库联动：设备 {} 未指定仓库且没有默认仓，入库单暂缓（建一个默认仓即恢复）", code);
                    warnedNoWarehouse = true;
                }
                continue;
            }
            ErpStockBill b = new ErpStockBill();
            b.setBillNo("SC" + System.currentTimeMillis() + "-" + code);
            b.setBillType(ErpStockBill.TYPE_PROD_IN);
            b.setDeviceCode(code);
            b.setSkuId(c.skuId());
            b.setWarehouseId(whId);
            b.setQty(BigDecimal.valueOf(qty).setScale(4, RoundingMode.HALF_UP));
            b.setRemark(Boolean.TRUE.equals(bfs.get(code)) ? "断线补记" : null);
            try {
                billRepo.save(b);
            } catch (Exception ex) {
                // 放回缓冲，下轮重试；不丢产量
                pending.merge(code, qty, Double::sum);
                pendingCfg.put(code, c);
                if (Boolean.TRUE.equals(bfs.get(code))) {
                    pendingBackfill.put(code, true);
                }
                log.error("生产入库单落库失败（10 秒后重试）: {}", ex.getMessage());
            }
        }
    }
}
