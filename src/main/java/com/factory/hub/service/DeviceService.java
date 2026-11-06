package com.factory.hub.service;

import com.factory.hub.driver.DriverContext;
import com.factory.hub.model.DeviceEntity;
import com.factory.hub.model.PointDef;
import com.factory.hub.repo.DeviceRepository;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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

    /**
     * 所有标记为总表（进线计量点）的设备。
     * <p>
     * 需量的全厂口径只累加这些——机床等分表的电已经包含在总表里，加进来就重复了。
     * 返回空列表表示客户还没勾选任何总表，此时需量退回旧口径（见 DemandService）。
     */
    public List<DeviceEntity> meters() {
        return repo.findByIsMeterTrueOrderByCodeAsc();
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
        // 模拟器要靠编码算这台机器的产量速率（跟播种的演示数据同一套公式），
        // 不给的话两边对不上，OEE 的性能率会离谱
        ctx.setCode(d.getCode() == null ? "" : d.getCode());
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
        DeviceEntity saved = repo.save(d);
        // 配置改了，缓存里那份就过期了。不清的话，客户刚填的「理想节拍」
        // 在报表里要等到重启才生效——看着像没保存成功，最容易招来「你们这系统是不是有 bug」
        invalidateOeeCache(saved == null ? null : saved.getCode());
        return saved;
    }

    public void delete(Long id) {
        repo.deleteById(id);
    }

    /* ==================== OEE 用的设备配置（DH-15） ==================== */

    /**
     * 理想节拍（秒/件）。没配返回 null。
     * <p>
     * 缓存跟着设备走：配置一改就失效（{@link #save} 里清掉），
     * 不用等 TTL，也不用等重启。
     */
    public Double idealCycleSecOf(String code) {
        if (code == null) {
            return null;
        }
        Double v = cycleCache.computeIfAbsent(code, c -> {
            DeviceEntity d = byCode(c);
            if (d == null || d.getIdealCycleSec() == null || d.getIdealCycleSec() <= 0) {
                return Double.NaN;          // NaN = 查过了，没配
            }
            return d.getIdealCycleSec();
        });
        return Double.isNaN(v) ? null : v;
    }

    /**
     * 点表里配了 role=reject 的不良品计数点位吗（DH-15）。
     * <p>
     * 「没配」和「配了但本期是 0 件」是两回事：前者是数据缺失（良率算不出来），
     * 后者是已知的零不良。报表靠这个区分。
     */
    public boolean hasRejectPoint(String code) {
        if (code == null) {
            return false;
        }
        return rejectCache.computeIfAbsent(code, c -> {
            try {
                DeviceEntity d = byCode(c);
                if (d == null) {
                    return false;
                }
                for (PointDef p : pointsOf(d)) {
                    if ("reject".equals(p.getRole())) {
                        return true;
                    }
                }
                return false;
            } catch (Exception e) {
                return false;
            }
        });
    }

    /** 设备配置变了，缓存里那份就过期了 */
    public void invalidateOeeCache(String code) {
        if (code == null) {
            cycleCache.clear();
            rejectCache.clear();
            return;
        }
        cycleCache.remove(code);
        rejectCache.remove(code);
    }

    /** 理想节拍缓存。NaN = 查过了但没配 */
    private final Map<String, Double> cycleCache = new ConcurrentHashMap<>();

    /** 有没有 reject 点位的缓存 */
    private final Map<String, Boolean> rejectCache = new ConcurrentHashMap<>();
}
