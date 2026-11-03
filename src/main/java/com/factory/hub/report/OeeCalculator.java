package com.factory.hub.report;

import java.util.ArrayList;
import java.util.List;

/**
 * OEE 计算（DH-15）。
 *
 * <p>OEE = 可用率 × 性能 × 良率，三个因子各自独立：
 * <ul>
 *   <li><b>可用率</b> = 运行时间 ÷ 计划生产时间（班次总时长）</li>
 *   <li><b>性能</b> = 实际产量 ÷ 理论产量（理想节拍 × 运行时间）</li>
 *   <li><b>良率</b> = 良品数 ÷ 总产出</li>
 * </ul>
 *
 * <p><b>这个类最重要的一条规矩：算不出来就说算不出来。</b>
 * 缺理想节拍、缺不良数数据 → 对应因子给 null，OEE 也给 null，并用人话说明缺什么、怎么补。
 * 绝不用「按 0 处理」「按 100% 处理」这类假设把一个假的 OEE 数字糊弄出去——
 * 客户拿一个假的 OEE 去做改善决策，比没有这个数字更糟。
 *
 * <p>只有「确实就是 0」的情况才给 0：设备整天没开工（运行时间 0）、跑了 8 小时一件没做（产出 0）。
 * 这两种是<b>已知的坏结果</b>，不是数据缺失。
 */
public class OeeCalculator {

    /**
     * 算 OEE 需要的输入。
     *
     * @param runSec         运行时间（秒）
     * @param output         总产出（件，含不良）
     * @param defect         不良数（件）
     * @param hasDefectData  <b>不良数数据存不存在</b>——不是「不良数是不是 0」。
     *                       false = 这块数据缺失，良率算不出来
     * @param theoreticalOutput 理论产量（件）。null = 算不出来（一般是缺理想节拍）
     * @param plannedSec     计划生产时间（秒，班次总时长）。0 = 没配班次
     * @param countedSec     已记录时长（秒）。计划时间为 0 时用它兜底
     * @param missingNote    理论产量算不出来的原因（人话）。理论产量有值时不用
     */
    public record Input(
            double runSec,
            double output,
            double defect,
            boolean hasDefectData,
            Double theoreticalOutput,
            double plannedSec,
            double countedSec,
            String missingNote) {
    }

    /** 算出来的结果。四个比率都是 null 表示「算不出来」，notes 说明原因 */
    public record Result(
            Double availability,
            Double performance,
            Double quality,
            Double oee,
            double plannedSec,
            double countedSec,
            double runSec,
            double output,
            double defect,
            boolean hasDefectData,
            Double theoreticalOutput,
            List<String> notes) {
    }

