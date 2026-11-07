package com.factory.hub.erp.quality.inspection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.factory.hub.erp.quality.defect.ErpQcDefect;

/**
 * 检验单的对外视图组装（单头 / 明细 / 关联不良记录）。
 * <p>
 * 抽出来的原因：同一份字段映射被"详情"和"列表"两处用到，
 * 两处各写一遍必然有一天只改了一边，于是同一个字段在列表和详情里名字或精度不同——
 * 这正是本模块要修的 F2/F3 那类"两处各写一份"的毛病。
 * <p>
 * <b>{@code ac = -1} 必须原样输出，不许转成 0 或 null</b>：
 * 界面把 -1 显示成「0 件（零容忍）」，把 0 显示成「0 件」+ 未配置提示。
 * 后端要是把它们都变成 0，这两个就会长得一模一样，
 * 更糟的是把"零容忍"和"未配置"当成一回事。
 */
final class ErpQcInspectionView {

    private ErpQcInspectionView() {
    }

    static Map<String, Object> header(ErpQcInspection i) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("inspectionNo", i.getInspectionNo());
        m.put("inspectionType", i.getInspectionType());
        m.put("status", i.getStatus());
        m.put("result", i.getResult());
        m.put("skuId", i.getSkuId());
        m.put("skuCode", i.getSkuCode());
        m.put("skuName", i.getSkuName());
        m.put("skuSpec", i.getSkuSpec());
        m.put("skuUnit", i.getSkuUnit());
        m.put("partnerId", i.getPartnerId());
        m.put("partnerName", i.getPartnerName());
        m.put("lotNo", i.getLotNo());
        m.put("lotQty", i.getLotQty());
        m.put("srcBillNo", i.getSrcBillNo());
        m.put("deviceCode", i.getDeviceCode());
        // 抽样参数快照：判定只认这几个数，界面也只用它们解释"为什么这么判"
        m.put("sampleSource", i.getSampleSource());
        m.put("sampleLevel", i.getSampleLevel());
        m.put("strictLevel", i.getStrictLevel());
        m.put("aql", i.getAql());
        m.put("codeLetter", i.getCodeLetter());
        m.put("sampleSize", i.getSampleSize());
        m.put("ac", i.getAc());
        m.put("re", i.getRe());
        m.put("zeroTolerance", i.getAc() != null && i.getAc() == ErpAqlService.AC_ZERO_TOLERANCE);
        m.put("fullInspection", i.isFullInspection());
        // 件数与项数是两个数：件数判合格与否，项数只做缺陷分析
        m.put("defectQty", i.getDefectQty());
        m.put("defectCr", i.getDefectCr());
        m.put("defectMa", i.getDefectMa());
        m.put("defectMi", i.getDefectMi());
        m.put("inspector", i.getInspector());
        m.put("inspectedAt", i.getInspectedAt());
        m.put("judgedBy", i.getJudgedBy());
        m.put("judgedAt", i.getJudgedAt());
        m.put("remark", i.getRemark());
        m.put("createdBy", i.getCreatedBy());
        m.put("createdAt", i.getCreatedAt());
        m.put("updatedAt", i.getUpdatedAt());
        m.put("voidedAt", i.getVoidedAt());
        m.put("voidReason", i.getVoidReason());
        return m;
    }

    static Map<String, Object> item(ErpQcInspectionItem it) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", it.getId());
        m.put("lineNo", it.getLineNo());
        m.put("itemName", it.getItemName());
        m.put("standard", it.getStandard());
        m.put("method", it.getMethod());
        m.put("defectCodeId", it.getDefectCodeId());
        m.put("defectCode", it.getDefectCode());
        m.put("defectName", it.getDefectName());
        m.put("severity", it.getSeverity());
        m.put("measured", it.getMeasured());
        m.put("result", it.getResult());
        m.put("defectQty", it.getDefectQty());
        m.put("remark", it.getRemark());
        return m;
    }

    static List<Map<String, Object>> items(List<ErpQcInspectionItem> rows) {
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (ErpQcInspectionItem it : rows) {
            out.add(item(it));
        }
        return out;
    }

    static List<Map<String, Object>> defects(List<ErpQcDefect> rows) {
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (ErpQcDefect d : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("defectNo", d.getDefectNo());
            m.put("qty", d.getQty());
            m.put("inStock", d.isInStock());
            m.put("status", d.getStatus());
            m.put("severity", d.getSeverity());
            out.add(m);
        }
        return out;
    }
}
