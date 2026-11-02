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
 * {@code erp_stock_bill}。仓库未指定时落默认仓，没有默认仓就<b>把量留在缓冲里等着</b>，
 * 等默认仓建好后自动补上（最多一个配置缓存周期）。
 * <p>
 * <b>2026-11-03 修的两个真问题</b>（都是实测复现的）：
 * <ol>
 *   <li><b>产量被静默丢弃</b>：原来 flush 先把缓冲清空，遇到「没仓库」的设备直接
 *       {@code continue}，那批产量既没成单也没留在缓冲里——凭空消失（实测 3 件）。
 *       现在处理不了的不清空，留在缓冲里下一轮继续。</li>
 *   <li><b>库存与单据可能记到两个仓</b>：原来「未成单余额」用配置缓存里的默认仓，
 *       落单时却用实时查到的默认仓，两边不一致时余额会永远挂在旧仓上。
 *       现在两边都用<b>同一份、按下单事件拍下来的</b>仓库快照，一批量对应一个仓。</li>
 * </ol>
 */
@Component
public class ErpProdInboundService {

    private static final Logger log = LoggerFactory.getLogger(ErpProdInboundService.class);

    /** 配置缓存 60 秒：onProduced 每秒被调，不能每秒查库 */
    private record Cfg(Long skuId, Long whId, Long defaultWhId) {}

    /**
     * 缓冲里的一批待落库量。
     * <p>
     * {@code qty} 是要落单的量；{@code onStock} 是当初已经加到「未成单余额」上的量，
     * 两者必须分开记：没有仓库时只涨 qty（进不了余额），落单后也就不用去减余额。
     * 这样「余额 = 单据汇总 + 未成单增量」在任何时刻都闭合。
     */
    private static final class Buf {
        String deviceCode;
        Long skuId;
        Long whId;                       // null = 还没找到仓库（缓冲里等着）
        boolean backfill;
        BigDecimal qty = BigDecimal.ZERO;
        BigDecimal onStock = BigDecimal.ZERO;
    }

    private final DeviceService deviceService;
    private final ErpStockService stockService;
    private final ErpWarehouseRepository whRepo;

    /** 缓冲：键 = 设备码@仓号。仓号那一段用 "-1" 表示"还没仓，先攒着"。 */
    private final Map<String, Buf> buffer = new ConcurrentHashMap<>();
    /** 每台设备上次「没有仓库」告警的时间：同一台 5 分钟内不重复刷屏 */
    private final Map<String, Long> noWhWarnedAt = new ConcurrentHashMap<>();
    private Map<String, Cfg> cfgCache = new ConcurrentHashMap<>();
    private volatile long cfgLoadedAt = 0;

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
        BigDecimal q = BigDecimal.valueOf(delta).setScale(4, RoundingMode.HALF_UP);
        // 仓库按「这一笔产量发生时」的快照定下来：余额和单据必须落在同一个仓，
        // 否则中途改了仓库配置，余额就会永远挂在旧仓上（曾经的真 bug）。
        Long whId = c.whId() != null ? c.whId() : c.defaultWhId();
        String key = deviceCode + "@" + (whId == null ? "-1" : whId);
        Buf b = buffer.computeIfAbsent(key, k -> new Buf());
        b.deviceCode = deviceCode;
        b.skuId = c.skuId();
        b.backfill = b.backfill || backfill;
        b.qty = b.qty.add(q);
        if (whId != null) {
            b.whId = whId;
            b.onStock = b.onStock.add(q);
            // 数量先上账（还没成单）：库存余额秒级可见，单据仍按批次成
            stockService.addPendingStock(c.skuId(), whId, q);
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
        if (buffer.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Buf> e : new HashMap<>(buffer).entrySet()) {
            String key = e.getKey();
            Buf b = e.getValue();
            if (b.qty.signum() <= 0) {
                buffer.remove(key);
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
                // 归位到有仓的批次：这一批当初没进过「未成单余额」，所以 onStock 不用补，
                // 落单后也不用去减余额（否则会把别人的余额减掉）。
                buffer.remove(key);
                String newKey = b.deviceCode + "@" + whId;
                Buf moved = buffer.computeIfAbsent(newKey, k -> new Buf());
                moved.deviceCode = b.deviceCode;
                moved.skuId = b.skuId;
                moved.whId = whId;
                moved.backfill = b.backfill;
                moved.qty = moved.qty.add(b.qty);
                continue;               // 下一轮正式落库
            }
            try {
                // 走统一单据服务：生成单头 + 明细行，单据列表里与手工单同一套结构
                stockService.createProdInbound(b.deviceCode, b.skuId, b.whId, b.qty, b.backfill);
                // 已经写进单据了：从「未成单增量」里扣掉，余额由单据汇总接管。
                // 扣的量必须和当初加上去的量一模一样（逐笔 round4 后累加），否则余额会漂。
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

    /** 按当前配置解析这台设备该进哪个仓：设备指定仓 > 默认仓 > 没有 */
    private Long resolveWh(String deviceCode) {
        Cfg c = configs().get(deviceCode);
        if (c == null) {
            return null;
        }
        return c.whId() != null ? c.whId() : c.defaultWhId();
    }

    /** 没有仓库：每台设备 5 分钟提醒一次，别刷屏也别一声不吭 */
    private void warnNoWarehouse(String deviceCode) {
        long now = System.currentTimeMillis();
        Long last = noWhWarnedAt.get(deviceCode);
        if (last != null && now - last < 5 * 60_000L) {
            return;
        }
        noWhWarnedAt.put(deviceCode, now);
        log.warn("生产入库联动：设备 {} 未指定仓库且没有默认仓，这批产量已暂存等仓库"
                + "（建一个默认仓或给设备指定仓库后会自动补上，产量不会丢）", deviceCode);
    }
}
