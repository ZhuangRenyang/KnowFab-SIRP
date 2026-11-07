package com.factory.hub.erp.production;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.factory.hub.erp.stock.ErpStockBill;
import com.factory.hub.erp.stock.ErpStockService;

/**
 * 产出反冲领料（DH-71）——<b>必须独立事务</b>。
 * <p>
 * <b>为什么单独一个 Bean，而不是 {@code ErpWorkOrderService} 里的一个私有方法</b>：
 * 反冲是「顺带做」的事，产出才是主业务。两者在同一个事务里，反冲一失败（库存不足、
 * 仓库选错）整个事务被标记 rollback-only，<b>连产出一起回滚</b>——产量就这么丢了。
 * 实测复现过：一张工单产出 5 件，反冲扣料失败，结果 0 张入库单、0 件产出，
 * 而且缓冲里每 10 秒重试一次，反冲量还越滚越大（41.2 → 61.8 → 82.4 → 103），永远失败。
 * <p>
 * 用 {@code REQUIRES_NEW} 把反冲隔离出去：扣料成功就一起记上，失败只脏自己的事务，
 * 产出照常入账。缺料这件事由工单详情的「已领 &lt; 需求」体现，比丢掉产量诚实得多。
 * <p>
 * <b>为什么必须是独立 Bean</b>：Spring 的 {@code @Transactional} 走代理，
 * 同一个类里自己调自己不走代理，注解形同虚设。这也正是"写了 REQUIRES_NEW 却不生效"的常见坑。
 */
@Service
public class ErpBackflushService {

    private static final Logger log = LoggerFactory.getLogger(ErpBackflushService.class);

    private final ErpStockService stockService;
    private final ErpWorkOrderMaterialRepository womRepo;

    public ErpBackflushService(ErpStockService stockService,
                               ErpWorkOrderMaterialRepository womRepo) {
        this.stockService = stockService;
        this.womRepo = womRepo;
    }

    /**
     * 按「材料 → 用量」生成一张生产领料出库单，并回写工单的已领量。
     *
     * @param whId 必须是<b>领料仓</b>（原料仓）。传成品仓会永远扣不到料——
     *             这是修这个类之前实测踩到的坑：产出入成品仓、反冲却去成品仓找原料。
     * @return 出库单号；want 为空时返回 null（不该发生，调用方已经过滤过）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String run(String orderNo, Long workOrderId, Long whId, Map<Long, BigDecimal> want) {
        if (want == null || want.isEmpty()) {
            return null;
        }
        List<ErpStockService.LineReq> reqs = new ArrayList<>();
        for (Map.Entry<Long, BigDecimal> e : want.entrySet()) {
            if (e.getValue() != null && e.getValue().signum() > 0) {
                reqs.add(new ErpStockService.LineReq(e.getKey(), e.getValue(), null));
            }
        }
        if (reqs.isEmpty()) {
            return null;
        }
        ErpStockService.Created c = stockService.createManual(
                ErpStockBill.TYPE_MATERIAL_OUT, null, whId, null,
                "工单 " + orderNo + " 产出反冲", "system", reqs, orderNo, null);

        // 回写已领量：在内存里累加后整批保存。
        // 不能「先 UPDATE 累加、再 saveAll 内存对象」——saveAll 会把内存里的旧值写回去。
        List<ErpWorkOrderMaterial> mats = womRepo.findByWorkOrderIdOrderBySkuIdAsc(workOrderId);
        Map<Long, ErpWorkOrderMaterial> bySku = new HashMap<>();
        for (ErpWorkOrderMaterial m : mats) {
            bySku.put(m.getSkuId(), m);
        }
        for (Map.Entry<Long, BigDecimal> e : want.entrySet()) {
            ErpWorkOrderMaterial m = bySku.get(e.getKey());
            if (m != null && e.getValue() != null) {
                m.setIssuedQty((m.getIssuedQty() == null ? BigDecimal.ZERO : m.getIssuedQty())
                        .add(e.getValue()));
            }
        }
        womRepo.saveAll(mats);
        log.debug("工单 {} 反冲领料 {}", orderNo, c.billNo());
        return c.billNo();
    }
}
