package com.factory.hub.alarm;

import java.util.ArrayList;
import java.util.List;

/**
 * 通知总配置。
 */
public class NotifyConfig {

    /** 总开关。关掉后告警照常记录，只是不往外发 */
    private boolean enabled = false;

    /**
     * 免打扰时段，格式 {@code 22:00-07:00}，空 = 不启用。
     * 这段时间里只有 CRITICAL 会推出去，MAJOR 以下压住。
     * <p>
     * 现场很需要这个：谁都不想在凌晨两点被告知某台机器温度偏高，
     * 但停机、报警这种真事故该叫还是得叫。
     */
    private String quietHours = "";

    /**
     * 未恢复的告警多久重复提醒一次，单位分钟。0 = 只提醒一次。
     * <p>
     * 默认 0：一台机器停了一整夜，每隔 10 分钟推一条会让人直接把群屏蔽掉，
     * 最后真事故也看不见。要重复提醒就配 120，两小时一次足够。
     */
    private int repeatMinutes = 0;

    private List<NotifyChannel> channels = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getQuietHours() {
        return quietHours == null ? "" : quietHours;
    }

    public void setQuietHours(String quietHours) {
        this.quietHours = quietHours;
    }

    public int getRepeatMinutes() {
        return repeatMinutes;
    }

    public void setRepeatMinutes(int repeatMinutes) {
        this.repeatMinutes = repeatMinutes;
    }

    public List<NotifyChannel> getChannels() {
        if (channels == null) {
            channels = new ArrayList<>();
        }
        return channels;
    }

    public void setChannels(List<NotifyChannel> channels) {
        this.channels = channels;
    }
}
