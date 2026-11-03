package com.factory.hub.quality;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 缺陷分类主数据（质量字典）的读写与引用校验。
 * <p>
 * <b>删除必须先查引用</b>：不良记录和检验项都按 id 指向分类。删掉被引用过的分类，
 * 历史记录会变成"未分类"，帕累托当场失真、质量成本报表按分类汇总时凭空少一块，
 * 而且这种失真<b>不可逆</b>（原文已经没了）。所以被引用就拒绝删除，引导去"停用"——
 * 停用之后不再出现在新建时的下拉里，历史记录照常显示，这才是正确的下线方式。
 * <p>
 * 查询列表把 CR 排最前：分级决定判定后果，列表第一眼要能看到最危险的条目。
 */
@Service
public class ErpQcDefectCodeService {

    /** 新增/修改请求。字段全部可空，由 {@link #save} 逐个校验并给出人话错误 */
    public record Req(String code, String name, String severity, String category,
                      BigDecimal defaultAql, String remark, Integer sortNo, Boolean enabled) {
    }

    private final ErpQcDefectCodeRepository codeRepo;
    private final ErpQcDefectRepository defectRepo;
    private final ErpQcInspectionItemRepository itemRepo;

    public ErpQcDefectCodeService(ErpQcDefectCodeRepository codeRepo,
                                  ErpQcDefectRepository defectRepo,
                                  ErpQcInspectionItemRepository itemRepo) {
        this.codeRepo = codeRepo;
        this.defectRepo = defectRepo;
        this.itemRepo = itemRepo;
    }

    /* ==================== 查询 ==================== */

    /**
     * 列表。三个条件都可空。
     *
     * @param q        关键字，匹配代码/名称/大类
     * @param severity CR / MA / MI
     * @param enabled  true = 只看启用，false = 只看停用，null = 全部
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String q, String severity, Boolean enabled) {
        String kw = blankToNull(q);
        String sev = blankToNull(severity);
        if (sev != null && !ErpQcDefectCode.SEVERITIES.contains(sev.toUpperCase())) {
            throw new IllegalArgumentException("不支持的分级：" + severity + "（可用 CR / MA / MI）");
        }
        List<ErpQcDefectCode> rows = codeRepo.search(kw, sev == null ? null : sev.toUpperCase(), enabled);
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (ErpQcDefectCode c : rows) {
            out.add(toMap(c));
        }
        return out;
    }

    /* ==================== 写入 ==================== */

    @Transactional
    public Map<String, Object> create(Req req) {
        ErpQcDefectCode c = new ErpQcDefectCode();
        apply(c, req, true);
        long now = System.currentTimeMillis();
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        c = codeRepo.save(c);
        return ok("已新增缺陷分类 " + c.getCode(), c);
    }

