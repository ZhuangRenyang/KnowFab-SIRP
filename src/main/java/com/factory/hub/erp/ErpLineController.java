package com.factory.hub.erp;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 产线管理（ROADMAP DH-40/41）。
 * <p>
 * 权限沿用全站约定：读 = 登录即可（大屏/班组长要看产线产量），写 = ADMIN/MANAGER
 * （SecurityConfig 对 {@code POST/PUT/DELETE /api/erp/**} 统一收口）。
 * <p>
 * 失败一律 200 + {@code ok:false} + 人话，例如
 * "该产线还有 3 台设备，请先移出设备再删除"、"设备 CNC-01 没有计数点（role=count），不能作为成品计数点"。
 */
@RestController
@RequestMapping("/api/erp/lines")
public class ErpLineController {

    private final ErpLineService service;

    public ErpLineController(ErpLineService service) {
        this.service = service;
    }

    @GetMapping
    public Map<String, Object> list() {
        return service.list();
    }

    @GetMapping("/{code}")
    public Map<String, Object> detail(@PathVariable String code,
                                      @RequestParam(defaultValue = "30") int days) {
        return service.detail(code, days);
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        return service.create(body);
    }

    @PutMapping("/{code}")
    public Map<String, Object> update(@PathVariable String code, @RequestBody Map<String, Object> body) {
        return service.update(code, body);
    }

    @DeleteMapping("/{code}")
    public Map<String, Object> remove(@PathVariable String code) {
        return service.remove(code);
    }

    /** 编排设备：传完整有序编码列表，数组顺序就是工位顺序 */
    @SuppressWarnings("unchecked")
    @PostMapping("/{code}/devices")
    public Map<String, Object> assign(@PathVariable String code,
                                      @RequestBody Map<String, Object> body) {
        Object raw = body.get("codes");
        List<String> codes = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                codes.add(String.valueOf(o));
            }
        }
        return service.assign(code, codes);
    }

    @PostMapping("/{code}/move")
    public Map<String, Object> move(@PathVariable String code, @RequestBody Map<String, Object> body) {
        return service.move(code,
                body.get("deviceCode") == null ? "" : String.valueOf(body.get("deviceCode")),
                body.get("dir") == null ? "up" : String.valueOf(body.get("dir")));
    }

    /** 指定成品计数点；deviceCode 传空 = 回到自动（取工位序号最大的一台） */
    @PostMapping("/{code}/finished")
    public Map<String, Object> finished(@PathVariable String code, @RequestBody Map<String, Object> body) {
        return service.setFinished(code,
                body.get("deviceCode") == null ? "" : String.valueOf(body.get("deviceCode")));
    }
}
