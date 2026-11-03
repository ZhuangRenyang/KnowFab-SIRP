package com.factory.hub.quality;

import com.factory.hub.erp.ErpSku;
import com.factory.hub.erp.ErpSkuRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.factory.hub.quality.ErpQcBody.intOf;
import static com.factory.hub.quality.ErpQcBody.longOf;
import static com.factory.hub.quality.ErpQcBody.qtyOf;
import static com.factory.hub.quality.ErpQcBody.str;

/**
 * 检验单：建单 / 改草稿 / 提交锁定 / 判定 / 作废（QC-SPEC §4.2）。
 * <p>
 * <b>三件事必须做对，否则整套质量记录都不可信</b>：
 * <ol>
 *   <li><b>抽样参数在建单那一刻抄成快照</b>（{@code sampleLevel/strictLevel/aql/codeLetter/sampleSize/ac/re}），
 *       提交后永不回查方案表。否则客户下个月改了方案，前天的检验单判定跟着变——
 *       等于质量记录可以被追溯篡改，审核时这一条足以否掉整套体系。</li>
 *   <li><b>判定只读快照</b>，算法是纯函数 {@link ErpInspectionJudge}；这里不做任何"差不多"的判断。</li>
 *   <li><b>{@code defectQty}（不合格品件数）与 {@code defectCr/Ma/Mi}（不合格项数）分开</b>。
 *       前者是判定唯一依据；后者由明细 NG 行汇总，只用于 CR 一票否决和帕累托。
 *       把件数算成"项数之和"会让结论反转（10 项 = 5 件，n=80/Ac=5 时正确判合格）。</li>
 * </ol>
 * <b>MANUAL 兜底不许静默发生</b>：查不到抽样方案时，要么调用方明确给 {@code manualSampleSize}
 * （此时全检 + Ac=0/Re=1 + {@code sampleSource=MANUAL}），要么把
 * {@link ErpAqlService.NoPlanException} 抛给界面去处理。系统不会自己拿个默认值凑合判。
 */
@Service
public class ErpQcInspectionService {

    private static final ZoneId ZONE = ZoneId.systemDefault();
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final ErpQcInspectionRepository insRepo;
    private final ErpQcInspectionItemRepository itemRepo;
    private final ErpQcDefectRepository defectRepo;
    private final ErpSkuRepository skuRepo;
    private final ErpAqlService aqlService;
    private final com.factory.hub.audit.AuditService audit;
    /** 库存单据头：查来料检验对应的那张入库单（定出不良品在哪个仓） */
    private final com.factory.hub.erp.ErpStockBillHdrRepository billHdrRepo;

    public ErpQcInspectionService(ErpQcInspectionRepository insRepo,
                                  ErpQcInspectionItemRepository itemRepo,
                                  ErpQcDefectRepository defectRepo,
                                  ErpSkuRepository skuRepo,
                                  ErpAqlService aqlService,
                                  com.factory.hub.audit.AuditService audit,
                                  com.factory.hub.erp.ErpStockBillHdrRepository billHdrRepo) {
        this.insRepo = insRepo;
        this.itemRepo = itemRepo;
        this.defectRepo = defectRepo;
        this.skuRepo = skuRepo;
        this.aqlService = aqlService;
        this.audit = audit;
        this.billHdrRepo = billHdrRepo;
    }

    /* ==================== 建单 ==================== */

