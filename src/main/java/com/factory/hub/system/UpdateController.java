package com.factory.hub.system;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 系统版本与升级接口。
 * <p>
 * 挂在 /api/system 下，页面「系统」页签用。
 * 注意：这里没有做鉴权——整套系统目前都没有登录（README 里排在下一步）。
 * 在此之前，这几个接口只能靠"服务器只在内网"来兜底，交付前必须补权限，
 * 否则任何能打开页面的人都能上传升级包（虽然签名能挡住伪造，但能挡住刷包）。
 */
@RestController
@RequestMapping("/api/system")
public class UpdateController {

    private final UpdateService updateService;
    private final VersionService versionService;

    public UpdateController(UpdateService updateService, VersionService versionService) {
        this.updateService = updateService;
        this.versionService = versionService;
    }

    /** 健康检查也用它：返回 200 且带版本号，说明服务和数据库都起来了 */
    @GetMapping("/version")
    public Map<String, Object> version() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("app", "device-hub");
        m.put("version", versionService.version());
        m.put("buildTime", versionService.buildTime());
        m.put("schemaVersion", versionService.schemaVersion());
        m.put("ts", System.currentTimeMillis());
        return m;
    }

    @GetMapping("/update/status")
    public Map<String, Object> status() {
        return updateService.status();
    }

    @PostMapping(value = "/update/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file) {
        return updateService.upload(file);
    }

    /** 联网检查：按配置的更新源问一遍有没有新版本 */
    @GetMapping("/update/check")
    public Map<String, Object> check() {
        return updateService.check();
    }

    /** 从更新源下载并验签。地址由前端从 check 结果里带回来 */
    @PostMapping("/update/download")
    public Map<String, Object> download(@RequestBody Map<String, String> body) {
        return updateService.download(body == null ? null : body.get("url"));
    }

    @PostMapping("/update/apply")
    public Map<String, Object> apply() {
        return updateService.apply();
    }

    @PostMapping("/update/rollback")
    public Map<String, Object> rollback() {
        return updateService.rollback();
    }

    @PostMapping("/update/cancel")
    public Map<String, Object> cancel() {
        return updateService.cancel();
    }
}
