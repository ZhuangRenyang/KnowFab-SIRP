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
    private record Cfg(Long skuId, Long whId, Long defaultWhId) {}

    private final DeviceService deviceService;
    private final ErpStockService stockService;
    private final ErpWarehouseRepository whRepo;

    private final Map<String, Double> pending = new ConcurrentHashMap<>();
    private final Map<String, Cfg> pendingCfg = new ConcurrentHashMap<>();
    private final Map<String, Boolean> pendingBackfill = new ConcurrentHashMap<>();
    private Map<String, Cfg> cfgCache = new ConcurrentHashMap<>();
    private volatile long cfgLoadedAt = 0;
    private volatile boolean warnedNoWarehouse = false;

    public ErpProdInboundService(DeviceService deviceService,
                                 ErpStockService stockService,
                                 ErpWarehouseRepository whRepo) {
        this.deviceService = deviceService;
        this.stockService = stockService;
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
        // 数量先上账（还没成单）：库存余额秒级可见，单据仍按批次成
        Long whId = c.whId() != null ? c.whId() : c.defaultWhId();
        if (whId != null) {
            stockService.addPendingStock(c.skuId(), whId,
                    BigDecimal.valueOf(delta).setScale(4, RoundingMode.HALF_UP));
        }
    }

    private synchronized Map<String, Cfg> configs() {
        long now = System.currentTimeMillis();
        if (now - cfgLoadedAt > 60_000) {
            Map<String, Cfg> m = new HashMap<>();
            try {
                Long defaultWh = whRepo.findByDefaultWarehouseTrue()
                        .map(ErpWarehouse::getId).orElse(null);
                for (DeviceEntity d : deviceService.all()) {
                    if (d.isProdAutoStockin() && d.getProdSkuId() != null) {
                        m.put(d.getCode(), new Cfg(d.getProdSkuId(), d.getProdWhId(), defaultWh));
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
            try {
                // 走统一单据服务：生成单头 + 明细行，单据列表里与手工单同一套结构
                BigDecimal qtyBig = BigDecimal.valueOf(qty).setScale(4, RoundingMode.HALF_UP);
                stockService.createProdInbound(code, c.skuId(), whId, qtyBig,
                        Boolean.TRUE.equals(bfs.get(code)));
                // 已经写进单据了：从「未成单增量」里扣掉，余额由单据汇总接管
                stockService.clearPendingStock(c.skuId(), whId, qtyBig);
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
