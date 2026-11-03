package com.factory.hub.erp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 启动时把「禁发仓」清单打出来，让人确认一次。
 * <p>
 * <b>为什么需要这个东西</b>：1.7.0 迁移脚本会按仓名把"待处理/退货/待检/特采/让步"这些仓
 * 自动标成 {@link ErpWarehouse#KIND_BLOCKED}，省掉客户逐个手改。但自动推断一定会误判——
 * 一个名叫「待处理仓」的仓，在有些厂里其实就是普通周转仓。
 * <p>
 * <b>误判的后果不对称</b>：误设成 BLOCKED → 那个仓的货发不出去（仓管当场就会喊，能发现）；
 * 误设成 STANDARD → 放行了不该放的货（月底对账才发现，可能已经发出去了）。
 * 所以推断时偏向 BLOCKED，再用这条日志让人<b>主动确认一遍</b>。
 * <p>
 * 反过来，如果一个禁发仓都没有，也要提示：客户用到让步或质量退货时会卡在"没有禁发仓，
 * 货入不了库"，那时再让人临时建仓就晚了。
 */
@Component
@Order(121)
public class ErpWarehouseKindNotice implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ErpWarehouseKindNotice.class);

    private final ErpWarehouseRepository whRepo;

    public ErpWarehouseKindNotice(ErpWarehouseRepository whRepo) {
        this.whRepo = whRepo;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            List<ErpWarehouse> blocked = new ArrayList<>();
            for (ErpWarehouse w : whRepo.findAll()) {
                if (w.isBlocked()) {
                    blocked.add(w);
                }
            }
            if (blocked.isEmpty()) {
                log.info("没有配置禁发仓。用到让步接收或质量问题退货时，货需要入「待处理仓」，"
                        + "请到「基础资料 → 仓库」建一个并把类型设为禁发仓");
                return;
            }
            StringBuilder sb = new StringBuilder();
            for (ErpWarehouse w : blocked) {
                if (!sb.isEmpty()) {
                    sb.append("、");
                }
                sb.append(w.getCode()).append("(").append(w.getName()).append(")");
            }
            log.warn("禁发仓共 {} 个：{}。禁发仓的货默认不能出库，放行必须有客户同意凭证。"
                            + "如果其中有仓其实是可正常发货的，请到「基础资料 → 仓库」把类型改回正常仓——"
                            + "设错的后果是那个仓发不出货",
                    blocked.size(), sb);
        } catch (Exception e) {
            // 只做提示，失败不影响启动
            log.debug("禁发仓检查跳过：{}", e.getMessage());
        }
    }
}