    /**
     * 建单。请求体关键字段：{@code inspectionType / skuId / lotQty / aql / level / items / defectQty}。
     * {@code inspectionType} 缺省按 IQC。
     */
    @Transactional
    public Map<String, Object> create(Map<String, Object> body, String operator) {
        String type = str(body.get("inspectionType"));
        type = type == null ? ErpQcInspection.TYPE_IQC : type.toUpperCase();
        if (!ErpQcInspection.TYPES.contains(type)) {
            throw new IllegalArgumentException("不支持的检验类型：" + type + "（可用 IQC / FAI / IPQC / FQC）");
        }
        Long skuId = longOf(body.get("skuId"));
        if (skuId == null) {
            throw new IllegalArgumentException("请选择送检产品");
        }
        ErpSku sku = skuRepo.findById(skuId)
                .orElseThrow(() -> new IllegalArgumentException("产品不存在：" + skuId));
        BigDecimal lotQty = qtyOf(body.get("lotQty"));
        if (lotQty.signum() <= 0) {
            throw new IllegalArgumentException("送检批量必须大于 0");
        }

        ErpQcInspection ins = new ErpQcInspection();
        ins.setInspectionNo(nextNo());
        ins.setInspectionType(type);
        ins.setStatus(ErpQcInspection.ST_DRAFT);
        ins.setSkuId(sku.getId());
        ins.setSkuCode(sku.getCode());
        ins.setSkuName(sku.getName());
        ins.setSkuSpec(sku.getSpec());
        ins.setSkuUnit(sku.getUnit());
        ins.setPartnerId(longOf(body.get("partnerId")));
        ins.setPartnerName(str(body.get("partnerName")));
        ins.setLotNo(str(body.get("lotNo")));
        ins.setLotQty(lotQty);
        ins.setSrcBillNo(str(body.get("srcBillNo")));
        ins.setDeviceCode(str(body.get("deviceCode")));
        ins.setInspector(str(body.get("inspector")));
        ins.setRemark(str(body.get("remark")));
        ins.setCreatedBy(operator);
        fillSampling(ins, body);
        long now = System.currentTimeMillis();
        ins.setCreatedAt(now);
        ins.setUpdatedAt(now);
        if (body.get("defectQty") != null) {
            ins.setDefectQty(qtyOf(body.get("defectQty")));
        }
        ins = insRepo.save(ins);
        applyItems(ins, body.get("items"));

        Map<String, Object> out = ErpQcBody.ok("检验单 " + ins.getInspectionNo() + " 已建（草稿）");
        out.put("inspectionNo", ins.getInspectionNo());
        out.put("sampleSource", ins.getSampleSource());
        out.put("sampleSize", ins.getSampleSize());
        out.put("ac", ins.getAc());
        out.put("re", ins.getRe());
        out.put("fullInspection", ins.isFullInspection());
        return out;
    }

    /**
     * 抽样参数快照。
     * <ul>
     *   <li><b>IQC / FQC</b>：走 AQL 方案表；查不到抛 {@link ErpAqlService.NoPlanException}
     *       （除非调用方给了 {@code manualSampleSize}）；</li>
     *   <li><b>FAI</b>：首件检验，标准规定 n=1、Ac=0，与主数据无关（{@code FIXED}）；</li>
     *   <li><b>IPQC</b>：巡检样本量由工艺定，人手填 n、Ac=0（{@code FIXED}）。</li>
     * </ul>
     */
    private void fillSampling(ErpQcInspection ins, Map<String, Object> body) {
        String type = ins.getInspectionType();
        if (ErpQcInspection.TYPE_FAI.equals(type)) {
            setSample(ins, ErpQcInspection.SAMPLE_FIXED, null, 1, 0, 1, true);
            return;
        }
        if (ErpQcInspection.TYPE_IPQC.equals(type)) {
            Integer n = intOf(body.get("sampleSize"));
            if (n == null || n <= 0) {
                throw new IllegalArgumentException("IPQC 巡检必须填样本量（工艺规定抽几件就填几件）");
            }
            ins.setSampleLevel(null);
            ins.setStrictLevel(null);
            ins.setAql(null);
            setSample(ins, ErpQcInspection.SAMPLE_FIXED, null, n, 0, 1, false);
            return;
        }

        String level = ErpAqlService.normalizeLevel(str(body.get("level")));
        String strict = ErpAqlService.normalizeStrict(str(body.get("strictLevel")));
        BigDecimal aql = ErpQcBody.decOf(body.get("aql"));
        Integer manualN = intOf(body.get("manualSampleSize"));
        ins.setSampleLevel(level);
        ins.setStrictLevel(strict);
        ins.setAql(aql);

        if (aql == null && manualN == null) {
            throw new IllegalArgumentException("请选 AQL 值（主数据缺失时可填临时样本量，按全检执行）");
        }
        if (manualN != null) {
            // 人工兜底：必须全检 + Ac=0。不许手填 Ac —— 那等于让人自己定验收标准，不叫抽样方案了
            setSample(ins, ErpQcInspection.SAMPLE_MANUAL, null, manualN, 0, 1, true);
            return;
        }
        ErpAqlService.Resolved r = aqlService.resolve(level, strict, ins.getLotQty(), aql);
        setSample(ins, ErpQcInspection.SAMPLE_AQL, r.codeLetter(), r.sampleSize(),
                r.ac(), r.re(), r.fullInspection());
    }

