package com.factory.hub.energy;

import com.factory.hub.config.AppSetting;
import com.factory.hub.config.AppSettingRepository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 能效对标值的存取。跟电价、班次一个套路：一条系统设置里放 JSON，内存缓存一份。
 * <p>
 * <b>为什么不建表：</b>对标值是配置不是业务数据，一个厂撑死十几条，
 * 放进 AppSetting 里跟着备份/升级一起走，不用为它单独做迁移脚本。
 * 哪天要做「标杆值变更历史」再建表也不迟。
 */
@Component
public class BenchmarkStore {

    private static final Logger log = LoggerFactory.getLogger(BenchmarkStore.class);
    private static final String KEY = "energy.benchmarks";

    private final AppSettingRepository repo;
    private final ObjectMapper mapper;

    private volatile List<EnergyBenchmark> cache;

    public BenchmarkStore(AppSettingRepository repo, ObjectMapper mapper) {
        this.repo = repo;
        this.mapper = mapper;
    }

    @PostConstruct
    public void init() {
        List<EnergyBenchmark> list = list();
        log.info("能效对标值：共 {} 条（启用 {} 条）",
                list.size(), list.stream().filter(EnergyBenchmark::isEnabled).count());
    }

    /** 全部对标值（含停用）。返回副本，调用方改不到缓存 */
    public List<EnergyBenchmark> list() {
        List<EnergyBenchmark> c = cache;
        if (c == null) {
            synchronized (this) {
                if (cache == null) {
                    cache = load();
                }
                c = cache;
            }
        }
        return new ArrayList<>(c);
    }

    private List<EnergyBenchmark> load() {
        try {
            AppSetting s = repo.findById(KEY).orElse(null);
            if (s != null && s.getValue() != null && !s.getValue().isBlank()) {
                List<EnergyBenchmark> list = mapper.readValue(
                        s.getValue(), new TypeReference<List<EnergyBenchmark>>() {
                        });
                if (list != null) {
                    return list;
                }
            }
        } catch (Exception e) {
            log.warn("能效对标值解析失败，按空处理：{}", e.getMessage());
        }
        return new ArrayList<>();
    }

    /**
     * 新增或更新一条。id 为空视为新增。
     *
     * @return 保存后的记录
     */
    public EnergyBenchmark save(EnergyBenchmark bm) {
        if (bm.getScopeType() == null || bm.getScopeType().isBlank()) {
            bm.setScopeType(EnergyBenchmark.SCOPE_PLANT);
        }
        bm.setScopeType(bm.normScope());
        if (bm.getUnit() == null || bm.getUnit().isBlank()) {
            bm.setUnit("kWh/件");
        }
        if (EnergyBenchmark.SCOPE_PLANT.equals(bm.normScope())) {
            bm.setScopeValue("");
        }
        List<EnergyBenchmark> list = new ArrayList<>(list());
        if (bm.getId() == null || bm.getId().isBlank()) {
            bm.setId("bm-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8));
            list.add(bm);
        } else {
            boolean replaced = false;
            for (int i = 0; i < list.size(); i++) {
                if (bm.getId().equals(list.get(i).getId())) {
                    list.set(i, bm);
                    replaced = true;
                    break;
                }
            }
            if (!replaced) {
                list.add(bm);
            }
        }
        persist(list);
        cache = list;
        log.info("能效对标已保存：{}（{} · {}）", bm.displayName(), bm.getStandardNo(), bm.normScope());
        return bm;
    }

    /** 删除一条。返回是否真的删掉了 */
    public boolean remove(String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        List<EnergyBenchmark> list = new ArrayList<>(list());
        boolean removed = list.removeIf(b -> id.equals(b.getId()));
        if (removed) {
            persist(list);
            cache = list;
            log.info("能效对标已删除：{}", id);
        }
        return removed;
    }

    private void persist(List<EnergyBenchmark> list) {
        try {
            repo.save(new AppSetting(KEY, mapper.writeValueAsString(list)));
        } catch (Exception e) {
            log.error("能效对标保存失败: {}", e.getMessage());
        }
    }
}
