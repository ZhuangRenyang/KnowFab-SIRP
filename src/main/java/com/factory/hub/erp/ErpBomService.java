package com.factory.hub.erp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 产品 BOM 维护（DH-70）。
 * <p>
 * <b>编辑语义是「整表替换」，不是逐行增删</b>。车间维护用料清单是一次改完的
 * （加两种料、去掉一种、调三个用量），逐行提交会在中间留下「加了一半」的状态，
 * 而 BOM 一旦在半成品状态被工单展开，用量就是错的。所以前端提交整张表，
 * 后端在一个事务里对齐：提交了的更新/新增，没提交的删掉。
 * <p>
 * 唯一性靠数据库的 {@code uk_bom_product_material} 兜底，应用层先查一遍只是为了
 * 给出人话错误（「这个材料已经在清单里了」比 Duplicate entry 好读）。
 */
@Service
public class ErpBomService {

    private static final Logger log = LoggerFactory.getLogger(ErpBomService.class);
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final ErpBomRepository bomRepo;
    private final ErpSkuRepository skuRepo;

    public ErpBomService(ErpBomRepository bomRepo, ErpSkuRepository skuRepo) {
        this.bomRepo = bomRepo;
        this.skuRepo = skuRepo;
    }

    /** 一行 BOM 的提交体（前端整表提交时的一个元素） */
    public record BomLine(Long materialSkuId, BigDecimal qty, BigDecimal lossRate, String remark) {}

