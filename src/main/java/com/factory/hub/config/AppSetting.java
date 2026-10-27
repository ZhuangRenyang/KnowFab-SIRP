package com.factory.hub.config;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import jakarta.persistence.Table;

/**
 * 通用键值配置表。现在存电价方案，以后存告警阈值、厂名、大屏标题都往这里塞。
 * <p>
 * 为什么不写配置文件：客户现场改电价是常事（每年供电局都会调），
 * 让客户去编辑 yml 再重启服务不现实，网页上改完立刻生效才对。
 */
@Entity
@Table(name = "app_setting")
public class AppSetting {

    @Id
    @Column(name = "k", length = 64)
    private String key;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "v")
    private String value;

    public AppSetting() {
    }

    public AppSetting(String key, String value) {
        this.key = key;
        this.value = value;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }
}
