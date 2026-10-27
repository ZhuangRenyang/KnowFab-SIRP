package com.factory.hub.erp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 升级兼容：给"一行即一单"时代的生产入库单补出单头（ROADMAP DH-43b）。
 * <p>
 * 为什么不在 SQL 迁移里做：单头要算合计数量/金额、要写 line_count，用 SQL 写一遍
 * 聚合再插一遍，逻辑重复且不好测；放在启动后用 JPA 做，幂等（只挑 header_id 为空的行），
 * 失败也只是下一轮再补，绝不阻断启动——车间服务器起不来比少一个单头严重得多。
 */
@Component
public class ErpStockBackfill implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ErpStockBackfill.class);

    private final ErpStockService stockService;

    public ErpStockBackfill(ErpStockService stockService) {
        this.stockService = stockService;
    }

    @Override
    public void run(ApplicationArguments args) {
        long t0 = System.currentTimeMillis();
        try {
            int n = stockService.backfillHeaders();
            if (n > 0) {
                log.info("历史单据单头补齐 {} 张，耗时 {} ms", n, System.currentTimeMillis() - t0);
            }
        } catch (Exception e) {
            // 不抛：单头缺失只影响单据列表显示，不影响库存计算（库存只认明细行）
            log.warn("历史单据单头补齐失败（不影响库存，下次启动重试）：{}", e.getMessage());
        }
    }
}
