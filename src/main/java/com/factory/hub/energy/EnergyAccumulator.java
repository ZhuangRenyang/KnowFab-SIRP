package com.factory.hub.energy;

/**
 * 累计电量增量计算器。一个 (设备, 点位) 一个实例，常驻内存，重启清零。
 * <p>
 * <b>为什么必须做这一步：</b>
 * 电表寄存器里放的是「累计电量」（比如 12345.67 kWh），这是个只增不减的数。
 * 要算「今天用了多少度」，必须自己拿两次读数相减。
 *
 * <h3>三个现场必然遇到的坑，这里都处理了</h3>
 *
 * <b>1. 读数变小。</b> 换表、手动清零、电表断电复位都会让累计量归零。
 * 如果代码里直接写 {@code delta = cur - prev}，就会得到一个负数，
 * 汇总的时候把整天的电量冲掉，客户看到「今天用了 -800 度」。
 * <p>
 * 这里的处理是：<b>读数变小就当清零，本次增量记 0</b>，等下一个周期恢复。
 * 代价是丢掉清零那一瞬间的几度电（通常电表刚从 0 开始，值很小），
 * 换来的是永远不会算出一个离谱的大数。
 * <p>
 * 有人会用「数据类型位宽」去判回绕（u16 最大 6553.5 度，会溢出）。
 * 但一个采集周期内溢出的可能性远低于清零，而误判回绕的代价是
 * 多算几十万度电——对一个要拿去跟客户对账的系统，这个错不能犯。
 * 真遇到 u16 溢出的电表，正确做法是把点表改成 u32。
 *
 * <b>2. 断线补偿。</b> 设备离线两小时再上线，一次读数就跨了两小时的电量。
 * 这部分电量是真的用掉了，必须算，但归到「上线那一小时」会让这小时的电费
 * 突然翻几倍。所以这里标记 backfill，前端可以提示「含断线补偿」，
 * 让客户知道不是软件算错了。
 *
 * <b>3. 点表配错。</b> 最常见的错误是把「瞬时功率」错配成 role=energy。
 * 功率会上下跳，于是读数天天变小，resets 疯狂增长。
 * 这里统计 resetCount，超过阈值就报 suspicious，提示去检查点表——
 * 比让客户盯着一个不动的电费数字猜要强得多。
 */
public class EnergyAccumulator {

    /** 一次读数产生的结果 */
    public record Delta(double kwh, boolean reset, boolean backfill) {
        public static final Delta ZERO = new Delta(0d, false, false);

        public boolean counted() {
            return kwh > 0;
        }
    }

    /** 超过这个间隔才算断线补偿，正常采集周期是秒级，5 分钟足够宽松 */
    private static final long BACKFILL_GAP_MS = 5 * 60 * 1000L;

    /** 一共才几百个样本就重置了十几次，几乎肯定是点表配错了 */
    private static final int SUSPICIOUS_RESETS = 12;

    private double lastRaw = Double.NaN;
    private long lastTs = 0L;
    private long samples = 0L;
    private long resets = 0L;
    private double totalKwh = 0d;

    /**
     * 喂一次新读数，返回本次增量（单位 kWh）。
     * <p>
     * 同一个 ts 重复喂会返回 0——采集器和汇总扫描是两套节拍，
     * 设备采集周期 5 秒、汇总 1 秒扫一次，同一条数据会被扫到 5 次。
     */
    public Delta feed(long ts, double raw, String unit) {
        double kwh = toKwh(raw, unit);

        if (Double.isNaN(lastRaw)) {
            // 首次读数没有参照，只能记住基线。重启后也是这样，
            // 所以重启后第一个周期不计电量——这一秒的误差可以忽略。
            lastRaw = kwh;
            lastTs = ts;
            return Delta.ZERO;
        }
        if (ts <= lastTs) {
            return Delta.ZERO;          // 同一条数据扫了多遍，不是新读数
        }

        double prev = lastRaw;
        long gap = ts - lastTs;
        lastRaw = kwh;
        lastTs = ts;
        samples++;

        if (kwh < prev) {
            // 清零 / 换表 / 通信读到脏数据，见类注释第 1 条
            resets++;
            return new Delta(0d, true, false);
        }

        double delta = kwh - prev;
        if (delta <= 0d) {
            return Delta.ZERO;
        }

        totalKwh += delta;
        return new Delta(delta, false, gap > BACKFILL_GAP_MS);
    }

    /** 点表把单位配成 Wh 时自动换算，避免客户少算 1000 倍 */
    private double toKwh(double raw, String unit) {
        if (unit == null) {
            return raw;
        }
        String u = unit.trim().toLowerCase();
        if (u.equals("wh") || u.equals("瓦时")) {
            return raw / 1000d;
        }
        return raw;
    }

    public long samples() {
        return samples;
    }

    public long resets() {
        return resets;
    }

    public double totalKwh() {
        return totalKwh;
    }

    /** 点表配错嫌疑，供 API 提示用 */
    public boolean suspicious() {
        return resets >= SUSPICIOUS_RESETS && resets > samples / 8;
    }

    /** 给前端的一句人话提示，没问题时返回 null */
    public String hint() {
        if (suspicious()) {
            return "读数频繁变小（" + resets + " 次），这个点可能配成了瞬时功率或电量变送器信号，"
                    + "请确认点表里 role 为 energy 的点对应的是累计电量寄存器";
        }
        return null;
    }

    public void reset() {
        lastRaw = Double.NaN;
        lastTs = 0L;
        samples = 0L;
        resets = 0L;
        totalKwh = 0d;
    }
}
