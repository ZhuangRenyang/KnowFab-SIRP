package com.factory.hub.collect;

import com.factory.hub.model.PointValue;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 实时快照 + 短时历史。全部在内存里，重启即清空。
 * <p>
 * 为什么不上时序库：单厂几十台设备、1 秒一采，
 * 内存里留最近 2 小时的数据够看趋势了。历史归档后期按客户需求再加。
 */
@Component
public class SnapshotStore {

    /** 每台设备最新一次采集结果 */
    private final Map<String, Map<String, PointValue>> latest = new ConcurrentHashMap<>();

    /** 每台设备的运行状态 */
    private final Map<String, Runtime> runtimes = new ConcurrentHashMap<>();

    /** key = deviceCode|pointKey，value = [ts, value] 环形缓冲 */
    private final Map<String, Deque<double[]>> history = new ConcurrentHashMap<>();

    private static final int HISTORY_LIMIT = 720;

    /** 设备运行状态，看板顶部的"在线/离线"就靠它 */
    public static class Runtime {
        public volatile boolean online = false;
        public volatile long lastOkTs = 0L;
        public volatile String lastError = null;
        public volatile long successCount = 0L;
        public volatile long failCount = 0L;
        public volatile long lastCostMs = 0L;
        public volatile String protocol = "";
    }

    public Runtime runtime(String deviceCode) {
        return runtimes.computeIfAbsent(deviceCode, k -> new Runtime());
    }

    public void update(String deviceCode, Map<String, PointValue> values, long costMs) {
        latest.put(deviceCode, values);
        Runtime rt = runtime(deviceCode);
        rt.online = true;
        rt.lastOkTs = System.currentTimeMillis();
        rt.lastError = null;
        rt.successCount++;
        rt.lastCostMs = costMs;

        for (PointValue v : values.values()) {
            Double d = v.asDouble();
            if (d == null) {
                continue;
            }
            Deque<double[]> q = history.computeIfAbsent(deviceCode + "|" + v.pointKey(),
                    k -> new ArrayDeque<>());
            synchronized (q) {
                q.addLast(new double[]{v.ts(), d});
                while (q.size() > HISTORY_LIMIT) {
                    q.removeFirst();
                }
            }
        }
    }

    public void markOffline(String deviceCode, String error) {
        Runtime rt = runtime(deviceCode);
        rt.online = false;
        rt.failCount++;
        rt.lastError = error;
    }

    public Map<String, PointValue> latest(String deviceCode) {
        Map<String, PointValue> m = latest.get(deviceCode);
        return m == null ? Map.of() : m;
    }

    /**
     * 全部设备的最新值，给能耗汇总这类后台任务遍历用。
     * <p>
     * 直接返回 entrySet 的弱一致视图：遍历过程中有设备新增/删除也不会抛
     * ConcurrentModificationException，而且每次 update 都是整块替换 map，
     * 所以遍历到的永远是某一刻的完整快照，不会看到半个设备的数据。
     */
    public java.util.Set<Map.Entry<String, Map<String, PointValue>>> entries() {
        return latest.entrySet();
    }

    public java.util.Set<String> deviceCodes() {
        return latest.keySet();
    }

    public List<double[]> history(String deviceCode, String pointKey, int limit) {
        Deque<double[]> q = history.get(deviceCode + "|" + pointKey);
        if (q == null) {
            return List.of();
        }
        List<double[]> all;
        synchronized (q) {
            all = new ArrayList<>(q);
        }
        if (all.size() <= limit) {
            return all;
        }
        return all.subList(all.size() - limit, all.size());
    }

    public void clear(String deviceCode) {
        latest.remove(deviceCode);
        runtimes.remove(deviceCode);
        history.keySet().removeIf(k -> k.startsWith(deviceCode + "|"));
    }
}
