package com.factory.hub.system;

import com.factory.hub.audit.AuditLog;
import com.factory.hub.audit.AuditService;
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
    private final AuditService audit;

    public UpdateController(UpdateService updateService, VersionService versionService,
                            AuditService audit) {
        this.updateService = updateService;
        this.versionService = versionService;
        this.audit = audit;
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
        String name = fileNameOf(file == null ? null : file.getOriginalFilename());
        Map<String, Object> out = updateService.upload(file);
        String detail = "上传升级包 " + (name.isEmpty() ? "（未命名文件）" : name);
        if (isOk(out)) {
            audit.ok(AuditLog.CAT_SYSTEM, AuditLog.ACT_UPDATE, "update", "应用升级", detail);
        } else {
            audit.fail(AuditLog.CAT_SYSTEM, AuditLog.ACT_UPDATE, "update", "应用升级",
                    detail, messageOf(out));
        }
        return out;
    }

    /** 联网检查：按配置的更新源问一遍有没有新版本 */
    @GetMapping("/update/check")
    public Map<String, Object> check() {
        return updateService.check();
    }

    /** 从更新源下载并验签。地址由前端从 check 结果里带回来 */
    @PostMapping("/update/download")
    public Map<String, Object> download(@RequestBody Map<String, String> body) {
        String url = body == null ? null : body.get("url");
        Map<String, Object> out = updateService.download(url);
        // source 是实际用上的那个地址（主源挂了会自动走备用源），比请求里带的准
        Object source = out == null ? null : out.get("source");
        String from = source == null ? url : String.valueOf(source);
        String detail = "下载升级包，源 " + (from == null || from.isBlank() ? "（未指定地址）" : from);
        if (isOk(out)) {
            audit.ok(AuditLog.CAT_SYSTEM, AuditLog.ACT_UPDATE, "update", "应用升级", detail);
        } else {
            audit.fail(AuditLog.CAT_SYSTEM, AuditLog.ACT_UPDATE, "update", "应用升级",
                    detail, messageOf(out));
        }
        return out;
    }

    /**
     * 执行升级。
     * <p>
     * <b>审计必须写在调用升级服务之前</b>：apply() 内部会安排服务重启，
     * 重启之后这行代码就执行不到了——"谁升的"这条记录要是没落库，
     * 客户出事故时第一个要问的问题就没人答得上。
     * <p>
     * 成功失败在调用前就能判定：没有待应用的升级包是唯一的失败分支，
     * 条件与 UpdateService.apply() 里判的是同一个（待应用文件是否存在）。
     */
    @PostMapping("/update/apply")
    public Map<String, Object> apply() {
        Map<String, Object> status = updateService.status();
        boolean hasPending = hasPending(status);
        String from = versionService.version();
        String to = pendingTargetVersion(status);
        String detail = "执行应用升级（当前版本 " + from + " → 目标版本 " + to + "）";
        if (hasPending) {
            audit.ok(AuditLog.CAT_SYSTEM, AuditLog.ACT_UPDATE, "update", "应用升级", detail);
        } else {
            audit.fail(AuditLog.CAT_SYSTEM, AuditLog.ACT_UPDATE, "update", "应用升级",
                    detail, "没有待应用的升级包");
        }
        return updateService.apply();
    }

    /**
     * 回滚到上一个版本。
     * <p>
     * 同样先写审计再执行——登记成功后会触发重启。
     * <p>
     * 登记失败（磁盘写不进去这种）会在后面补一条失败记录，
     * 成功时只有一条干净的记录。
     */
    @PostMapping("/update/rollback")
    public Map<String, Object> rollback() {
        String detail = "回滚到上一个版本";
        audit.ok(AuditLog.CAT_SYSTEM, AuditLog.ACT_UPDATE, "update", "应用升级", detail);
        Map<String, Object> out = updateService.rollback();
        if (!isOk(out)) {
            audit.fail(AuditLog.CAT_SYSTEM, AuditLog.ACT_UPDATE, "update", "应用升级",
                    detail, messageOf(out));
        }
        return out;
    }

    @PostMapping("/update/cancel")
    public Map<String, Object> cancel() {
        Map<String, Object> out = updateService.cancel();
        boolean ok = out != null && Boolean.TRUE.equals(out.get("ok"));
        if (ok) {
            audit.ok(AuditLog.CAT_SYSTEM, AuditLog.ACT_UPDATE, "update", "应用升级",
                    "取消待应用的升级包");
        } else {
            audit.fail(AuditLog.CAT_SYSTEM, AuditLog.ACT_UPDATE, "update", "应用升级",
                    "取消待应用的升级包", "没有待应用的升级包");
        }
        return out;
    }

    /* ==================== 审计辅助 ==================== */

    /** 有没有待应用的升级包。判据和 UpdateService.apply() 里一致 */
    private boolean hasPending(Map<String, Object> status) {
        Object pending = status == null ? null : status.get("pending");
        if (pending instanceof Map<?, ?> m) {
            return Boolean.TRUE.equals(m.get("present"));
        }
        return false;
    }

    /** 待升级的目标版本号。取不到就写"未知"，不能因为查不到版本就不记审计 */
    private String pendingTargetVersion(Map<String, Object> status) {
        Object pending = status == null ? null : status.get("pending");
        if (pending instanceof Map<?, ?> m) {
            Object v = m.get("toVersion");
            if (v != null && !String.valueOf(v).isBlank()) {
                return String.valueOf(v);
            }
        }
        return "未知";
    }

    /** 上传文件名，不带路径。有些老浏览器会把整条本地路径一起传上来 */
    private static String fileNameOf(String raw) {
        if (raw == null) {
            return "";
        }
        String n = raw.replace('\\', '/');
        int i = n.lastIndexOf('/');
        return i >= 0 ? n.substring(i + 1) : n;
    }

    private static boolean isOk(Map<String, Object> out) {
        return out != null && Boolean.TRUE.equals(out.get("ok"));
    }

    private static String messageOf(Map<String, Object> out) {
        if (out == null) {
            return "未知错误";
        }
        Object m = out.get("message");
        return m == null ? "操作失败" : String.valueOf(m);
    }
}
