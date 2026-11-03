package com.factory.hub.quality;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 双通道对账接口（QC-SPEC §4.2）。
 * <p>
 * <b>两个方向要分开做</b>：对账条上"设备 30 / 质检 24 / 差 -6"，
 * 往下有两个方向相反的动作（一个减不良数、一个增不良数），
 * 混成一个"同步"按钮，现场谁点谁背锅。
 */
@RestController
@RequestMapping("/api/erp/qc/recon")
public class ErpQcReconController {

    private final ErpQcReconService service;

    public ErpQcReconController(ErpQcReconService service) {
        this.service = service;
    }

    /**
     * 对账条列表。{@code gap} 与 {@code challenged} 在后端算好，
     * 前端直接显示，不自己减（差多少、算不算冲突是业务口径）。
     */
    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) Long from,
                                    @RequestParam(required = false) Long to) {
        try {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            List<Map<String, Object>> items = service.list(from, to);
            out.put("items", items);
            out.put("total", items.size());
            return out;
        } catch (Exception e) {
            return ErpQcBody.fail(e);
        }
    }

    /**
     * 逐工单定责：{@code choice = TRUST_QC | TRUST_DEVICE}，必须写原因。
     * 校准只进质量域，{@code prod_hourly} 不动（OEE 的唯一数据源不能碰）。
     */
    @PostMapping("/{workOrderNo}/resolve")
    public Map<String, Object> resolve(@PathVariable String workOrderNo,
                                       @RequestBody Map<String, Object> body) {
        try {
            return service.resolve(workOrderNo,
                    ErpQcBody.str(body.get("choice")),
                    ErpQcBody.str(body.get("reason")),
                    ErpQcDefectController.operator());
        } catch (Exception e) {
            return ErpQcBody.fail(e);
        }
    }
}
