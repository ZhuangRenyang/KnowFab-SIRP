package com.factory.hub.erp.production;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 生产工单排程：**登记计划时间 + 检测同产线冲突 + 给甘特视图供数**。
 *
 * <p><b>本类不是自动排产。</b>自动排产（CP-SAT / 启发式求解）是另一个量级的工程，
 * 本次明确不做。这里的能力是：
 * <ul>
 *   <li>人给工单定计划时间（什么时候做哪张）</li>
 *   <li>系统告诉人<b>哪两张在同一产线上撞车了</b>（而不是撞了才知道）</li>
 *   <li>甘特视图让"这条产线接下来做什么"一眼看得见</li>
 * </ul>
 *
 * <p><b>口径（最易做错的几处，详见 db/migrations/1.9.0.sql）</b>：
 * <ol>
 *   <li><b>NULL = 未排程</b>：不占产线、不参与冲突、甘特图里单独一栏。</li>
 *   <li><b>半开区间 [start, end)</b>：前一张 end == 后一张 start <b>不算冲突</b>
 *       （交接班/换型时刻是正常衔接）。</li>
 *   <li><b>只有排了程的工单才参与冲突判定</b>。</li>
 *   <li><b>只有非终态工单参与冲突判定</b>：DONE / CANCELLED 不占产线
 *       （否则历史单会把现在的排程全判成冲突）。</li>
 *   <li><b>冲突粒度是产线（lineCode），不是设备</b>：产线上的设备本来可以并行工作
 *       （不同工位同时干），按设备判会把正常并行误报成冲突。
 *       工单没指定产线 → 不参与冲突判定。</li>
 * </ol>
 */
@Service
public class ErpScheduleService {

    /** 参与冲突判定的状态：非终态 */
    private static final Set<String> ACTIVE_STATUS =
            Set.of(ErpWorkOrder.ST_DRAFT, ErpWorkOrder.ST_RELEASED, ErpWorkOrder.ST_RUNNING);

    private final ErpWorkOrderRepository repo;

    public ErpScheduleService(ErpWorkOrderRepository repo) {
        this.repo = repo;
    }

    /** 一条冲突：同一产线上时间重叠的两张工单 */
    public static class Conflict {
        public String lineCode;
        public String aOrderNo;
        public String bOrderNo;
        public long overlapStart;
        public long overlapEnd;

        Conflict(String lineCode, String aOrderNo, String bOrderNo, long s, long e) {
            this.lineCode = lineCode;
            this.aOrderNo = aOrderNo;
            this.bOrderNo = bOrderNo;
            this.overlapStart = s;
            this.overlapEnd = e;
        }
    }

    /** 排程结果：能不能排 + 排完会和谁撞 */
    public static class ScheduleResult {
        public boolean ok = true;
        public String message = "";
        public List<Conflict> conflicts = new ArrayList<>();
        /** 只是提示（不阻断），比如"这张单还没定计划时间" */
        public List<String> warnings = new ArrayList<>();
    }