    private static void setSample(ErpQcInspection ins, String source, String letter,
                                  int n, int ac, int re, boolean full) {
        ins.setSampleSource(source);
        ins.setCodeLetter(letter);
        ins.setSampleSize(n);
        ins.setAc(ac);
        ins.setRe(re);
        ins.setFullInspection(full);
    }

    /* ==================== 改草稿 / 提交 / 作废 ==================== */

    /** 改草稿：明细整表替换（提交上来的就是这张单的最终样子） */
    @Transactional
    public Map<String, Object> updateDraft(String no, Map<String, Object> body, String operator) {
        ErpQcInspection ins = load(no);
        if (!ins.isEditable()) {
            throw new IllegalArgumentException("只有草稿能改（当前状态 " + ins.getStatus()
                    + "）。提交后抽样参数和明细就冻结了——判定要能按当时的快照复算");
        }
        if (body.get("lotQty") != null) {
            BigDecimal lotQty = qtyOf(body.get("lotQty"));
            if (lotQty.signum() <= 0) {
                throw new IllegalArgumentException("送检批量必须大于 0");
            }
            ins.setLotQty(lotQty);
        }
        if (body.get("lotNo") != null) {
            ins.setLotNo(str(body.get("lotNo")));
        }
        if (body.get("partnerId") != null) {
            ins.setPartnerId(longOf(body.get("partnerId")));
        }
        if (body.get("partnerName") != null) {
            ins.setPartnerName(str(body.get("partnerName")));
        }
        if (body.get("srcBillNo") != null) {
            ins.setSrcBillNo(str(body.get("srcBillNo")));
        }
        if (body.get("deviceCode") != null) {
            ins.setDeviceCode(str(body.get("deviceCode")));
        }
        if (body.get("inspector") != null) {
            ins.setInspector(str(body.get("inspector")));
        }
        if (body.get("remark") != null) {
            ins.setRemark(str(body.get("remark")));
        }
        if (body.get("defectQty") != null) {
            ins.setDefectQty(qtyOf(body.get("defectQty")));
        }
        // 批量或 AQL 变了必须重算样本量，否则会出现"批量 5000 却抽 8 件"的荒唐快照
        if (body.containsKey("lotQty") || body.get("aql") != null
                || body.get("manualSampleSize") != null || body.get("sampleSize") != null) {
            fillSampling(ins, body);
        }
        ins.setUpdatedAt(System.currentTimeMillis());
        insRepo.save(ins);
        if (body.get("items") != null) {
            applyItems(ins, body.get("items"));
        }
        return ErpQcBody.ok("已保存草稿 " + no);
    }

