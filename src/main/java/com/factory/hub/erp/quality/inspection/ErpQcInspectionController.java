package com.factory.hub.erp.quality.inspection;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static com.factory.hub.erp.ErpQcBody.str;
import com.factory.hub.erp.quality.defect.ErpQcDefectController;
import com.factory.hub.erp.ErpQcBody;

/**
 * 检验单接口（QC-SPEC §4.2）。
 * <p>
 * <b>权限</b>（{@code SecurityConfig} 里排在 {@code /api/erp/**} 通配之前）：
 * 建单 / 改草稿 / 提交 / 判定 / 作废 = ADMIN / MANAGER；
 * 判定与作废要更严的话在配置里单独收口——<b>判定权不外包</b>，
 * 班组长自己录自己判等于没质检。
 */
@RestController
@RequestMapping("/api/erp/qc/inspections")
public class ErpQcInspectionController {

    private final ErpQcInspectionService service;

    public ErpQcInspectionController(ErpQcInspectionService service) {
        this.service = service;
    }

    /** 建单（草稿）。抽样参数在这一步抄成快照 */
    /**
     * 检验单列表。
     * <p>
     * {@code from}/{@code to} 是毫秒时间戳。
     */
    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) String type,
                                    @RequestParam(required = false) String status,
                                    @RequestParam(required = false) Long from,
                                    @RequestParam(required = false) Long to,
                                    @RequestParam(required = false) String kw,
                                    @RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "20") int size) {
        try {
            return service.list(type, status, from, to, kw, page, size);
        } catch (Exception e) {
            return ErpQcBody.fail(e);
        }
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        try {
            return service.create(body, operator());
        } catch (ErpAqlService.NoPlanException e) {
            // 主数据缺方案 → 独立错误码，前端据此显示"去补方案 / 临时全检"两个出路
            return ErpQcBody.fail("NO_AQL_PLAN", e);
        } catch (Exception e) {
            return ErpQcBody.fail(e);
        }
    }

    /** 改草稿：明细整表替换 */
    @PutMapping("/{no}")
    public Map<String, Object> update(@PathVariable String no,
                                      @RequestBody Map<String, Object> body) {
        try {
            return service.updateDraft(no, body, operator());
        } catch (ErpAqlService.NoPlanException e) {
            return ErpQcBody.fail("NO_AQL_PLAN", e);
        } catch (Exception e) {
            return ErpQcBody.fail(e);
        }
    }

    /** 提交：锁定快照 */
    @PostMapping("/{no}/submit")
    public Map<String, Object> submit(@PathVariable String no) {
        try {
            return service.submit(no, operator());
        } catch (Exception e) {
            return ErpQcBody.fail(e);
        }
    }

    /**
     * 判定。结论由纯函数算法算，人工只能改成「让步接收」且必须写原因。
     */
    @PostMapping("/{no}/judge")
    public Map<String, Object> judge(@PathVariable String no,
                                      @RequestBody(required = false) Map<String, Object> body) {
        try {
            return service.judge(no, body == null ? Map.of() : body, operator());
        } catch (Exception e) {
            return ErpQcBody.fail(e);
        }
    }

    /** 作废：必须填原因；已判定的不可作废 */
    @PostMapping("/{no}/cancel")
    public Map<String, Object> cancel(@PathVariable String no,
                                      @RequestBody(required = false) Map<String, Object> body) {
        try {
            return service.cancel(no, str(body == null ? null : body.get("reason")), operator());
        } catch (Exception e) {
            return ErpQcBody.fail(e);
        }
    }

    /** 详情：单头 + 明细 + 关联不良记录 */
    @GetMapping("/{no}")
    public Map<String, Object> detail(@PathVariable String no) {
        try {
            return service.detail(no);
        } catch (Exception e) {
            return ErpQcBody.fail(e);
        }
    }

    private static String operator() {
        return ErpQcDefectController.operator();
    }
}