    /**
     * 给一张工单排程。
     *
     * @param force true = 即使有冲突也排（只提示），false = 有冲突就拒绝
     */
    @Transactional
    public ScheduleResult schedule(String orderNo, Long planStart, Long planEnd,
                                   boolean force, String lineCode) {
        ScheduleResult r = new ScheduleResult();
        ErpWorkOrder wo = repo.findByOrderNo(orderNo).orElse(null);
        if (wo == null) {
            r.ok = false;
            r.message = "工单不存在：" + orderNo;
            return r;
        }
        if (planStart == null || planEnd == null) {
            r.ok = false;
            r.message = "要填计划开始和结束时间";
            return r;
        }
        if (planEnd <= planStart) {
            r.ok = false;
            r.message = "计划结束时间必须晚于开始时间";
            return r;
        }
        if (!ACTIVE_STATUS.contains(wo.getStatus())) {
            r.warnings.add("工单当前状态是 " + wo.getStatus() + "（已结束/已取消），排程只作记录，不占产线");
        }
        // 冲突判定必须用**本次要排进去的产线**，不是工单原来的产线——
        // 原来写的是 wo.getLineCode()，而新传的 lineCode 这时还没写进实体，
        // 于是「原来没产线 → 按口径不参与判定」→ **冲突检测直接失效**
        //（真机实测：重叠的两张单排上去，系统说没冲突。2026-11-05）
        String targetLine = (lineCode != null) ? lineCode.trim() : wo.getLineCode();
        if (targetLine != null && targetLine.isEmpty()) {
            targetLine = null;      // 传空串 = 不占用产线
        }
        // 把自己排除掉再算冲突（重排同一张单不该跟自己冲突）
        List<Conflict> cs = conflictsAmong(List.of(wo.getId()), targetLine, planStart, planEnd);
        r.conflicts = cs;
        if (!cs.isEmpty() && !force) {
            r.ok = false;
            r.message = "与同产线的工单时间重叠：" + describe(cs);
            return r;
        }
        wo.setPlanStart(planStart);
        wo.setPlanEnd(planEnd);
        // 产线跟排程一起设置：排程的人本来就要指定「这条单排哪条线」。
        // 单独改工单的产线走不通（已下达的工单不让改，「只有草稿能改」）。
        // 留空表示不占用产线（不参与冲突判定）——**不覆盖原值**，除非显式传了值。
        if (lineCode != null) {
            String lc = lineCode.trim();
            wo.setLineCode(lc.isEmpty() ? null : lc);
        }
        repo.save(wo);
        if (!cs.isEmpty()) {
            r.message = "已排程（强制），但与 " + describe(cs) + " 有重叠";
        }
        return r;
    }

    /** 取消排程（回到未排程） */
    @Transactional
    public ScheduleResult unschedule(String orderNo) {
        ScheduleResult r = new ScheduleResult();
        ErpWorkOrder wo = repo.findByOrderNo(orderNo).orElse(null);
        if (wo == null) {
            r.ok = false;
            r.message = "工单不存在：" + orderNo;
            return r;
        }
        wo.setPlanStart(null);
        wo.setPlanEnd(null);
        repo.save(wo);
        r.message = "已取消排程";
        return r;
    }

    /**
     * 甘特数据：按产线分组，每条产线下是排了程的工单（按开始时间排序），
     * 另给一份「未排程」的工单列表。
     *
     * @param dateFrom 查询起点（含），null = 不限
     * @param dateTo   查询终点（不含），null = 不限
     */
    public Map<String, Object> gantt(Long dateFrom, Long dateTo) {
        List<ErpWorkOrder> all = repo.findAll();
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> lines = new ArrayList<>();
        List<Map<String, Object>> unscheduled = new ArrayList<>();

        for (ErpWorkOrder wo : all) {
            if (wo.getPlanStart() == null || wo.getPlanEnd() == null) {
                if (ACTIVE_STATUS.contains(wo.getStatus())) {
                    unscheduled.add(row(wo, null, null, false));
                }
                continue;
            }
            if (dateFrom != null && wo.getPlanEnd() <= dateFrom) {
                continue;
            }
            if (dateTo != null && wo.getPlanStart() >= dateTo) {
                continue;
            }
            String line = wo.getLineCode() == null || wo.getLineCode().isBlank()
                    ? "（未指定产线）" : wo.getLineCode();
            Map<String, Object> ln = lines.stream()
                    .filter(x -> line.equals(x.get("lineCode")))
                    .findFirst().orElse(null);
            if (ln == null) {
                ln = new LinkedHashMap<>();
                ln.put("lineCode", line);
                ln.put("orders", new ArrayList<Map<String, Object>>());
                lines.add(ln);
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> lst = (List<Map<String, Object>>) ln.get("orders");
            boolean conflict = !conflictsAmong(List.of(wo.getId()), line,
                    wo.getPlanStart(), wo.getPlanEnd()).isEmpty();
            lst.add(row(wo, wo.getPlanStart(), wo.getPlanEnd(), conflict));
        }
        for (Map<String, Object> ln : lines) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> lst = (List<Map<String, Object>>) ln.get("orders");
            lst.sort(Comparator.comparingLong(x -> (Long) x.get("planStart")));
        }
        out.put("lines", lines);
        out.put("unscheduled", unscheduled);
        out.put("conflicts", allConflicts());
        return out;
    }