    /**
     * 查一个产品的 BOM 明细。
     *
     * @return rows：每行带材料编码/名称/单位/单价/小计；cost：单件成品材料成本；
     *         hasBom：这个产品到底配没配 BOM（配了空清单和不配是两件事——
     *         前者说明「确实不用料」，后者说明「还没配」，界面提示不一样）
     */
    public Map<String, Object> list(Long productSkuId) {
        ErpSku product = skuRepo.findById(productSkuId == null ? -1L : productSkuId)
                .orElseThrow(() -> new IllegalArgumentException("产品不存在：" + productSkuId));
        List<ErpBom> boms = bomRepo.findByProductSkuIdOrderByMaterialSkuIdAsc(productSkuId);

        // 一次把材料查出来，别在循环里查库
        Map<Long, ErpSku> skus = new LinkedHashMap<>();
        for (ErpSku s : skuRepo.findAllById(boms.stream().map(ErpBom::getMaterialSkuId).toList())) {
            skus.put(s.getId(), s);
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal cost = BigDecimal.ZERO;
        for (ErpBom b : boms) {
            ErpSku m = skus.get(b.getMaterialSkuId());
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", b.getId());
            r.put("materialSkuId", b.getMaterialSkuId());
            r.put("materialCode", m == null ? null : m.getCode());
            r.put("materialName", m == null ? ("已删除的商品#" + b.getMaterialSkuId()) : m.getName());
            r.put("unit", m == null ? null : m.getUnit());
            r.put("spec", m == null ? null : m.getSpec());
            r.put("qty", b.getQty());
            r.put("lossRate", b.getLossRate());
            r.put("remark", b.getRemark());
            BigDecimal price = m == null ? null : m.getPurchasePrice();
            r.put("purchasePrice", price);
            // 单件成品在这一行材料上的成本：用量 ×（1+损耗）× 采购价
            BigDecimal lineCost = null;
            if (price != null && b.getQty() != null) {
                lineCost = b.getQty()
                        .multiply(BigDecimal.ONE.add(nz(b.getLossRate()).divide(HUNDRED, 6, RoundingMode.HALF_UP)))
                        .multiply(price);
                cost = cost.add(lineCost);
            }
            r.put("lineCost", lineCost == null ? null : lineCost.setScale(4, RoundingMode.HALF_UP));
            rows.add(r);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("productSkuId", productSkuId);
        out.put("productCode", product.getCode());
        out.put("productName", product.getName());
        out.put("productUnit", product.getUnit());
        out.put("hasBom", !boms.isEmpty());
        out.put("rows", rows);
        // 单件材料成本：只算得出采购价的材料；一个价都没有就返回 null，不要给个 0 骗人
        out.put("materialCost", cost.signum() == 0 ? null : cost.setScale(4, RoundingMode.HALF_UP));
        return out;
    }

    /**
     * 整表保存。提交上来的就是这张 BOM 的最终样子：不在列表里的行会被删掉。
     * <p>
     * 会做三件事：① 校验（材料不能是产品自己、用量必须 >0、不能重复）
     * ② 已有的更新、新的插入 ③ 不在提交列表里的删除。
     */
    @Transactional
    public Map<String, Object> save(Long productSkuId, List<BomLine> lines, String operator) {
        ErpSku product = skuRepo.findById(productSkuId == null ? -1L : productSkuId)
                .orElseThrow(() -> new IllegalArgumentException("产品不存在：" + productSkuId));
        List<BomLine> clean = lines == null ? List.of() : lines;

        Set<Long> seen = new HashSet<>();
        for (BomLine l : clean) {
            if (l.materialSkuId() == null) {
                throw new IllegalArgumentException("有一行没选材料");
            }
            if (l.materialSkuId().equals(productSkuId)) {
                throw new IllegalArgumentException("材料不能是产品自己：「" + product.getName() + "」");
            }
            if (l.qty() == null || l.qty().signum() <= 0) {
                String name = skuRepo.findById(l.materialSkuId()).map(ErpSku::getName).orElse("#" + l.materialSkuId());
                throw new IllegalArgumentException("「" + name + "」的用量必须大于 0");
            }
            if (l.lossRate() != null
                    && (l.lossRate().signum() < 0 || l.lossRate().compareTo(HUNDRED) > 0)) {
                throw new IllegalArgumentException("损耗率必须在 0~100 之间");
            }
            if (!seen.add(l.materialSkuId())) {
                throw new IllegalArgumentException("同一个材料在清单里出现了两次，请合并成一行");
            }
            if (!skuRepo.existsById(l.materialSkuId())) {
                throw new IllegalArgumentException("材料不存在：" + l.materialSkuId());
            }
        }

        Map<Long, ErpBom> exist = new LinkedHashMap<>();
        for (ErpBom b : bomRepo.findByProductSkuIdOrderByMaterialSkuIdAsc(productSkuId)) {
            exist.put(b.getMaterialSkuId(), b);
        }

        long now = System.currentTimeMillis();
        List<ErpBom> toSave = new ArrayList<>();
        for (BomLine l : clean) {
            ErpBom b = exist.remove(l.materialSkuId());
            if (b == null) {
                b = new ErpBom();
                b.setProductSkuId(productSkuId);
                b.setMaterialSkuId(l.materialSkuId());
                b.setCreatedAt(now);
            }
            b.setQty(l.qty());
            b.setLossRate(nz(l.lossRate()));
            b.setRemark(l.remark());
            b.setUpdatedAt(now);
            toSave.add(b);
        }
        // 剩下的就是「原来有、这次没提交」→ 删掉
        if (!exist.isEmpty()) {
            bomRepo.deleteAll(exist.values());
        }
        bomRepo.saveAll(toSave);

        log.info("BOM 保存：产品 {} 共 {} 种材料（操作人 {}，删除 {} 行）",
                product.getCode(), toSave.size(), operator, exist.size());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("count", toSave.size());
        out.put("removed", exist.size());
        out.put("message", toSave.isEmpty()
                ? "已清空该产品的 BOM"
                : "已保存 " + toSave.size() + " 种材料");
        return out;
    }

    /** 有 BOM 的产品清单：BOM 管理页左侧列表用 */
    public List<Map<String, Object>> products() {
        List<ErpSku> all = skuRepo.findAll();
        List<ErpBom> allBom = bomRepo.findAll();
        Map<Long, Integer> countByProduct = new LinkedHashMap<>();
        for (ErpBom b : allBom) {
            countByProduct.merge(b.getProductSkuId(), 1, Integer::sum);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (ErpSku s : all) {
            if (!s.isEnabled()) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.getId());
            m.put("code", s.getCode());
            m.put("name", s.getName());
            m.put("unit", s.getUnit());
            m.put("category", s.getCategory());
            m.put("materialCount", countByProduct.getOrDefault(s.getId(), 0));
            out.add(m);
        }
        // 有 BOM 的排前面（配过了的好找），同组按编码
        out.sort((a, b) -> {
            int ca = ((Integer) a.get("materialCount")) > 0 ? 0 : 1;
            int cb = ((Integer) b.get("materialCount")) > 0 ? 0 : 1;
            if (ca != cb) {
                return ca - cb;
            }
            return String.valueOf(a.get("code")).compareTo(String.valueOf(b.get("code")));
        });
        return out;
    }

    /** 这个材料被哪些产品用着：删商品前要拦的依据 */
    public List<Map<String, Object>> usage(Long materialSkuId) {
        List<ErpBom> list = bomRepo.findByMaterialSkuId(materialSkuId);
        if (list.isEmpty()) {
            return List.of();
        }
        Map<Long, ErpSku> products = new LinkedHashMap<>();
        for (ErpSku s : skuRepo.findAllById(list.stream().map(ErpBom::getProductSkuId).toList())) {
            products.put(s.getId(), s);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (ErpBom b : list) {
            ErpSku p = products.get(b.getProductSkuId());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("productSkuId", b.getProductSkuId());
            m.put("productCode", p == null ? null : p.getCode());
            m.put("productName", p == null ? ("已删除的商品#" + b.getProductSkuId()) : p.getName());
            m.put("qty", b.getQty());
            m.put("lossRate", b.getLossRate());
            out.add(m);
        }
        return out;
    }

    /**
     * 把 BOM 展开成工单用料。<b>工单下达时调用一次</b>，结果固化进 erp_work_order_material。
     *
     * @param productSkuId 成品
     * @param planQty      计划产量
     * @return 每种材料的 {@code needQty = planQty × 单位用量 × (1 + 损耗率/100)}，已 round4
     */
    public List<ErpWorkOrderMaterial> expand(Long productSkuId, BigDecimal planQty) {
        return expand(productSkuId, planQty, null);
    }

    /**
     * 把 BOM 展开成工单用料，并记下计划从哪个仓领。
     *
     * @param whId 领料仓；null 表示还没定（领料时再选）。
     *             传了的话会写进每一行，缺料看板才能显示"这个仓里还有多少"。
     */
    public List<ErpWorkOrderMaterial> expand(Long productSkuId, BigDecimal planQty, Long whId) {
        List<ErpBom> boms = bomRepo.findByProductSkuIdOrderByMaterialSkuIdAsc(productSkuId);
        if (boms.isEmpty()) {
            throw new IllegalArgumentException("这个产品还没配 BOM，无法下达工单。请先到「基础资料 → 物料清单」里配好用料");
        }
        Map<Long, ErpSku> skus = new LinkedHashMap<>();
        for (ErpSku s : skuRepo.findAllById(boms.stream().map(ErpBom::getMaterialSkuId).toList())) {
            skus.put(s.getId(), s);
        }
        List<ErpWorkOrderMaterial> out = new ArrayList<>();
        for (ErpBom b : boms) {
            ErpSku m = skus.get(b.getMaterialSkuId());
            ErpWorkOrderMaterial row = new ErpWorkOrderMaterial();
            row.setSkuId(b.getMaterialSkuId());
            row.setSkuCode(m == null ? null : m.getCode());
            row.setSkuName(m == null ? ("已删除的商品#" + b.getMaterialSkuId()) : m.getName());
            row.setSkuUnit(m == null ? null : m.getUnit());
            row.setWarehouseId(whId);
            BigDecimal factor = BigDecimal.ONE.add(
                    nz(b.getLossRate()).divide(HUNDRED, 6, RoundingMode.HALF_UP));
            row.setNeedQty(planQty.multiply(b.getQty()).multiply(factor)
                    .setScale(4, RoundingMode.HALF_UP));
            out.add(row);
        }
        return out;
    }

    /** 单件材料成本（工单/报表/AI 都用这一个口径，避免各算各的） */
    public BigDecimal unitMaterialCost(Long productSkuId) {
        Object v = list(productSkuId).get("materialCost");
        return v == null ? null : (BigDecimal) v;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