    /** 提交：锁定快照。{@code defectQty} 必填——它是判定唯一依据，缺了不能猜 */
    @Transactional
    public Map<String, Object> submit(String no, String operator) {
        ErpQcInspection ins = load(no);
        if (!ErpQcInspection.ST_DRAFT.equals(ins.getStatus())) {
            throw new IllegalArgumentException("只有草稿能提交（当前 " + ins.getStatus() + "）");
        }
        if (ins.getDefectQty() == null) {
            throw new IllegalArgumentException("提交前必须填「不合格品数」——判定只认这个数"
                    + "（一件产品带两个缺陷仍然只算 1 件不合格品）");
        }
        syncItemStats(ins);
        ins.setStatus(ErpQcInspection.ST_SUBMITTED);
        if (ins.getInspectedAt() == null) {
            ins.setInspectedAt(System.currentTimeMillis());
        }
        ins.setUpdatedAt(System.currentTimeMillis());
        insRepo.save(ins);
        return ErpQcBody.ok("检验单 " + no + " 已提交，抽样参数与明细已冻结");
    }

    /** 作废：已判定的不可作废（结论一旦产生就要留档） */
    @Transactional
    public Map<String, Object> cancel(String no, String reason, String operator) {
        ErpQcInspection ins = load(no);
        if (ErpQcInspection.ST_JUDGED.equals(ins.getStatus())) {
            throw new IllegalArgumentException("已判定的检验单不能作废——判定结论要留档。"
                    + "如果判错了，请新开一张单重新检验");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("作废必须填原因");
        }
        ins.setStatus(ErpQcInspection.ST_CANCELLED);
        ins.setVoidedAt(System.currentTimeMillis());
        ins.setVoidedBy(operator);
        ins.setVoidReason(reason);
        ins.setUpdatedAt(System.currentTimeMillis());
        insRepo.save(ins);
        audit.ok(com.factory.hub.audit.AuditLog.CAT_QUALITY, com.factory.hub.audit.AuditLog.ACT_REVOKE,
                no, ins.getSkuName(), "作废检验单：" + reason);
        return ErpQcBody.ok("检验单 " + no + " 已作废");
    }

    /* ==================== 判定 ==================== */

    /**
     * 判定。结论由纯函数 {@link ErpInspectionJudge} 算，这里只取快照、落库。
     * <p>
     * <b>只允许人工把结论改成 CONDITIONAL（让步接收）且必须写原因</b>；
     * 不允许把 PASS 改成 FAIL 或反过来——那等于判定权变成"谁点一下谁说了算"，
     * 而这正是整套质检存在的理由。判错了应该作废重开一张单。
     */
    @Transactional
    public Map<String, Object> judge(String no, Map<String, Object> body, String operator) {
        ErpQcInspection ins = load(no);
        if (!ErpQcInspection.ST_SUBMITTED.equals(ins.getStatus())) {
            throw new IllegalArgumentException("只有已提交的检验单能判定（当前 " + ins.getStatus() + "）");
        }
        if (ins.getAc() == null || ins.getRe() == null) {
            throw new IllegalArgumentException("这张单没有抽样参数快照（Ac/Re 为空），无法判定。请补全后重新提交");
        }
        syncItemStats(ins);
        if (body != null && body.get("defectQty") != null) {
            ins.setDefectQty(qtyOf(body.get("defectQty")));
        }

        ErpInspectionJudge.Result r = ErpInspectionJudge.judge(new ErpInspectionJudge.Input(
                BigDecimal.valueOf(ins.getDefectCr()), ins.getDefectQty(),
                ins.getAc(), ins.getRe(), ins.isFullInspection()));
        String result = r.verdict() == ErpInspectionJudge.Verdict.PASS
                ? ErpQcInspection.RESULT_PASS : ErpQcInspection.RESULT_FAIL;
        String text = r.text();

        String override = str(body == null ? null : body.get("result"));
        if (override != null) {
            if (!ErpQcInspection.RESULT_CONDITIONAL.equalsIgnoreCase(override)) {
                throw new IllegalArgumentException("不能把判定结论改成 " + override
                        + "（系统算出 " + result + "）。改判只允许「让步接收」且必须写原因；"
                        + "如果确实是数据错了，请作废这张单重新检验");
            }
            String why = str(body.get("remark"));
            if (why == null) {
                throw new IllegalArgumentException("改为让步接收必须写原因（这是商务决定，要能追溯谁批的）");
            }
            result = ErpQcInspection.RESULT_CONDITIONAL;
            text = text + "；人工改为让步接收：" + why;
        }

        ins.setResult(result);
        ins.setStatus(ErpQcInspection.ST_JUDGED);
        // 判定不是 PASS → 必须生成不良记录，否则这批货不进待处置队列，
        // 没人处置 → 它在系统里根本不存在 → 按 F1 的口径它就是良品，可以直接发货。
        // 质检判了不合格、货却能正常卖出去，正是这个模块要防的那件事。
        createDefectFromInspection(ins);
        ins.setJudgedBy(operator);
        ins.setJudgedAt(System.currentTimeMillis());
        ins.setRemark(ins.getRemark() == null ? text : ins.getRemark() + "；" + text);
        ins.setUpdatedAt(System.currentTimeMillis());
        insRepo.save(ins);
        audit.ok(com.factory.hub.audit.AuditLog.CAT_QUALITY, com.factory.hub.audit.AuditLog.ACT_UPDATE,
                no, ins.getSkuName(), "判定 " + result + "：" + text);

        Map<String, Object> out = ErpQcBody.ok("检验单 " + no + " 判定为 " + result);
        out.put("result", result);
        out.put("why", r.why());
        out.put("judgeText", text);
        return out;
    }

