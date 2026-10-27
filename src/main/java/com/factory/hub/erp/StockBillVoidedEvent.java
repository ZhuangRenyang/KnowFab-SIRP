package com.factory.hub.erp;

/**
 * 单据作废事件（DH-52）。
 * <p>
 * 为什么用事件而不是让 {@link ErpStockService} 直接调采购服务：库存是"被依赖方"，
 * 采购是"依赖方"（采购入库要调库存建单）。库存反过来调采购就成环，Spring 启动会报
 * circular dependency，或者要加 @Lazy 把依赖关系糊住。事件是单向的，谁关心谁监听。
 * <p>
 * 同步发布（默认行为），和作废处于同一个事务：监听到的时候，明细行的 voided_at 已经写进去了，
 * 重算订单进度自然把作废的那几张单排除掉。
 */
public record StockBillVoidedEvent(String billNo, String billType, String srcOrderNo) {
}
