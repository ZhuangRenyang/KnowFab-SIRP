package com.factory.hub.quality;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.factory.hub.quality.ErpQcBody.longOf;
import static com.factory.hub.quality.ErpQcBody.str;

/**
 * 不良品记录接口（QC-SPEC §4.2）。
 * <p>
 * <b>权限</b>：登记与处置动作 = ADMIN/MANAGER/LEADER（现场要能登记），
 * 但撤销与让步确认留给 ADMIN/MANAGER（见 {@code SecurityConfig}）。
 * <p>
 * 失败一律 200 + {@code ok:false}，消息直接给现场看。
 */
@RestController
@RequestMapping("/api/erp/qc/defects")
public class ErpQcDefectController {

    private final ErpQcDefectService defectService;
    private final ErpQcDefectQueryService queryService;
    private final ErpDefectSyncService syncService;

    public ErpQcDefectController(ErpQcDefectService defectService,
                                 ErpQcDefectQueryService queryService,
                                 ErpDefectSyncService syncService) {
        this.defectService = defectService;
        this.queryService = queryService;
        this.syncService = syncService;
    }

    /**
     * 列表：severity / source / status / triaged / partial / from / to / kw / page / size
     * <p>
     * {@code partial=true} 筛「处置了一半」的记录（{@code disposed_qty > 0 AND disposed_qty < qty}）。
     * <b>不为它新增第四种状态</b>：那样 {@code status='PENDING'} 的筛选会漏掉部分处置的行，
     * 8 件处置 3 件的单会从待处置队列里消失。
     */
    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) String status,
                                    @RequestParam(required = false) String source,
                                    @RequestParam(required = false) String severity,
                                    @RequestParam(required = false) Boolean triaged,
                                    @RequestParam(required = false) Boolean partial,
                                    @RequestParam(required = false) Long from,
                                    @RequestParam(required = false) Long to,
                                    @RequestParam(required = false) String kw,
                                    @RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "20") int size) {
        try {
            return queryService.list(status, source, severity, triaged, partial, from, to, kw, page, size);
        } catch (Exception e) {
            return ErpQcBody.fail(e);
        }
    }

    /** 详情：单头 + 处置明细 + 撤销次数 */
    @GetMapping("/{no}")
    public Map<String, Object> detail(@PathVariable String no) {
        try {
            return queryService.detail(no);
        } catch (Exception e) {
            return ErpQcBody.fail(e);
        }
    }

    /**
     * 分诊：给一条不良记录补缺陷分类。
     * <p>
     * 设备通道采来的记录天生没有缺陷代码（机器只知道坏了几件，不知道为什么坏），
     * 全部落在帕累托的「未分类」行里。没有这个入口，那批记录永远分不掉。
     */
    @PostMapping("/{no}/triage")
    public Map<String, Object> triage(@PathVariable String no,
                                      @RequestBody Map<String, Object> body) {
        try {
            return queryService.triage(no, body, operator());
        } catch (IllegalArgumentException e) {
            return ErpQcBody.fail(e);
        }
    }

    /** 人工补录不良记录 */
    @PostMapping
    public Map<String, Object> register(@RequestBody Map<String, Object> body) {
        try {
            return queryService.registerManual(body, operator());
        } catch (Exception e) {
            return ErpQcBody.fail(e);
        }
    }

    /** 报废 */
    @PostMapping("/{no}/scrap")
    public Map<String, Object> scrap(@PathVariable String no, @RequestBody(required = false) Map<String, Object> body) {
        return dispose(no, ErpQcDisposition.ACT_SCRAP, body);
    }

    /** 返工 */
    @PostMapping("/{no}/rework")
    public Map<String, Object> rework(@PathVariable String no, @RequestBody(required = false) Map<String, Object> body) {
        return dispose(no, ErpQcDisposition.ACT_REWORK, body);
    }

    /** 让步接收 */
    @PostMapping("/{no}/concession")
    public Map<String, Object> concession(@PathVariable String no, @RequestBody(required = false) Map<String, Object> body) {
        return dispose(no, ErpQcDisposition.ACT_CONCESSION, body);
    }

    private Map<String, Object> dispose(String no, String action, Map<String, Object> body) {
        try {
            return defectService.dispose(no, action, body == null ? Map.of() : body, operator());
        } catch (Exception e) {
            return ErpQcBody.fail(e);
        }
    }

    /** 撤销一条处置：先冲销它产生的库存单据，再把处置行标作废 */
    @PostMapping("/{no}/revoke")
    public Map<String, Object> revoke(@PathVariable String no,
                                      @RequestBody Map<String, Object> body) {
        try {
            Long dispId = longOf(body.get("dispositionId"));
            if (dispId == null) {
                throw new IllegalArgumentException("请指定要撤销的处置记录（dispositionId）");
            }
            return defectService.revoke(no, dispId, str(body.get("reason")), operator());
        } catch (Exception e) {
            return ErpQcBody.fail(e);
        }
    }

    /** 批量处置：逐条处理，失败的进 skipped，不整体回滚 */
    @PostMapping("/batch-dispose")
    public Map<String, Object> batchDispose(@RequestBody Map<String, Object> body) {
        try {
            List<String> nos = new ArrayList<>();
            Object raw = body.get("recordNos");
            if (raw instanceof List<?> list) {
                for (Object o : list) {
                    String s = str(o);
                    if (s != null) {
                        nos.add(s);
                    }
                }
            }
            String action = str(body.get("action"));
            if (action == null) {
                throw new IllegalArgumentException("请指定处置方式");
            }
            return queryService.batchDispose(nos, action, body, operator());
        } catch (Exception e) {
            return ErpQcBody.fail(e);
        }
    }

    /** 双通道对照：设备采了多少、人工补了多少 */
    @GetMapping("/device-baseline")
    public Map<String, Object> deviceBaseline(@RequestParam String workOrderNo) {
        try {
            return queryService.deviceBaseline(workOrderNo);
        } catch (Exception e) {
            return ErpQcBody.fail(e);
        }
    }

    /** 手动触发一次设备通道同步（补数、修历史数据时用） */
    @PostMapping("/sync-device")
    public Map<String, Object> syncDevice() {
        try {
            int n = syncService.sync(System.currentTimeMillis());
            Map<String, Object> out = ErpQcBody.ok("设备通道同步完成，处理 " + n + " 条");
            out.put("processed", n);
            return out;
        } catch (Exception e) {
            return ErpQcBody.fail(e);
        }
    }

    static String operator() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null || a.getName() == null || a.getName().isBlank()
                || "anonymousUser".equals(a.getName())) {
            return "system";
        }
        return a.getName();
    }

    /** 预留：接口返回体统一结构（与其它质量接口一致） */
    static Map<String, Object> emptyBody() {
        return new LinkedHashMap<>();
    }
}