    /**
     * 判定生成不良记录（{@code source=INSPECTION}）。
     * <p>
     * <b>幂等键 = {@code INSP:检验单号}</b>：一张检验单一条汇总记录，
     * 重复判定/网络重试都不会产生第二条（唯一键兜底）。
     * <p>
     * <b>件数用 {@code defectQty}（不合格品件数），不用 {@code defectCr+Ma+Mi}（项数）</b>：
     * 一件产品带 3 个 MA 缺陷，件数是 1、项数是 3；处置对象的单位是件。
     * 判定口径与处置口径必须一致，否则看板的待处置件数会翻三倍。
     * <p>
     * <b>{@code inStock} 按检验类型区分</b>（这条最容易搞反）：
     * <ul>
     *   <li><b>IQC</b> → {@code true}：来料已通过 {@code PO_IN} 进了原料仓，
     *       判不合格要出库（报废）或退供应商；</li>
     *   <li><b>FAI/IPQC/FQC</b> → {@code false}：F1 起入库量 = 总产出 − 不良数，
     *       <b>不良品从没进过成品仓</b>，报废不动库存。
     *       按 true 处理会去扣一批不存在的货，把库存做成负数。</li>
     * </ul>
     */
    private void createDefectFromInspection(ErpQcInspection ins) {
        if (ErpQcInspection.RESULT_PASS.equals(ins.getResult())) {
            return;
        }
        BigDecimal qty = ins.getDefectQty();
        if (qty == null || qty.signum() <= 0) {
            // 判了不合格却没填件数：建一条 qty=0 的记录毫无意义，
            // 还会让待处置统计出现一堆 0 行
            return;
        }
        String key = "INSP:" + ins.getInspectionNo();
        ErpQcDefect d = defectRepo.findBySourceKey(key).orElseGet(ErpQcDefect::new);
        if (d.getDefectNo() == null) {
            d.setDefectNo(nextDefectNo());
        }
        d.setSource(ErpQcDefect.SRC_INSPECTION);
        d.setSourceKey(key);
        d.setInspectionNo(ins.getInspectionNo());
        d.setSkuId(ins.getSkuId());
        d.setSkuCode(ins.getSkuCode());
        d.setSkuName(ins.getSkuName());
        d.setLotNo(ins.getLotNo());
        d.setQty(qty);
        d.setStatus(ErpQcDefect.ST_PENDING);
        d.setDisposedQty(BigDecimal.ZERO);
        d.setFoundAt(ins.getInspectedAt() == null ? System.currentTimeMillis() : ins.getInspectedAt());
        d.setRegisteredBy(ins.getJudgedBy());
        d.setSeverity(severityOf(ins));
        d.setRemark("检验单 " + ins.getInspectionNo() + " 判定 " + ins.getResult());

        // 缺陷分类：取明细里第一个填了的（检验员可能没填 → 留空进"未分类"等人工分诊）
        for (ErpQcInspectionItem it : itemRepo.findByInspectionIdOrderByLineNoAsc(ins.getId())) {
            if (it.getDefectCodeId() != null) {
                d.setDefectCodeId(it.getDefectCodeId());
                d.setDefectCode(it.getDefectCode());
                d.setDefectName(it.getDefectName());
                break;
            }
        }

        // inStock：IQC 来料已入库，其余（FAI/IPQC/FQC）不良品从未进过成品仓
        if (ErpQcInspection.TYPE_IQC.equals(ins.getInspectionType())) {
            d.setInStock(true);
            resolveSourceBill(ins, d);
        } else {
            d.setInStock(false);
        }
        defectRepo.save(d);
    }

