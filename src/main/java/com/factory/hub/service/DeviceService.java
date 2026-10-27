package com.factory.hub.service;

import com.factory.hub.driver.DriverContext;
import com.factory.hub.model.DeviceEntity;
import com.factory.hub.model.PointDef;
import com.factory.hub.repo.DeviceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class DeviceService {

    private static final Logger log = LoggerFactory.getLogger(DeviceService.class);

    private final DeviceRepository repo;
    private final ObjectMapper mapper;

    public DeviceService(DeviceRepository repo, ObjectMapper mapper) {
        this.repo = repo;
        this.mapper = mapper;
    }

    public List<DeviceEntity> all() {
        return repo.findAllByOrderByIdAsc();
    }

    public List<DeviceEntity> enabledDevices() {
        return repo.findByEnabledTrue();
    }

    public DeviceEntity byCode(String code) {
        return repo.findByCode(code).orElse(null);
    }

    /** 解析点表 JSON。解析失败不让整个服务挂掉，只是这台设备采不了 */
    public List<PointDef> pointsOf(DeviceEntity device) {
        List<PointDef> list = new ArrayList<>();
        String json = device.getPointsJson();
        if (json == null || json.isBlank()) {
            return list;
        }
        try {
            PointDef[] arr = mapper.readValue(json, PointDef[].class);
            for (PointDef p : arr) {
                if (p.getKey() == null || p.getKey().isBlank()) {
                    log.warn("设备 {} 有空的点 key，已跳过", device.getCode());
                    continue;
                }
                list.add(p);
            }
        } catch (Exception e) {
            log.error("设备 {} 点表 JSON 解析失败: {}", device.getCode(), e.getMessage());
        }
        return list;
    }

    public DriverContext contextOf(DeviceEntity d) {
        DriverContext ctx = new DriverContext();
        ctx.setHost(d.getHost());
        ctx.setPort(d.getPort() == null ? 502 : d.getPort());
        ctx.setSerialPort(d.getSerialPort());
        ctx.setBaudRate(d.getBaudRate() == null ? 9600 : d.getBaudRate());
        ctx.setDataBits(d.getDataBits() == null ? 8 : d.getDataBits());
        ctx.setStopBits(d.getStopBits() == null ? 1 : d.getStopBits());
        ctx.setParity(d.getParity() == null ? 0 : d.getParity());
        ctx.setUnitId(d.getUnitId() == null ? 1 : d.getUnitId());
        ctx.setTimeoutMs(d.getTimeoutMs());
        return ctx;
    }

    public DeviceEntity save(DeviceEntity d) {
        return repo.save(d);
    }

    public void delete(Long id) {
        repo.deleteById(id);
    }
}
