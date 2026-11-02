package com.factory.hub.alarm;

import com.factory.hub.model.DeviceEntity;
import com.factory.hub.model.PointDef;
import com.factory.hub.service.DeviceService;
import org.springframework.stereotype.Component;

/**
 * 告警的"人话描述"生成器。
 * <p>
 * 这几个方法只负责把告警对象翻译成一句客户看得懂的话，写进审计日志。
 * 跟「告警的增删改查」是两件事，混在 Controller 里越堆越大，
 * 所以单独放一个文件——动告警逻辑的人不必在这里看，动文案的人也不必翻 Controller。
 * <p>
 * 纯展示逻辑，不改任何告警状态，也不抛异常：点表读不到就少写单位，
 * 审计少几个字好过整个接口挂掉。
 */
@Component
public class AlarmDescribe {

    private final DeviceService deviceService;

    public AlarmDescribe(DeviceService deviceService) {
        this.deviceService = deviceService;
    }

    /** 告警一句话标题：设备名 + 告警内容 */
    public String alarmTitle(AlarmEvent ev) {
        if (ev == null) {
            return null;
        }
        String dev = ev.getDeviceName() == null || ev.getDeviceName().isBlank()
                ? ev.getDeviceCode() : ev.getDeviceName();
        String msg = ev.getMessage() == null || ev.getMessage().isBlank()
                ? ev.getRuleName() : ev.getMessage();
        return dev + " " + msg;
    }

    /**
     * 规则的关键条件，写成人话。
     * <p>
     * 阈值单位从设备点表里查——规则本身不存单位，同一个数值在温度点是 ℃、
     * 在压力点是 MPa，写错了客户会照着错单位去判断。
     */
    public String describeCondition(AlarmRule r) {
        String unit = unitOf(r);
        StringBuilder sb = new StringBuilder();
        if (AlarmRule.TYPE_THRESHOLD.equals(r.getType())) {
            sb.append("点位 ").append(r.getPointKey()).append(" ")
                    .append(r.getOp()).append(" ").append(r.getThreshold());
            if (!unit.isEmpty()) {
                sb.append(" ").append(unit);
            }
        } else if (r.getThreshold() != null) {
            sb.append("阈值 ").append(r.getThreshold());
            if (!unit.isEmpty()) {
                sb.append(" ").append(unit);
            }
        }
        if (sb.length() > 0) {
            sb.append("，持续 ").append(r.getForSeconds()).append(" 秒");
        } else {
            sb.append("持续 ").append(r.getForSeconds()).append(" 秒");
        }
        if (r.getSchedule() != null && !r.getSchedule().isBlank()) {
            sb.append("，生效时段 ").append(r.getSchedule());
        }
        if (r.getDeviceCodes() != null && !r.getDeviceCodes().isBlank()) {
            sb.append("，适用设备 ").append(r.getDeviceCodes());
        }
        return sb.toString();
    }

    /** 阈值单位：在这条规则管的设备的点表里找同名点位 */
    public String unitOf(AlarmRule r) {
        String key = r.getPointKey();
        if (key == null || key.isBlank()) {
            return "";
        }
        try {
            for (DeviceEntity d : deviceService.all()) {
                if (!r.covers(d.getCode())) {
                    continue;
                }
                for (PointDef p : deviceService.pointsOf(d)) {
                    if (key.equalsIgnoreCase(p.getKey())) {
                        String u = p.getUnit();
                        return u == null ? "" : u.trim();
                    }
                }
            }
        } catch (Exception ignored) {
            // 点表读不到不影响审计记录，只是少了单位
        }
        return "";
    }

    /** 通知配置摘要：只写渠道和启停，不写地址和 token */
    public String describeNotify(NotifyConfig cfg) {
        StringBuilder sb = new StringBuilder();
        sb.append("保存通知配置，总开关").append(cfg.isEnabled() ? "已启用" : "已停用");
        if (!cfg.getQuietHours().isBlank()) {
            sb.append("，免打扰时段 ").append(cfg.getQuietHours());
        }
        if (cfg.getRepeatMinutes() > 0) {
            sb.append("，未恢复告警每 ").append(cfg.getRepeatMinutes()).append(" 分钟重复提醒");
        }
        if (cfg.getChannels().isEmpty()) {
            sb.append("，没有配置任何通知渠道");
            return sb.toString();
        }
        sb.append("；渠道：");
        for (int i = 0; i < cfg.getChannels().size(); i++) {
            NotifyChannel c = cfg.getChannels().get(i);
            if (i > 0) {
                sb.append("、");
            }
            sb.append(c.getName()).append("（").append(c.typeName()).append("，")
                    .append(c.isEnabled() ? "已启用" : "已停用").append("）");
        }
        return sb.toString();
    }
}
