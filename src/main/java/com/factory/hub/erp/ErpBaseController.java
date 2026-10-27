package com.factory.hub.erp;

import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ERP 基础资料：商品（SKU）/ 仓库 / 往来单位 的增删改查（ROADMAP DH-42/43 前置）。
 * <p>
 * 权限：读 = 登录即可（班组长要能看）；写 = ADMIN/MANAGER（SecurityConfig 按 HTTP 方法收口）。
 * 返回统一 {@code {ok, message, ...}}，失败是 200 + ok:false，与全站约定一致。
 * 单据（采购/销售/盘点/调拨）在后续 DH 里做，届时库存 = 流水累计，本控制器只管主数据。
 */
@RestController
@RequestMapping("/api/erp")
public class ErpBaseController {

    private final ErpSkuRepository skuRepo;
    private final ErpWarehouseRepository whRepo;
    private final ErpPartnerRepository partnerRepo;

    public ErpBaseController(ErpSkuRepository skuRepo,
                             ErpWarehouseRepository whRepo,
                             ErpPartnerRepository partnerRepo) {
        this.skuRepo = skuRepo;
        this.whRepo = whRepo;
        this.partnerRepo = partnerRepo;
    }

    /* ==================== 商品 SKU ==================== */

    @GetMapping("/skus")
    public List<Map<String, Object>> skus(@RequestParam(required = false) String q) {
        List<ErpSku> all = skuRepo.findAll(Sort.by(Sort.Direction.DESC, "id"));
        if (q != null && !q.isBlank()) {
            String k = q.trim().toLowerCase();
            all = all.stream().filter(s -> (nz(s.getCode()) + " " + nz(s.getName()) + " "
                    + nz(s.getCategory()) + " " + nz(s.getSpec())).toLowerCase().contains(k)).toList();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (ErpSku s : all) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.getId());
            m.put("code", s.getCode());
            m.put("name", s.getName());
            m.put("category", s.getCategory());
            m.put("unit", s.getUnit());
            m.put("spec", s.getSpec());
            m.put("barcode", s.getBarcode());
            m.put("purchasePrice", s.getPurchasePrice());
            m.put("salePrice", s.getSalePrice());
            m.put("minStock", s.getMinStock());
            m.put("maxStock", s.getMaxStock());
            m.put("remark", s.getRemark());
            m.put("enabled", s.isEnabled());
            m.put("updatedAt", s.getUpdatedAt());
            out.add(m);
        }
        return out;
    }

    @PostMapping("/skus")
    public Map<String, Object> createSku(@RequestBody Map<String, Object> body) {
        String code = str(body.get("code"));
        String name = str(body.get("name"));
        if (code.isBlank()) return fail("商品编码必填");
        if (name.isBlank()) return fail("商品名称必填");
        if (skuRepo.findByCodeIgnoreCase(code).isPresent()) return fail("商品编码已存在：" + code);
        ErpSku s = new ErpSku();
        s.setCode(code);
        s.setName(name);
        applySku(s, body, false);
        skuRepo.save(s);
        return ok("商品已创建：" + code);
    }

    @PutMapping("/skus/{id}")
    public Map<String, Object> updateSku(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        ErpSku s = skuRepo.findById(id).orElse(null);
        if (s == null) return fail("商品不存在");
        String code = str(body.get("code"));
        if (!code.isBlank() && !code.equalsIgnoreCase(s.getCode())) {
            if (skuRepo.findByCodeIgnoreCase(code).isPresent()) return fail("商品编码已存在：" + code);
            s.setCode(code);
        }
        if (!str(body.get("name")).isBlank()) s.setName(str(body.get("name")));
        applySku(s, body, true);
        skuRepo.save(s);
        return ok("已保存");
    }

    @DeleteMapping("/skus/{id}")
    public Map<String, Object> deleteSku(@PathVariable Long id) {
        if (!skuRepo.existsById(id)) return fail("商品不存在");
        skuRepo.deleteById(id);
        return ok("已删除");
    }

    private void applySku(ErpSku s, Map<String, Object> body, boolean update) {
        if (body.get("category") != null || !update) s.setCategory(str(body.get("category")));
        if (body.get("unit") != null || !update) s.setUnit(str(body.get("unit")));
        if (body.get("spec") != null || !update) s.setSpec(str(body.get("spec")));
        if (body.get("barcode") != null || !update) s.setBarcode(str(body.get("barcode")));
        if (body.get("purchasePrice") != null || !update) s.setPurchasePrice(big(body.get("purchasePrice")));
        if (body.get("salePrice") != null || !update) s.setSalePrice(big(body.get("salePrice")));
        if (body.get("minStock") != null || !update) s.setMinStock(big(body.get("minStock")));
        if (body.get("maxStock") != null || !update) s.setMaxStock(big(body.get("maxStock")));
        if (body.get("remark") != null || !update) s.setRemark(str(body.get("remark")));
        if (body.get("enabled") != null) s.setEnabled(Boolean.parseBoolean(String.valueOf(body.get("enabled"))));
    }

    /* ==================== 仓库 ==================== */

    @GetMapping("/warehouses")
    public List<Map<String, Object>> warehouses(@RequestParam(required = false) String q) {
        List<ErpWarehouse> all = whRepo.findAll(Sort.by(Sort.Direction.DESC, "id"));
        if (q != null && !q.isBlank()) {
            String k = q.trim().toLowerCase();
            all = all.stream().filter(w -> (nz(w.getCode()) + " " + nz(w.getName()) + " "
                    + nz(w.getAddress()) + " " + nz(w.getManager())).toLowerCase().contains(k)).toList();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (ErpWarehouse w : all) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", w.getId());
            m.put("code", w.getCode());
            m.put("name", w.getName());
            m.put("address", w.getAddress());
            m.put("manager", w.getManager());
            m.put("phone", w.getPhone());
            m.put("remark", w.getRemark());
            m.put("default", w.isDefaultWarehouse());
            m.put("enabled", w.isEnabled());
            m.put("updatedAt", w.getUpdatedAt());
            out.add(m);
        }
        return out;
    }

    @PostMapping("/warehouses")
    public Map<String, Object> createWarehouse(@RequestBody Map<String, Object> body) {
        String code = str(body.get("code"));
        String name = str(body.get("name"));
        if (code.isBlank()) return fail("仓库编码必填");
        if (name.isBlank()) return fail("仓库名称必填");
        if (whRepo.findByCodeIgnoreCase(code).isPresent()) return fail("仓库编码已存在：" + code);
        ErpWarehouse w = new ErpWarehouse();
        w.setCode(code);
        w.setName(name);
        applyWarehouse(w, body, false);
        if (w.isDefaultWarehouse()) clearOtherDefaults(w.getId());
        whRepo.save(w);
        return ok("仓库已创建：" + code);
    }

    @PutMapping("/warehouses/{id}")
    public Map<String, Object> updateWarehouse(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        ErpWarehouse w = whRepo.findById(id).orElse(null);
        if (w == null) return fail("仓库不存在");
        String code = str(body.get("code"));
        if (!code.isBlank() && !code.equalsIgnoreCase(w.getCode())) {
            if (whRepo.findByCodeIgnoreCase(code).isPresent()) return fail("仓库编码已存在：" + code);
            w.setCode(code);
        }
        if (!str(body.get("name")).isBlank()) w.setName(str(body.get("name")));
        applyWarehouse(w, body, true);
        if (w.isDefaultWarehouse()) clearOtherDefaults(w.getId());
        whRepo.save(w);
        return ok("已保存");
    }

    @DeleteMapping("/warehouses/{id}")
    public Map<String, Object> deleteWarehouse(@PathVariable Long id) {
        if (!whRepo.existsById(id)) return fail("仓库不存在");
        whRepo.deleteById(id);
        return ok("已删除");
    }

    private void applyWarehouse(ErpWarehouse w, Map<String, Object> body, boolean update) {
        if (body.get("address") != null || !update) w.setAddress(str(body.get("address")));
        if (body.get("manager") != null || !update) w.setManager(str(body.get("manager")));
        if (body.get("phone") != null || !update) w.setPhone(str(body.get("phone")));
        if (body.get("remark") != null || !update) w.setRemark(str(body.get("remark")));
        if (body.get("default") != null) w.setDefaultWarehouse(Boolean.parseBoolean(String.valueOf(body.get("default"))));
        if (body.get("enabled") != null) w.setEnabled(Boolean.parseBoolean(String.valueOf(body.get("enabled"))));
    }

    private void clearOtherDefaults(Long keepId) {
        whRepo.findByDefaultWarehouseTrueAndIdNot(keepId == null ? -1L : keepId)
                .forEach(w -> w.setDefaultWarehouse(false));
        // save 由调用方事务落库前完成：这里显式保存，避免调用方遗漏
        whRepo.findByDefaultWarehouseTrueAndIdNot(keepId == null ? -1L : keepId)
                .forEach(whRepo::save);
    }

    /* ==================== 往来单位 ==================== */

    @GetMapping("/partners")
    public List<Map<String, Object>> partners(@RequestParam(required = false) String q,
                                              @RequestParam(required = false) String type) {
        List<ErpPartner> all = partnerRepo.findAll(Sort.by(Sort.Direction.DESC, "id"));
        if (type != null && !type.isBlank()) {
            all = all.stream().filter(p -> p.getPartnerType().equals(type)
                    || p.getPartnerType().equals(ErpPartner.TYPE_BOTH)).toList();
        }
        if (q != null && !q.isBlank()) {
            String k = q.trim().toLowerCase();
            all = all.stream().filter(p -> (nz(p.getCode()) + " " + nz(p.getName()) + " "
                    + nz(p.getContact()) + " " + nz(p.getPhone())).toLowerCase().contains(k)).toList();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (ErpPartner p : all) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.getId());
            m.put("code", p.getCode());
            m.put("name", p.getName());
            m.put("type", p.getPartnerType());
            m.put("contact", p.getContact());
            m.put("phone", p.getPhone());
            m.put("address", p.getAddress());
            m.put("remark", p.getRemark());
            m.put("enabled", p.isEnabled());
            m.put("updatedAt", p.getUpdatedAt());
            out.add(m);
        }
        return out;
    }

    @PostMapping("/partners")
    public Map<String, Object> createPartner(@RequestBody Map<String, Object> body) {
        String code = str(body.get("code"));
        String name = str(body.get("name"));
        if (code.isBlank()) return fail("单位编码必填");
        if (name.isBlank()) return fail("单位名称必填");
        if (partnerRepo.findByCodeIgnoreCase(code).isPresent()) return fail("单位编码已存在：" + code);
        ErpPartner p = new ErpPartner();
        p.setCode(code);
        p.setName(name);
        applyPartner(p, body, false);
        if (!validType(p.getPartnerType())) return fail("类型只能是 SUPPLIER / CUSTOMER / BOTH");
        partnerRepo.save(p);
        return ok("往来单位已创建：" + code);
    }

    @PutMapping("/partners/{id}")
    public Map<String, Object> updatePartner(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        ErpPartner p = partnerRepo.findById(id).orElse(null);
        if (p == null) return fail("往来单位不存在");
        String code = str(body.get("code"));
        if (!code.isBlank() && !code.equalsIgnoreCase(p.getCode())) {
            if (partnerRepo.findByCodeIgnoreCase(code).isPresent()) return fail("单位编码已存在：" + code);
            p.setCode(code);
        }
        if (!str(body.get("name")).isBlank()) p.setName(str(body.get("name")));
        applyPartner(p, body, true);
        if (!validType(p.getPartnerType())) return fail("类型只能是 SUPPLIER / CUSTOMER / BOTH");
        partnerRepo.save(p);
        return ok("已保存");
    }

    @DeleteMapping("/partners/{id}")
    public Map<String, Object> deletePartner(@PathVariable Long id) {
        if (!partnerRepo.existsById(id)) return fail("往来单位不存在");
        partnerRepo.deleteById(id);
        return ok("已删除");
    }

    private void applyPartner(ErpPartner p, Map<String, Object> body, boolean update) {
        if (body.get("type") != null || !update) p.setPartnerType(str(body.get("type")));
        if (body.get("contact") != null || !update) p.setContact(str(body.get("contact")));
        if (body.get("phone") != null || !update) p.setPhone(str(body.get("phone")));
        if (body.get("address") != null || !update) p.setAddress(str(body.get("address")));
        if (body.get("remark") != null || !update) p.setRemark(str(body.get("remark")));
        if (body.get("enabled") != null) p.setEnabled(Boolean.parseBoolean(String.valueOf(body.get("enabled"))));
    }

    private boolean validType(String t) {
        return ErpPartner.TYPE_SUPPLIER.equals(t) || ErpPartner.TYPE_CUSTOMER.equals(t)
                || ErpPartner.TYPE_BOTH.equals(t);
    }

    /* ==================== 工具 ==================== */

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static BigDecimal big(Object o) {
        if (o == null) return null;
        try {
            return new BigDecimal(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Map<String, Object> ok(String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("message", msg);
        return m;
    }

    private static Map<String, Object> fail(String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", false);
        m.put("message", msg);
        return m;
    }
}