    @Transactional
    public Map<String, Object> update(Long id, Req req) {
        ErpQcDefectCode c = codeRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("缺陷分类不存在，可能已被删除：" + id));
        apply(c, req, false);
        c.setUpdatedAt(System.currentTimeMillis());
        c = codeRepo.save(c);
        return ok("已保存缺陷分类 " + c.getCode(), c);
    }

    /**
     * 删除。被引用过就拒绝，并把"N 条不良记录 / M 条检验项"说清楚，
     * 让人知道该改成停用而不是继续找别的办法删。
     */
    @Transactional
    public Map<String, Object> delete(Long id) {
        ErpQcDefectCode c = codeRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("缺陷分类不存在，可能已被删除：" + id));
        long defectRefs = defectRepo.countByDefectCodeId(id);
        long itemRefs = itemRepo.countByDefectCodeId(id);
        if (defectRefs > 0 || itemRefs > 0) {
            StringBuilder sb = new StringBuilder("不能删除「").append(c.getCode()).append(" ")
                    .append(c.getName()).append("」：");
            if (defectRefs > 0) {
                sb.append("已被 ").append(defectRefs).append(" 条不良记录引用");
            }
            if (itemRefs > 0) {
                if (defectRefs > 0) {
                    sb.append("、");
                }
                sb.append("已被 ").append(itemRefs).append(" 条检验项引用");
            }
            sb.append("。删掉会让这些历史记录变成「未分类」，报表失真且不可恢复，请改为停用");
            throw new IllegalArgumentException(sb.toString());
        }
        codeRepo.delete(c);
        return ok("已删除缺陷分类 " + c.getCode(), null);
    }

    /* ==================== 内部 ==================== */

    private void apply(ErpQcDefectCode c, Req req, boolean isNew) {
        if (req == null) {
            throw new IllegalArgumentException("请求内容为空");
        }
        String code = trimToNull(req.code());
        if (code == null) {
            throw new IllegalArgumentException("缺陷代码不能为空");
        }
        if (code.length() > 32) {
            throw new IllegalArgumentException("缺陷代码最长 32 个字符");
        }
        String name = trimToNull(req.name());
        if (name == null) {
            throw new IllegalArgumentException("缺陷名称不能为空");
        }
        String severity = trimToNull(req.severity());
        if (severity == null) {
            severity = ErpQcDefectCode.SEV_MA;
        }
        severity = severity.toUpperCase();
        if (!ErpQcDefectCode.SEVERITIES.contains(severity)) {
            throw new IllegalArgumentException("不支持的分级：" + req.severity() + "（可用 CR / MA / MI）");
        }
        if (req.defaultAql() != null && req.defaultAql().signum() < 0) {
            throw new IllegalArgumentException("默认 AQL 不能为负数");
        }
        // 代码唯一：改自己时排除自己，否则保存自己的表单都会报重复
        boolean dup = isNew ? codeRepo.existsByCode(code)
                : codeRepo.existsByCodeAndIdNot(code, c.getId());
        if (dup) {
            throw new IllegalArgumentException("缺陷代码已存在：" + code);
        }

        c.setCode(code);
        c.setName(name);
        c.setSeverity(severity);
        c.setCategory(trimToNull(req.category()));
        c.setDefaultAql(req.defaultAql());
        c.setRemark(trimToNull(req.remark()));
        c.setSortNo(req.sortNo() == null ? 0 : req.sortNo());
        // 新增默认启用；修改时不传就保持原值（避免前端漏字段把启用状态改掉）
        if (req.enabled() != null) {
            c.setEnabled(req.enabled());
        } else if (isNew) {
            c.setEnabled(true);
        }
    }

    private static Map<String, Object> toMap(ErpQcDefectCode c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("code", c.getCode());
        m.put("name", c.getName());
        m.put("severity", c.getSeverity());
        m.put("severityLabel", severityLabel(c.getSeverity()));
        m.put("category", c.getCategory());
        m.put("defaultAql", c.getDefaultAql());
        m.put("remark", c.getRemark());
        m.put("sortNo", c.getSortNo());
        m.put("enabled", c.isEnabled());
        m.put("createdAt", c.getCreatedAt());
        m.put("updatedAt", c.getUpdatedAt());
        return m;
    }

    private static String severityLabel(String severity) {
        if (severity == null) {
            return "";
        }
        return switch (severity) {
            case ErpQcDefectCode.SEV_CR -> "致命";
            case ErpQcDefectCode.SEV_MI -> "轻微";
            default -> "严重";
        };
    }

    private static Map<String, Object> ok(String message, Object data) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("message", message);
        if (data != null) {
            m.put("data", data instanceof ErpQcDefectCode c ? toMap(c) : data);
        }
        return m;
    }

    private static String trimToNull(String v) {
        if (v == null) {
            return null;
        }
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }

    private static String blankToNull(String v) {
        return trimToNull(v);
    }

    /** 供其他服务按 id 取分类（不良登记、检验项选分类时用） */
    @Transactional(readOnly = true)
    public Optional<ErpQcDefectCode> find(Long id) {
        return id == null ? Optional.empty() : codeRepo.findById(id);
    }
}
