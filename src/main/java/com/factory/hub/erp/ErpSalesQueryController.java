package com.factory.hub.erp;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 销售查询（DH-45）：销售角色的唯一入口。
 * <p>
 * 只有两个端点，都是 GET：
 * <ul>
 *   <li>{@code /stock}：按关键词/类别/仓库查现存、订单占用、可承诺量与所在仓库</li>
 *   <li>{@code /export}：同一份数据导 xlsx（<b>不含成本</b>，可以直接发给客户）</li>
 * </ul>
 * 写接口一个都没有——销售不该能改库存，这是设计而不是没做完。
 */
@RestController
@RequestMapping("/api/erp/sales-query")
public class ErpSalesQueryController {

    private final ErpSalesQueryService service;

    public ErpSalesQueryController(ErpSalesQueryService service) {
        this.service = service;
    }

    @GetMapping("/stock")
    public Map<String, Object> stock(@RequestParam(required = false) String kw,
                                     @RequestParam(required = false) String category,
                                     @RequestParam(required = false) Long warehouseId,
                                     @RequestParam(defaultValue = "false") boolean onlyAvailable) {
        return service.stock(kw, category, warehouseId, onlyAvailable);
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@RequestParam(required = false) String kw,
                                         @RequestParam(required = false) String category,
                                         @RequestParam(required = false) Long warehouseId,
                                         @RequestParam(defaultValue = "false") boolean onlyAvailable) {
        try {
            Map<String, Object> data = service.stock(kw, category, warehouseId, onlyAvailable);
            byte[] body = ErpReportExcel.salesStock(data);
            String name = "销售库存查询-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + ".xlsx";
            String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"sales-stock.xlsx\"; filename*=UTF-8''" + encoded)
                    .contentType(MediaType.parseMediaType(
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(body);
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(("{\"ok\":false,\"message\":\"导出失败：" + safe(e.getMessage()) + "\"}")
                            .getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String safe(String s) {
        return s == null ? "未知错误" : s.replace("\"", "'").replace("\n", " ");
    }
}