    /**
     * 算一次。
     *
     * @param in 输入
     * @return 结果；比率是百分比（0~100），null 表示算不出来
     */
    public Result compute(Input in) {
        List<String> notes = new ArrayList<>();
        double run = Math.max(0d, in.runSec());
        double out = Math.max(0d, in.output());
        double planned = Math.max(0d, in.plannedSec());
        double counted = Math.max(0d, in.countedSec());

        /* ---------- 可用率 ---------- */
        Double availability = null;
        if (planned > 0) {
            availability = pct(run / planned);
            // 数据完整度：采到的时长占计划生产时间的比例。
            // 系统是下午才接上的时候，采到的时长远小于计划时长，可用率会低得离谱——
            // 那是「没采到」不是「没干活」，不说清楚会被当成设备的问题追责。
            double coverage = counted / planned;
            if (coverage < 0.9) {
                notes.add(String.format(
                        "本期只采到计划生产时间的 %.0f%%（系统接入时间在那之前），可用率会偏低，"
                                + "不能直接当成设备效率看", coverage * 100));
            }
        } else if (counted > 0) {
            // 没配班次计划：退回「已记录时长」口径，并说清楚这跟标准 OEE 不一样
            availability = pct(run / counted);
            notes.add("没配班次计划，可用率按「已记录时长」算，口径跟标准 OEE 不一样");
        } else {
            notes.add("这个周期没有任何运行数据，可用率算不出来");
        }

        /* ---------- 性能 ---------- */
        Double performance = null;
        double theoretical = in.theoreticalOutput() == null ? 0d : Math.max(0d, in.theoreticalOutput());
        if (in.theoreticalOutput() == null) {
            notes.add(in.missingNote() == null || in.missingNote().isBlank()
                    ? "缺标准产能数据，性能率算不出来" : in.missingNote());
        } else if (theoretical <= 0) {
            // 一秒都没运行 → 性能就是 0，这是已知的结果不是数据缺失
            performance = 0d;
        } else {
            performance = pct(out / theoretical);
            if (performance > 100.0001) {
                notes.add("性能率超过 100%，理想节拍可能配小了（配小的节拍会让理论产量偏高）");
            }
        }

        /* ---------- 良率 ---------- */
        Double quality = null;
        if (!in.hasDefectData()) {
            notes.add("没有不良数数据，良率算不出来。机床有不良品计数器就配一个 role=reject 的点，"
                    + "没有就用「产量补录」把不良数录进来");
        } else if (out <= 0) {
            // 没有产出就没有不良品，良率记 100%（性能率已经是 0，OEE 照样是 0）
            quality = 100d;
            notes.add("这个周期没有产出，良率没有实际意义");
        } else {
            quality = pct((out - Math.max(0d, in.defect())) / out);
            if (quality < 0) {
                quality = 0d;
                notes.add("不良数比产量还多，数据可能录错了");
            }
        }

        /* ---------- OEE ---------- */
        Double oee = null;
        if (availability != null && performance != null && quality != null) {
            // 三个因子都是「百分数」（0~100），相乘要除两次 100：
            // 50% × 83% × 97% = 0.5 × 0.83 × 0.97 = 40.3%
            // 写成 a×p×q/100 会得到 4034（大了 100 倍），页面上直接显示成 4034.0%
            oee = round2(availability * performance * quality / 10000d);
        }

        return new Result(availability, performance, quality, oee,
                planned, counted, run, out, Math.max(0d, in.defect()), in.hasDefectData(),
                in.theoreticalOutput() == null ? null : round1(theoretical), notes);
    }

    /**
     * 单台设备的输入。
     *
     * @param cycle 理想节拍（秒/件）。null 或 &lt;= 0 = 没配
     */
    public static Input forDevice(double runSec, double output, double defect,
                                   boolean hasDefectData, Double cycle,
                                   double plannedSec, double countedSec) {
        Double theoretical = null;
        String note = null;
        if (cycle == null || cycle <= 0) {
            note = "没配「理想节拍（秒/件）」，性能率算不出来。"
                    + "到「设备配置」里填一个：设备状态最好的时候，加工一件要多少秒";
        } else {
            theoretical = runSec / cycle;
        }
        return new Input(runSec, output, defect, hasDefectData, theoretical,
                plannedSec, countedSec, note);
    }

    /**
     * 合计的输入。理论产量由调用方把各设备的加总（不能拿一个节拍算全厂）。
     *
     * @param theoretical 各设备理论产量之和；null = 有设备缺理想节拍
     * @param missingNote 有设备缺理想节拍时的说明
     */
    public static Input forAggregate(double runSec, double output, double defect,
                                     boolean hasDefectData, Double theoretical,
                                     double plannedSec, double countedSec,
                                     String missingNote) {
        return new Input(runSec, output, defect, hasDefectData, theoretical,
                plannedSec, countedSec, missingNote);
    }

    private static double pct(double ratio) {
        return round2(ratio * 100d);
    }

    private static double round1(double v) {
        return Math.round(v * 10d) / 10d;
    }

    private static double round2(double v) {
        return Math.round(v * 100d) / 100d;
    }
}