    /**
     * IQC 的不良品在哪：查来源单据对应的那张入库单（{@code src_order_no} = 采购单号）。
     * <p>
     * 查不到就留空——处置时会明确报错让人补，不会拿一个"猜的仓"去出库
     * （猜错仓就是从别人的库存里扣货）。
     */
    private void resolveSourceBill(ErpQcInspection ins, ErpQcDefect d) {
        String src = ins.getSrcBillNo();
        if (src == null || src.isBlank()) {
            return;
        }
        for (com.factory.hub.erp.ErpStockBillHdr h :
                billHdrRepo.findBySrcOrderNoOrderByCreatedAtDesc(src)) {
            if (h.getDstWhId() != null) {
                d.setWarehouseId(h.getDstWhId());
                d.setSrcBillNo(h.getBillNo());
            }
            break;
        }
    }

    /** 分级取最严重的那个：CR &gt; MA &gt; MI */
    private static String severityOf(ErpQcInspection ins) {
        if (ins.getDefectCr() > 0) {
            return ErpQcDefectCode.SEV_CR;
        }
        if (ins.getDefectMa() > 0) {
            return ErpQcDefectCode.SEV_MA;
        }
        return ErpQcDefectCode.SEV_MI;
    }

    /** 不良单号：与设备同步同一套前缀规则 */
    private String nextDefectNo() {
        String base = "BLP" + java.time.LocalDate.now().format(
                java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd")) + "-";
        String max = defectRepo.maxNoByPrefix(base);
        int n = 1;
        if (max != null && max.length() > base.length()) {
            try {
                n = Integer.parseInt(max.substring(base.length())) + 1;
            } catch (NumberFormatException ignored) {
                n = 1;
            }
        }
        return base + String.format("%04d", n);
    }

    /* ==================== 查询 ==================== */

    /**
     * 检验单列表。
     * <p>
     * 排序：未判定的排前面（待办优先），同态按创建时间倒序。
     * <p>
     * 每行只给单头字段（{@link ErpQcInspectionView#header}）——明细与不良记录
     * 是详情页的事，列表上拉明细会让这个查询变成 N+1。
     */
    @Transactional(readOnly = true)
    public Map<String, Object> list(String type, String status, Long from, Long to,
                                    String kw, int page, int size) {
        int p = Math.max(1, page);
        int s = Math.max(1, Math.min(200, size));
        long toTs = to == null ? System.currentTimeMillis() : to;
        long fromTs = from == null ? 0L : from;
        Page<ErpQcInspection> res = insRepo.search(
                str(type), str(status), fromTs, toTs, str(kw),
                PageRequest.of(p - 1, s));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ErpQcInspection i : res.getContent()) {
            rows.add(ErpQcInspectionView.header(i));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("rows", rows);
        out.put("total", res.getTotalElements());
        out.put("page", p);
        out.put("size", s);
        out.put("pages", res.getTotalPages());
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> detail(String no) {
        ErpQcInspection ins = load(no);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("header", ErpQcInspectionView.header(ins));
        out.put("items", itemsOf(ins.getId()));
        out.put("defects", ErpQcInspectionView.defects(
                defectRepo.findByInspectionNoOrderByIdAsc(no)));
        return out;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> itemsOf(Long inspectionId) {
        return ErpQcInspectionView.items(
                itemRepo.findByInspectionIdOrderByLineNoAsc(inspectionId));
    }

    /* ==================== 内部 ==================== */

    ErpQcInspection load(String no) {
        return insRepo.findByInspectionNo(no)
                .orElseThrow(() -> new IllegalArgumentException("检验单不存在：" + no));
    }

    /** 明细整表替换：先删后插，行号不会累加出错 */
    private void applyItems(ErpQcInspection ins, Object raw) {
        if (!(raw instanceof List<?> list)) {
            return;
        }
        itemRepo.deleteByInspectionId(ins.getId());
        int lineNo = 1;
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) {
                continue;
            }
            ErpQcInspectionItem it = new ErpQcInspectionItem();
            it.setInspectionId(ins.getId());
            it.setLineNo(lineNo++);
            it.setItemName(str(m.get("itemName")));
            it.setStandard(str(m.get("standard")));
            it.setMethod(str(m.get("method")));
            it.setDefectCodeId(longOf(m.get("defectCodeId")));
            it.setDefectCode(str(m.get("defectCode")));
            it.setDefectName(str(m.get("defectName")));
            it.setSeverity(str(m.get("severity")));
            it.setMeasured(str(m.get("measured")));
            it.setResult(ErpQcInspectionItem.RESULT_NG.equalsIgnoreCase(str(m.get("result")))
                    ? ErpQcInspectionItem.RESULT_NG : ErpQcInspectionItem.RESULT_OK);
            it.setDefectQty(qtyOf(m.get("defectQty")));
            it.setRemark(str(m.get("remark")));
            it.setCreatedAt(System.currentTimeMillis());
            itemRepo.save(it);
        }
    }

    /**
     * 从明细 NG 行汇总「不合格项数」到单头。
     * <p>
     * <b>只汇总项数，绝不动 {@code defectQty}</b>：一件产品可以同时在两行 NG，件数是 1、项数是 2。
     * 拿项数当件数去判，结论会反转。
     */
    private void syncItemStats(ErpQcInspection ins) {
        int cr = 0;
        int ma = 0;
        int mi = 0;
        for (ErpQcInspectionItem it : itemRepo.findByInspectionIdOrderByLineNoAsc(ins.getId())) {
            if (!it.isNg()) {
                continue;
            }
            if (ErpQcDefectCode.SEV_CR.equals(it.getSeverity())) {
                cr++;
            } else if (ErpQcDefectCode.SEV_MI.equals(it.getSeverity())) {
                mi++;
            } else {
                ma++;
            }
        }
        ins.setDefectCr(cr);
        ins.setDefectMa(ma);
        ins.setDefectMi(mi);
    }

    private String nextNo() {
        String base = "QC" + LocalDate.now(ZONE).format(DAY) + "-";
        String max = insRepo.maxNoByPrefix(base);
        int n = 1;
        if (max != null && max.length() > base.length()) {
            try {
                n = Integer.parseInt(max.substring(base.length())) + 1;
            } catch (NumberFormatException ignored) {
                n = 1;
            }
        }
        String no;
        do {
            no = base + String.format("%04d", n);
            n++;
        } while (insRepo.existsByInspectionNo(no));
        return no;
    }
}