    /** 全部冲突（跨产线） */
    public List<Conflict> allConflicts() {
        List<ErpWorkOrder> all = repo.findAll();
        List<Conflict> out = new ArrayList<>();
        // 按产线分组，两两比
        Map<String, List<ErpWorkOrder>> byLine = new LinkedHashMap<>();
        for (ErpWorkOrder wo : all) {
            if (!ACTIVE_STATUS.contains(wo.getStatus())) {
                continue;
            }
            if (wo.getPlanStart() == null || wo.getPlanEnd() == null) {
                continue;
            }
            String line = wo.getLineCode();
            if (line == null || line.isBlank()) {
                continue;   // 没指定产线 → 不参与
            }
            byLine.computeIfAbsent(line, k -> new ArrayList<>()).add(wo);
        }
        for (Map.Entry<String, List<ErpWorkOrder>> e : byLine.entrySet()) {
            List<ErpWorkOrder> lst = e.getValue();
            for (int i = 0; i < lst.size(); i++) {
                for (int j = i + 1; j < lst.size(); j++) {
                    ErpWorkOrder a = lst.get(i);
                    ErpWorkOrder b = lst.get(j);
                    long s = Math.max(a.getPlanStart(), b.getPlanStart());
                    long t = Math.min(a.getPlanEnd(), b.getPlanEnd());
                    if (s < t) {   // 半开区间：s == t 不算冲突
                        out.add(new Conflict(e.getKey(), a.getOrderNo(), b.getOrderNo(), s, t));
                    }
                }
            }
        }
        return out;
    }

    /** 指定产线与时间窗内，与给定集合（可为空=全部）冲突的工单 */
    private List<Conflict> conflictsAmong(List<Long> excludeIds, String lineCode,
                                          long start, long end) {
        List<Conflict> out = new ArrayList<>();
        if (lineCode == null || lineCode.isBlank()) {
            return out;   // 没指定产线 → 不参与冲突判定
        }
        for (ErpWorkOrder other : repo.findAll()) {
            if (excludeIds != null && excludeIds.contains(other.getId())) {
                continue;
            }
            if (!ACTIVE_STATUS.contains(other.getStatus())) {
                continue;
            }
            if (other.getPlanStart() == null || other.getPlanEnd() == null) {
                continue;
            }
            if (!lineCode.equals(other.getLineCode())) {
                continue;
            }
            long s = Math.max(start, other.getPlanStart());
            long t = Math.min(end, other.getPlanEnd());
            if (s < t) {
                out.add(new Conflict(lineCode, "（本次）", other.getOrderNo(), s, t));
            }
        }
        return out;
    }

    private static String describe(List<Conflict> cs) {
        StringBuilder sb = new StringBuilder();
        for (Conflict c : cs) {
            if (sb.length() > 0) {
                sb.append("、");
            }
            sb.append(c.bOrderNo);
        }
        return sb.toString();
    }

    private static Map<String, Object> row(ErpWorkOrder wo, Long s, Long e, boolean conflict) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("orderNo", wo.getOrderNo());
        m.put("productCode", wo.getProductCode());
        m.put("productName", wo.getProductName());
        m.put("planQty", wo.getPlanQty());
        m.put("doneQty", wo.getDoneQty());
        m.put("status", wo.getStatus());
        m.put("lineCode", wo.getLineCode());
        m.put("deviceCode", wo.getDeviceCode());
        m.put("planStart", s);
        m.put("planEnd", e);
        m.put("conflict", conflict);
        return m;
    }
}
